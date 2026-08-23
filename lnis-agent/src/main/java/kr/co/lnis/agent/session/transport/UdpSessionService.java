package kr.co.lnis.agent.session.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.agent.codec.NativeAfsCodec;
import kr.co.lnis.agent.session.afs.*;
import kr.co.lnis.protocol.codec.*;
import kr.co.lnis.protocol.model.AgentProtocol.EventType;
import kr.co.lnis.protocol.model.LnisModels.*;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import static kr.co.lnis.protocol.codec.AfsPacketCodec.*;

/**
 * AFS 시험 프레임의 UDP 송수신, 오류 시험, 복원 및 역할별 판정을 수행한다.
 *
 * <p>Sender는 원본 GRAW의 SHA-256 manifest와 AFS frame을 설정된 횟수만큼 전송하고
 * Receiver 결과를 result port에서 기다린다. Receiver는 논리 sequence 단위로
 * 중복 datagram을 제거하고 frame을 복호화해 GRAW를 재조립한다.
 * 이후 길이, record 수와 SHA-256을 비교한다. Test E의 Drop은 실제 송신 직전에
 * 결정론적으로 적용해 동일 seed의 결과를 재현한다.
 */
public final class UdpSessionService implements AutoCloseable {
    public record SessionCommand(String senderAgentId, String receiverAgentId, UUID inputId, TransportSettings transport, TestOptions options) {}
    private record Manifest(UUID testId, int protocolVersion, int prn, int customMessageType, long sourceLength, String sourceSha256,
                            int recordCount, int frameCount, int startWeek, int startIntervalOfWeek, int startTimeOfInterval,
                            double simulatedDropRatePercent, int simulatedDropSeed, long simulatedDroppedDatagrams,
                            TestType testType, int errorCount, int errorSeed, int syncDamageInterval, int injectedFrameCount) {}
    private record WireResult(Verdict verdict, IntegrityResult integrity, long expectedFrames, long receivedFrames,
                              long receivedDatagrams, long duplicates, long corrupt, long decodedFrames,
                              long sb2ValidFrames, long sb3ValidFrames, long sb4ValidFrames, long correctedSymbols,
                              long recoveredSyncFrames, String error) {}
    private record Key(Kind kind, long sequence) {}
    private record ReceivedFrame(long sequence, int toi, byte[] payload) {}

    private final NativeAfsCodec codec; private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor(); private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile DatagramSocket activeSocket;
    public UdpSessionService(NativeAfsCodec codec) { this.codec = codec; }

    /** Sender 작업을 별도 virtual thread에 제출해 WebSocket 수신 thread를 차단하지 않는다. */
    public void send(
            UUID sessionId,
            SessionCommand command,
            byte[] source,
            BiConsumer<EventType, Object> event,
            java.util.function.Consumer<RoleResult> resultSink) {
        executor.submit(() -> runSender(sessionId, command, source, event, resultSink));
    }

    /** Receiver socket 대기 작업을 별도 virtual thread에 제출한다. */
    public void receive(
            UUID sessionId,
            SessionCommand command,
            BiConsumer<EventType, Object> event,
            java.util.function.Consumer<RoleResult> resultSink) {
        executor.submit(() -> runReceiver(sessionId, command, event, resultSink));
    }

    /** 활성 socket을 닫아 blocking receive를 깨우고 현재 세션에 취소 신호를 전달한다. */
    public void cancel() {
        cancelled.set(true);
        DatagramSocket socket = activeSocket;
        if (socket != null) {
            socket.close();
        }
    }

    /** GRAW를 AFS frame으로 변환하고 manifest/frame/end 순서로 송신한 뒤 결과를 기다린다. */
    private void runSender(UUID id, SessionCommand command, byte[] source, BiConsumer<EventType,Object> event, java.util.function.Consumer<RoleResult> sink) {
        Instant started = Instant.now();
        try {
            cancelled.set(false);
            List<byte[]> records = GrawCodec.splitLengthPrefixed(source);
            AfsFrameBuilder.Prepared prepared =
                    new AfsFrameBuilder(codec).prepare(records, command.options());
            String sourceHash = Hashing.hex(Hashing.sha256Digest().digest(source));
            var first = prepared.frames().getFirst();
            double dropRate = command.options().testType() == TestType.TEST_E_UDP_DROP
                    ? command.options().dropRatePercent()
                    : 0;
            long plannedDrops = 0;
            for (int frameIndex = 0; frameIndex < prepared.frames().size(); frameIndex++) {
                for (int copy = 0; copy < command.transport().repeatCount(); copy++) {
                    if (AfsDropSimulator.shouldDrop(
                            frameIndex, copy, dropRate, command.options().dropSeed())) {
                        plannedDrops++;
                    }
                }
            }
            Manifest manifest = new Manifest(
                    id,
                    1,
                    8,
                    63,
                    source.length,
                    sourceHash,
                    records.size(),
                    prepared.frames().size(),
                    first.week(),
                    first.intervalOfWeek(),
                    first.timeOfInterval(),
                    dropRate,
                    command.options().dropSeed(),
                    plannedDrops,
                    command.options().testType(),
                    command.options().errorCount(),
                    command.options().errorSeed(),
                    command.options().syncDamageInterval(),
                    prepared.injectedFrameCount());
            InetAddress destination = InetAddress.getByName(command.transport().broadcastAddress());
            try (DatagramSocket socket = new DatagramSocket(command.transport().resultPort())) {
                activeSocket = socket;
                socket.setBroadcast(true);
                socket.setSoTimeout(command.transport().resultTimeoutSeconds() * 1000);
                Packet start = new Packet(
                        Kind.SESSION_START,
                        id,
                        0,
                        0,
                        8,
                        first.week(),
                        first.intervalOfWeek(),
                        first.timeOfInterval(),
                        utcTicks(),
                        json.writeValueAsBytes(manifest));
                sendCopies(
                        socket,
                        destination,
                        command.transport().dataPort(),
                        start,
                        command.transport().repeatCount());
                long sent = 0;
                for (int index = 0;
                        index < prepared.frames().size() && !cancelled.get();
                        index++) {
                    var frame = prepared.frames().get(index);
                    Packet packet = new Packet(
                            Kind.FRAME,
                            id,
                            index,
                            0,
                            8,
                            frame.week(),
                            frame.intervalOfWeek(),
                            frame.timeOfInterval(),
                            utcTicks(),
                            frame.payload());
                    for (int copy = 0; copy < command.transport().repeatCount(); copy++) {
                        if (!AfsDropSimulator.shouldDrop(
                                index, copy, dropRate, command.options().dropSeed())) {
                            send(
                                    socket,
                                    destination,
                                    command.transport().dataPort(),
                                    withCopy(packet, copy));
                            sent++;
                        }
                    }
                    event.accept(EventType.TX_STATUS, Map.of(
                            "percent", 35 + (int) (45.0 * (index + 1) / prepared.frames().size()),
                            "stage", "Transmitting",
                            "message", "Sent " + (index + 1) + "/"
                                    + prepared.frames().size() + " frames"));
                }
                Packet end = new Packet(
                        Kind.SESSION_END,
                        id,
                        prepared.frames().size(),
                        0,
                        8,
                        0,
                        0,
                        0,
                        utcTicks(),
                        new byte[0]);
                sendCopies(
                        socket,
                        destination,
                        command.transport().dataPort(),
                        end,
                        command.transport().repeatCount());
                while (!cancelled.get()) {
                    DatagramPacket datagram = new DatagramPacket(new byte[1500], 1500);
                    socket.receive(datagram);
                    Packet packet = AfsPacketCodec.decode(
                            Arrays.copyOf(datagram.getData(), datagram.getLength()));
                    if (packet.kind() != Kind.RESULT || !packet.testId().equals(id)) {
                        continue;
                    }
                    WireResult wire = json.readValue(packet.payload(), WireResult.class);
                    NetworkCounters counters = new NetworkCounters(
                            wire.expectedFrames,
                            wire.receivedFrames,
                            sent,
                            wire.receivedDatagrams,
                            wire.duplicates,
                            wire.corrupt,
                            0,
                            0,
                            source.length,
                            Duration.between(started, Instant.now()),
                            List.of(),
                            plannedDrops,
                            dropRate);
                    RoleResult result = new RoleResult(
                            1,
                            id,
                            AgentRole.SENDER,
                            wire.verdict,
                            Instant.now(),
                            wire.integrity,
                            metrics(wire),
                            counters,
                            List.of(),
                            wire.error);
                    sink.accept(result);
                    event.accept(EventType.RESULT, result);
                    return;
                }
            }
        } catch (Exception error) {
            RoleResult result = failed(id, AgentRole.SENDER, error);
            sink.accept(result);
            event.accept(EventType.ERROR, Map.of("message", safe(error)));
        } finally {
            activeSocket = null;
        }
    }

    /** SESSION_START부터 END까지 수신하고 복원 무결성을 계산해 RESULT packet을 Sender에 반환한다. */
    private void runReceiver(UUID requestedId, SessionCommand command, BiConsumer<EventType,Object> event, java.util.function.Consumer<RoleResult> sink) {
        Instant started=Instant.now();
        try (DatagramSocket socket = new DatagramSocket(command.transport().dataPort())) {
            cancelled.set(false);
            activeSocket = socket;
            socket.setSoTimeout(1000);
            Manifest manifest = null;
            InetAddress senderAddress = null;
            int senderPort = 0;
            Set<Key> accepted = new HashSet<>();
            SortedMap<Long, ReceivedFrame> frames = new TreeMap<>();
            long datagrams = 0;
            long duplicates = 0;
            long corrupt = 0;
            while (!cancelled.get()) {
                try {
                    DatagramPacket datagram = new DatagramPacket(new byte[1500], 1500);
                    socket.receive(datagram);
                    datagrams++;
                    Packet packet;
                    try {
                        packet = AfsPacketCodec.decode(
                                Arrays.copyOf(datagram.getData(), datagram.getLength()));
                    } catch (Exception invalid) {
                        corrupt++;
                        continue;
                    }
                    if (!accepted.add(new Key(packet.kind(), packet.sequence()))) {
                        duplicates++;
                        continue;
                    }
                    if (packet.kind() == Kind.SESSION_START) {
                        manifest = json.readValue(packet.payload(), Manifest.class);
                        senderAddress = datagram.getAddress();
                        senderPort = command.transport().resultPort();
                        event.accept(EventType.RX_STATUS, Map.of(
                                "percent", 5,
                                "stage", "Receiving",
                                "message", manifest.testType + " session started",
                                "testType", manifest.testType));
                        continue;
                    }
                    if (manifest == null || !packet.testId().equals(manifest.testId)) {
                        continue;
                    }
                    if (packet.kind() == Kind.FRAME) {
                        frames.put(
                                packet.sequence(),
                                new ReceivedFrame(
                                        packet.sequence(),
                                        packet.timeOfInterval(),
                                        packet.payload()));
                        event.accept(EventType.RX_STATUS, Map.of(
                                "percent", 10 + (int) (70.0 * frames.size()
                                        / Math.max(1, manifest.frameCount)),
                                "stage", "Receiving",
                                "message", "Received frame " + packet.sequence()));
                        continue;
                    }
                    if (packet.kind() == Kind.SESSION_END) {
                        Thread.sleep(command.transport().endGraceMilliseconds());
                        break;
                    }
                } catch (SocketTimeoutException ignored) {
                    // 취소 여부를 주기적으로 확인하기 위한 정상 timeout이다.
                }
            }
            if (manifest == null || senderAddress == null) {
                throw new IllegalStateException("Session ended without manifest");
            }
            DecodeAggregate aggregate = decodeFrames(frames.values(), manifest.testType);
            List<byte[]> records = aggregate.reassembler.completeRecords();
            ByteArrayOutputStream reconstructed = new ByteArrayOutputStream();
            for (byte[] record : records) {
                reconstructed.writeBytes(ByteBuffer.allocate(4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .putInt(record.length)
                        .array());
                reconstructed.writeBytes(record);
            }
            byte[] raw = reconstructed.toByteArray();
            String hash = Hashing.hex(Hashing.sha256Digest().digest(raw));
            boolean integrityOk = raw.length == manifest.sourceLength
                    && hash.equals(manifest.sourceSha256)
                    && records.size() == manifest.recordCount
                    && aggregate.reassembler.incompleteCount() == 0;
            IntegrityResult integrity = new IntegrityResult(
                    integrityOk,
                    manifest.sourceLength,
                    raw.length,
                    manifest.sourceSha256,
                    hash,
                    manifest.recordCount,
                    records.size(),
                    integrityOk ? "RAW comparison completed." : "RAW comparison failed.");
            boolean passed = manifest.testType == TestType.TEST_D_SYNC_RECOVERY
                    ? frames.size() == manifest.frameCount
                            && aggregate.recoveredSyncFrames
                                    == manifest.frameCount - manifest.injectedFrameCount
                            && aggregate.decodedFrames
                                    == manifest.frameCount - manifest.injectedFrameCount
                    : integrityOk;
            WireResult wire = new WireResult(
                    passed ? Verdict.PASS : Verdict.FAIL,
                    integrity,
                    manifest.frameCount,
                    frames.size(),
                    datagrams,
                    duplicates,
                    corrupt + aggregate.corruptFrames,
                    aggregate.decodedFrames,
                    aggregate.sb2Valid,
                    aggregate.sb3Valid,
                    aggregate.sb4Valid,
                    aggregate.corrected,
                    aggregate.recoveredSyncFrames,
                    passed ? null : integrity.detail());
            NetworkCounters counters = new NetworkCounters(
                    manifest.frameCount,
                    frames.size(),
                    manifest.frameCount * command.transport().repeatCount()
                            - manifest.simulatedDroppedDatagrams,
                    datagrams,
                    duplicates,
                    corrupt + aggregate.corruptFrames,
                    0,
                    0,
                    raw.length,
                    Duration.between(started, Instant.now()),
                    List.of(),
                    manifest.simulatedDroppedDatagrams,
                    manifest.simulatedDropRatePercent);
            RoleResult result = new RoleResult(
                    1,
                    manifest.testId,
                    AgentRole.RECEIVER,
                    wire.verdict,
                    Instant.now(),
                    integrity,
                    metrics(wire),
                    counters,
                    List.of(resourceSample()),
                    wire.error);
            sink.accept(result);
            event.accept(EventType.RESULT, result);

            byte[] payload = json.writeValueAsBytes(wire);
            if (payload.length > MAXIMUM_PAYLOAD_LENGTH) {
                throw new IllegalStateException("Compact result exceeds UDP limit");
            }
            Packet resultPacket = new Packet(
                    Kind.RESULT,
                    manifest.testId,
                    0,
                    0,
                    8,
                    0,
                    0,
                    0,
                    utcTicks(),
                    payload);
            sendCopies(
                    socket,
                    senderAddress,
                    senderPort,
                    resultPacket,
                    command.transport().repeatCount());
        } catch (Exception error) {
            RoleResult result = failed(requestedId, AgentRole.RECEIVER, error);
            sink.accept(result);
            event.accept(EventType.ERROR, Map.of("message", safe(error)));
        } finally {
            activeSocket = null;
        }
    }

    /** Test D는 bit stream에서 sync를 다시 탐색하고, 나머지 시험은 frame 경계를 그대로 사용한다. */
    private DecodeAggregate decodeFrames(
            Collection<ReceivedFrame> input,
            TestType type) {
        DecodeAggregate total = new DecodeAggregate();
        if (type == TestType.TEST_D_SYNC_RECOVERY) {
            byte[] joined = input.stream()
                    .flatMapToInt(frame -> java.util.stream.IntStream
                            .range(0, frame.payload.length)
                            .map(index -> frame.payload[index] & 0xff))
                    .collect(
                            ByteArrayOutputStream::new,
                            (output, value) -> output.write(value),
                            (left, right) -> left.writeBytes(right.toByteArray()))
                    .toByteArray();
            List<Long> offsets = findSync(joined);
            List<ReceivedFrame> ordered = new ArrayList<>(input);
            total.recoveredSyncFrames = offsets.size();
            for (long offset : offsets) {
                int source = (int) (offset / 6000);
                if (source < ordered.size()) {
                    decodeOne(
                            extract(joined, offset),
                            ordered.get(source).toi,
                            total);
                }
            }
            return total;
        }
        for (ReceivedFrame frame : input) {
            decodeOne(frame.payload, frame.toi, total);
        }
        return total;
    }

    private void decodeOne(byte[] frame, int toi, DecodeAggregate total) {
        try {
            var decoded = codec.decode(toi, frame);
            total.decodedFrames++;
            if (decoded.sb2Valid()) {
                total.sb2Valid++;
            }
            if (decoded.sb3Valid()) {
                total.sb3Valid++;
            }
            if (decoded.sb4Valid()) {
                total.sb4Valid++;
            }
            total.corrected += Math.max(0, decoded.sb2Corrections())
                    + Math.max(0, decoded.sb3Corrections())
                    + Math.max(0, decoded.sb4Corrections());
            if (!decoded.sb3Valid() || !decoded.sb4Valid()) {
                total.corruptFrames++;
                return;
            }
            total.reassembler.add(AfsRawFragmentCodec.decode(
                    AfsRawFragmentCodec.fromSbBits(decoded.sb3())));
            total.reassembler.add(AfsRawFragmentCodec.decode(
                    AfsRawFragmentCodec.fromSbBits(decoded.sb4())));
        } catch (Exception ignored) {
            total.corruptFrames++;
        }
    }
    private static final byte[] SYNC={(byte)0xCC,0x63,(byte)0xF7,0x45,0x36,(byte)0xF4,(byte)0x9E,0x04,(byte)0xA0};
    private static List<Long> findSync(byte[] bytes) {
        List<Long> offsets = new ArrayList<>();
        long bits = (long) bytes.length * 8;
        for (long offset = 0; offset + 68 <= bits; offset++) {
            boolean matched = true;
            for (int index = 0; index < 68; index++) {
                if (bit(bytes, offset + index) != bit(SYNC, index)) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                offsets.add(offset);
                offset += 67;
            }
        }
        return offsets;
    }

    private static byte[] extract(byte[] bytes, long offset) {
        byte[] output = new byte[750];
        for (int index = 0; index < 6000; index++) {
            if (bit(bytes, offset + index) != 0) {
                output[index >>> 3] |= (byte) (1 << (7 - (index & 7)));
            }
        }
        return output;
    }

    private static int bit(byte[] bytes, long bit) {
        return (bytes[(int) (bit >>> 3)] >>> (7 - (bit & 7))) & 1;
    }

    private static final class DecodeAggregate {
        final AfsReassembler reassembler = new AfsReassembler();
        long decodedFrames;
        long sb2Valid;
        long sb3Valid;
        long sb4Valid;
        long corrected;
        long recoveredSyncFrames;
        long corruptFrames;
    }

    private static List<Metric> metrics(WireResult result) {
        List<Metric> metrics = new ArrayList<>();
        metrics.add(metric("DecodedFrames", result.decodedFrames, "frame"));
        metrics.add(metric("Sb2CrcValidFrames", result.sb2ValidFrames, "frame"));
        metrics.add(metric("Sb3CrcValidFrames", result.sb3ValidFrames, "frame"));
        metrics.add(metric("Sb4CrcValidFrames", result.sb4ValidFrames, "frame"));
        metrics.add(metric("CorrectedSymbols", result.correctedSymbols, "symbol"));
        if (result.recoveredSyncFrames > 0) {
            metrics.add(metric(
                    "RecoveredSyncFrames", result.recoveredSyncFrames, "frame"));
        }
        return List.copyOf(metrics);
    }

    private static Metric metric(String name, long value, String unit) {
        return new Metric(
                MetricCategory.DATA_INTEGRITY,
                name,
                name,
                unit,
                (double) value,
                MetricStatus.MEASURED,
                null,
                null);
    }

    private static ResourceSample resourceSample() {
        long memory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        double cpu = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        return new ResourceSample(Instant.now(), Math.max(0, cpu), memory);
    }

    private static RoleResult failed(UUID id, AgentRole role, Exception error) {
        IntegrityResult integrity = new IntegrityResult(
                false, 0, 0, "", "", 0, 0, safe(error));
        NetworkCounters counters = new NetworkCounters(
                0, 0, 0, 0, 0, 0, 0, 0, 0, Duration.ZERO, List.of(), 0, 0);
        return new RoleResult(
                1,
                id,
                role,
                Verdict.INCONCLUSIVE,
                Instant.now(),
                integrity,
                List.of(),
                counters,
                List.of(),
                safe(error));
    }

    private static String safe(Throwable error) {
        return error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private static Packet withCopy(Packet packet, int copy) {
        return new Packet(
                packet.kind(),
                packet.testId(),
                packet.sequence(),
                copy,
                packet.prn(),
                packet.week(),
                packet.intervalOfWeek(),
                packet.timeOfInterval(),
                packet.sentUtcTicks(),
                packet.payload());
    }

    private static void sendCopies(
            DatagramSocket socket,
            InetAddress address,
            int port,
            Packet packet,
            int repeat) throws Exception {
        for (int copy = 0; copy < repeat; copy++) {
            send(socket, address, port, withCopy(packet, copy));
        }
    }

    private static void send(
            DatagramSocket socket,
            InetAddress address,
            int port,
            Packet packet) throws Exception {
        byte[] bytes = AfsPacketCodec.encode(packet);
        socket.send(new DatagramPacket(bytes, bytes.length, address, port));
    }

    private static long utcTicks() {
        return 621355968000000000L + System.currentTimeMillis() * 10_000L;
    }

    @Override
    public void close() {
        cancel();
        executor.shutdownNow();
    }
}

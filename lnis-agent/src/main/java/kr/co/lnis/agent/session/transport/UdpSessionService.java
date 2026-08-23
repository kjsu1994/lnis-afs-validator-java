package kr.co.lnis.agent.session.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.agent.codec.NativeAfsCodec;
import kr.co.lnis.agent.session.afs.*;
import kr.co.lnis.protocol.codec.*;
import kr.co.lnis.protocol.model.AgentProtocol.EventType;
import kr.co.lnis.protocol.model.AgentProtocol.FrameEvidenceMessage;
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
    /** Redis 상세 증거 보관 상한과 동일한 Agent 전송 상한이다. */
    private static final int MAX_EVIDENCE_FRAMES = 500;
    public record SessionCommand(String senderAgentId, String receiverAgentId, UUID inputId, TransportSettings transport, TestOptions options) {}
    private record Manifest(UUID testId, int protocolVersion, int prn, int customMessageType, long sourceLength, String sourceSha256,
                            int recordCount, int frameCount, int startWeek, int startIntervalOfWeek, int startTimeOfInterval,
                            double simulatedDropRatePercent, int simulatedDropSeed, long simulatedDroppedDatagrams,
                            TestType testType, int errorCount, int errorSeed, int syncDamageInterval, int injectedFrameCount) {}
    private record WireResult(Verdict verdict, IntegrityResult integrity, long expectedFrames, long receivedFrames,
                              long receivedDatagrams, long duplicates, long corrupt,
                              long invalidDatagrams, long decodeFailedFrames,
                              long injectedBitCount, long syncRejectedFrames, long decodedFrames,
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
            java.util.function.Consumer<FrameEvidenceMessage> evidenceSink,
            java.util.function.Consumer<RoleResult> resultSink) {
        executor.submit(() -> runSender(
                sessionId,
                command,
                source,
                event,
                evidenceSink,
                resultSink));
    }

    /** Receiver socket 대기 작업을 별도 virtual thread에 제출한다. */
    public void receive(
            UUID sessionId,
            SessionCommand command,
            BiConsumer<EventType, Object> event,
            java.util.function.Consumer<FrameEvidenceMessage> evidenceSink,
            java.util.function.Consumer<RoleResult> resultSink) {
        executor.submit(() -> runReceiver(
                sessionId,
                command,
                event,
                evidenceSink,
                resultSink));
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
    private void runSender(
            UUID id,
            SessionCommand command,
            byte[] source,
            BiConsumer<EventType, Object> event,
            java.util.function.Consumer<FrameEvidenceMessage> evidenceSink,
            java.util.function.Consumer<RoleResult> sink) {
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

            Map<Integer, AfsFrameBuilder.InjectionDetail> injectionsByFrame =
                    new HashMap<>();
            for (var injection : prepared.injections()) {
                injectionsByFrame.put(injection.frameIndex(), injection);
            }

            // 시험 시작 전에 원본 규모, 전송 목적지와 Test A~E 조건을 구조화해 브라우저에 알린다.
            Map<String, Object> preparedDetails = new LinkedHashMap<>();
            preparedDetails.put("percent", 30);
            preparedDetails.put("stage", "Prepared");
            preparedDetails.put("message", "Transmission plan prepared");
            preparedDetails.put("testType", command.options().testType());
            preparedDetails.put("sourceBytes", source.length);
            preparedDetails.put("recordCount", records.size());
            preparedDetails.put("totalFrames", prepared.frames().size());
            preparedDetails.put("destinationAddress", command.transport().broadcastAddress());
            preparedDetails.put("dataPort", command.transport().dataPort());
            preparedDetails.put("resultPort", command.transport().resultPort());
            preparedDetails.put("repeatCount", command.transport().repeatCount());
            preparedDetails.put("errorCount", command.options().errorCount());
            preparedDetails.put("errorSeed", command.options().errorSeed());
            preparedDetails.put("syncDamageInterval", command.options().syncDamageInterval());
            preparedDetails.put("injectedFrameCount", prepared.injectedFrameCount());
            preparedDetails.put("dropRatePercent", dropRate);
            preparedDetails.put("dropSeed", command.options().dropSeed());
            preparedDetails.put("plannedDroppedDatagrams", plannedDrops);
            event.accept(EventType.TX_STATUS, preparedDetails);

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
                    int sentCopies = 0;
                    List<Integer> droppedCopyIndexes = new ArrayList<>();
                    for (int copy = 0; copy < command.transport().repeatCount(); copy++) {
                        if (!AfsDropSimulator.shouldDrop(
                                index, copy, dropRate, command.options().dropSeed())) {
                            send(
                                    socket,
                                    destination,
                                    command.transport().dataPort(),
                                    withCopy(packet, copy));
                            sent++;
                            sentCopies++;
                        } else {
                            droppedCopyIndexes.add(copy);
                        }
                    }

                    Map<String, Object> frameDetails = new LinkedHashMap<>();
                    frameDetails.put(
                            "percent",
                            35 + (int) (45.0 * (index + 1) / prepared.frames().size()));
                    frameDetails.put("stage", "Transmitting");
                    frameDetails.put(
                            "message",
                            "Sent " + (index + 1) + "/"
                                    + prepared.frames().size() + " frames");
                    frameDetails.put("testType", command.options().testType());
                    frameDetails.put("frameIndex", index);
                    frameDetails.put("frameNumber", index + 1);
                    frameDetails.put("totalFrames", prepared.frames().size());
                    frameDetails.put("repeatCount", command.transport().repeatCount());
                    frameDetails.put("sentCopies", sentCopies);
                    frameDetails.put("droppedCopyIndexes", droppedCopyIndexes);

                    var injection = injectionsByFrame.get(index);
                    if (injection != null) {
                        frameDetails.put("injectionMode", injection.mode());
                        frameDetails.put("injectedBitPositions", injection.bitPositions());
                    }
                    event.accept(EventType.TX_STATUS, frameDetails);

                    // 대용량 시험에서는 앞/뒤 프레임을 균형 있게 남겨 Redis 사용량을 제한한다.
                    if (shouldKeepEvidence(index, prepared.frames().size())) {
                        evidenceSink.accept(new FrameEvidenceMessage(
                                index,
                                prepared.referenceFrames().get(index).payload(),
                                frame.payload(),
                                null,
                                null,
                                injection == null ? List.of() : injection.bitPositions(),
                                false,
                                injection == null
                                        ? "오류가 주입되지 않은 Sender 프레임"
                                        : "Sender가 시험 조건에 따라 비트를 반전한 프레임"));
                    }
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
                            dropRate,
                            wire.invalidDatagrams,
                            wire.decodeFailedFrames,
                            wire.injectedBitCount,
                            wire.syncRejectedFrames);
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
    private void runReceiver(
            UUID requestedId,
            SessionCommand command,
            BiConsumer<EventType, Object> event,
            java.util.function.Consumer<FrameEvidenceMessage> evidenceSink,
            java.util.function.Consumer<RoleResult> sink) {
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
            Instant lastPacketAt = started;
            Duration armTimeout = Duration.ofSeconds(Math.max(
                    30,
                    command.transport().resultTimeoutSeconds() + 10L));
            Duration packetTimeout = Duration.ofSeconds(
                    command.transport().resultTimeoutSeconds());
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
                    lastPacketAt = Instant.now();
                    if (!accepted.add(new Key(packet.kind(), packet.sequence()))) {
                        duplicates++;
                        continue;
                    }
                    if (packet.kind() == Kind.SESSION_START) {
                        manifest = json.readValue(packet.payload(), Manifest.class);
                        senderAddress = datagram.getAddress();
                        senderPort = command.transport().resultPort();
                        Map<String, Object> sessionDetails = new LinkedHashMap<>();
                        sessionDetails.put("percent", 5);
                        sessionDetails.put("stage", "Receiving");
                        sessionDetails.put(
                                "message",
                                manifest.testType + " session started");
                        sessionDetails.put("testType", manifest.testType);
                        sessionDetails.put("sourceBytes", manifest.sourceLength);
                        sessionDetails.put("recordCount", manifest.recordCount);
                        sessionDetails.put("expectedFrames", manifest.frameCount);
                        sessionDetails.put("repeatCount", command.transport().repeatCount());
                        sessionDetails.put("errorCount", manifest.errorCount);
                        sessionDetails.put("errorSeed", manifest.errorSeed);
                        sessionDetails.put(
                                "syncDamageInterval",
                                manifest.syncDamageInterval);
                        sessionDetails.put("injectedFrameCount", manifest.injectedFrameCount);
                        sessionDetails.put(
                                "dropRatePercent",
                                manifest.simulatedDropRatePercent);
                        sessionDetails.put("dropSeed", manifest.simulatedDropSeed);
                        sessionDetails.put(
                                "plannedDroppedDatagrams",
                                manifest.simulatedDroppedDatagrams);
                        event.accept(EventType.RX_STATUS, sessionDetails);
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
                        Map<String, Object> frameDetails = new LinkedHashMap<>();
                        frameDetails.put(
                                "percent",
                                10 + (int) (70.0 * frames.size()
                                        / Math.max(1, manifest.frameCount)));
                        frameDetails.put("stage", "Receiving");
                        frameDetails.put(
                                "message",
                                "Received frame " + packet.sequence());
                        frameDetails.put("testType", manifest.testType);
                        frameDetails.put("frameIndex", packet.sequence());
                        frameDetails.put("receivedFrames", frames.size());
                        frameDetails.put("expectedFrames", manifest.frameCount);
                        frameDetails.put("receivedDatagrams", datagrams);
                        frameDetails.put("duplicateDatagrams", duplicates);
                        frameDetails.put("invalidDatagrams", corrupt);
                        frameDetails.put("corruptDatagrams", corrupt);
                        event.accept(EventType.RX_STATUS, frameDetails);
                        continue;
                    }
                    if (packet.kind() == Kind.SESSION_END) {
                        Thread.sleep(command.transport().endGraceMilliseconds());
                        break;
                    }
                } catch (SocketTimeoutException timeout) {
                    // 짧은 socket timeout은 취소 확인에 사용하되, 전체 무수신 상태는 자동 종료한다.
                    Instant deadline = manifest == null
                            ? started.plus(armTimeout)
                            : lastPacketAt.plus(packetTimeout);
                    if (Instant.now().isAfter(deadline)) {
                        throw new SocketTimeoutException(manifest == null
                                ? "Receiver timed out waiting for session data"
                                : "Receiver timed out waiting for the next packet");
                    }
                }
            }
            if (manifest == null || senderAddress == null) {
                throw new IllegalStateException("Session ended without manifest");
            }

            event.accept(EventType.RX_STATUS, Map.of(
                    "percent", 85,
                    "stage", "Evaluating",
                    "message", "Reassembling frames and verifying integrity",
                    "receivedFrames", frames.size(),
                    "expectedFrames", manifest.frameCount,
                    "receivedDatagrams", datagrams,
                    "duplicateDatagrams", duplicates,
                    "invalidDatagrams", corrupt,
                    "corruptDatagrams", corrupt));

            DecodeAggregate aggregate = decodeFrames(frames.values(), manifest.testType);

            // Receiver가 실제로 채택한 6,000비트와 복호화 후 재인코딩한 검증 프레임을 서버에 전달한다.
            for (ReceivedFrame frame : frames.values()) {
                int frameIndex = Math.toIntExact(frame.sequence());
                if (!shouldKeepEvidence(frameIndex, manifest.frameCount)) {
                    continue;
                }
                byte[] reencoded = aggregate.reencodedFrames.get(frameIndex);
                evidenceSink.accept(new FrameEvidenceMessage(
                        frameIndex,
                        null,
                        null,
                        frame.payload(),
                        reencoded,
                        List.of(),
                        aggregate.decodedFrameIndexes.contains(frameIndex),
                        reencoded == null
                                ? "복호화에 성공하지 못해 재인코딩 검증 프레임이 없습니다."
                                : "Receiver 복호화 결과를 같은 TOI로 다시 인코딩한 검증 프레임"));
            }
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
                    corrupt,
                    aggregate.corruptFrames,
                    (long) manifest.errorCount * manifest.injectedFrameCount,
                    manifest.testType == TestType.TEST_D_SYNC_RECOVERY
                            ? frames.size() - aggregate.recoveredSyncFrames
                            : 0,
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
                    manifest.simulatedDropRatePercent,
                    corrupt,
                    aggregate.corruptFrames,
                    (long) manifest.errorCount * manifest.injectedFrameCount,
                    manifest.testType == TestType.TEST_D_SYNC_RECOVERY
                            ? frames.size() - aggregate.recoveredSyncFrames
                            : 0);
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

            Map<String, Object> verificationDetails = new LinkedHashMap<>();
            verificationDetails.put("percent", 95);
            verificationDetails.put("stage", "Verifying");
            verificationDetails.put("message", "Integrity comparison completed");
            verificationDetails.put("integritySuccess", integrity.success());
            verificationDetails.put("sourceLength", integrity.sourceLength());
            verificationDetails.put(
                    "reconstructedLength",
                    integrity.reconstructedLength());
            verificationDetails.put("expectedRecords", integrity.expectedRecords());
            verificationDetails.put(
                    "reconstructedRecords",
                    integrity.reconstructedRecords());
            verificationDetails.put(
                    "sha256Match",
                    integrity.sourceSha256().equals(integrity.reconstructedSha256()));
            event.accept(EventType.RX_STATUS, verificationDetails);

            sink.accept(result);

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
                            Math.toIntExact(ordered.get(source).sequence),
                            total);
                }
            }
            return total;
        }
        for (ReceivedFrame frame : input) {
            decodeOne(
                    frame.payload,
                    frame.toi,
                    Math.toIntExact(frame.sequence),
                    total);
        }
        return total;
    }

    private void decodeOne(
            byte[] frame,
            int toi,
            int frameIndex,
            DecodeAggregate total) {
        try {
            var decoded = codec.decode(toi, frame);
            total.decodedFrames++;
            total.decodedFrameIndexes.add(frameIndex);
            total.reencodedFrames.put(
                    frameIndex,
                    codec.encode(toi, decoded.sb2(), decoded.sb3(), decoded.sb4()));
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
        final Map<Integer, byte[]> reencodedFrames = new HashMap<>();
        final Set<Integer> decodedFrameIndexes = new HashSet<>();
        long decodedFrames;
        long sb2Valid;
        long sb3Valid;
        long sb4Valid;
        long corrected;
        long recoveredSyncFrames;
        long corruptFrames;
    }

    /**
     * 500프레임 이하는 모두 보관하고, 초과하면 시험 시작과 끝을 각각 250프레임씩 보관한다.
     * 전체 프레임 수가 아무리 커도 WebSocket과 Redis 사용량이 선형으로 증가하지 않는다.
     */
    private static boolean shouldKeepEvidence(int frameIndex, int totalFrames) {
        if (totalFrames <= MAX_EVIDENCE_FRAMES) {
            return true;
        }
        int half = MAX_EVIDENCE_FRAMES / 2;
        return frameIndex < half || frameIndex >= totalFrames - half;
    }

    private static List<Metric> metrics(WireResult result) {
        List<Metric> metrics = new ArrayList<>();
        metrics.add(metric("DecodedFrames", result.decodedFrames, "frame"));
        metrics.add(metric("Sb2CrcValidFrames", result.sb2ValidFrames, "frame"));
        metrics.add(metric("Sb3CrcValidFrames", result.sb3ValidFrames, "frame"));
        metrics.add(metric("Sb4CrcValidFrames", result.sb4ValidFrames, "frame"));
        metrics.add(new Metric(
                MetricCategory.DATA_INTEGRITY,
                "CorrectedSymbols",
                "LDPC decoder internal decision changes; not the injected error count",
                "bit",
                (double) result.correctedSymbols,
                MetricStatus.MEASURED,
                null,
                null));
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
                0, 0, 0, 0, 0, 0, 0, 0, 0, Duration.ZERO, List.of(), 0, 0,
                0, 0, 0, 0);
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

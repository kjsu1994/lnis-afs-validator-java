package kr.co.lnis.agent.session.transport;

import static kr.co.lnis.protocol.codec.AfsPacketCodec.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import kr.co.lnis.agent.codec.NativeAfsCodec;
import kr.co.lnis.agent.session.afs.*;
import kr.co.lnis.protocol.codec.*;
import kr.co.lnis.protocol.model.AgentProtocol.EventType;
import kr.co.lnis.protocol.model.AgentProtocol.FrameEvidenceMessage;
import kr.co.lnis.protocol.model.LnisModels.*;

/**
 * AFS 시험 프레임의 UDP 송수신, 오류 시험, 복원 및 역할별 판정을 수행한다.
 *
 * <p>Sender는 원본 GRAW의 SHA-256 manifest와 AFS frame을 설정된 횟수만큼 전송하고 Receiver 결과를 result port에서 기다린다.
 * Receiver는 논리 sequence 단위로 중복 datagram을 제거하고 frame을 복호화해 GRAW를 재조립한다. 이후 길이, record 수와 SHA-256을
 * 비교한다. Test E의 Drop은 실제 송신 직전에 결정론적으로 적용해 동일 seed의 결과를 재현한다.
 */
public final class UdpSessionService implements AutoCloseable {
  /** Redis 상세 증거 보관 상한과 동일한 Agent 전송 상한이다. */
  private static final int MAX_EVIDENCE_FRAMES = 500;

  /** 서버가 두 Agent에 공통으로 전달하는 세션 실행 명령이다. */
  @lombok.Value
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class SessionCommand {
    /** AFS 프레임을 송신할 Agent ID다. */
    String senderAgentId;

    /** AFS 프레임을 수신·복호화할 Agent ID다. */
    String receiverAgentId;

    /** 원본 GRAW가 저장된 서버 입력 버퍼 UUID다. */
    UUID inputId;

    /** SB2 ephemeris에 사용할 AFS PRN 설정이다. */
    AfsSettings afs;

    /** UDP 주소·포트·반복·타임아웃 설정이다. */
    TransportSettings transport;

    /** Test A~E 오류 주입과 판정 설정이다. */
    TestOptions options;

    public SessionCommand(
        /** AFS 프레임을 송신할 Agent ID다. */
        String senderAgentId,
        /** AFS 프레임을 수신·복호화할 Agent ID다. */
        String receiverAgentId,
        /** 원본 GRAW가 저장된 서버 입력 버퍼 UUID다. */
        UUID inputId,
        /** SB2 ephemeris에 사용할 AFS PRN 설정이다. */
        AfsSettings afs,
        /** UDP 주소·포트·반복·타임아웃 설정이다. */
        TransportSettings transport,
        /** Test A~E 오류 주입과 판정 설정이다. */
        TestOptions options) {
      afs = afs == null ? new AfsSettings(1) : afs;

      this.senderAgentId = senderAgentId;
      this.receiverAgentId = receiverAgentId;
      this.inputId = inputId;
      this.afs = afs;
      this.transport = transport;
      this.options = options;
    }
  }

  /** Sender가 SESSION_START UDP payload로 Receiver에 전달하는 원본·시험 조건 manifest다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  private static class Manifest {
    /** 시험 세션 UUID다. */
    UUID testId;

    /** SESSION UDP payload 계약 버전이다. */
    int protocolVersion;

    /** AFS 인코딩에 사용한 PRN 식별값이다. */
    int prn;

    /** GRAW 조각을 담는 AFS Custom Message Type 값이다. */
    int customMessageType;

    /** Sender 원본 GRAW 크기이며 단위는 byte다. */
    long sourceLength;

    /** Sender 원본 GRAW 전체 바이트의 SHA-256이다. */
    String sourceSha256;

    /** 원본 GRAW 레코드 개수다. */
    int recordCount;

    /** Sender가 준비한 논리 AFS 프레임 개수다. */
    int frameCount;

    /** 첫 프레임의 GPS week다. */
    int startWeek;

    /** 첫 프레임의 GPS week 내부 1,200초 구간 번호다. */
    int startIntervalOfWeek;

    /** 첫 프레임의 0~99 범위 TOI다. */
    int startTimeOfInterval;

    /** Test E에 설정한 미전송 확률이며 단위는 percent다. */
    double simulatedDropRatePercent;

    /** Test E Drop 재현용 Seed다. */
    int simulatedDropSeed;

    /** Sender가 실제로 미전송하기로 결정한 복제 데이터그램 수다. */
    long simulatedDroppedDatagrams;

    /** 실행 중인 Test A~E 유형이다. */
    TestType testType;

    /** 오류 대상 프레임 하나당 주입할 비트 개수다. */
    int errorCount;

    /** 오류 위치 재현용 Seed다. */
    int errorSeed;

    /** Test D 동기 손상 프레임 선택 간격이다. */
    int syncDamageInterval;

    /** 오류가 하나 이상 주입된 논리 프레임 개수다. */
    int injectedFrameCount;
  }

  /** Receiver가 UDP RESULT payload로 Sender에 돌려주는 압축 결과다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  private static class WireResult {
    /** Receiver 관점 최종 판정이다. */
    Verdict verdict;

    /** 원본과 재조립 GRAW의 무결성 비교 결과다. */
    IntegrityResult integrity;

    /** Sender가 준비했다고 선언한 논리 프레임 수다. */
    long expectedFrames;

    /** Receiver가 중복 제거 후 채택한 논리 프레임 수다. */
    long receivedFrames;

    /** Receiver가 받은 전체 시험 UDP 데이터그램 수다. */
    long receivedDatagrams;

    /** 동일 kind·sequence라 제외한 반복 데이터그램 수다. */
    long duplicates;

    /** 하위 호환용 UDP 해석 실패 수다. */
    long corrupt;

    /** 패킷 구조 또는 CRC를 해석하지 못한 UDP 데이터그램 수다. */
    long invalidDatagrams;

    /** Decoder 예외 또는 하나 이상의 SB CRC가 실패한 프레임 수다. */
    long decodeFailedFrames;

    /** Test B/C/D에서 의도적으로 반전한 전체 비트 수다. */
    long injectedBitCount;

    /** Test D에서 동기 손상으로 제외한 프레임 수다. */
    long syncRejectedFrames;

    /** Native Decoder가 예외 없이 처리한 프레임 수다. */
    long decodedFrames;

    /** SB2·SB3·SB4 CRC를 모두 통과한 완전 복호 프레임 수다. */
    long fullyDecodedFrames;

    /** SB2 CRC 정상 프레임 수다. */
    long sb2ValidFrames;

    /** SB3 CRC 정상 프레임 수다. */
    long sb3ValidFrames;

    /** SB4 CRC 정상 프레임 수다. */
    long sb4ValidFrames;

    /** 모든 블록의 LDPC 내부 판정 변경량 합계다. 주입 오류 수가 아니다. */
    long correctedSymbols;

    /** Test D 동기 재탐색에서 정상 후보로 복구한 프레임 수다. */
    long recoveredSyncFrames;

    /** FAIL 또는 내부 오류의 대표 설명이며 정상일 때는 {@code null}이다. */
    String error;
  }

  /** 반복 UDP 데이터그램의 중복 제거에 사용하는 패킷 종류와 논리 순번 조합이다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  private static class Key {
    /** UDP 패킷 종류다. */
    Kind kind;

    /** 같은 종류 안의 논리 sequence다. */
    long sequence;
  }

  /** Receiver가 중복 제거 후 메모리에 채택한 한 개의 AFS FRAME payload다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  private static class ReceivedFrame {
    /** 0부터 시작하는 논리 AFS 프레임 순번이다. */
    long sequence;

    /** UDP AFS 채널 PRN이다. */
    int prn;

    /** AFS week 번호다. */
    int week;

    /** GPS 주 내 1,200초 구간 번호인 AFS ITOW다. */
    int afsIntervalOfWeek;

    /** Native Decoder에 전달할 0~99 범위 TOI다. */
    int toi;

    /** UDP payload에서 꺼낸 750 byte AFSFrame이다. */
    byte[] payload;
  }

  private final NativeAfsCodec codec;
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private volatile DatagramSocket activeSocket;

  public UdpSessionService(NativeAfsCodec codec) {
    this.codec = codec;
  }

  /** Sender 작업을 별도 virtual thread에 제출해 WebSocket 수신 thread를 차단하지 않는다. */
  public void send(
      UUID sessionId,
      SessionCommand command,
      byte[] source,
      BiConsumer<EventType, Object> event,
      java.util.function.Consumer<FrameEvidenceMessage> evidenceSink,
      java.util.function.Consumer<RoleResult> resultSink) {
    executor.submit(() -> runSender(sessionId, command, source, event, evidenceSink, resultSink));
  }

  /** Receiver socket 대기 작업을 별도 virtual thread에 제출한다. */
  public void receive(
      UUID sessionId,
      SessionCommand command,
      BiConsumer<EventType, Object> event,
      java.util.function.Consumer<FrameEvidenceMessage> evidenceSink,
      java.util.function.Consumer<RoleResult> resultSink) {
    executor.submit(() -> runReceiver(sessionId, command, event, evidenceSink, resultSink));
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
      // 길이-prefix 파일을 검증된 GRAW record로 분리한 뒤 각 record를 SB3/SB4 조각으로 만든다.
      List<byte[]> records = GrawCodec.splitLengthPrefixed(source);
      AfsFrameBuilder.Prepared prepared =
          new AfsFrameBuilder(codec).prepare(records, command.options(), command.afs().prn());
      String sourceHash = Hashing.hex(Hashing.sha256Digest().digest(source));
      var first = prepared.frames().getFirst();
      double dropRate =
          command.options().testType() == TestType.TEST_E_UDP_DROP
              ? command.options().dropRatePercent()
              : 0;
      // Test E는 실제 루프와 같은 결정 함수를 미리 실행해 Receiver가 기대할 Drop 수를 manifest에 기록한다.
      long plannedDrops = 0;
      for (int frameIndex = 0; frameIndex < prepared.frames().size(); frameIndex++) {
        for (int copy = 0; copy < command.transport().repeatCount(); copy++) {
          if (AfsDropSimulator.shouldDrop(
              frameIndex, copy, dropRate, command.options().dropSeed())) {
            plannedDrops++;
          }
        }
      }
      Manifest manifest =
          new Manifest(
              id,
              1,
              command.afs().prn(),
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

      // 프레임 송신 이벤트와 증거 메시지에서 동일한 오류 위치를 재사용하기 위한 조회 맵이다.
      Map<Integer, AfsFrameBuilder.InjectionDetail> injectionsByFrame = new HashMap<>();
      for (var injection : prepared.injections()) {
        injectionsByFrame.put(injection.frameIndex(), injection);
      }

      // 시험 시작 전에 원본 규모, 전송 목적지와 Test A~E 조건을 구조화해 브라우저에 알린다.
      Map<String, Object> preparedDetails = new LinkedHashMap<>();
      preparedDetails.put("percent", 30);
      preparedDetails.put("stage", "Prepared");
      preparedDetails.put("message", "Transmission plan prepared");
      preparedDetails.put("testType", command.options().testType());
      preparedDetails.put("afsPrn", command.afs().prn());
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
      // Sender는 결과 포트에 bind한 같은 소켓으로 데이터 송신과 Receiver RESULT 수신을 함께 수행한다.
      try (DatagramSocket socket = new DatagramSocket(command.transport().resultPort())) {
        activeSocket = socket;
        socket.setBroadcast(true);
        socket.setSoTimeout(command.transport().resultTimeoutSeconds() * 1000);
        Packet start =
            new Packet(
                Kind.SESSION_START,
                id,
                0,
                0,
                command.afs().prn(),
                first.week(),
                first.intervalOfWeek(),
                first.timeOfInterval(),
                utcTicks(),
                json.writeValueAsBytes(manifest));
        // SESSION_START가 먼저 도착해야 Receiver가 뒤따르는 FRAME의 세션 ID와 기대 개수를 해석할 수 있다.
        sendCopies(
            socket,
            destination,
            command.transport().dataPort(),
            start,
            command.transport().repeatCount());
        // FRAME은 논리 순번마다 repeatCount회 시도하며 Test E에서 선택된 복제본만 실제 전송하지 않는다.
        long sent = 0;
        for (int index = 0; index < prepared.frames().size() && !cancelled.get(); index++) {
          var frame = prepared.frames().get(index);
          Packet packet =
              new Packet(
                  Kind.FRAME,
                  id,
                  index,
                  0,
                  command.afs().prn(),
                  frame.week(),
                  frame.intervalOfWeek(),
                  frame.timeOfInterval(),
                  utcTicks(),
                  frame.payload());
          int sentCopies = 0;
          List<Integer> droppedCopyIndexes = new ArrayList<>();
          for (int copy = 0; copy < command.transport().repeatCount(); copy++) {
            if (!AfsDropSimulator.shouldDrop(index, copy, dropRate, command.options().dropSeed())) {
              send(socket, destination, command.transport().dataPort(), withCopy(packet, copy));
              sent++;
              sentCopies++;
            } else {
              droppedCopyIndexes.add(copy);
            }
          }

          Map<String, Object> frameDetails = new LinkedHashMap<>();
          frameDetails.put("percent", 35 + (int) (45.0 * (index + 1) / prepared.frames().size()));
          frameDetails.put("stage", "Transmitting");
          frameDetails.put(
              "message", "Sent " + (index + 1) + "/" + prepared.frames().size() + " frames");
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
            evidenceSink.accept(
                new FrameEvidenceMessage(
                    index,
                    prepared.referenceFrames().get(index).payload(),
                    frame.payload(),
                    null,
                    null,
                    injection == null ? List.of() : injection.bitPositions(),
                    false,
                    false,
                    false,
                    false,
                    false,
                    0,
                    0,
                    0,
                    false,
                    null,
                    null,
                    injection == null
                        ? "오류가 주입되지 않은 Sender 프레임"
                        : "Sender가 시험 조건에 따라 비트를 반전한 프레임"));
          }
        }
        Packet end =
            new Packet(
                Kind.SESSION_END,
                id,
                prepared.frames().size(),
                0,
                command.afs().prn(),
                0,
                0,
                0,
                utcTicks(),
                new byte[0]);
        // SESSION_END도 반복 전송해 하나가 유실돼 Receiver가 불필요하게 timeout까지 기다릴 가능성을 낮춘다.
        sendCopies(
            socket,
            destination,
            command.transport().dataPort(),
            end,
            command.transport().repeatCount());
        // 다른 세션이나 종류의 UDP가 섞여도 이 시험의 RESULT가 올 때까지 무시한다.
        while (!cancelled.get()) {
          DatagramPacket datagram = new DatagramPacket(new byte[1500], 1500);
          socket.receive(datagram);
          Packet packet =
              AfsPacketCodec.decode(Arrays.copyOf(datagram.getData(), datagram.getLength()));
          if (packet.kind() != Kind.RESULT || !packet.testId().equals(id)) {
            continue;
          }
          WireResult wire = json.readValue(packet.payload(), WireResult.class);
          NetworkCounters counters =
              new NetworkCounters(
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
          RoleResult result =
              new RoleResult(
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
    Instant started = Instant.now();
    // ARM_RECEIVER 명령 시점에 dataPort를 먼저 점유하므로 Sender의 첫 UDP 패킷을 놓치지 않는다.
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
      Duration armTimeout =
          Duration.ofSeconds(Math.max(30, command.transport().resultTimeoutSeconds() + 10L));
      Duration packetTimeout = Duration.ofSeconds(command.transport().resultTimeoutSeconds());
      while (!cancelled.get()) {
        try {
          DatagramPacket datagram = new DatagramPacket(new byte[1500], 1500);
          socket.receive(datagram);
          datagrams++;
          Packet packet;
          try {
            packet = AfsPacketCodec.decode(Arrays.copyOf(datagram.getData(), datagram.getLength()));
          } catch (Exception invalid) {
            corrupt++;
            continue;
          }
          lastPacketAt = Instant.now();
          // repeatCount로 다시 온 같은 종류·sequence는 통계만 올리고 복호화 입력에는 한 번만 채택한다.
          if (!accepted.add(new Key(packet.kind(), packet.sequence()))) {
            duplicates++;
            continue;
          }
          // manifest가 원본 크기·Hash·시험 조건을 제공하며 응답 주소는 실제 송신 datagram에서 얻는다.
          if (packet.kind() == Kind.SESSION_START) {
            manifest = json.readValue(packet.payload(), Manifest.class);
            senderAddress = datagram.getAddress();
            senderPort = command.transport().resultPort();
            Map<String, Object> sessionDetails = new LinkedHashMap<>();
            sessionDetails.put("percent", 5);
            sessionDetails.put("stage", "Receiving");
            sessionDetails.put("message", manifest.testType + " session started");
            sessionDetails.put("testType", manifest.testType);
            sessionDetails.put("sourceBytes", manifest.sourceLength);
            sessionDetails.put("recordCount", manifest.recordCount);
            sessionDetails.put("expectedFrames", manifest.frameCount);
            sessionDetails.put("repeatCount", command.transport().repeatCount());
            sessionDetails.put("errorCount", manifest.errorCount);
            sessionDetails.put("errorSeed", manifest.errorSeed);
            sessionDetails.put("syncDamageInterval", manifest.syncDamageInterval);
            sessionDetails.put("injectedFrameCount", manifest.injectedFrameCount);
            sessionDetails.put("dropRatePercent", manifest.simulatedDropRatePercent);
            sessionDetails.put("dropSeed", manifest.simulatedDropSeed);
            sessionDetails.put("plannedDroppedDatagrams", manifest.simulatedDroppedDatagrams);
            event.accept(EventType.RX_STATUS, sessionDetails);
            continue;
          }
          if (manifest == null || !packet.testId().equals(manifest.testId)) {
            continue;
          }
          if (packet.kind() == Kind.FRAME) {
            if (packet.prn() != manifest.prn) {
              corrupt++;
              continue;
            }
            frames.put(
                packet.sequence(),
                new ReceivedFrame(
                    packet.sequence(),
                    packet.prn(),
                    packet.week(),
                    packet.intervalOfWeek(),
                    packet.timeOfInterval(),
                    packet.payload()));
            Map<String, Object> frameDetails = new LinkedHashMap<>();
            frameDetails.put(
                "percent", 10 + (int) (70.0 * frames.size() / Math.max(1, manifest.frameCount)));
            frameDetails.put("stage", "Receiving");
            frameDetails.put("message", "Received frame " + packet.sequence());
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
          // END보다 앞서 보낸 FRAME이 네트워크에서 늦게 도착할 수 있어 짧은 grace를 둔다.
          if (packet.kind() == Kind.SESSION_END) {
            Thread.sleep(command.transport().endGraceMilliseconds());
            break;
          }
        } catch (SocketTimeoutException timeout) {
          // 짧은 socket timeout은 취소 확인에 사용하되, 전체 무수신 상태는 자동 종료한다.
          Instant deadline =
              manifest == null ? started.plus(armTimeout) : lastPacketAt.plus(packetTimeout);
          if (Instant.now().isAfter(deadline)) {
            throw new SocketTimeoutException(
                manifest == null
                    ? "Receiver timed out waiting for session data"
                    : "Receiver timed out waiting for the next packet");
          }
        }
      }
      if (manifest == null || senderAddress == null) {
        throw new IllegalStateException("Session ended without manifest");
      }

      event.accept(
          EventType.RX_STATUS,
          Map.of(
              "percent",
              85,
              "stage",
              "Evaluating",
              "message",
              "Reassembling frames and verifying integrity",
              "receivedFrames",
              frames.size(),
              "expectedFrames",
              manifest.frameCount,
              "receivedDatagrams",
              datagrams,
              "duplicateDatagrams",
              duplicates,
              "invalidDatagrams",
              corrupt,
              "corruptDatagrams",
              corrupt));

      // UDP 수집이 끝난 뒤에만 Native Decoder를 호출해 수신 loop와 CPU 작업을 분리한다.
      DecodeAggregate aggregate = decodeFrames(frames.values(), manifest.testType);

      // Receiver가 실제로 채택한 6,000비트와 복호화 후 재인코딩한 검증 프레임을 서버에 전달한다.
      for (ReceivedFrame frame : frames.values()) {
        int frameIndex = Math.toIntExact(frame.sequence());
        if (!shouldKeepEvidence(frameIndex, manifest.frameCount)) {
          continue;
        }
        byte[] reencoded = aggregate.reencodedFrames.get(frameIndex);
        FrameDecodeDiagnostic diagnostic = aggregate.diagnostics.get(frameIndex);
        evidenceSink.accept(
            new FrameEvidenceMessage(
                frameIndex,
                null,
                null,
                frame.payload(),
                reencoded,
                List.of(),
                diagnostic != null && diagnostic.fullyDecoded(),
                diagnostic != null && diagnostic.decoderCompleted(),
                diagnostic != null && diagnostic.sb2Valid(),
                diagnostic != null && diagnostic.sb3Valid(),
                diagnostic != null && diagnostic.sb4Valid(),
                diagnostic == null ? 0 : diagnostic.sb2DecisionChanges(),
                diagnostic == null ? 0 : diagnostic.sb3DecisionChanges(),
                diagnostic == null ? 0 : diagnostic.sb4DecisionChanges(),
                diagnostic != null && diagnostic.usedForGrawReassembly(),
                aggregate.sb2Ephemeris.get(frameIndex),
                diagnostic == null ? "동기 패턴을 찾지 못해 복호화 대상에서 제외됐습니다." : diagnostic.failureReason(),
                diagnostic == null || !diagnostic.decoderCompleted()
                    ? "복호화에 성공하지 못해 재인코딩 검증 프레임이 없습니다."
                    : diagnostic.fullyDecoded()
                        ? "SB2·SB3·SB4 CRC를 모두 통과한 복호화 결과의 재인코딩 검증 프레임"
                        : "CRC 실패가 포함된 복호화 출력의 진단용 재인코딩 프레임"));
      }
      List<byte[]> records = aggregate.reassembler.completeRecords();
      ByteArrayOutputStream reconstructed = new ByteArrayOutputStream();
      for (byte[] record : records) {
        reconstructed.writeBytes(
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(record.length).array());
        reconstructed.writeBytes(record);
      }
      byte[] raw = reconstructed.toByteArray();
      String hash = Hashing.hex(Hashing.sha256Digest().digest(raw));
      // 크기·전체 Hash·record 수·미완성 조각을 함께 검사해야 부분 복원이 우연히 성공으로 보이지 않는다.
      boolean integrityOk =
          raw.length == manifest.sourceLength
              && hash.equals(manifest.sourceSha256)
              && records.size() == manifest.recordCount
              && aggregate.reassembler.incompleteCount() == 0;
      IntegrityResult integrity =
          new IntegrityResult(
              integrityOk,
              manifest.sourceLength,
              raw.length,
              manifest.sourceSha256,
              hash,
              manifest.recordCount,
              records.size(),
              integrityOk ? "RAW comparison completed." : "RAW comparison failed.");
      long expectedRecoveredFrames = manifest.frameCount - manifest.injectedFrameCount;

      // PocketSDR-AFS는 68심볼 SP를 오차 없이 확인한 뒤 AFS 프레임을 복호화한다.
      // Test D는 손상 프레임 자체의 복구 시험이 아니므로 해당 프레임은 제외한다.
      // 나머지 프레임은 동기 재획득뿐 아니라 SB2·SB3·SB4 CRC까지 모두 통과해야 PASS다.
      boolean passed =
          manifest.testType == TestType.TEST_D_SYNC_RECOVERY
              ? frames.size() == manifest.frameCount
                  && aggregate.recoveredSyncFrames == expectedRecoveredFrames
                  && aggregate.decodedFrames == expectedRecoveredFrames
                  && aggregate.fullyDecodedFrames == expectedRecoveredFrames
              : integrityOk;
      WireResult wire =
          new WireResult(
              passed ? Verdict.PASS : Verdict.FAIL,
              integrity,
              manifest.frameCount,
              frames.size(),
              datagrams,
              duplicates,
              // UDP 데이터그램 해석 실패와 AFS 프레임 CRC 실패는 서로 다른 계층의 오류다.
              // 서브블록 CRC 실패를 데이터그램 손상에 더하면 네트워크 장애처럼 오해할 수 있다.
              corrupt,
              corrupt,
              aggregate.decodeFailedFrames,
              (long) manifest.errorCount * manifest.injectedFrameCount,
              manifest.testType == TestType.TEST_D_SYNC_RECOVERY
                  ? frames.size() - aggregate.recoveredSyncFrames
                  : 0,
              aggregate.decodedFrames,
              aggregate.fullyDecodedFrames,
              aggregate.sb2Valid,
              aggregate.sb3Valid,
              aggregate.sb4Valid,
              aggregate.corrected,
              aggregate.recoveredSyncFrames,
              passed ? null : integrity.detail());
      NetworkCounters counters =
          new NetworkCounters(
              manifest.frameCount,
              frames.size(),
              manifest.frameCount * command.transport().repeatCount()
                  - manifest.simulatedDroppedDatagrams,
              datagrams,
              duplicates,
              // 이 값은 AfsPacketCodec으로 해석하지 못한 UDP 데이터그램만 집계한다.
              corrupt,
              0,
              0,
              raw.length,
              Duration.between(started, Instant.now()),
              List.of(),
              manifest.simulatedDroppedDatagrams,
              manifest.simulatedDropRatePercent,
              corrupt,
              // Decoder 실행 후 하나 이상의 SB CRC가 실패한 프레임은 별도 항목으로 제공한다.
              aggregate.decodeFailedFrames,
              (long) manifest.errorCount * manifest.injectedFrameCount,
              manifest.testType == TestType.TEST_D_SYNC_RECOVERY
                  ? frames.size() - aggregate.recoveredSyncFrames
                  : 0);
      RoleResult result =
          new RoleResult(
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
      verificationDetails.put("reconstructedLength", integrity.reconstructedLength());
      verificationDetails.put("expectedRecords", integrity.expectedRecords());
      verificationDetails.put("reconstructedRecords", integrity.reconstructedRecords());
      verificationDetails.put(
          "sha256Match", integrity.sourceSha256().equals(integrity.reconstructedSha256()));
      event.accept(EventType.RX_STATUS, verificationDetails);

      // Receiver 자신의 RoleResult는 중앙 서버 WebSocket으로 먼저 보내고,
      // 아래의 축약 WireResult는 UDP로 Sender에 돌려줘 Sender 결과 생성에 사용한다.
      sink.accept(result);

      byte[] payload = json.writeValueAsBytes(wire);
      if (payload.length > MAXIMUM_PAYLOAD_LENGTH) {
        throw new IllegalStateException("Compact result exceeds UDP limit");
      }
      Packet resultPacket =
          new Packet(
              Kind.RESULT, manifest.testId, 0, 0, manifest.prn, 0, 0, 0, utcTicks(), payload);
      sendCopies(
          socket, senderAddress, senderPort, resultPacket, command.transport().repeatCount());
    } catch (Exception error) {
      RoleResult result = failed(requestedId, AgentRole.RECEIVER, error);
      sink.accept(result);
      event.accept(EventType.ERROR, Map.of("message", safe(error)));
    } finally {
      activeSocket = null;
    }
  }

  /**
   * Test D는 수신 프레임을 연속 bit stream으로 연결한 뒤 SP를 다시 탐색한다.
   *
   * <p>PocketSDR-AFS 수신기는 68심볼 SP 두 개가 6,000심볼 간격으로 완전히 일치할 때 프레임 동기를 인정한다. 이 오프라인 검증기는 유한한 stream의
   * 마지막 프레임도 검증할 수 있도록 앞 또는 뒤의 정상 SP와 쌍을 이루는 SP를 채택한다. 나머지 시험은 UDP가 제공한 논리 frame 경계를 그대로 사용한다.
   */
  private DecodeAggregate decodeFrames(Collection<ReceivedFrame> input, TestType type) {
    DecodeAggregate total = new DecodeAggregate();
    if (type == TestType.TEST_D_SYNC_RECOVERY) {
      byte[] joined =
          input.stream()
              .flatMapToInt(
                  frame ->
                      java.util.stream.IntStream.range(0, frame.payload.length)
                          .map(index -> frame.payload[index] & 0xff))
              .collect(
                  ByteArrayOutputStream::new,
                  (output, value) -> output.write(value),
                  (left, right) -> left.writeBytes(right.toByteArray()))
              .toByteArray();
      List<Long> offsets = findConfirmedSyncOffsets(joined);
      List<ReceivedFrame> ordered = new ArrayList<>(input);
      total.recoveredSyncFrames = offsets.size();
      for (long offset : offsets) {
        int source = (int) (offset / 6000);
        if (source < ordered.size()) {
          decodeOne(extract(joined, offset), ordered.get(source), total);
        }
      }
      return total;
    }
    for (ReceivedFrame frame : input) {
      decodeOne(frame.payload, frame, total);
    }
    return total;
  }

  /** 프레임 하나를 복호화하고 CRC가 유효한 GRAW 조각만 재조립기에 전달한다. */
  private void decodeOne(byte[] frame, ReceivedFrame source, DecodeAggregate total) {
    int frameIndex = Math.toIntExact(source.sequence);
    try {
      var decoded = codec.decode(source.toi, frame);
      total.decodedFrames++;
      total.reencodedFrames.put(
          frameIndex, codec.encode(source.toi, decoded.sb2(), decoded.sb3(), decoded.sb4()));
      if (decoded.sb2Valid()) {
        total.sb2Valid++;
        total.sb2Ephemeris.put(
            frameIndex,
            Sb2PayloadCodec.decode(
                decoded.sb2(), source.prn, source.week, source.afsIntervalOfWeek));
      }
      if (decoded.sb3Valid()) {
        total.sb3Valid++;
      }
      if (decoded.sb4Valid()) {
        total.sb4Valid++;
      }
      total.corrected +=
          Math.max(0, decoded.sb2Corrections())
              + Math.max(0, decoded.sb3Corrections())
              + Math.max(0, decoded.sb4Corrections());
      boolean fullyDecoded = decoded.sb2Valid() && decoded.sb3Valid() && decoded.sb4Valid();
      // 실제 GRAW 조각은 SB3/SB4에 있으므로 SB2 실패만으로 재조립 가능한 데이터를 버리지는 않는다.
      boolean usedForGraw = decoded.sb3Valid() && decoded.sb4Valid();
      if (fullyDecoded) {
        total.fullyDecodedFrames++;
      }
      total.diagnostics.put(
          frameIndex,
          new FrameDecodeDiagnostic(
              true,
              fullyDecoded,
              decoded.sb2Valid(),
              decoded.sb3Valid(),
              decoded.sb4Valid(),
              Math.max(0, decoded.sb2Corrections()),
              Math.max(0, decoded.sb3Corrections()),
              Math.max(0, decoded.sb4Corrections()),
              usedForGraw,
              failureReason(decoded)));
      if (!fullyDecoded) {
        total.decodeFailedFrames++;
      }
      if (!usedForGraw) {
        return;
      }
      total.reassembler.add(
          AfsRawFragmentCodec.decode(AfsRawFragmentCodec.fromSbBits(decoded.sb3())));
      total.reassembler.add(
          AfsRawFragmentCodec.decode(AfsRawFragmentCodec.fromSbBits(decoded.sb4())));
    } catch (Exception error) {
      total.decodeFailedFrames++;
      total.diagnostics.put(
          frameIndex,
          new FrameDecodeDiagnostic(
              false, false, false, false, false, 0, 0, 0, false, safe(error)));
    }
  }

  /** CRC 실패 블록을 일반 사용자가 바로 확인할 수 있는 한글 원인으로 조합한다. */
  private static String failureReason(NativeAfsCodec.Decoded decoded) {
    List<String> failed = new ArrayList<>();
    if (!decoded.sb2Valid()) {
      failed.add("SB2 CRC 실패");
    }
    if (!decoded.sb3Valid()) {
      failed.add("SB3 CRC 실패");
    }
    if (!decoded.sb4Valid()) {
      failed.add("SB4 CRC 실패");
    }
    return failed.isEmpty() ? null : String.join(", ", failed);
  }

  /** AFS Data Frame의 고정 68심볼 동기 패턴이다. 마지막 byte의 하위 4bit는 사용하지 않는다. */
  private static final byte[] SYNC = {
    (byte) 0xCC, 0x63, (byte) 0xF7, 0x45, 0x36, (byte) 0xF4, (byte) 0x9E, 0x04, (byte) 0xA0
  };

  /**
   * PocketSDR-AFS의 동기 판정을 본떠 68심볼 완전 일치 후보를 먼저 찾고, 서로 정확히 6,000심볼 떨어진 후보끼리만 확정한다. 단독으로 우연히 나타난 동일
   * bit열은 프레임 동기로 채택하지 않는다.
   *
   * <p>실시간 수신기는 현재 SP와 다음 SP를 함께 본다. 이 메서드는 저장이 끝난 유한한 시험 stream을 다루므로 마지막 프레임에 한해 직전 SP와의 쌍도 인정한다.
   */
  static List<Long> findConfirmedSyncOffsets(byte[] bytes) {
    List<Long> candidates = new ArrayList<>();
    long bits = (long) bytes.length * 8;
    for (long offset = 0; offset + 6000 <= bits; offset++) {
      boolean matched = true;
      for (int index = 0; index < 68; index++) {
        if (bit(bytes, offset + index) != bit(SYNC, index)) {
          matched = false;
          break;
        }
      }
      if (matched) {
        candidates.add(offset);
        offset += 67;
      }
    }

    var candidateSet = new java.util.HashSet<>(candidates);
    return candidates.stream()
        .filter(
            offset -> candidateSet.contains(offset - 6000) || candidateSet.contains(offset + 6000))
        .toList();
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
    final Map<Integer, FrameDecodeDiagnostic> diagnostics = new HashMap<>();
    final Map<Integer, Sb2EphemerisResult> sb2Ephemeris = new HashMap<>();
    long decodedFrames;
    long fullyDecodedFrames;
    long sb2Valid;
    long sb3Valid;
    long sb4Valid;
    long corrected;
    long recoveredSyncFrames;

    /** Decoder 처리 오류 또는 SB2/SB3/SB4 CRC 실패가 발생한 AFS 프레임 수다. */
    long decodeFailedFrames;
  }

  /** Receiver가 프레임 하나를 처리하며 얻은 블록별 CRC와 GRAW 사용 여부다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  private static class FrameDecodeDiagnostic {
    /** Native Decoder가 예외 없이 반환했는지 여부다. */
    boolean decoderCompleted;

    /** SB2·SB3·SB4 CRC가 모두 정상인지 여부다. */
    boolean fullyDecoded;

    /** SB2 CRC 정상 여부다. */
    boolean sb2Valid;

    /** SB3 CRC 정상 여부다. */
    boolean sb3Valid;

    /** SB4 CRC 정상 여부다. */
    boolean sb4Valid;

    /** SB2 LDPC 내부 판정 변경량이다. */
    int sb2DecisionChanges;

    /** SB3 LDPC 내부 판정 변경량이다. */
    int sb3DecisionChanges;

    /** SB4 LDPC 내부 판정 변경량이다. */
    int sb4DecisionChanges;

    /** SB3·SB4 조각을 GRAW 재조립기에 전달했는지 여부다. */
    boolean usedForGrawReassembly;

    /** 실패한 CRC 블록 또는 Decoder 예외 설명이다. */
    String failureReason;
  }

  /**
   * 500프레임 이하는 모두 보관하고, 초과하면 시험 시작과 끝을 각각 250프레임씩 보관한다. 전체 프레임 수가 아무리 커도 WebSocket과 Redis 사용량이 선형으로
   * 증가하지 않는다.
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
    metrics.add(metric("FullyDecodedFrames", result.fullyDecodedFrames, "frame"));
    metrics.add(metric("Sb2CrcValidFrames", result.sb2ValidFrames, "frame"));
    metrics.add(metric("Sb3CrcValidFrames", result.sb3ValidFrames, "frame"));
    metrics.add(metric("Sb4CrcValidFrames", result.sb4ValidFrames, "frame"));
    metrics.add(
        new Metric(
            MetricCategory.DATA_INTEGRITY,
            "CorrectedSymbols",
            "LDPC decoder internal decision changes; not the injected error count",
            "bit",
            (double) result.correctedSymbols,
            MetricStatus.MEASURED,
            null,
            null));
    if (result.recoveredSyncFrames > 0) {
      metrics.add(metric("RecoveredSyncFrames", result.recoveredSyncFrames, "frame"));
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
    IntegrityResult integrity = new IntegrityResult(false, 0, 0, "", "", 0, 0, safe(error));
    NetworkCounters counters =
        new NetworkCounters(0, 0, 0, 0, 0, 0, 0, 0, 0, Duration.ZERO, List.of(), 0, 0, 0, 0, 0, 0);
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
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
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
      DatagramSocket socket, InetAddress address, int port, Packet packet, int repeat)
      throws Exception {
    for (int copy = 0; copy < repeat; copy++) {
      send(socket, address, port, withCopy(packet, copy));
    }
  }

  private static void send(DatagramSocket socket, InetAddress address, int port, Packet packet)
      throws Exception {
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

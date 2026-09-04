package server.protocol.model;

import static server.protocol.model.LnisModels.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 중앙 서버와 Windows Agent가 교환하는 WebSocket protocol 계약이다.
 *
 * <p>모든 메시지는 {@link Envelope}로 감싸며 {@link #PROTOCOL_VERSION}이 다른 메시지는 Agent가 처리하지 않는다. payload는 메시지
 * 종류에 따라 아래 불변 DTO 중 하나로 역직렬화한다. 새 필드를 추가할 때는 구버전 수신자가 알 수 없는 필드를 무시하는지 확인하고, 의미가 바뀌는 변경은 protocol
 * version을 올려야 한다.
 */
public final class AgentProtocol {
  /** 현재 서버/Agent wire protocol version이다. */
  public static final int PROTOCOL_VERSION = 2;

  private AgentProtocol() {}

  /** Envelope payload가 어떤 형태인지 결정하는 최상위 메시지 종류다. */
  public enum MessageType {
    HELLO,
    HELLO_ACK,
    HEARTBEAT,
    COMMAND,
    COMMAND_ACK,
    STATUS,
    PORT_LIST,
    INPUT_CHUNK,
    INPUT_COMPLETE,
    FRAME_EVIDENCE,
    ROLE_RESULT,
    ERROR
  }

  /** 중앙 서버가 Agent에 실행을 요청할 수 있는 명령 목록이다. */
  public enum CommandType {
    LIST_PORTS,
    START_CAPTURE,
    STOP_CAPTURE,
    ARM_RECEIVER,
    START_SENDER,
    CANCEL_SESSION
  }

  /** Agent 상태를 브라우저 화면에 전달할 때 사용하는 실시간 이벤트 종류다. */
  public enum EventType {
    AGENT_STATUS,
    GNSS_STATUS,
    TX_STATUS,
    RX_STATUS,
    SESSION_STATUS,
    RESULT,
    ERROR
  }

  /**
   * WebSocket으로 전달되는 공통 envelope다.
   *
   * @param correlationId 응답이 참조하는 원본 message ID이며 단방향 메시지는 {@code null}
   * @param sessionId 특정 시험/수집에 속하지 않는 Agent 이벤트는 {@code null}
   * @param payload 메시지 종류별 JSON payload
   */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Envelope {
    /** 발신 측의 wire protocol 버전이며 현재 값은 {@link #PROTOCOL_VERSION}이다. */
    int protocolVersion;

    /** {@link #payload()}를 어떤 DTO로 역직렬화할지 결정하는 메시지 종류다. */
    MessageType type;

    /** 메시지마다 새로 발급하는 UUID로 ACK와 로그 추적의 기준이다. */
    UUID messageId;

    /** 응답이 참조하는 원본 메시지 UUID이며 단방향 이벤트에서는 {@code null}이다. */
    UUID correlationId;

    /** 발신 또는 대상 Agent ID이며 서버 자체 이벤트에서는 {@code null}일 수 있다. */
    String agentId;

    /** Agent의 Sender/Receiver 역할이며 역할 무관 이벤트에서는 {@code null}일 수 있다. */
    AgentRole role;

    /** 관련 시험·수집 세션 UUID이며 연결 이벤트에서는 {@code null}일 수 있다. */
    UUID sessionId;

    /** 발신 측에서 메시지를 생성한 UTC 시각이다. */
    Instant occurredAt;

    /** 메시지 종류별 실제 데이터이며 수신 측에서 알맞은 DTO로 변환한다. */
    JsonNode payload;

    /** 새 단방향 메시지에 UUID와 현재 시각, protocol version을 자동으로 채운다. */
    public static Envelope of(
        MessageType type, String agentId, AgentRole role, UUID sessionId, JsonNode payload) {
      return new Envelope(
          PROTOCOL_VERSION,
          type,
          UUID.randomUUID(),
          null,
          agentId,
          role,
          sessionId,
          Instant.now(),
          payload);
    }
  }

  /** Agent 접속 직후 버전, OS와 지원 기능을 서버에 알리는 payload다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Hello {
    /** Agent 애플리케이션 배포 버전 문자열이다. */
    String agentVersion;

    /** Native AFS Codec 라이브러리의 ABI 버전이다. */
    int codecAbiVersion;

    /** Agent가 실행되는 운영체제 이름이다. */
    String os;

    /** Agent JVM의 CPU 아키텍처 문자열이다. */
    String architecture;

    /** GNSS 수집 등 기능 이름별 지원 여부를 담은 확장 가능한 맵이다. */
    Map<String, Boolean> capabilities;

    List<String> ipv4Addresses;
  }

  /** Agent 생존 여부와 현재 작업 상태를 5초 주기로 알리는 payload다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Heartbeat {
    /** Heartbeat 생성 시점의 READY/BUSY/ERROR 등 Agent 상태다. */
    AgentState state;

    /** 수행 중인 명령 이름이며 대기 중에는 {@code null}일 수 있다. */
    String activeOperation;

    /** Agent 기동 후 증가하는 순번으로 누락·역순 탐지에 사용한다. */
    long sequence;
  }

  /** 명령 종류와 명령별 JSON 인수를 함께 전달한다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Command {
    /** COM 조회, 수집, 송수신 또는 취소 중 수행할 명령이다. */
    CommandType command;

    /** 명령별 설정 객체이며 Agent가 해당 설정 DTO로 변환한다. */
    JsonNode arguments;
  }

  /** Agent가 명령의 접수 여부를 원본 message ID와 연계해 반환한다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class CommandAck {
    /** 명령을 실행 큐에 접수했는지 여부이며 최종 작업 성공을 뜻하지는 않는다. */
    boolean accepted;

    /** 접수 또는 거절 이유를 설명하는 사용자·로그용 메시지다. */
    String message;
  }

  /** 운영체제에서 발견한 COM 포트 한 개의 표시 정보다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class PortDescriptor {
    /** API 명령에 사용할 Windows 포트 이름이다. 예: {@code COM3}. */
    String name;

    /** 장치 드라이버가 제공하는 사람이 읽을 수 있는 포트 설명이다. */
    String description;
  }

  /** COM 포트 새로고침 명령에 대한 포트 목록 payload다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class PortList {
    /** 조회 시점에 운영체제가 열거한 직렬 포트 목록이며 없으면 빈 목록이다. */
    List<PortDescriptor> ports;
  }

  /** GNSS/TX/RX 작업의 진행률, 단계, 사용자 메시지와 추가 카운터를 전달한다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class Progress {
    /** GNSS, TX, RX 등 브라우저에서 분류할 실시간 이벤트 종류다. */
    EventType type;

    /** 해당 작업의 0~100 범위 진행률이다. */
    int percent;

    /** 수신, 복호화, 검증 등 기계가 구분할 현재 단계 이름이다. */
    String stage;

    /** 이벤트 로그에 표시할 사용자용 진행 설명이다. */
    String message;

    /** 프레임 수, byte 수 등 단계별 부가 값을 담는 확장 가능한 맵이다. */
    Map<String, Object> counters;
  }

  /**
   * 한 Agent가 확보한 AFS 6,000비트 프레임 증거를 서버로 전달한다.
   *
   * <p>Sender는 {@code referenceFrame}/{@code transmittedFrame}, Receiver는 {@code
   * receivedFrame}/{@code reencodedFrame}을 채운다. 역할별로 확보할 수 없는 값은 null이며, 서버가 sessionId와 frameIndex를
   * 기준으로 두 메시지를 하나의 비교 자료로 병합한다.
   */
  @lombok.Value
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class FrameEvidenceMessage {
    /** 세션 내부의 0부터 시작하는 논리 AFS 프레임 번호다. */
    int frameIndex;

    /** Sender가 GRAW를 정상 인코딩한 오류 주입 전 750 byte 기준 프레임이다. */
    byte[] referenceFrame;

    /** 시험 오류를 주입한 뒤 Sender가 실제 UDP로 보낸 750 byte 프레임이다. */
    byte[] transmittedFrame;

    /** Receiver가 UDP 패킷 검사를 통과시켜 채택한 750 byte 프레임이다. */
    byte[] receivedFrame;

    /** Receiver 복호 결과를 동일 TOI로 다시 인코딩한 진단용 750 byte 프레임이다. */
    byte[] reencodedFrame;

    /** Sender가 반전한 0~5,999 범위 비트 위치 목록이다. */
    List<Integer> injectedBitPositions;

    /** SB2·SB3·SB4 CRC가 모두 정상인 완전 복호 여부다. */
    boolean decodeSucceeded;

    /** Native Decoder가 예외 없이 반환했는지 여부이며 CRC 성공을 의미하지 않는다. */
    boolean decoderCompleted;

    /** SB2 복호 데이터의 CRC 정상 여부다. */
    boolean sb2CrcValid;

    /** SB3 복호 데이터의 CRC 정상 여부다. */
    boolean sb3CrcValid;

    /** SB4 복호 데이터의 CRC 정상 여부다. */
    boolean sb4CrcValid;

    /** SB2 LDPC 내부 판정 변경량이며 시험에서 주입한 비트 수가 아니다. */
    int sb2DecisionChanges;

    /** SB3 LDPC 내부 판정 변경량이다. */
    int sb3DecisionChanges;

    /** SB4 LDPC 내부 판정 변경량이다. */
    int sb4DecisionChanges;

    /** SB3·SB4 CRC가 정상이라 GRAW 조각을 재조립에 사용했는지 여부다. */
    boolean usedForGrawReassembly;

    /** CRC 정상 SB2에서 해석한 LANS ephemeris와 profile 검증 결과다. */
    Sb2EphemerisResult sb2Ephemeris;

    /** 실패한 SB CRC 또는 Decoder 예외의 요약 설명이다. */
    String failureReason;

    /** Test D 동기 제외 등 Agent가 서버에 전달하는 추가 진단 메모다. */
    String note;

    public FrameEvidenceMessage(
        /** 세션 내부의 0부터 시작하는 논리 AFS 프레임 번호다. */
        int frameIndex,
        /** Sender가 GRAW를 정상 인코딩한 오류 주입 전 750 byte 기준 프레임이다. */
        byte[] referenceFrame,
        /** 시험 오류를 주입한 뒤 Sender가 실제 UDP로 보낸 750 byte 프레임이다. */
        byte[] transmittedFrame,
        /** Receiver가 UDP 패킷 검사를 통과시켜 채택한 750 byte 프레임이다. */
        byte[] receivedFrame,
        /** Receiver 복호 결과를 동일 TOI로 다시 인코딩한 진단용 750 byte 프레임이다. */
        byte[] reencodedFrame,
        /** Sender가 반전한 0~5,999 범위 비트 위치 목록이다. */
        List<Integer> injectedBitPositions,
        /** SB2·SB3·SB4 CRC가 모두 정상인 완전 복호 여부다. */
        boolean decodeSucceeded,
        /** Native Decoder가 예외 없이 반환했는지 여부이며 CRC 성공을 의미하지 않는다. */
        boolean decoderCompleted,
        /** SB2 복호 데이터의 CRC 정상 여부다. */
        boolean sb2CrcValid,
        /** SB3 복호 데이터의 CRC 정상 여부다. */
        boolean sb3CrcValid,
        /** SB4 복호 데이터의 CRC 정상 여부다. */
        boolean sb4CrcValid,
        /** SB2 LDPC 내부 판정 변경량이며 시험에서 주입한 비트 수가 아니다. */
        int sb2DecisionChanges,
        /** SB3 LDPC 내부 판정 변경량이다. */
        int sb3DecisionChanges,
        /** SB4 LDPC 내부 판정 변경량이다. */
        int sb4DecisionChanges,
        /** SB3·SB4 CRC가 정상이라 GRAW 조각을 재조립에 사용했는지 여부다. */
        boolean usedForGrawReassembly,
        /** CRC 정상 SB2에서 해석한 LANS ephemeris와 profile 검증 결과다. */
        Sb2EphemerisResult sb2Ephemeris,
        /** 실패한 SB CRC 또는 Decoder 예외의 요약 설명이다. */
        String failureReason,
        /** Test D 동기 제외 등 Agent가 서버에 전달하는 추가 진단 메모다. */
        String note) {
      referenceFrame = copy(referenceFrame);
      transmittedFrame = copy(transmittedFrame);
      receivedFrame = copy(receivedFrame);
      reencodedFrame = copy(reencodedFrame);
      injectedBitPositions =
          injectedBitPositions == null ? List.of() : List.copyOf(injectedBitPositions);

      this.frameIndex = frameIndex;
      this.referenceFrame = referenceFrame;
      this.transmittedFrame = transmittedFrame;
      this.receivedFrame = receivedFrame;
      this.reencodedFrame = reencodedFrame;
      this.injectedBitPositions = injectedBitPositions;
      this.decodeSucceeded = decodeSucceeded;
      this.decoderCompleted = decoderCompleted;
      this.sb2CrcValid = sb2CrcValid;
      this.sb3CrcValid = sb3CrcValid;
      this.sb4CrcValid = sb4CrcValid;
      this.sb2DecisionChanges = sb2DecisionChanges;
      this.sb3DecisionChanges = sb3DecisionChanges;
      this.sb4DecisionChanges = sb4DecisionChanges;
      this.usedForGrawReassembly = usedForGrawReassembly;
      this.sb2Ephemeris = sb2Ephemeris;
      this.failureReason = failureReason;
      this.note = note;
    }

    private static byte[] copy(byte[] value) {
      return value == null ? null : value.clone();
    }
  }

  /** 서버가 순번과 발생 시각을 붙여 브라우저 상태 WebSocket으로 방송하는 이벤트다. */
  @lombok.Value
  @lombok.AllArgsConstructor
  @lombok.Builder
  @lombok.extern.jackson.Jacksonized
  @lombok.experimental.Accessors(fluent = true)
  @com.fasterxml.jackson.annotation.JsonAutoDetect(
      fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
  public static class BrowserEvent {
    /** 서버 프로세스 안에서 1씩 증가하는 브라우저 이벤트 순번이다. */
    long sequence;

    /** 화면이 Agent/GNSS/TX/RX/세션/결과를 구분하는 이벤트 종류다. */
    EventType type;

    /** 서버가 브라우저 이벤트를 생성한 UTC 시각이다. */
    Instant occurredAt;

    /** 관련 Agent ID이며 서버 자체 세션 이벤트에서는 {@code null}일 수 있다. */
    String agentId;

    /** 관련 Agent 역할이며 역할 무관 이벤트에서는 {@code null}일 수 있다. */
    AgentRole role;

    /** 관련 시험 세션 UUID이며 연결 상태 이벤트에서는 {@code null}일 수 있다. */
    UUID sessionId;

    /** 이벤트 종류별 DTO 또는 Map으로 구성된 화면 전달 데이터다. */
    Object payload;
  }
}

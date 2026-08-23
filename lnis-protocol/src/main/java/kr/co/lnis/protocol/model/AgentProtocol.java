package kr.co.lnis.protocol.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static kr.co.lnis.protocol.model.LnisModels.*;

/**
 * 중앙 서버와 Windows Agent가 교환하는 WebSocket protocol 계약이다.
 *
 * <p>모든 메시지는 {@link Envelope}로 감싸며 {@link #PROTOCOL_VERSION}이 다른 메시지는 Agent가
 * 처리하지 않는다. payload는 메시지 종류에 따라 아래 record 중 하나로 역직렬화한다. 새 필드를
 * 추가할 때는 구버전 수신자가 알 수 없는 필드를 무시하는지 확인하고, 의미가 바뀌는 변경은 protocol
 * version을 올려야 한다.
 */
public final class AgentProtocol {
    /** 현재 서버/Agent wire protocol version이다. */
    public static final int PROTOCOL_VERSION = 1;
    private AgentProtocol() {}

    /** Envelope payload가 어떤 형태인지 결정하는 최상위 메시지 종류다. */
    public enum MessageType {
        HELLO, HELLO_ACK, HEARTBEAT, COMMAND, COMMAND_ACK, STATUS, PORT_LIST,
        INPUT_CHUNK, INPUT_COMPLETE, FRAME_EVIDENCE, ROLE_RESULT, ERROR
    }

    /** 중앙 서버가 Agent에 실행을 요청할 수 있는 명령 목록이다. */
    public enum CommandType {
        LIST_PORTS, START_CAPTURE, STOP_CAPTURE, ARM_RECEIVER, START_SENDER, CANCEL_SESSION
    }

    /** Agent 상태를 브라우저 화면에 전달할 때 사용하는 실시간 이벤트 종류다. */
    public enum EventType { AGENT_STATUS, GNSS_STATUS, TX_STATUS, RX_STATUS, SESSION_STATUS, RESULT, ERROR }

    /**
     * WebSocket으로 전달되는 공통 envelope다.
     *
     * @param correlationId 응답이 참조하는 원본 message ID이며 단방향 메시지는 {@code null}
     * @param sessionId 특정 시험/수집에 속하지 않는 Agent 이벤트는 {@code null}
     * @param payload 메시지 종류별 JSON payload
     */
    public record Envelope(
            int protocolVersion,
            MessageType type,
            UUID messageId,
            UUID correlationId,
            String agentId,
            AgentRole role,
            UUID sessionId,
            Instant occurredAt,
            JsonNode payload) {
        /** 새 단방향 메시지에 UUID와 현재 시각, protocol version을 자동으로 채운다. */
        public static Envelope of(MessageType type, String agentId, AgentRole role, UUID sessionId, JsonNode payload) {
            return new Envelope(PROTOCOL_VERSION, type, UUID.randomUUID(), null, agentId, role, sessionId, Instant.now(), payload);
        }
    }

    /** Agent 접속 직후 version, OS와 지원 기능을 서버에 알리는 payload다. */
    public record Hello(String agentVersion, int codecAbiVersion, String os, String architecture, Map<String, Boolean> capabilities) {}

    /** Agent 생존 여부와 현재 작업 상태를 5초 주기로 알리는 payload다. */
    public record Heartbeat(AgentState state, String activeOperation, long sequence) {}

    /** 명령 종류와 명령별 JSON 인수를 함께 전달한다. */
    public record Command(CommandType command, JsonNode arguments) {}

    /** Agent가 명령의 접수 여부를 원본 message ID와 연계해 반환한다. */
    public record CommandAck(boolean accepted, String message) {}

    /** 운영체제에서 발견한 COM 포트 한 개의 표시 정보다. */
    public record PortDescriptor(String name, String description) {}

    /** COM 포트 새로고침 명령에 대한 포트 목록 payload다. */
    public record PortList(List<PortDescriptor> ports) {}

    /** GNSS/TX/RX 작업의 진행률, 단계, 사용자 메시지와 추가 카운터를 전달한다. */
    public record Progress(EventType type, int percent, String stage, String message, Map<String, Object> counters) {}

    /**
     * 한 Agent가 확보한 AFS 6,000비트 프레임 증거를 서버로 전달한다.
     *
     * <p>Sender는 {@code referenceFrame}/{@code transmittedFrame}, Receiver는
     * {@code receivedFrame}/{@code reencodedFrame}을 채운다. 역할별로 확보할 수 없는 값은 null이며,
     * 서버가 sessionId와 frameIndex를 기준으로 두 메시지를 하나의 비교 자료로 병합한다.
     */
    public record FrameEvidenceMessage(
            int frameIndex,
            byte[] referenceFrame,
            byte[] transmittedFrame,
            byte[] receivedFrame,
            byte[] reencodedFrame,
            List<Integer> injectedBitPositions,
            boolean decodeSucceeded,
            boolean decoderCompleted,
            boolean sb2CrcValid,
            boolean sb3CrcValid,
            boolean sb4CrcValid,
            int sb2DecisionChanges,
            int sb3DecisionChanges,
            int sb4DecisionChanges,
            boolean usedForGrawReassembly,
            String failureReason,
            String note) {
        public FrameEvidenceMessage {
            referenceFrame = copy(referenceFrame);
            transmittedFrame = copy(transmittedFrame);
            receivedFrame = copy(receivedFrame);
            reencodedFrame = copy(reencodedFrame);
            injectedBitPositions = injectedBitPositions == null
                    ? List.of()
                    : List.copyOf(injectedBitPositions);
        }

        private static byte[] copy(byte[] value) {
            return value == null ? null : value.clone();
        }
    }

    /** 서버가 순번과 발생 시각을 붙여 브라우저 상태 WebSocket으로 방송하는 이벤트다. */
    public record BrowserEvent(long sequence, EventType type, Instant occurredAt, String agentId, AgentRole role, UUID sessionId, Object payload) {}
}

package kr.co.lnis.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static kr.co.lnis.common.model.LnisModels.*;

public final class AgentProtocol {
    public static final int PROTOCOL_VERSION = 1;
    private AgentProtocol() {}

    public enum MessageType {
        HELLO, HELLO_ACK, HEARTBEAT, COMMAND, COMMAND_ACK, STATUS, PORT_LIST,
        INPUT_CHUNK, INPUT_COMPLETE, ROLE_RESULT, ERROR
    }

    public enum CommandType {
        LIST_PORTS, START_CAPTURE, STOP_CAPTURE, ARM_RECEIVER, START_SENDER, CANCEL_SESSION
    }

    public enum EventType { AGENT_STATUS, GNSS_STATUS, TX_STATUS, RX_STATUS, SESSION_STATUS, RESULT, ERROR }

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
        public static Envelope of(MessageType type, String agentId, AgentRole role, UUID sessionId, JsonNode payload) {
            return new Envelope(PROTOCOL_VERSION, type, UUID.randomUUID(), null, agentId, role, sessionId, Instant.now(), payload);
        }
    }

    public record Hello(String agentVersion, int codecAbiVersion, String os, String architecture, Map<String, Boolean> capabilities) {}
    public record Heartbeat(AgentState state, String activeOperation, long sequence) {}
    public record Command(CommandType command, JsonNode arguments) {}
    public record CommandAck(boolean accepted, String message) {}
    public record PortDescriptor(String name, String description) {}
    public record PortList(List<PortDescriptor> ports) {}
    public record Progress(EventType type, int percent, String stage, String message, Map<String, Object> counters) {}
    public record BrowserEvent(long sequence, EventType type, Instant occurredAt, String agentId, AgentRole role, UUID sessionId, Object payload) {}
}


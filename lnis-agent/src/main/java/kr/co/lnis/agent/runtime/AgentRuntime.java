package kr.co.lnis.agent.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import kr.co.lnis.agent.gnss.SerialCaptureService;
import kr.co.lnis.agent.codec.NativeAfsCodec;
import kr.co.lnis.agent.config.AgentConfig;
import kr.co.lnis.agent.session.transport.UdpSessionService;
import kr.co.lnis.common.model.AgentProtocol.*;
import kr.co.lnis.common.model.LnisModels.AgentState;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 서버 명령을 GNSS 수집 또는 Sender/Receiver UDP 작업으로 분배하는 Agent 핵심 런타임이다. */
public final class AgentRuntime implements AutoCloseable {
    private final AgentConfig config;
    private final NativeAfsCodec codec;
    private final SerialCaptureService capture = new SerialCaptureService();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<AgentState> state =
            new AtomicReference<>(AgentState.READY);
    private final UdpSessionService udp;
    private final Map<UUID, ByteArrayOutputStream> sessionInputs =
            new ConcurrentHashMap<>();
    private volatile Consumer<Envelope> outbound = ignored -> {};

    public AgentRuntime(AgentConfig config, NativeAfsCodec codec) {
        this.config = config;
        this.codec = codec;
        this.udp = new UdpSessionService(codec);
        json.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public void outbound(Consumer<Envelope> outbound) {
        this.outbound = outbound;
    }

    public AgentState state() {
        return state.get();
    }

    public void handle(Envelope envelope) {
        if (envelope.protocolVersion()
                != kr.co.lnis.common.model.AgentProtocol.PROTOCOL_VERSION) {
            return;
        }
        try {
            if (envelope.type() == MessageType.INPUT_CHUNK) {
                receiveInputChunk(envelope);
                return;
            }
            if (envelope.type() == MessageType.INPUT_COMPLETE) {
                int bytes = sessionInputs
                        .getOrDefault(envelope.sessionId(), new ByteArrayOutputStream())
                        .size();
                status(
                        envelope.sessionId(),
                        EventType.TX_STATUS,
                        10,
                        "InputReady",
                        "Input transfer completed",
                        Map.of("bytes", bytes));
                return;
            }
            if (envelope.type() != MessageType.COMMAND) {
                return;
            }
            Command command = json.treeToValue(envelope.payload(), Command.class);
            switch (command.command()) {
                case LIST_PORTS -> send(
                        MessageType.PORT_LIST,
                        envelope.sessionId(),
                        json.valueToTree(new PortList(capture.portNames().stream()
                                .map(name -> new PortDescriptor(name, name))
                                .toList())));
                case START_CAPTURE -> startCapture(
                        envelope.sessionId(), command.arguments());
                case STOP_CAPTURE -> stopCapture(envelope.sessionId());
                case CANCEL_SESSION -> cancel(envelope.sessionId());
                case ARM_RECEIVER -> startReceiver(
                        envelope.sessionId(), command.arguments());
                case START_SENDER -> startSender(
                        envelope.sessionId(), command.arguments());
            }
            ack(envelope, true, "Accepted");
        } catch (Exception error) {
            ack(envelope, false, error.getMessage());
            status(
                    envelope.sessionId(),
                    EventType.ERROR,
                    0,
                    "Failed",
                    safe(error),
                    Map.of());
            state.set(AgentState.ERROR);
        }
    }

    private void receiveInputChunk(Envelope envelope) {
        byte[] data = Base64.getDecoder().decode(
                envelope.payload().path("dataBase64").asText());
        sessionInputs.computeIfAbsent(
                        envelope.sessionId(), ignored -> new ByteArrayOutputStream())
                .writeBytes(data);
    }

    private void stopCapture(UUID sessionId) {
        capture.stop();
        state.set(AgentState.READY);
        status(
                sessionId,
                EventType.GNSS_STATUS,
                100,
                "Stopped",
                "GNSS capture stopped",
                Map.of());
    }

    private void cancel(UUID sessionId) {
        capture.stop();
        udp.cancel();
        sessionInputs.remove(sessionId);
        state.set(AgentState.READY);
        status(
                sessionId,
                EventType.SESSION_STATUS,
                0,
                "Cancelled",
                "Operation cancelled",
                Map.of());
    }

    private void startReceiver(UUID sessionId, JsonNode args) throws Exception {
        if (config.role() != kr.co.lnis.common.model.LnisModels.AgentRole.RECEIVER) {
            throw new IllegalStateException("Only RECEIVER can arm UDP reception");
        }
        state.set(AgentState.BUSY);
        var command = json.treeToValue(args, UdpSessionService.SessionCommand.class);
        udp.receive(
                sessionId,
                command,
                (type, payload) -> event(sessionId, type, payload),
                result -> completeRole(sessionId, result));
    }

    private void startSender(UUID sessionId, JsonNode args) throws Exception {
        if (config.role() != kr.co.lnis.common.model.LnisModels.AgentRole.SENDER) {
            throw new IllegalStateException("Only SENDER can start UDP transmission");
        }
        ByteArrayOutputStream input = sessionInputs.remove(sessionId);
        if (input == null || input.size() == 0) {
            throw new IllegalStateException("No GRAW input was transferred");
        }
        state.set(AgentState.BUSY);
        var command = json.treeToValue(args, UdpSessionService.SessionCommand.class);
        udp.send(
                sessionId,
                command,
                input.toByteArray(),
                (type, payload) -> event(sessionId, type, payload),
                result -> completeRole(sessionId, result));
    }

    private void completeRole(UUID sessionId, Object result) {
        send(MessageType.ROLE_RESULT, sessionId, json.valueToTree(result));
        state.set(AgentState.READY);
    }

    private void startCapture(UUID sessionId, JsonNode args) throws Exception {
        if (config.role() != kr.co.lnis.common.model.LnisModels.AgentRole.SENDER) {
            throw new IllegalStateException("Only SENDER can capture GNSS");
        }
        var settings = json.treeToValue(args, SerialCaptureService.Settings.class);
        state.set(AgentState.BUSY);
        capture.start(
                settings,
                chunk -> publishCaptureChunk(sessionId, chunk),
                error -> {
                    state.set(AgentState.ERROR);
                    status(
                            sessionId,
                            EventType.ERROR,
                            0,
                            "CaptureFailed",
                            safe(error),
                            Map.of());
                });
    }

    private void publishCaptureChunk(
            UUID sessionId,
            SerialCaptureService.CaptureChunk chunk) {
        var payload = JsonNodeFactory.instance.objectNode()
                .put("chunkIndex", chunk.index())
                .put("bytesRead", chunk.bytesRead())
                .put("records", chunk.records())
                .put("rawBase64", Base64.getEncoder().encodeToString(chunk.rawSerial()))
                .put("canonicalBase64", Base64.getEncoder().encodeToString(chunk.canonical()));
        send(MessageType.INPUT_CHUNK, sessionId, payload);
        status(
                sessionId,
                EventType.GNSS_STATUS,
                0,
                "Capturing",
                chunk.bytesRead() + " bytes",
                Map.of("records", chunk.records()));
    }

    private void event(UUID sessionId, EventType type, Object payload) {
        if (payload instanceof Map<?, ?> map) {
            int percent = map.get("percent") instanceof Number number
                    ? number.intValue()
                    : 0;
            Object stageValue = map.containsKey("stage") ? map.get("stage") : "Running";
            Object messageValue = map.containsKey("message") ? map.get("message") : "";
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
            status(
                    sessionId,
                    type,
                    percent,
                    String.valueOf(stageValue),
                    String.valueOf(messageValue),
                    values);
        } else {
            send(MessageType.STATUS, sessionId, json.valueToTree(payload));
        }
    }

    private void ack(Envelope original, boolean accepted, String message) {
        outbound.accept(new Envelope(
                kr.co.lnis.common.model.AgentProtocol.PROTOCOL_VERSION,
                MessageType.COMMAND_ACK,
                UUID.randomUUID(),
                original.messageId(),
                config.agentId(),
                config.role(),
                original.sessionId(),
                java.time.Instant.now(),
                json.valueToTree(new CommandAck(accepted, message))));
    }

    private void send(MessageType type, UUID sessionId, JsonNode payload) {
        outbound.accept(Envelope.of(
                type, config.agentId(), config.role(), sessionId, payload));
    }

    public void status(
            UUID sessionId,
            EventType type,
            int percent,
            String stage,
            String message,
            Map<String, Object> counters) {
        send(
                MessageType.STATUS,
                sessionId,
                json.valueToTree(new Progress(type, percent, stage, message, counters)));
    }

    private static String safe(Throwable error) {
        return error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    @Override
    public void close() {
        capture.close();
        udp.close();
        codec.close();
    }
}

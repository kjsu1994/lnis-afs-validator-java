package kr.co.lnis.agent.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.agent.config.AgentConfig;
import kr.co.lnis.agent.runtime.AgentRuntime;
import kr.co.lnis.common.model.AgentProtocol.*;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** 중앙 서버 연결, 재연결, HELLO 및 heartbeat 전송을 관리한다. */
public final class AgentWebSocketClient implements WebSocket.Listener, AutoCloseable {
    private final AgentConfig config;
    private final AgentRuntime runtime;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> new Thread(runnable, "lnis-agent-heartbeat"));
    private final AtomicLong heartbeat = new AtomicLong();
    private final StringBuilder text = new StringBuilder();
    private volatile WebSocket socket;

    public AgentWebSocketClient(AgentConfig config, AgentRuntime runtime) {
        this.config = config;
        this.runtime = runtime;
        runtime.outbound(this::send);
    }

    public void start() {
        connect();
        scheduler.scheduleAtFixedRate(this::heartbeat, 5, 5, TimeUnit.SECONDS);
    }

    private void connect() {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().newWebSocketBuilder()
                .header("Authorization", "Bearer " + config.token())
                .header("X-LNIS-Agent-Id", config.agentId())
                .buildAsync(config.serverUri(), this)
                .whenComplete((webSocket, error) -> {
                    if (error != null) {
                        scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
                    } else {
                        socket = webSocket;
                    }
                });
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        Hello hello = new Hello(
                "1.0.0",
                1,
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                Map.of("com", config.role().name().equals("SENDER"), "udp", true));
        send(Envelope.of(
                MessageType.HELLO,
                config.agentId(),
                config.role(),
                null,
                json.valueToTree(hello)));
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        text.append(data);
        if (last) {
            try {
                runtime.handle(json.readValue(text.toString(), Envelope.class));
            } catch (Exception ignored) {
                // 잘못된 단일 메시지는 버리고 다음 서버 메시지를 계속 수신한다.
            } finally {
                text.setLength(0);
            }
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        socket = null;
        scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        socket = null;
        scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
        return CompletableFuture.completedFuture(null);
    }

    private void heartbeat() {
        Heartbeat payload = new Heartbeat(runtime.state(), "", heartbeat.incrementAndGet());
        send(Envelope.of(
                MessageType.HEARTBEAT,
                config.agentId(),
                config.role(),
                null,
                json.valueToTree(payload)));
    }

    private void send(Envelope envelope) {
        WebSocket current = socket;
        if (current != null) {
            try {
                current.sendText(json.writeValueAsString(envelope), true);
            } catch (Exception ignored) {
                // 재연결 스케줄러가 연결을 복구하므로 개별 전송 실패는 무시한다.
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
    }
}

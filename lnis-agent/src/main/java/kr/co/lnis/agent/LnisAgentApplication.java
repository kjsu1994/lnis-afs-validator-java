package kr.co.lnis.agent;

import kr.co.lnis.agent.codec.NativeAfsCodec;
import kr.co.lnis.agent.config.AgentConfig;
import kr.co.lnis.agent.connection.AgentWebSocketClient;
import kr.co.lnis.agent.runtime.AgentRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CountDownLatch;

/** 네이티브 코덱, Agent 런타임과 중앙 WebSocket 연결을 조립하는 진입점이다. */
public final class LnisAgentApplication {
    private static final Logger log = LoggerFactory.getLogger(LnisAgentApplication.class);
    public static void main(String[] args) throws Exception {
        AgentConfig config = AgentConfig.load(args);
        NativeAfsCodec codec = NativeAfsCodec.load(config.nativeDirectory());
        AgentRuntime runtime = new AgentRuntime(config, codec);
        AgentWebSocketClient client = new AgentWebSocketClient(config, runtime);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            client.close();
            runtime.close();
        }, "lnis-agent-shutdown"));
        client.start();
        log.info("LNIS {} agent {} started; native codec ABI {}", config.role(), config.agentId(), codec.abiVersion());
        new CountDownLatch(1).await();
    }
}

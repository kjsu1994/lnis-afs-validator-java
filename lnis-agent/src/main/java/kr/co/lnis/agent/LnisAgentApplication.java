package kr.co.lnis.agent;

import kr.co.lnis.agent.nativecodec.NativeAfsCodec;
import kr.co.lnis.agent.transport.AgentWebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CountDownLatch;

public final class LnisAgentApplication {
    private static final Logger log = LoggerFactory.getLogger(LnisAgentApplication.class);
    public static void main(String[] args) throws Exception {
        AgentConfig config = AgentConfig.load(args);
        NativeAfsCodec codec = NativeAfsCodec.load(config.nativeDirectory());
        AgentRuntime runtime = new AgentRuntime(config, codec);
        AgentWebSocketClient client = new AgentWebSocketClient(config, runtime);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { client.close(); runtime.close(); }, "lnis-agent-shutdown"));
        client.start();
        log.info("LNIS {} agent {} started; native codec ABI {}", config.role(), config.agentId(), codec.abiVersion());
        new CountDownLatch(1).await();
    }
}


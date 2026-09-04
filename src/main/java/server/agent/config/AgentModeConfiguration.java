package server.agent.config;

import java.util.Arrays;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import server.agent.codec.NativeAfsCodec;
import server.agent.connection.AgentWebSocketClient;
import server.agent.runtime.AgentProcess;
import server.agent.runtime.AgentRuntime;
import server.protocol.model.LnisModels.AgentRole;

/** Sender와 Receiver 모드에서만 외부 DLL과 Agent 연결 수명주기를 조립한다. */
@Configuration(proxyBeanMethods = false)
@Profile({"sender", "receiver"})
public class AgentModeConfiguration {
  @Bean
  AgentConfig agentConfig(Environment environment) {
    AgentRole role =
        Arrays.asList(environment.getActiveProfiles()).contains("sender")
            ? AgentRole.SENDER
            : AgentRole.RECEIVER;
    return AgentConfig.from(environment, role);
  }

  @Bean(destroyMethod = "")
  NativeAfsCodec nativeAfsCodec(AgentConfig config) {
    return NativeAfsCodec.load(config.nativeDirectory());
  }

  @Bean(destroyMethod = "")
  AgentRuntime agentRuntime(AgentConfig config, NativeAfsCodec codec) {
    return new AgentRuntime(config, codec);
  }

  @Bean(destroyMethod = "")
  AgentWebSocketClient agentWebSocketClient(AgentConfig config, AgentRuntime runtime) {
    return new AgentWebSocketClient(config, runtime);
  }

  @Bean(destroyMethod = "close")
  AgentProcess agentProcess(AgentConfig config, AgentRuntime runtime, AgentWebSocketClient client) {
    return new AgentProcess(config, runtime, client);
  }
}

package server;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import server.agent.config.AgentModeConfiguration;
import server.agent.runtime.AgentProcess;
import server.bootstrap.RunMode;
import server.central.config.CentralModeConfiguration;

/** 같은 Boot JAR을 중앙 서버, Sender 또는 Receiver로 시작하는 유일한 진입점이다. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({CentralModeConfiguration.class, AgentModeConfiguration.class})
public class LnisApplication {
  public static void main(String[] args) throws InterruptedException {
    RunMode.Selection selection = RunMode.select(args);
    RunMode mode = selection.mode();
    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(LnisApplication.class)
            .web(mode == RunMode.SERVER ? WebApplicationType.SERVLET : WebApplicationType.NONE)
            .profiles(mode.profile())
            .run(selection.springArguments());
    if (mode != RunMode.SERVER) {
      context.getBean(AgentProcess.class).await();
    }
  }
}

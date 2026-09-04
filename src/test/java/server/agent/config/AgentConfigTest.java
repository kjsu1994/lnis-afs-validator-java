package server.agent.config;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import server.protocol.model.LnisModels.AgentRole;

class AgentConfigTest {
  @Test
  void readsExistingPropertyNamesFromSpringEnvironment() {
    var environment =
        new MockEnvironment()
            .withProperty("lnis.agent.id", "sender-a")
            .withProperty("lnis.agent.role", "SENDER")
            .withProperty("lnis.agent.token", "secret")
            .withProperty("lnis.server.ws", "ws://192.0.2.1:8088/lnis/agent/ws")
            .withProperty("lnis.native.dir", "native-test");

    AgentConfig config = AgentConfig.from(environment, AgentRole.SENDER);

    assertEquals("sender-a", config.agentId());
    assertEquals(URI.create("ws://192.0.2.1:8088/lnis/agent/ws"), config.serverUri());
    assertEquals("secret", config.token());
    assertEquals(Path.of("native-test"), config.nativeDirectory());
  }

  @Test
  void rejectsRoleDifferentFromCommandMode() {
    var environment = new MockEnvironment().withProperty("lnis.agent.role", "RECEIVER");
    assertThrows(
        IllegalStateException.class, () -> AgentConfig.from(environment, AgentRole.SENDER));
  }
}

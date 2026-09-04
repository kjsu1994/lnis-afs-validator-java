package server.agent.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerDiscoveryTest {
  @Test
  void acceptsOnlyTheLnisIdentityResponseAndBuildsAgentWebSocketUri() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/lnis/api/v1/discovery",
        exchange -> {
          byte[] body =
              """
              {"service":"lnis-server","agentWebSocketPath":"/lnis/agent/ws"}
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var discovered =
          new ServerDiscovery()
              .discover(server.getAddress().getPort(), List.of("127.0.0.1"), Duration.ofSeconds(2));

      assertTrue(discovered.isPresent());
      assertEquals(
          URI.create("ws://127.0.0.1:" + server.getAddress().getPort() + "/lnis/agent/ws"),
          discovered.get());
    } finally {
      server.stop(0);
    }
  }
}

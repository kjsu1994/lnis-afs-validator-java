package kr.co.lnis.server.config;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/lnis/api/v1/discovery")
/** Provides a stable identity response for Windows Agent LAN discovery. */
public class DiscoveryController {
    @GetMapping
    public Map<String, String> discover() {
        return Map.of(
                "service", "lnis-server",
                "agentWebSocketPath", "/lnis/agent/ws");
    }
}

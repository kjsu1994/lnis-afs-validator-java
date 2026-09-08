package server.central.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/lnis/api/v1/discovery")
/** Provides a stable identity response for Windows Agent LAN discovery. */
public class DiscoveryController {
    /* Agent 자동 탐색에 필요한 서버 식별 정보 조회 */
    @GetMapping
    public ResponseEntity<Map<String, String>> discover()
    {
        Map<String, String> response =
                Map.of(
                        "service", "lnis-server",
                        "agentWebSocketPath", "/lnis/agent/ws");
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
}

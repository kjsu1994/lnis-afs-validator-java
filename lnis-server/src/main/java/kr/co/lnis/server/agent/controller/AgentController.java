package kr.co.lnis.server.agent.controller;

import kr.co.lnis.common.model.AgentProtocol.CommandType;
import kr.co.lnis.server.agent.repository.AgentRepository;
import kr.co.lnis.server.agent.service.AgentCommandService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/lnis/api/v1/agents")
/** 연결된 Windows Agent와 COM 포트 조회 명령을 제공한다. */
public class AgentController {
    private final AgentRepository agents;
    private final AgentCommandService commands;

    public AgentController(AgentRepository agents, AgentCommandService commands) {
        this.agents = agents;
        this.commands = commands;
    }

    @GetMapping
    public List<?> all() {
        return agents.findAll();
    }

    @GetMapping("/{agentId}")
    public Object one(@PathVariable String agentId) {
        return agents.find(agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found"));
    }

    @PostMapping("/{agentId}/serial-ports/refresh")
    public Map<String, Object> ports(@PathVariable String agentId) {
        UUID command = commands.command(agentId, null, CommandType.LIST_PORTS, null);
        return Map.of("commandId", command, "accepted", true);
    }
}

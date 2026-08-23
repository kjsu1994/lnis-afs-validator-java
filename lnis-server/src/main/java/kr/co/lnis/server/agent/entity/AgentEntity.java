package kr.co.lnis.server.agent.entity;

import java.time.Instant;
import kr.co.lnis.common.model.LnisModels.AgentRole;
import kr.co.lnis.common.model.LnisModels.AgentState;

/** Redis에 저장되는 Agent 연결 및 실행 상태 스냅샷이다. */
public record AgentEntity(
        String agentId,
        AgentRole role,
        AgentState state,
        Instant lastSeen,
        String version,
        int codecAbiVersion,
        String os,
        String architecture,
        String error) {}

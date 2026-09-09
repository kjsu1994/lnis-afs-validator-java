package server.central.node;

import lombok.Getter;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import server.shared.model.LnisModels.AgentRole;

import java.net.URI;
import java.util.Locale;

/** 노드 역할과 상대 주소는 시작 시 고정한다. 요청 본문이 통신 대상을 바꾸지 못하게 한다. */
@Getter
@Component
@Profile("node")
public class NodeProperties {
    private final AgentRole role;
    private final String agentId;
    private final String peerAgentId;
    private final URI baseUrl;
    private final URI peerBaseUrl;
    private final String managementToken;

    public NodeProperties(Environment environment)
    {
        role = AgentRole.valueOf(environment.getRequiredProperty("lnis.node.role")
                .trim().toUpperCase(Locale.ROOT));
        agentId = environment.getProperty("lnis.agent.id", role == AgentRole.SENDER ? "sender-1" : "receiver-1");
        peerAgentId = environment.getProperty("lnis.node.peer-id", role == AgentRole.SENDER ? "receiver-1" : "sender-1");
        baseUrl = parseBaseUrl(environment.getProperty("lnis.node.base-url", "http://localhost:8088"));
        String peerAddress = environment.getProperty("lnis.node.peer-url", "").trim();
        peerBaseUrl = peerAddress.isEmpty() ? null : parseBaseUrl(peerAddress);
        managementToken = environment.getProperty("lnis.node.management-token", "");
        if (agentId.isBlank() || peerAgentId.isBlank() || agentId.equals(peerAgentId)) {
            throw new IllegalArgumentException("노드 ID와 상대 노드 ID는 서로 다른 값이어야 합니다.");
        }
        if (peerBaseUrl != null && peerBaseUrl.equals(baseUrl)) {
            throw new IllegalArgumentException("상대 노드 주소는 현재 노드 주소와 달라야 합니다.");
        }
    }

    public boolean peerConfigured()
    {
        return peerBaseUrl != null && !managementToken.isBlank();
    }

    private static URI parseBaseUrl(String value)
    {
        URI address = URI.create(value.trim());
        if (!("http".equals(address.getScheme()) || "https".equals(address.getScheme()))
                || address.getHost() == null || address.getUserInfo() != null
                || address.getQuery() != null || address.getFragment() != null
                || !(address.getPath().isEmpty() || "/".equals(address.getPath()))) {
            throw new IllegalArgumentException("노드 주소는 경로 없는 http(s)://호스트:포트 형식이어야 합니다.");
        }
        return URI.create(address.getScheme() + "://" + address.getRawAuthority());
    }
}

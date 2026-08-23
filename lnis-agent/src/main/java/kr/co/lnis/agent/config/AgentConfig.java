package kr.co.lnis.agent.config;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import static kr.co.lnis.protocol.model.LnisModels.AgentRole;

/** 환경 변수, 시스템 속성 또는 properties 파일에서 Agent 실행 설정을 읽는다. */
public record AgentConfig(
        String agentId,
        AgentRole role,
        URI serverUri,
        String token,
        Path nativeDirectory) {
    public static AgentConfig load(String[] args) {
        Properties file = new Properties();
        Path path = configPath();
        if (Files.isRegularFile(path)) {
            try (var in = Files.newInputStream(path)) {
                file.load(in);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to read " + path, e);
            }
        }
        String id = value("LNIS_AGENT_ID", "lnis.agent.id", file, "agent-1");
        AgentRole role = AgentRole.valueOf(value("LNIS_AGENT_ROLE", "lnis.agent.role", file, "SENDER").toUpperCase(Locale.ROOT));
        URI uri = URI.create(value("LNIS_SERVER_WS", "lnis.server.ws", file, "ws://localhost:8088/lnis/agent/ws"));
        String token = value("LNIS_AGENT_TOKEN", "lnis.agent.token", file, "change-me");
        Path nativeDir = Path.of(value("LNIS_NATIVE_DIR", "lnis.native.dir", file, "native"));
        return new AgentConfig(id, role, uri, token, nativeDir);
    }

    /**
     * 역할별 실행 스크립트가 공백이 포함된 절대 경로도 안전하게 전달할 수 있도록
     * 환경 변수 설정 파일 경로를 시스템 속성보다 우선한다.
     */
    private static Path configPath() {
        String configured = System.getenv("LNIS_AGENT_CONFIG");
        if (configured == null || configured.isBlank()) {
            configured = System.getProperty("lnis.agent.config", "conf/agent.properties");
        }
        return Path.of(configured.trim());
    }

    private static String value(String env, String key, Properties file, String fallback) {
        String value = System.getenv(env);
        if (value == null || value.isBlank()) value = System.getProperty(key);
        if (value == null || value.isBlank()) value = file.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

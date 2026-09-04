package server.agent.config;

import static server.protocol.model.LnisModels.AgentRole;

import java.net.URI;
import java.nio.file.Path;
import org.springframework.core.env.Environment;

/** 환경 변수, 시스템 속성 또는 properties 파일에서 Agent 실행 설정을 읽는다. */
@lombok.Value
@lombok.AllArgsConstructor
@lombok.Builder
@lombok.extern.jackson.Jacksonized
@lombok.experimental.Accessors(fluent = true)
@com.fasterxml.jackson.annotation.JsonAutoDetect(
    fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
public class AgentConfig {
  /** 서버와 브라우저에서 Agent를 식별할 고유 문자열이다. */
  String agentId;

  /** 이 프로세스가 수행할 고정 Sender 또는 Receiver 역할이다. */
  AgentRole role;

  /** 중앙 서버의 Agent 전용 WebSocket URI다. */
  URI serverUri;

  /** WebSocket 연결 시 서버와 공유하는 인증 토큰이다. */
  String token;

  /** 운영체제별 Native AFS Codec DLL 또는 SO 파일이 위치한 디렉터리다. */
  Path nativeDirectory;

  /** Spring 외부 설정과 환경 변수에서 값을 읽고 명령행 실행 역할과 설정 역할이 같은지 검증한다. */
  public static AgentConfig from(Environment environment, AgentRole expectedRole) {
    String configuredRole = environment.getProperty("lnis.agent.role", expectedRole.name());
    AgentRole role = AgentRole.valueOf(configuredRole.trim().toUpperCase(java.util.Locale.ROOT));
    if (role != expectedRole) {
      throw new IllegalStateException(
          "실행 모드 " + expectedRole + "와 lnis.agent.role " + role + "이 일치하지 않습니다.");
    }
    String defaultId = expectedRole == AgentRole.SENDER ? "sender-1" : "receiver-1";
    return new AgentConfig(
        environment.getProperty("lnis.agent.id", defaultId),
        role,
        URI.create(environment.getProperty("lnis.server.ws", "ws://localhost:8088/lnis/agent/ws")),
        environment.getProperty("lnis.agent.token", "change-me"),
        Path.of(environment.getProperty("lnis.native.dir", "native")));
  }
}

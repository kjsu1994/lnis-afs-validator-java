package kr.co.lnis.server.session.entity;

import java.time.Instant;
import java.util.UUID;
import kr.co.lnis.protocol.model.LnisModels.SessionState;
import kr.co.lnis.protocol.model.LnisModels.TestType;
import kr.co.lnis.protocol.model.LnisModels.Verdict;

/**
 * Redis에 저장하는 시험 세션의 현재 실행 상태와 재사용할 요청 원문이다.
 *
 * <p>TX/RX 상세 결과는 별도 Redis 키에 저장하며 이 엔티티에는 세션 요약만 보관한다.
 */
@lombok.Value
@lombok.AllArgsConstructor
@lombok.Builder
@lombok.extern.jackson.Jacksonized
@lombok.experimental.Accessors(fluent = true)
@com.fasterxml.jackson.annotation.JsonAutoDetect(
    fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
public class TestSessionEntity {
  /** 세션 생성 시 서버가 발급하며 API·WebSocket·산출물 경로에서 공통으로 사용하는 UUID다. */
  UUID sessionId;

  /** 생성, Receiver 대기, 전송, 검증, 종료 중 현재 단계다. */
  SessionState state;

  /** 이 세션에 적용한 Test A~E 시험 유형이다. */
  TestType testType;

  /** 세션에 고정된 Sender Agent ID다. */
  String senderAgentId;

  /** 세션에 고정된 Receiver Agent ID다. */
  String receiverAgentId;

  /** 시험 원본 GRAW가 저장된 입력 버퍼 UUID다. */
  UUID inputId;

  /** 브라우저 전체 진행률이며 0~100 범위의 백분율 정수다. */
  int progress;

  /** 현재 단계 또는 종료 사유를 사용자가 읽을 수 있게 표현한 메시지다. */
  String message;

  /** 최종 판정이며 시험 종료 전에는 보통 {@code INCONCLUSIVE}다. */
  Verdict verdict;

  /** Agent 명령을 다시 구성할 수 있도록 세션 생성 요청 전체를 보관한 JSON 문자열이다. */
  String requestJson;

  /** 세션 레코드를 최초 생성한 UTC 시각이다. */
  Instant createdAt;

  /** 상태·진행률·판정이 마지막으로 변경된 UTC 시각이다. */
  Instant updatedAt;
}

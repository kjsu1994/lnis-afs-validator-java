package server.session.mapper;

import server.model.LnisModels.SessionSnapshot;
import server.session.entity.TestSessionEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
/** 저장 엔티티를 외부 조회용 세션 스냅샷으로 변환한다. */
public interface SessionMapper {
  /**
   * Lombok fluent 접근자는 MapStruct의 JavaBeans 이름 규칙과 다르므로 필드를 명시적으로 옮긴다. 아직 Agent 결과를 결합하기 전 단계이므로
   * TX/RX 결과는 기존과 동일하게 {@code null}로 둔다.
   */
  default SessionSnapshot toSnapshot(TestSessionEntity entity) {
    if (entity == null) return null;
    return new SessionSnapshot(
        entity.sessionId(),
        entity.state(),
        entity.testType(),
        entity.senderAgentId(),
        entity.receiverAgentId(),
        entity.inputId(),
        entity.progress(),
        entity.message(),
        entity.verdict(),
        entity.createdAt(),
        entity.updatedAt(),
        null,
        null);
  }
}

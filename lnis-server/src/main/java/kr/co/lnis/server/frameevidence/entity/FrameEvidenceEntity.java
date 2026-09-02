package kr.co.lnis.server.frameevidence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import kr.co.lnis.protocol.model.AgentProtocol.FrameEvidenceMessage;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;

/** Sender 또는 Receiver가 제출한 단일 AFS 프레임 증거를 H2 BLOB에 저장하는 엔티티다. */
@Entity
@Table(
    name = "frame_evidence",
    uniqueConstraints =
        @UniqueConstraint(columnNames = {"session_id", "agent_role", "frame_index"}))
@lombok.Getter
@lombok.AllArgsConstructor
@lombok.NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@lombok.experimental.Accessors(fluent = true)
@com.fasterxml.jackson.annotation.JsonAutoDetect(
    fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
public class FrameEvidenceEntity {
  /** 내부 DB 식별자이며 외부 API에는 노출하지 않는다. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @com.fasterxml.jackson.annotation.JsonIgnore
  Long id;

  /** 프레임 증거가 속한 시험 세션 UUID다. */
  @Column(name = "session_id", nullable = false)
  UUID sessionId;

  /** 증거를 제출한 Agent 역할이며 같은 프레임에 Sender/Receiver 엔티티가 각각 존재할 수 있다. */
  @Enumerated(EnumType.STRING)
  @Column(name = "agent_role", nullable = false)
  AgentRole role;

  /** 세션 내부의 0부터 시작하는 논리 AFS 프레임 번호다. */
  @Column(name = "frame_index", nullable = false)
  int frameIndex;

  /** 6,000비트 원문, CRC 진단값과 오류 위치를 포함한 Agent 전달 객체다. */
  @Lob
  @Convert(converter = kr.co.lnis.server.frameevidence.entity.FrameEvidenceMessageConverter.class)
  FrameEvidenceMessage evidence;

  /** 서버가 해당 WebSocket 증거 메시지를 수신한 UTC 시각이다. */
  Instant receivedAt;

  /** 외부 메시지에서 새 증거 행을 만들 때 DB 식별자는 H2가 생성한다. */
  public FrameEvidenceEntity(
      UUID sessionId,
      AgentRole role,
      int frameIndex,
      FrameEvidenceMessage evidence,
      Instant receivedAt) {
    this.sessionId = sessionId;
    this.role = role;
    this.frameIndex = frameIndex;
    this.evidence = evidence;
    this.receivedAt = receivedAt;
  }

  /** 동일 역할·프레임의 재전송은 새 행을 만들지 않고 기존 증거를 최신 값으로 바꾼다. */
  public void replace(FrameEvidenceMessage value, Instant at) {
    evidence = value;
    receivedAt = at;
  }
}

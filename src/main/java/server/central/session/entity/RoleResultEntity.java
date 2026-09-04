package server.central.session.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.*;
import server.protocol.model.LnisModels.AgentRole;

/** Sender 또는 Receiver가 제출한 최종 결과 JSON이다. */
@Entity
@Table(name = "role_results")
@IdClass(RoleResultEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class RoleResultEntity {
  @Id private UUID sessionId;

  @Id
  @Enumerated(EnumType.STRING)
  @Column(name = "agent_role")
  private AgentRole role;

  @Lob
  @Column(nullable = false)
  private String resultJson;

  @Column(nullable = false)
  private Instant receivedAt;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Key implements Serializable {
    private UUID sessionId;
    private AgentRole role;
  }
}

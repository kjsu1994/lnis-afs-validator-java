package server.central.dtn;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

/** 기존 AFS 테이블과 분리해 DTN 전달 및 계산 상태를 보관한다. */
@Entity @Table(name = "dtn_job") @Data
public class DtnJob {
  @Id private UUID id;
  private UUID inputId;
  private String senderAgentId;
  private String receiverAgentId;
  private String state;
  private Instant createdAt;
  private Instant updatedAt;
  @Column(length = 2048) private String message;
  @Lob private String sentJson;
  @Lob private String receivedJson;
  @Lob private String referenceJson;
  @Lob private String receiverJson;
  @Lob private String comparisonJson;
}

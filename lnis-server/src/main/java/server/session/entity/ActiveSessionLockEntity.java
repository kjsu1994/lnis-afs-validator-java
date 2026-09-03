package server.session.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.*;

/** 동시에 하나의 시험만 실행하도록 소유 세션을 기록하는 단일 행이다. */
@Entity
@Table(name = "active_session_lock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActiveSessionLockEntity {
  @Id private Integer id;
  private UUID sessionId;
  private Instant acquiredAt;
  @Version private long version;

  public ActiveSessionLockEntity(Integer id) {
    this.id = id;
  }

  public void acquire(UUID value) {
    sessionId = value;
    acquiredAt = Instant.now();
  }

  public void release() {
    sessionId = null;
    acquiredAt = null;
  }
}

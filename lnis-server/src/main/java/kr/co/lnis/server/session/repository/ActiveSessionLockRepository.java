package kr.co.lnis.server.session.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import kr.co.lnis.server.session.entity.ActiveSessionLockEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 활성 시험 소유 ID를 H2의 단일 잠금 행에서 원자적으로 관리한다. */
@Repository
public class ActiveSessionLockRepository {
  private static final int LOCK_ID = 1;
  private final ActiveSessionLockJpaRepository database;

  public ActiveSessionLockRepository(ActiveSessionLockJpaRepository database) {
    this.database = database;
  }

  @Transactional
  public synchronized boolean tryAcquire(UUID sessionId) {
    database
        .findById(LOCK_ID)
        .orElseGet(() -> database.saveAndFlush(new ActiveSessionLockEntity(LOCK_ID)));
    ActiveSessionLockEntity lock = database.findLocked(LOCK_ID).orElseThrow();
    if (lock.getSessionId() != null) return false;
    lock.acquire(sessionId);
    return true;
  }

  @Transactional(readOnly = true)
  public Optional<UUID> current() {
    return database.findById(LOCK_ID).map(ActiveSessionLockEntity::getSessionId);
  }

  @Transactional
  public synchronized void release(UUID sessionId) {
    database
        .findLocked(LOCK_ID)
        .ifPresent(
            lock -> {
              if (sessionId.equals(lock.getSessionId())) lock.release();
            });
  }
}

interface ActiveSessionLockJpaRepository extends JpaRepository<ActiveSessionLockEntity, Integer> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select lock from ActiveSessionLockEntity lock where lock.id = :id")
  Optional<ActiveSessionLockEntity> findLocked(@Param("id") Integer id);
}

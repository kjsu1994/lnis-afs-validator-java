package server.central.session;

import jakarta.persistence.LockModeType;

import lombok.RequiredArgsConstructor;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** 활성 시험 소유 ID를 H2의 단일 잠금 행에서 원자적으로 관리한다. */
@RequiredArgsConstructor
@Repository
public class ActiveSessionLockRepository {
    private static final int LOCK_ID = 1;
    private final ActiveSessionLockJpaRepository activeSessionLockJpaRepository;

    @Transactional
    public synchronized boolean tryAcquire(UUID sessionId)
    {
        activeSessionLockJpaRepository
                .findById(LOCK_ID)
                .orElseGet(
                        () ->
                                activeSessionLockJpaRepository.saveAndFlush(
                                        new ActiveSessionLockEntity(LOCK_ID)));
        ActiveSessionLockEntity lock =
                activeSessionLockJpaRepository.findLocked(LOCK_ID).orElseThrow();
        if (lock.getSessionId() != null) {
            return false;
        }
        lock.acquire(sessionId);
        return true;
    }

    @Transactional(readOnly = true)
    public Optional<UUID> current()
    {
        return activeSessionLockJpaRepository
                .findById(LOCK_ID)
                .map(ActiveSessionLockEntity::getSessionId);
    }

    @Transactional
    public synchronized void release(UUID sessionId)
    {
        activeSessionLockJpaRepository
                .findLocked(LOCK_ID)
                .ifPresent(
                        lock -> {
                            if (sessionId.equals(lock.getSessionId())) {
                                lock.release();
                            }
                        });
    }
}

interface ActiveSessionLockJpaRepository extends JpaRepository<ActiveSessionLockEntity, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select lock from ActiveSessionLockEntity lock where lock.id = :id")
    Optional<ActiveSessionLockEntity> findLocked(@Param("id") Integer id);
}


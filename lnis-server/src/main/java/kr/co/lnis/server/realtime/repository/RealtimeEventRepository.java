package kr.co.lnis.server.realtime.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.lnis.protocol.model.AgentProtocol.EventType;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import kr.co.lnis.server.realtime.entity.RealtimeEventEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 기준 Redis Stream의 전역 sequence, 세션별 최근 10,000개와 보존 기간을 H2로 이식한다. */
@Repository
public class RealtimeEventRepository {
  private static final long MAX_EVENTS_PER_STREAM = 10_000;
  private final RealtimeEventJpaRepository database;

  public RealtimeEventRepository(RealtimeEventJpaRepository database) {
    this.database = database;
  }

  @Transactional
  public synchronized long append(
      String streamKey,
      EventType type,
      String agentId,
      AgentRole role,
      UUID sessionId,
      String payload,
      Instant createdAt) {
    var saved =
        database.saveAndFlush(
            new RealtimeEventEntity(streamKey, type, agentId, role, sessionId, payload, createdAt));
    long overflow = database.countByStreamKey(streamKey) - MAX_EVENTS_PER_STREAM;
    if (overflow > 0) {
      var ids = database.findOldestIds(streamKey, PageRequest.of(0, (int) overflow));
      database.deleteAllByIdInBatch(ids);
    }
    return saved.getSequence();
  }

  public long count(String streamKey) {
    return database.countByStreamKey(streamKey);
  }

  @Transactional
  public void deleteExpiredStreams(Instant cutoff) {
    for (String streamKey : database.findExpiredStreamKeys(cutoff)) {
      database.deleteByStreamKey(streamKey);
    }
  }
}

interface RealtimeEventJpaRepository extends JpaRepository<RealtimeEventEntity, Long> {
  long countByStreamKey(String streamKey);

  @Query(
      "select event.sequence from RealtimeEventEntity event "
          + "where event.streamKey = :streamKey order by event.sequence")
  List<Long> findOldestIds(
      @Param("streamKey") String streamKey, org.springframework.data.domain.Pageable pageable);

  @Query(
      "select event.streamKey from RealtimeEventEntity event group by event.streamKey "
          + "having max(event.createdAt) < :cutoff")
  List<String> findExpiredStreamKeys(@Param("cutoff") Instant cutoff);

  void deleteByStreamKey(String streamKey);
}

package server.central.config;

import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import server.central.agent.repository.AgentRepository;
import server.central.frameevidence.repository.FrameEvidenceRepository;
import server.central.input.repository.InputBufferRepository;
import server.central.input.service.InputBufferService;
import server.central.realtime.repository.RealtimeEventRepository;
import server.central.session.repository.SessionRepository;

/** 설정된 보존 기간이 지난 H2 행과 GRAW 파일을 참조 순서에 맞춰 정리한다. */
@Service
public class StorageCleanupService {
  private static final Duration EVENT_RETENTION = Duration.ofHours(24);
  private final StorageProperties properties;
  private final AgentRepository agents;
  private final SessionRepository sessions;
  private final FrameEvidenceRepository evidence;
  private final InputBufferRepository inputs;
  private final InputBufferService inputService;
  private final RealtimeEventRepository events;

  public StorageCleanupService(
      StorageProperties properties,
      AgentRepository agents,
      SessionRepository sessions,
      FrameEvidenceRepository evidence,
      InputBufferRepository inputs,
      InputBufferService inputService,
      RealtimeEventRepository events) {
    this.properties = properties;
    this.agents = agents;
    this.sessions = sessions;
    this.evidence = evidence;
    this.inputs = inputs;
    this.inputService = inputService;
    this.events = events;
  }

  @Scheduled(fixedDelayString = "${lnis.storage.cleanup-delay:PT10M}")
  @Transactional
  public void cleanup() {
    Instant now = Instant.now();
    // 기준 Redis Stream과 동일하게 일반 데이터 보존 설정과 무관하게 이벤트 스트림은 24시간 보존한다.
    events.deleteExpiredStreams(now.minus(EVENT_RETENTION));
    if (!properties.getCompletedRetention().isZero()) {
      Instant cutoff = now.minus(properties.getCompletedRetention());
      for (var sessionId : sessions.terminalBefore(cutoff)) {
        evidence.deleteBySessionId(sessionId);
        sessions.delete(sessionId);
      }
      for (var input : inputs.completeBefore(cutoff)) inputService.removeExpired(input);
      agents.deleteLastSeenBefore(cutoff);
    }
    if (!properties.getIncompleteRetention().isZero()) {
      Instant cutoff = now.minus(properties.getIncompleteRetention());
      for (var input : inputs.incompleteBefore(cutoff)) inputService.removeExpired(input);
    }
  }
}

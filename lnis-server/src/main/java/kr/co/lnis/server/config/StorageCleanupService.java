package kr.co.lnis.server.config;

import java.time.Duration;
import java.time.Instant;
import kr.co.lnis.server.agent.repository.AgentRepository;
import kr.co.lnis.server.frameevidence.repository.FrameEvidenceRepository;
import kr.co.lnis.server.input.repository.InputBufferRepository;
import kr.co.lnis.server.input.service.InputBufferService;
import kr.co.lnis.server.realtime.repository.RealtimeEventRepository;
import kr.co.lnis.server.session.repository.SessionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

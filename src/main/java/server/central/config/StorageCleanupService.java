package server.central.config;

import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import server.central.agent.AgentRepository;
import server.central.frameevidence.FrameEvidenceRepository;
import server.central.input.InputBufferEntity;
import server.central.input.InputBufferRepository;
import server.central.input.InputBufferService;
import server.central.realtime.RealtimeEventRepository;
import server.central.session.SessionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 설정된 보존 기간이 지난 H2 행과 GRAW 파일을 참조 순서에 맞춰 정리한다. */
@RequiredArgsConstructor
@Service
public class StorageCleanupService {
    private static final Duration EVENT_RETENTION = Duration.ofHours(24);
    private final StorageProperties storageProperties;
    private final AgentRepository agentRepository;
    private final SessionRepository sessionRepository;
    private final FrameEvidenceRepository frameEvidenceRepository;
    private final InputBufferRepository inputBufferRepository;
    private final InputBufferService inputBufferService;
    private final RealtimeEventRepository realtimeEventRepository;

    @Scheduled(fixedDelayString = "${lnis.storage.cleanup-delay:PT10M}")
    @Transactional
    public void cleanup()
    {
        Instant now = Instant.now();
        // 기준 Redis Stream과 동일하게 일반 데이터 보존 설정과 무관하게 이벤트 스트림은 24시간 보존한다.
        realtimeEventRepository.deleteExpiredStreams(now.minus(EVENT_RETENTION));
        if (!storageProperties.getCompletedRetention().isZero()) {
            Instant cutoff = now.minus(storageProperties.getCompletedRetention());
            for (UUID sessionId : sessionRepository.terminalBefore(cutoff)) {
                frameEvidenceRepository.deleteBySessionId(sessionId);
                sessionRepository.delete(sessionId);
            }
            for (InputBufferEntity input : inputBufferRepository.completeBefore(cutoff)) {
                inputBufferService.removeExpired(input);
            }
            agentRepository.deleteLastSeenBefore(cutoff);
        }
        if (!storageProperties.getIncompleteRetention().isZero()) {
            Instant cutoff = now.minus(storageProperties.getIncompleteRetention());
            for (InputBufferEntity input : inputBufferRepository.incompleteBefore(cutoff)) {
                inputBufferService.removeExpired(input);
            }
        }
    }
}


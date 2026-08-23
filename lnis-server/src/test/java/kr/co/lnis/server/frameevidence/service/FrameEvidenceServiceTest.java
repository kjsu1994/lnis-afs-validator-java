package kr.co.lnis.server.frameevidence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.protocol.model.AgentProtocol.FrameEvidenceMessage;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import kr.co.lnis.server.frameevidence.entity.FrameEvidenceEntity;
import kr.co.lnis.server.frameevidence.repository.FrameEvidenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** 네 단계 프레임을 병합했을 때 비트 차이 수와 위치가 정확한지 검증한다. */
@ExtendWith(MockitoExtension.class)
class FrameEvidenceServiceTest {
    @Mock
    private FrameEvidenceRepository repository;

    @Test
    void mergesSenderAndReceiverEvidenceAndCountsBitDifferences() {
        UUID sessionId = UUID.randomUUID();
        byte[] reference = new byte[750];
        byte[] transmitted = reference.clone();
        transmitted[0] = (byte) 0b1000_0000;
        transmitted[749] = (byte) 0b0000_0001;
        byte[] received = transmitted.clone();
        byte[] reencoded = reference.clone();

        FrameEvidenceMessage sender = new FrameEvidenceMessage(
                0, reference, transmitted, null, null,
                List.of(0, 5999), false, "sender");
        FrameEvidenceMessage receiver = new FrameEvidenceMessage(
                0, null, null, received, reencoded,
                List.of(), true, "receiver");
        when(repository.find(sessionId, AgentRole.SENDER, 0))
                .thenReturn(java.util.Optional.of(new FrameEvidenceEntity(
                        sessionId, AgentRole.SENDER, 0, sender, Instant.now())));
        when(repository.find(sessionId, AgentRole.RECEIVER, 0))
                .thenReturn(java.util.Optional.of(new FrameEvidenceEntity(
                        sessionId, AgentRole.RECEIVER, 0, receiver, Instant.now())));

        FrameEvidenceService service = new FrameEvidenceService(
                repository,
                new ObjectMapper().findAndRegisterModules());
        var detail = service.detail(sessionId, 0);

        assertEquals(2, detail.summary().referenceToTransmittedDifferences());
        assertEquals(0, detail.summary().transmittedToReceivedDifferences());
        assertEquals(0, detail.summary().referenceToReencodedDifferences());
        assertEquals(List.of(0, 5999), detail.referenceToTransmittedPositions());
        assertTrue(detail.summary().interpretation().contains("완전히 같아졌습니다"));
    }

    @Test
    void rejectsEvidenceThatIsNotExactlySixThousandBits() {
        FrameEvidenceService service = new FrameEvidenceService(
                repository,
                new ObjectMapper().findAndRegisterModules());
        FrameEvidenceMessage invalid = new FrameEvidenceMessage(
                0, new byte[749], null, null, null,
                List.of(), false, "invalid");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.save(UUID.randomUUID(), AgentRole.SENDER, invalid));
    }
}

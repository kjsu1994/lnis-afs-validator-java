package server.central.frameevidence.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import server.central.frameevidence.entity.FrameEvidenceEntity;
import server.central.frameevidence.repository.FrameEvidenceRepository;
import server.protocol.model.AgentProtocol.FrameEvidenceMessage;
import server.protocol.model.LnisModels.AgentRole;
import server.protocol.model.LnisModels.Sb2EphemerisResult;

/** 네 단계 프레임을 병합했을 때 비트 차이 수와 위치가 정확한지 검증한다. */
@ExtendWith(MockitoExtension.class)
class FrameEvidenceServiceTest {
  @Mock private FrameEvidenceRepository repository;

  @Test
  void mergesSenderAndReceiverEvidenceAndCountsBitDifferences() throws Exception {
    UUID sessionId = UUID.randomUUID();
    byte[] reference = new byte[750];
    byte[] transmitted = reference.clone();
    transmitted[0] = (byte) 0b1000_0000;
    transmitted[749] = (byte) 0b0000_0001;
    byte[] received = transmitted.clone();
    byte[] reencoded = reference.clone();
    var sb2 =
        new Sb2EphemerisResult(
            "profile-1",
            1,
            2300,
            123,
            319488,
            0.6,
            2557.342371,
            0.98,
            0,
            1.57,
            0,
            319488,
            0,
            0,
            true,
            true,
            true);

    FrameEvidenceMessage sender =
        new FrameEvidenceMessage(
            0,
            reference,
            transmitted,
            null,
            null,
            List.of(0, 5999),
            false,
            false,
            false,
            false,
            false,
            0,
            0,
            0,
            false,
            null,
            null,
            "sender");
    FrameEvidenceMessage receiver =
        new FrameEvidenceMessage(
            0,
            null,
            null,
            received,
            reencoded,
            List.of(),
            true,
            true,
            true,
            true,
            true,
            10,
            20,
            30,
            true,
            sb2,
            null,
            "receiver");
    when(repository.find(sessionId, AgentRole.SENDER, 0))
        .thenReturn(
            java.util.Optional.of(
                new FrameEvidenceEntity(sessionId, AgentRole.SENDER, 0, sender, Instant.now())));
    when(repository.find(sessionId, AgentRole.RECEIVER, 0))
        .thenReturn(
            java.util.Optional.of(
                new FrameEvidenceEntity(
                    sessionId, AgentRole.RECEIVER, 0, receiver, Instant.now())));

    FrameEvidenceService service =
        new FrameEvidenceService(repository, new ObjectMapper().findAndRegisterModules());
    var detail = service.detail(sessionId, 0);

    assertEquals(2, detail.summary().referenceToTransmittedDifferences());
    assertEquals(0, detail.summary().transmittedToReceivedDifferences());
    assertEquals(0, detail.summary().referenceToReencodedDifferences());
    assertEquals(List.of(0, 5999), detail.referenceToTransmittedPositions());
    assertTrue(detail.summary().interpretation().contains("완전히 같아졌습니다"));
    assertEquals(123, detail.summary().sb2Ephemeris().afsItow());
    String resultJson = new ObjectMapper().findAndRegisterModules().writeValueAsString(detail);
    assertTrue(resultJson.contains("\"afsItow\":123"));
  }

  @Test
  void rejectsEvidenceThatIsNotExactlySixThousandBits() {
    FrameEvidenceService service =
        new FrameEvidenceService(repository, new ObjectMapper().findAndRegisterModules());
    FrameEvidenceMessage invalid =
        new FrameEvidenceMessage(
            0,
            new byte[749],
            null,
            null,
            null,
            List.of(),
            false,
            false,
            false,
            false,
            false,
            0,
            0,
            0,
            false,
            null,
            null,
            "invalid");

    assertThrows(
        IllegalArgumentException.class,
        () -> service.save(UUID.randomUUID(), AgentRole.SENDER, invalid));
  }
}

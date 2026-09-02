package kr.co.lnis.server.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.co.lnis.protocol.model.AgentProtocol.FrameEvidenceMessage;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import kr.co.lnis.protocol.model.LnisModels.AgentState;
import kr.co.lnis.protocol.model.LnisModels.InputKind;
import kr.co.lnis.server.agent.entity.AgentEntity;
import kr.co.lnis.server.agent.repository.AgentRepository;
import kr.co.lnis.server.frameevidence.repository.FrameEvidenceRepository;
import kr.co.lnis.server.input.service.InputBufferService;
import kr.co.lnis.server.session.repository.ActiveSessionLockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Redis 제거 후 핵심 메타데이터, BLOB 증거, GRAW 파일 저장이 함께 동작하는지 검증한다. */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:lnis-persistence;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "lnis.storage.data-directory=${java.io.tmpdir}/lnis-server-tests",
      "lnis.storage.cleanup-delay=PT24H"
    })
class H2PersistenceIntegrationTest {
  @Autowired private AgentRepository agents;
  @Autowired private FrameEvidenceRepository evidence;
  @Autowired private ActiveSessionLockRepository locks;
  @Autowired private InputBufferService inputs;

  @Test
  void storesAgentAndMaintainsSingleActiveSessionLock() {
    var agent =
        new AgentEntity(
            "sender-integration",
            AgentRole.SENDER,
            AgentState.READY,
            Instant.now(),
            "test",
            1,
            "Windows",
            "amd64",
            List.of("192.0.2.10"),
            null);
    agents.save(agent);

    assertEquals(List.of("192.0.2.10"), agents.find(agent.agentId()).orElseThrow().ipv4Addresses());

    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    assertTrue(locks.tryAcquire(first));
    assertFalse(locks.tryAcquire(second));
    assertEquals(first, locks.current().orElseThrow());
    locks.release(second);
    assertEquals(first, locks.current().orElseThrow());
    locks.release(first);
    assertTrue(locks.current().isEmpty());
  }

  @Test
  void storesFrameEvidenceAsBlobAndUpdatesRetransmission() {
    UUID sessionId = UUID.randomUUID();
    evidence.save(sessionId, AgentRole.SENDER, frame("first"));
    evidence.save(sessionId, AgentRole.SENDER, frame("retransmitted"));

    var stored = evidence.find(sessionId, AgentRole.SENDER, 0).orElseThrow();
    assertEquals("retransmitted", stored.evidence().note());
    assertArrayEquals(new byte[750], stored.evidence().referenceFrame());
    assertEquals(1, evidence.findAll(sessionId).size());
  }

  @Test
  void storesGrawBytesInFileAndMetadataInH2() {
    var input = inputs.create("integration.graw", 3, InputKind.GRAW_UPLOAD);
    inputs.append(input.inputId(), 0, new byte[] {1, 2, 3});

    assertArrayEquals(new byte[] {1, 2, 3}, inputs.chunk(input.inputId(), 0));
    assertEquals(3, inputs.get(input.inputId()).receivedSize());

    inputs.remove(input.inputId());
    assertThrows(IllegalArgumentException.class, () -> inputs.get(input.inputId()));
  }

  private static FrameEvidenceMessage frame(String note) {
    return new FrameEvidenceMessage(
        0,
        new byte[750],
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
        note);
  }
}

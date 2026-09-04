package server.central.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import server.central.agent.entity.AgentEntity;
import server.central.agent.repository.AgentRepository;
import server.central.frameevidence.repository.FrameEvidenceRepository;
import server.central.input.service.GrawFileStorage;
import server.central.input.service.InputBufferService;
import server.central.realtime.repository.RealtimeEventRepository;
import server.central.realtime.service.EventService;
import server.central.session.entity.TestSessionEntity;
import server.central.session.repository.ActiveSessionLockRepository;
import server.central.session.repository.SessionRepository;
import server.protocol.codec.GrawCodec;
import server.protocol.model.AgentProtocol.FrameEvidenceMessage;
import server.protocol.model.LnisModels.AgentRole;
import server.protocol.model.LnisModels.AgentState;
import server.protocol.model.LnisModels.InputKind;
import server.protocol.model.LnisModels.SessionState;
import server.protocol.model.LnisModels.TestType;
import server.protocol.model.LnisModels.Verdict;

/** Redis 제거 후 핵심 메타데이터, BLOB 증거, GRAW 파일 저장이 함께 동작하는지 검증한다. */
@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:lnis-persistence;DB_CLOSE_DELAY=-1",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "lnis.storage.data-directory=${java.io.tmpdir}/lnis-server-tests",
      "lnis.storage.cleanup-delay=PT24H"
    })
@ActiveProfiles("server")
class H2PersistenceIntegrationTest {
  @Autowired private AgentRepository agents;
  @Autowired private FrameEvidenceRepository evidence;
  @Autowired private ActiveSessionLockRepository locks;
  @Autowired private InputBufferService inputs;
  @Autowired private GrawFileStorage files;
  @Autowired private RealtimeEventRepository realtimeEvents;
  @Autowired private EventService eventService;
  @Autowired private SessionRepository sessions;

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
  void persistsIncreasingEventSequenceAndSessionStream() {
    UUID sessionId = UUID.randomUUID();
    var first =
        eventService.publish(
            server.protocol.model.AgentProtocol.EventType.SESSION_STATUS,
            null,
            null,
            sessionId,
            "first");
    var second =
        eventService.publish(
            server.protocol.model.AgentProtocol.EventType.RESULT, null, null, sessionId, "second");

    assertTrue(second.sequence() > first.sequence());
    assertEquals(2, realtimeEvents.count(sessionId.toString()));
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

  @Test
  void explicitlyRemovesInputReferencedBySessionLikeBaseline() {
    var input = inputs.create("explicit-remove.graw", 3, InputKind.GRAW_UPLOAD);
    UUID sessionId = UUID.randomUUID();
    Instant now = Instant.now();
    sessions.save(
        new TestSessionEntity(
            sessionId,
            SessionState.CREATED,
            TestType.TEST_A_NORMAL,
            "sender",
            "receiver",
            input.inputId(),
            0,
            "created",
            Verdict.INCONCLUSIVE,
            "{}",
            now,
            now));

    inputs.remove(input.inputId());

    assertThrows(IllegalArgumentException.class, () -> inputs.get(input.inputId()));
    sessions.delete(sessionId);
  }

  @Test
  void completesValidGrawAndReadsChunksFromFinalFile() {
    byte[] graw = validGraw();
    var input = inputs.create("complete-integration.graw", graw.length, InputKind.GRAW_UPLOAD);
    inputs.append(input.inputId(), 0, graw);
    var completed = inputs.complete(input.inputId());

    assertTrue(completed.complete());
    assertEquals(1, completed.recordCount());
    assertNotNull(completed.sha256());
    assertArrayEquals(graw, inputs.chunk(input.inputId(), 0));
    inputs.remove(input.inputId());
  }

  @Test
  void retriesCompletionAfterFileWasRenamedBeforeDatabaseCommit() {
    byte[] graw = validGraw();
    var input = inputs.create("interrupted-complete.graw", graw.length, InputKind.GRAW_UPLOAD);
    inputs.append(input.inputId(), 0, graw);
    files.complete(input.inputId());

    var completed = inputs.complete(input.inputId());

    assertTrue(completed.complete());
    assertArrayEquals(graw, inputs.chunk(input.inputId(), 0));
    inputs.remove(input.inputId());
  }

  private static byte[] validGraw() {
    byte[] record =
        GrawCodec.encode(
            new GrawCodec.Envelope(
                UUID.randomUUID(),
                UUID.randomUUID(),
                0,
                Instant.parse("2026-01-01T00:00:00Z"),
                new GrawCodec.ReceiverMetadata("F9P", "1", "COM3", 115200, "test")));
    return ByteBuffer.allocate(record.length + Integer.BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(record.length)
        .put(record)
        .array();
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

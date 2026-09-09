package server.central.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import server.central.agent.AgentConnectionRegistry;
import server.central.dtn.DtnJob;
import server.central.dtn.DtnPayloadDigest;
import server.central.dtn.DtnRepository;
import server.central.session.ActiveSessionLockRepository;
import server.central.session.CreateSessionRequest;
import server.central.session.SessionRepository;
import server.central.session.SessionService;
import server.central.session.TestSessionEntity;
import server.shared.model.AgentProtocol.*;
import server.shared.model.DtnModels;
import server.shared.model.LnisModels.*;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 중복 관리 요청과 부분 준비 실패가 재실행/잠금 누수로 이어지지 않는지 검증한다. */
class NodeFailureTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final NodeProperties properties = new NodeProperties(new MockEnvironment()
            .withProperty("lnis.node.role", "receiver"));

    @Test
    void digestIgnoresWhitespaceAndObjectOrderButNotArrayOrder() throws Exception
    {
        String first = DtnPayloadDigest.sha256(mapper, mapper.readTree("{\"b\":[1,2],\"a\":true}"));
        assertEquals(first, DtnPayloadDigest.sha256(mapper, mapper.readTree("{ \"a\":true, \"b\":[1,2] }")));
        assertNotEquals(first, DtnPayloadDigest.sha256(mapper, mapper.readTree("{\"a\":true,\"b\":[2,1]}")));
    }

    @Test
    void duplicateDtnRegistrationPreservesStoredStateAndRejectsChangedDigest()
    {
        DtnRepository repository = mock(DtnRepository.class);
        AtomicReference<DtnJob> stored = new AtomicReference<>();
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            stored.set(call.getArgument(0));
            return stored.get();
        });
        NodeDtnService service = new NodeDtnService(properties, mock(NodePeerClient.class), repository, mapper);
        NodeDtnRegistration registration = new NodeDtnRegistration();
        registration.setTestId(UUID.randomUUID());
        registration.setSenderAgentId("sender-1");
        registration.setReceiverAgentId("receiver-1");
        registration.setProfile(DtnModels.PROFILE);
        registration.setPayloadSha256("a".repeat(64));
        service.accept(registration);
        stored.get().setState("COMPLETED");
        assertEquals("COMPLETED", service.accept(registration).getState());
        assertNull(stored.get().getSentJson());
        assertNull(stored.get().getReferenceJson());
        verify(repository, times(1)).saveAndFlush(any());
        registration.setPayloadSha256("b".repeat(64));
        assertThrows(RuntimeException.class, () -> service.accept(registration));
        assertEquals("a".repeat(64), stored.get().getExpectedPayloadSha256());
    }

    @Test
    void armFailureCancelsStoredSessionAndReleasesReceiverLock() throws Exception
    {
        AgentConnectionRegistry connections = mock(AgentConnectionRegistry.class);
        SessionRepository repository = mock(SessionRepository.class);
        ActiveSessionLockRepository locks = mock(ActiveSessionLockRepository.class);
        SessionService sessions = mock(SessionService.class);
        AtomicReference<TestSessionEntity> stored = new AtomicReference<>();
        when(repository.find(any())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        doAnswer(call -> { stored.set(call.getArgument(0)); return null; }).when(repository).save(any());
        when(connections.online("receiver-1")).thenReturn(true);
        when(locks.tryAcquire(any())).thenReturn(true);
        doThrow(new IllegalStateException("UDP bind failed")).when(connections).send(eq("receiver-1"), any());
        NodeAfsService service = new NodeAfsService(properties, connections, repository, locks, sessions, mapper);
        UUID id = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> service.command(arm(id)));
        verify(sessions).cancel(id);
        verify(locks).release(id);
    }

    @Test
    void repeatedArmDoesNotStartSocketAgainAndInputCommandsAreRejected() throws Exception
    {
        AgentConnectionRegistry connections = mock(AgentConnectionRegistry.class);
        SessionRepository repository = mock(SessionRepository.class);
        ActiveSessionLockRepository locks = mock(ActiveSessionLockRepository.class);
        SessionService sessions = mock(SessionService.class);
        AtomicReference<TestSessionEntity> stored = new AtomicReference<>();
        when(repository.find(any())).thenAnswer(call -> Optional.ofNullable(stored.get()));
        doAnswer(call -> { stored.set(call.getArgument(0)); return null; }).when(repository).save(any());
        when(connections.online("receiver-1")).thenReturn(true);
        when(locks.tryAcquire(any())).thenReturn(true);
        NodeAfsService service = new NodeAfsService(properties, connections, repository, locks, sessions, mapper);
        Envelope request = arm(UUID.randomUUID());
        service.command(request);
        service.command(request);
        verify(connections, times(1)).send(eq("receiver-1"), any());
        verify(locks, times(1)).tryAcquire(any());
        Envelope input = Envelope.of(MessageType.INPUT_CHUNK, "receiver-1", AgentRole.RECEIVER,
                request.sessionId(), mapper.createObjectNode());
        assertThrows(IllegalArgumentException.class, () -> service.command(input));
        Envelope dtn = Envelope.of(MessageType.COMMAND, "receiver-1", AgentRole.RECEIVER,
                request.sessionId(), mapper.valueToTree(new Command(CommandType.DTN_PROCESS, mapper.createObjectNode())));
        assertThrows(IllegalArgumentException.class, () -> service.command(dtn));
    }

    private Envelope arm(UUID id)
    {
        CreateSessionRequest request = new CreateSessionRequest("sender-1", "receiver-1", UUID.randomUUID(),
                new AfsSettings(1), new TransportSettings("127.0.0.1", 45821, 45822, 2, 10, 200, 200),
                new TestOptions(TestType.TEST_A_NORMAL, 1, 1, 10, 0, 1, Map.of()));
        return Envelope.of(MessageType.COMMAND, "receiver-1", AgentRole.RECEIVER, id,
                mapper.valueToTree(new Command(CommandType.ARM_RECEIVER, mapper.valueToTree(request))));
    }

    @Test
    void restartCancelsAfsAndFailsLostInMemoryDtnWorkWithoutResending()
    {
        ActiveSessionLockRepository locks = mock(ActiveSessionLockRepository.class);
        SessionService sessions = mock(SessionService.class);
        DtnRepository repository = mock(DtnRepository.class);
        UUID id = UUID.randomUUID();
        when(locks.current()).thenReturn(Optional.of(id));
        DtnJob preparing = new DtnJob();
        preparing.setState("PREPARING");
        DtnJob calculating = new DtnJob();
        calculating.setState("CALCULATING");
        when(repository.findByStateIn(java.util.List.of("PREPARING", "CALCULATING")))
                .thenReturn(java.util.List.of(preparing, calculating));
        new NodeRecoveryService(locks, sessions, repository).recover();
        verify(sessions).cancel(id);
        assertEquals("FAILED", preparing.getState());
        assertEquals("FAILED", calculating.getState());
        verify(repository, times(2)).save(any());
    }
}

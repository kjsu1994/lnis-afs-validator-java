package server.central.dtn;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import server.central.agent.repository.AgentRepository;
import server.central.agent.service.AgentCommandService;
import server.central.agent.websocket.AgentConnectionRegistry;
import server.central.input.service.InputBufferService;
import server.protocol.codec.DtnChunks;
import server.protocol.model.AgentProtocol.*;
import server.protocol.model.DtnModels;
import server.protocol.model.DtnModels.*;
import server.protocol.model.LnisModels.*;

/** 외부 REST 전달과 별도 Receiver PC의 계산을 조정하는 DTN 전용 서비스다. */
@Service
public class DtnService {
  private static final List<String> ACTIVE = List.of("PREPARING", "WAITING_DTN", "WAITING_RECEIVER", "CALCULATING");
  private final DtnRepository database;
  private final AgentCommandService commands;
  private final AgentRepository agents;
  private final AgentConnectionRegistry connections;
  private final InputBufferService inputs;
  private final ObjectMapper json;
  private final Map<UUID, DtnChunks> chunks = new HashMap<>();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  @Value("${lnis.dtn.send-url:}") private String sendUrl;
  @Value("${lnis.dtn.send-token:}") private String sendToken;
  @Value("${lnis.dtn.receive-token:}") private String receiveToken;

  public DtnService(DtnRepository database, AgentCommandService commands, AgentRepository agents,
      AgentConnectionRegistry connections, InputBufferService inputs, ObjectMapper json) {
    this.database = database; this.commands = commands; this.agents = agents;
    this.connections = connections; this.inputs = inputs; this.json = json;
  }

  public Map<String, Object> configuration() {
    return Map.of("configured", !sendUrl.isBlank() && !receiveToken.isBlank(),
        "profile", DtnModels.PROFILE, "maximumInputBytes", DtnModels.MAX_INPUT_BYTES);
  }

  public synchronized DtnJob create(UUID inputId, String sender, String receiver) {
    if (sendUrl.isBlank() || receiveToken.isBlank())
      throw new IllegalStateException("DTN 송신 URL과 수신 인증 토큰을 설정하세요.");
    URI uri = URI.create(sendUrl);
    if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null)
      throw new IllegalStateException("DTN URL 오류");
    if (!database.findByStateIn(ACTIVE).isEmpty()) throw new IllegalStateException("다른 DTN 시험 진행 중");
    requireAgent(sender, AgentRole.SENDER);
    var rx = agents.find(receiver).orElseThrow(() -> new IllegalArgumentException("Receiver를 선택하세요."));
    if (rx.role() != AgentRole.RECEIVER) throw new IllegalArgumentException("Receiver 역할 오류");
    var input = inputs.get(inputId);
    if (!input.complete() || input.receivedSize() <= 0 || input.receivedSize() > DtnModels.MAX_INPUT_BYTES)
      throw new IllegalArgumentException("수집 종료 후 1 MiB 이하의 입력을 확정하세요.");
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    for (long i = 0; i < input.chunkCount(); i++) data.writeBytes(inputs.chunk(inputId, i));
    if (data.size() != input.receivedSize()) throw new IllegalStateException("입력 크기 불일치");
    DtnJob job = new DtnJob();
    job.setId(UUID.randomUUID()); job.setInputId(inputId);
    job.setSenderAgentId(sender); job.setReceiverAgentId(receiver); job.setCreatedAt(Instant.now());
    update(job, "PREPARING", "기준 PVT 계산 및 AFS 생성 중");
    try { sendChunks(job, sender, "PREPARE", data.toByteArray()); }
    catch (RuntimeException e) { fail(job, e); }
    return job;
  }

  /** 브라우저 저장소에 의존하지 않아 별도 수신 PC에서도 최근 시험을 볼 수 있다. */
  public List<DtnJob> recent() { return database.findTop50ByOrderByCreatedAtDesc(); }

  public DtnJob get(UUID id) {
    return database.findById(id).orElseThrow(() -> new IllegalArgumentException("DTN 시험을 찾을 수 없습니다."));
  }

  public UUID stopCapture(UUID id, String sender) {
    inputs.get(id);
    return commands.command(sender, id, CommandType.DTN_STOP_CAPTURE, null);
  }

  /** 인증 및 동일성 검증 후 H2 저장이 끝나야 callback 접수를 완료한다. */
  public synchronized DtnJob receive(String authorization, byte[] body) throws Exception {
    authenticate(authorization);
    if (body.length > DtnModels.MAX_JSON_BYTES) throw new IllegalArgumentException("DTN JSON 크기 초과");
    JsonNode received = json.readTree(body);
    UUID id = UUID.fromString(received.path("testId").asText());
    DtnJob job = get(id);
    if (job.getSentJson() == null || !received.equals(json.readTree(job.getSentJson())))
      throw new IllegalArgumentException("전송 JSON과 수신 JSON이 다릅니다.");
    if (job.getReceivedJson() != null) return job;
    if (!"WAITING_DTN".equals(job.getState())) throw new IllegalStateException("수신 대기 상태가 아닙니다.");
    job.setReceivedJson(json.writeValueAsString(received));
    update(job, "WAITING_RECEIVER", "DTN 수신 완료 / Receiver 연결 대기");
    return job;
  }

  public void authenticate(String authorization) {
    if (receiveToken.isBlank() || authorization == null || !java.security.MessageDigest.isEqual(
        ("Bearer " + receiveToken).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8)))
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
  }

  /** WebSocket 인증 ID를 기준으로 시험 소유 Agent를 재검증한다. */
  public synchronized void agentData(Envelope envelope) throws Exception {
    DtnJob job = get(envelope.sessionId());
    boolean preparing = "PREPARING".equals(job.getState());
    if (!preparing && !"CALCULATING".equals(job.getState())) return;
    String expected = preparing ? job.getSenderAgentId() : job.getReceiverAgentId();
    if (!expected.equals(envelope.agentId())) throw new IllegalArgumentException("DTN 결과 Agent 불일치");
    try {
      JsonNode payload = envelope.payload();
      byte[] bytes = chunks.computeIfAbsent(job.getId(), ignored -> new DtnChunks()).append(
          payload.path("index").asInt(-1), payload.path("last").asBoolean(),
          Base64.getDecoder().decode(payload.path("dataBase64").asText()));
      if (bytes == null) return;
      chunks.remove(job.getId());
      AgentResult result = json.readValue(bytes, AgentResult.class);
      if (result.getError() != null) throw new IllegalStateException(result.getError());
      if (result.getPvt() == null || result.getPvt().isEmpty()) throw new IllegalArgumentException("PVT 결과 없음");
      if (preparing) {
        if (result.getTransfer() == null || !job.getId().equals(result.getTransfer().getTestId()))
          throw new IllegalArgumentException("송신 payload 식별 오류");
        job.setReferenceJson(json.writeValueAsString(result.getPvt()));
        job.setSentJson(json.writeValueAsString(result.getTransfer()));
        update(job, "WAITING_DTN", "외부 DTN 전달 및 수신 대기");
        String packet = job.getSentJson();
        Thread.ofVirtual().name("dtn-http-send").start(() -> sendExternal(job.getId(), packet));
      } else {
        job.setReceiverJson(json.writeValueAsString(result.getPvt()));
        List<Pvt> reference = json.readValue(job.getReferenceJson(), new TypeReference<>() {});
        var comparison = DtnComparison.compare(reference, result.getPvt());
        job.setComparisonJson(json.writeValueAsString(comparison));
        update(job, "INCONCLUSIVE".equals(comparison.get("verdict")) ? "INCONCLUSIVE" : "COMPLETED",
            "PVT 비교 완료: " + comparison.get("verdict"));
      }
    } catch (Exception e) { fail(job, e); }
  }

  /** ACK 거절은 10분 타임아웃까지 기다리지 않고 즉시 실패로 기록한다. */
  public synchronized void rejected(Envelope envelope) {
    if (envelope.sessionId() == null) return;
    database.findById(envelope.sessionId()).ifPresent(job -> {
      if (ACTIVE.contains(job.getState()) &&
          (job.getSenderAgentId().equals(envelope.agentId()) || job.getReceiverAgentId().equals(envelope.agentId())))
        fail(job, new IllegalStateException(envelope.payload().path("message").asText("Agent 명령 거절")));
    });
  }

  @Scheduled(fixedDelay = 3000)
  public synchronized void tick() {
    for (DtnJob job : database.findByStateIn(ACTIVE)) {
      if (Instant.now().isAfter(job.getCreatedAt().plus(Duration.ofMinutes(10)))) {
        fail(job, new IllegalStateException("DTN 시험 제한 시간 10분 초과")); continue;
      }
      if ("WAITING_RECEIVER".equals(job.getState()) && connections.online(job.getReceiverAgentId())
          && agents.find(job.getReceiverAgentId()).map(a -> a.state() == AgentState.READY).orElse(false)) {
        try {
          update(job, "CALCULATING", "Receiver AFS 복호화 및 PVT 계산 중");
          sendChunks(job, job.getReceiverAgentId(), "RECEIVE", job.getReceivedJson().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { fail(job, e); }
      }
    }
  }

  private void sendExternal(UUID id, String packet) {
    try {
      var request = HttpRequest.newBuilder(URI.create(sendUrl)).timeout(Duration.ofSeconds(30))
          .header("Content-Type", "application/json");
      if (!sendToken.isBlank()) request.header("Authorization", "Bearer " + sendToken);
      int status = http.send(request.POST(HttpRequest.BodyPublishers.ofString(packet)).build(),
          HttpResponse.BodyHandlers.discarding()).statusCode();
      if (status < 200 || status >= 300) throw new IllegalStateException("외부 DTN HTTP 응답: " + status);
    } catch (Exception e) {
      synchronized (this) {
        DtnJob job = get(id);
        // callback이 먼저 도착했다면 전달 성공 상태를 뒤늦은 HTTP 오류로 되돌리지 않는다.
        if ("WAITING_DTN".equals(job.getState())) fail(job, e);
      }
    }
  }

  private void sendChunks(DtnJob job, String agent, String mode, byte[] bytes) {
    for (int offset = 0, index = 0; offset < bytes.length; offset += DtnChunks.CHUNK_BYTES, index++) {
      int end = Math.min(bytes.length, offset+DtnChunks.CHUNK_BYTES);
      commands.command(agent, job.getId(), CommandType.DTN_PROCESS, Map.of(
          "mode", mode, "index", index, "last", end == bytes.length,
          "dataBase64", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, end))));
    }
  }
  private void requireAgent(String id, AgentRole role) {
    var agent = agents.find(id).orElseThrow(() -> new IllegalArgumentException("Agent를 선택하세요."));
    if (agent.role() != role || agent.state() != AgentState.READY || !connections.online(id))
      throw new IllegalStateException("Agent가 연결된 READY 상태여야 합니다.");
  }
  private void update(DtnJob job, String state, String message) {
    job.setState(state); job.setMessage(message); job.setUpdatedAt(Instant.now()); database.save(job);
  }
  private void fail(DtnJob job, Exception error) {
    chunks.remove(job.getId());
    String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    update(job, "FAILED", message.substring(0, Math.min(2000, message.length())));
  }
}

package server.central.dtn;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import lombok.Data;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import server.protocol.model.DtnModels;

/** DTN 담당자에게 공개하는 callback과 화면용 시험 제어 API다. */
@RestController @RequestMapping("/lnis/api/v1/dtn")
public class DtnController {
  private final DtnService service;
  private final ObjectMapper json;
  public DtnController(DtnService service, ObjectMapper json) { this.service = service; this.json = json; }

  @Data public static class CreateRequest {
    @NotNull private UUID inputId;
    @NotBlank private String senderAgentId;
    @NotBlank private String receiverAgentId;
  }
  @GetMapping("/config") public Map<String, Object> config() { return service.configuration(); }

  /** 원본 데이터와 PVT 전체를 제외한 최근 50개 시험 요약이다. */
  @GetMapping("/tests") public List<Map<String, Object>> recent() throws Exception {
    List<Map<String, Object>> result = new ArrayList<>();
    for (DtnJob job : service.recent()) result.add(summary(job));
    return result;
  }

  @PostMapping("/captures/{id}/stop")
  public Map<String, Object> stop(@PathVariable UUID id, @RequestParam String senderAgentId) {
    return Map.of("commandId", service.stopCapture(id, senderAgentId), "accepted", true);
  }

  @PostMapping("/tests") @ResponseStatus(HttpStatus.ACCEPTED)
  public Map<String, Object> create(@Valid @RequestBody CreateRequest request) throws Exception {
    return summary(service.create(request.getInputId(), request.getSenderAgentId(), request.getReceiverAgentId()));
  }
  @GetMapping("/tests/{id}") public Map<String, Object> get(@PathVariable UUID id) throws Exception {
    return summary(service.get(id));
  }
  @GetMapping(value="/tests/{id}/report", produces=MediaType.APPLICATION_JSON_VALUE)
  public Map<String, Object> report(@PathVariable UUID id) throws Exception {
    DtnJob job = service.get(id);
    Map<String, Object> report = new LinkedHashMap<>(summary(job));
    report.put("referencePvt", job.getReferenceJson() == null ? null : json.readTree(job.getReferenceJson()));
    report.put("receivedPvt", job.getReceiverJson() == null ? null : json.readTree(job.getReceiverJson()));
    report.put("comparison", job.getComparisonJson() == null ? null : json.readTree(job.getComparisonJson()));
    return report;
  }

  @PostMapping(value="/receive", consumes=MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> receive(HttpServletRequest request) throws Exception {
    String authorization = request.getHeader("Authorization");
    service.authenticate(authorization);
    byte[] bytes = request.getInputStream().readNBytes(DtnModels.MAX_JSON_BYTES+1);
    if (bytes.length > DtnModels.MAX_JSON_BYTES)
      return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("message", "JSON은 16 MiB 이하입니다."));
    DtnJob job;
    try { job = service.receive(authorization, bytes); }
    catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalArgumentException("올바른 DTN JSON을 보내주세요.", error);
    }
    return ResponseEntity.accepted().body(Map.of("testId", job.getId(), "accepted", true, "state", job.getState()));
  }
  private Map<String, Object> summary(DtnJob job) throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("testId", job.getId()); result.put("inputId", job.getInputId());
    result.put("senderAgentId", job.getSenderAgentId());
    result.put("receiverAgentId", job.getReceiverAgentId());
    result.put("state", job.getState()); result.put("message", job.getMessage());
    result.put("createdAt", job.getCreatedAt()); result.put("updatedAt", job.getUpdatedAt());
    result.put("dtnReceived", job.getReceivedJson() != null);
    if (job.getComparisonJson() != null) {
      var comparison = json.readTree(job.getComparisonJson());
      result.put("verdict", comparison.path("verdict").asText());
      result.put("comparableEpochs", comparison.path("comparableEpochs").asInt());
      result.put("velocityComparableEpochs", comparison.path("velocityComparableEpochs").asInt());
    }
    return result;
  }
}

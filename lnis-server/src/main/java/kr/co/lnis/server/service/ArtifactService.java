package kr.co.lnis.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.common.model.LnisModels.AgentRole;
import kr.co.lnis.common.model.LnisModels.RoleResult;
import kr.co.lnis.server.repository.SessionRepository;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class ArtifactService {
    private final SessionRepository sessions; private final ObjectMapper json;
    public ArtifactService(SessionRepository sessions, ObjectMapper json) { this.sessions = sessions; this.json = json; }
    public byte[] create(UUID id, AgentRole role, String fileName) {
        RoleResult result = sessions.result(id, role).orElseThrow(() -> new IllegalArgumentException("Result not found for " + role));
        try {
            return switch (fileName) {
                case "result.json" -> json.writerWithDefaultPrettyPrinter().writeValueAsBytes(result);
                case "metrics-summary.csv" -> bom(summary(result));
                case "metrics-timeseries.csv" -> bom(timeseries(result));
                default -> throw new IllegalArgumentException("Unknown artifact: " + fileName);
            };
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private static String summary(RoleResult result) {
        StringBuilder csv = new StringBuilder("Role,Category,Name,Description,Value,Unit,Status\r\n");
        for (var m : result.metrics()) csv.append(result.role()).append(',').append(m.category()).append(',').append(cell(m.name())).append(',')
                .append(cell(m.description())).append(',').append(m.value() == null ? "" : m.value()).append(',').append(cell(m.unit())).append(',').append(m.status()).append("\r\n");
        return csv.toString();
    }
    private static String timeseries(RoleResult result) {
        StringBuilder csv = new StringBuilder("Role,Timestamp,CpuPercent,WorkingSetBytes\r\n");
        for (var x : result.samples()) csv.append(result.role()).append(',').append(x.timestamp()).append(',').append(x.cpuPercent()).append(',').append(x.workingSetBytes()).append("\r\n");
        return csv.toString();
    }
    private static String cell(String value) { if (value == null) return ""; return '"' + value.replace("\"", "\"\"") + '"'; }
    private static byte[] bom(String value) { byte[] body = value.getBytes(StandardCharsets.UTF_8), out = new byte[body.length + 3]; out[0]=(byte)0xEF; out[1]=(byte)0xBB; out[2]=(byte)0xBF; System.arraycopy(body,0,out,3,body.length); return out; }
}


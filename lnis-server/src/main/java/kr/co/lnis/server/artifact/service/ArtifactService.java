package kr.co.lnis.server.artifact.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import kr.co.lnis.protocol.model.LnisModels.RoleResult;
import kr.co.lnis.server.session.repository.SessionRepository;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
/**
 * 역할별 시험 결과를 JSON 또는 UTF-8 BOM CSV 산출물로 직렬화한다.
 *
 * <p>파일을 서버 디스크에 미리 만들지 않고 HTTP 요청 시 Redis 결과에서 즉시 생성한다. 허용된 세 파일
 * 이름만 switch로 처리해 사용자가 임의의 서버 파일 경로를 지정할 수 없게 한다.
 */
public class ArtifactService {
    private final SessionRepository sessions;
    private final ObjectMapper json;

    public ArtifactService(SessionRepository sessions, ObjectMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    /** session/role 결과를 조회하고 요청한 허용 산출물의 byte 배열을 반환한다. */
    public byte[] create(UUID id, AgentRole role, String fileName) {
        RoleResult result = sessions.result(id, role)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Result not found for " + role));
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
        for (var metric : result.metrics()) {
            csv.append(result.role()).append(',')
                    .append(metric.category()).append(',')
                    .append(cell(metric.name())).append(',')
                    .append(cell(metric.description())).append(',')
                    .append(metric.value() == null ? "" : metric.value()).append(',')
                    .append(cell(metric.unit())).append(',')
                    .append(metric.status()).append("\r\n");
        }
        return csv.toString();
    }
    private static String timeseries(RoleResult result) {
        StringBuilder csv = new StringBuilder("Role,Timestamp,CpuPercent,WorkingSetBytes\r\n");
        for (var sample : result.samples()) {
            csv.append(result.role()).append(',')
                    .append(sample.timestamp()).append(',')
                    .append(sample.cpuPercent()).append(',')
                    .append(sample.workingSetBytes()).append("\r\n");
        }
        return csv.toString();
    }
    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static byte[] bom(String value) {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        byte[] output = new byte[body.length + 3];
        output[0] = (byte) 0xEF;
        output[1] = (byte) 0xBB;
        output[2] = (byte) 0xBF;
        System.arraycopy(body, 0, output, 3, body.length);
        return output;
    }
}

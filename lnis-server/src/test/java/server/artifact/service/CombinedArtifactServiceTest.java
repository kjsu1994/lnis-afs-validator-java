package server.artifact.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import server.model.LnisModels.*;
import server.frameevidence.service.FrameEvidenceService;
import server.session.repository.SessionRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CombinedArtifactServiceTest {
    @Test
    void createsCombinedJsonAndFourSheetWorkbook() throws Exception {
        UUID sessionId = UUID.randomUUID();
        SessionRepository sessions = mock(SessionRepository.class);
        FrameEvidenceService evidence = mock(FrameEvidenceService.class);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        RoleResult sender = result(sessionId);
        when(sessions.result(sessionId, AgentRole.SENDER))
                .thenReturn(Optional.of(sender));
        when(sessions.result(sessionId, AgentRole.RECEIVER))
                .thenReturn(Optional.empty());
        when(evidence.details(sessionId)).thenReturn(List.of());
        CombinedArtifactService service =
                new CombinedArtifactService(sessions, evidence, json);

        var tree = json.readTree(service.create(sessionId, "lnis-report.json"));
        assertEquals(sessionId.toString(), tree.get("sessionId").asText());
        assertEquals("SENDER", tree.at("/senderResult/role").asText());
        assertTrue(tree.get("receiverResult").isNull());
        assertTrue(tree.get("frameEvidence").isArray());

        byte[] xlsx = service.create(sessionId, "lnis-report.xlsx");
        assertEquals('P', xlsx[0]);
        assertEquals('K', xlsx[1]);
        try (XSSFWorkbook book =
                     new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals(
                    List.of("요약", "지표", "시계열", "프레임 비교"),
                    java.util.stream.IntStream.range(0, book.getNumberOfSheets())
                            .mapToObj(index -> book.getSheetAt(index).getSheetName())
                            .toList());
            assertEquals("역할", book.getSheet("요약")
                    .getRow(0).getCell(0).getStringCellValue());
            assertEquals("DecodedFrames", book.getSheet("지표")
                    .getRow(1).getCell(2).getStringCellValue());
            assertEquals("SENDER", book.getSheet("시계열")
                    .getRow(1).getCell(0).getStringCellValue());
        }
    }

    private static RoleResult result(UUID sessionId) {
        IntegrityResult integrity = new IntegrityResult(
                true,
                464,
                464,
                "SOURCE",
                "SOURCE",
                4,
                4,
                "일치");
        Metric metric = new Metric(
                MetricCategory.DATA_INTEGRITY,
                "DecodedFrames",
                "처리 프레임",
                "frame",
                4.0,
                MetricStatus.PASS,
                null,
                null);
        NetworkCounters counters = new NetworkCounters(
                4,
                4,
                12,
                16,
                10,
                0,
                1,
                1,
                464,
                Duration.ofMillis(20),
                List.of(1.25),
                0,
                0,
                0,
                0,
                0,
                0);
        return new RoleResult(
                1,
                sessionId,
                AgentRole.SENDER,
                Verdict.PASS,
                Instant.parse("2026-08-24T00:00:00Z"),
                integrity,
                List.of(metric),
                counters,
                List.of(new ResourceSample(
                        Instant.parse("2026-08-24T00:00:00Z"),
                        3.5,
                        1024)),
                null);
    }
}

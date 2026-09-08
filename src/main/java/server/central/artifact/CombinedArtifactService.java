package server.central.artifact;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import server.central.frameevidence.FrameEvidenceDetail;
import server.central.frameevidence.FrameEvidenceService;
import server.central.frameevidence.FrameEvidenceSummary;
import server.central.session.SessionRepository;
import server.shared.model.LnisModels.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/** Sender, Receiver와 프레임 증거를 단일 JSON 또는 다중 시트 Excel로 묶는다. */
@RequiredArgsConstructor
@Service
public class CombinedArtifactService {
    private final SessionRepository sessionRepository;
    private final FrameEvidenceService frameEvidenceService;
    private final ObjectMapper objectMapper;

    public byte[] create(UUID sessionId, String fileName)
    {
        // 한 역할만 결과를 제출한 장애 상황도 분석할 수 있도록 null인 반대 역할을 허용한다.
        RoleResult sender = sessionRepository.result(sessionId, AgentRole.SENDER).orElse(null);
        RoleResult receiver = sessionRepository.result(sessionId, AgentRole.RECEIVER).orElse(null);
        if (sender == null && receiver == null) {
            throw new IllegalArgumentException("Session results not found: " + sessionId);
        }
        CombinedReport report =
                new CombinedReport(
                        1,
                        sessionId,
                        Instant.now(),
                        sender,
                        receiver,
                        frameEvidenceService.details(sessionId));
        try {
            return switch (fileName) {
                case "lnis-report.json" ->
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
                case "lnis-report.xlsx" -> excel(report);
                default ->
                        throw new IllegalArgumentException(
                                "Unknown combined artifact: " + fileName);
            };
        } catch (IOException error) {
            throw new IllegalStateException("통합 산출물 생성에 실패했습니다.", error);
        }
    }

    @Value
    @Builder
    @Jacksonized
    @Accessors(fluent = true)
    @JsonAutoDetect(fieldVisibility = Visibility.ANY)
    public static class CombinedReport {
        int schemaVersion;
        UUID sessionId;
        Instant generatedAt;
        RoleResult senderResult;
        RoleResult receiverResult;
        List<FrameEvidenceDetail> frameEvidenceService;

        public CombinedReport(
                int schemaVersion,
                UUID sessionId,
                Instant generatedAt,
                RoleResult senderResult,
                RoleResult receiverResult,
                List<FrameEvidenceDetail> frameEvidenceService)
        {
            frameEvidenceService =
                    frameEvidenceService == null ? List.of() : List.copyOf(frameEvidenceService);

            this.schemaVersion = schemaVersion;
            this.sessionId = sessionId;
            this.generatedAt = generatedAt;
            this.senderResult = senderResult;
            this.receiverResult = receiverResult;
            this.frameEvidenceService = frameEvidenceService;
        }
    }

    private byte[] excel(CombinedReport report) throws IOException
    {
        try (XSSFWorkbook book = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            // 개요, 판정 지표, 자원 시계열, 비트 수준 증거를 목적별 시트로 분리한다.
            Styles styles = styles(book);
            summarySheet(book, report, styles);
            metricsSheet(book, report, styles);
            samplesSheet(book, report, styles);
            frameSheet(book, report.frameEvidenceService(), styles);
            book.getProperties()
                    .getCoreProperties()
                    .setTitle("LNIS 통합 시험 결과 " + report.sessionId());
            book.setActiveSheet(0);
            book.write(output);
            return output.toByteArray();
        }
    }

    private void summarySheet(Workbook book, CombinedReport report, Styles styles)
            throws JsonProcessingException
    {
        Sheet sheet = book.createSheet("요약");
        int row = 0;
        header(sheet, row++, styles, "역할", "항목", "값");
        row = data(sheet, row, styles, "", "sessionId", report.sessionId());
        row = data(sheet, row, styles, "", "generatedAt", report.generatedAt());
        row = flattenResult(sheet, row, report.senderResult(), styles);
        row = flattenResult(sheet, row, report.receiverResult(), styles);
        finish(sheet, row - 1, 2, 14, 42, 72);
    }

    /** 중첩된 결과 객체를 {@code 역할 / 경로 / 값} 행으로 펼쳐 Excel 요약 시트에 기록한다. */
    @SuppressWarnings("unchecked")
    private int flattenResult(Sheet sheet, int row, RoleResult result, Styles styles)
            throws JsonProcessingException
    {
        if (result == null) {
            return row;
        }
        Map<String, Object> values = objectMapper.convertValue(result, LinkedHashMap.class);
        values.remove("metrics");
        values.remove("samples");
        return flatten(sheet, row, styles, result.role().name(), "", values);
    }

    private int flatten(Sheet sheet, int row, Styles styles, String role, String path, Object value)
            throws JsonProcessingException
    {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String next =
                        path.isEmpty() ? entry.getKey().toString() : path + "." + entry.getKey();
                row = flatten(sheet, row, styles, role, next, entry.getValue());
            }
            return row;
        }
        if (value instanceof Collection<?> collection) {
            return data(
                    sheet, row, styles, role, path, objectMapper.writeValueAsString(collection));
        }
        return data(sheet, row, styles, role, path, value);
    }

    private static void metricsSheet(Workbook book, CombinedReport report, Styles styles)
    {
        Sheet sheet = book.createSheet("지표");
        int row = 0;
        header(sheet, row++, styles, "역할", "분류", "지표", "설명", "값", "단위", "상태", "임계 조건", "상세");
        for (RoleResult result : results(report)) {
            for (Metric metric : result.metrics()) {
                MetricThreshold threshold = metric.threshold();
                String condition =
                        threshold == null || !threshold.enabled()
                                ? ""
                                : (threshold.minimum() ? "최소 " : "최대 ") + threshold.value();
                row =
                        data(
                                sheet,
                                row,
                                styles,
                                result.role(),
                                metric.category(),
                                metric.name(),
                                metric.description(),
                                metric.value(),
                                metric.unit(),
                                metric.status(),
                                condition,
                                metric.detail());
            }
        }
        finish(sheet, row - 1, 8, 12, 17, 28, 58, 14, 11, 17, 18, 52);
    }

    private static void samplesSheet(Workbook book, CombinedReport report, Styles styles)
    {
        Sheet sheet = book.createSheet("시계열");
        int row = 0;
        header(sheet, row++, styles, "역할", "측정 시각", "CPU 사용률 (%)", "Working Set (byte)");
        for (RoleResult result : results(report)) {
            for (ResourceSample sample : result.samples()) {
                row =
                        data(
                                sheet,
                                row,
                                styles,
                                result.role(),
                                sample.timestamp(),
                                sample.cpuPercent(),
                                sample.workingSetBytes());
            }
        }
        finish(sheet, row - 1, 3, 12, 28, 18, 22);
    }

    private static void frameSheet(
            Workbook book, List<FrameEvidenceDetail> details, Styles styles)
    {
        Sheet sheet = book.createSheet("프레임 비교");
        int row = 0;
        header(
                sheet,
                row++,
                styles,
                "프레임",
                "Decoder 완료",
                "완전 복호",
                "SB2 CRC",
                "SB3 CRC",
                "SB4 CRC",
                "SB2 판정 변경",
                "SB3 판정 변경",
                "SB4 판정 변경",
                "GRAW 사용",
                "실패 원인",
                "기준→송신",
                "송신→수신",
                "기준→복구",
                "기준 SHA-256",
                "송신 SHA-256",
                "수신 SHA-256",
                "복구 SHA-256",
                "해석");
        for (FrameEvidenceDetail detail : details) {
            FrameEvidenceSummary item = detail.summary();
            row =
                    data(
                            sheet,
                            row,
                            styles,
                            item.frameIndex() + 1,
                            item.decoderCompleted(),
                            item.decodeSucceeded(),
                            item.sb2CrcValid(),
                            item.sb3CrcValid(),
                            item.sb4CrcValid(),
                            item.sb2DecisionChanges(),
                            item.sb3DecisionChanges(),
                            item.sb4DecisionChanges(),
                            item.usedForGrawReassembly(),
                            item.failureReason(),
                            item.referenceToTransmittedDifferences(),
                            item.transmittedToReceivedDifferences(),
                            item.referenceToReencodedDifferences(),
                            item.referenceSha256(),
                            item.transmittedSha256(),
                            item.receivedSha256(),
                            item.reencodedSha256(),
                            item.interpretation());
        }
        finish(
                sheet, row - 1, 18, 10, 13, 11, 11, 11, 11, 16, 16, 16, 12, 36, 13, 13, 13, 28, 28,
                28, 28, 54);
    }

    private static List<RoleResult> results(CombinedReport report)
    {
        return Stream.of(report.senderResult(), report.receiverResult())
                .filter(Objects::nonNull)
                .toList();
    }

    private static void header(Sheet sheet, int rowIndex, Styles styles, Object... values)
    {
        Row row = sheet.createRow(rowIndex);
        row.setHeightInPoints(24);
        for (int column = 0; column < values.length; column++) {
            Cell cell = row.createCell(column);
            set(cell, values[column]);
            cell.setCellStyle(styles.header());
        }
    }

    private static int data(Sheet sheet, int rowIndex, Styles styles, Object... values)
    {
        Row row = sheet.createRow(rowIndex);
        for (int column = 0; column < values.length; column++) {
            Cell cell = row.createCell(column);
            set(cell, values[column]);
            String text = String.valueOf(values[column]);
            cell.setCellStyle(
                    "PASS".equals(text)
                            ? styles.pass()
                            : "FAIL".equals(text) ? styles.fail() : styles.body());
        }
        return rowIndex + 1;
    }

    private static void set(Cell cell, Object value)
    {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else {
            cell.setCellValue(value.toString());
        }
    }

    private static void finish(Sheet sheet, int lastRow, int lastColumn, int... widths)
    {
        // 모든 데이터 시트에 같은 탐색 규칙(헤더 고정, 필터, 명시 폭)을 적용한다.
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, lastRow), 0, lastColumn));
        sheet.setDisplayGridlines(false);
        for (int column = 0; column < widths.length; column++) {
            sheet.setColumnWidth(column, Math.min(255, widths[column]) * 256);
        }
    }

    private static Styles styles(Workbook book)
    {
        Font headerFont = book.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        CellStyle header = book.createCellStyle();
        header.setFont(headerFont);
        header.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        header.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle body = book.createCellStyle();
        body.setVerticalAlignment(VerticalAlignment.TOP);
        body.setWrapText(true);

        CellStyle pass = book.createCellStyle();
        pass.cloneStyleFrom(body);
        pass.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        pass.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle fail = book.createCellStyle();
        fail.cloneStyleFrom(body);
        fail.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        fail.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return new Styles(header, body, pass, fail);
    }

    @Value
    @AllArgsConstructor
    @Builder
    @Jacksonized
    @Accessors(fluent = true)
    @JsonAutoDetect(fieldVisibility = Visibility.ANY)
    private static class Styles {
        CellStyle header;
        CellStyle body;
        CellStyle pass;
        CellStyle fail;
    }
}


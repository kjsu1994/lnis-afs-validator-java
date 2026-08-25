package kr.co.lnis.server.frameevidence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.lnis.protocol.codec.Hashing;
import kr.co.lnis.protocol.model.AgentProtocol.FrameEvidenceMessage;
import kr.co.lnis.protocol.model.LnisModels.AgentRole;
import kr.co.lnis.server.frameevidence.dto.*;
import kr.co.lnis.server.frameevidence.entity.FrameEvidenceEntity;
import kr.co.lnis.server.frameevidence.repository.FrameEvidenceRepository;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** 역할별 증거를 병합하고 SHA-256, 비트 차이 수와 차이 위치를 계산한다. */
@Service
public class FrameEvidenceService {
    private static final int AFS_FRAME_BYTES = 750;
    private final FrameEvidenceRepository repository;
    private final ObjectMapper json;

    public FrameEvidenceService(
            FrameEvidenceRepository repository,
            ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    public void save(
            UUID sessionId,
            AgentRole role,
            FrameEvidenceMessage evidence) {
        validate(evidence);
        repository.save(sessionId, role, evidence);
    }

    public List<FrameEvidenceSummary> summaries(UUID sessionId) {
        Map<Integer, EnumMap<AgentRole, FrameEvidenceMessage>> grouped = group(sessionId);
        return grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> summary(
                        entry.getKey(),
                        entry.getValue().get(AgentRole.SENDER),
                        entry.getValue().get(AgentRole.RECEIVER)))
                .toList();
    }

    public FrameEvidenceDetail detail(UUID sessionId, int frameIndex) {
        FrameEvidenceMessage sender = repository
                .find(sessionId, AgentRole.SENDER, frameIndex)
                .map(FrameEvidenceEntity::evidence)
                .orElse(null);
        FrameEvidenceMessage receiver = repository
                .find(sessionId, AgentRole.RECEIVER, frameIndex)
                .map(FrameEvidenceEntity::evidence)
                .orElse(null);
        if (sender == null && receiver == null) {
            throw new IllegalArgumentException("보관된 AFS 프레임 증거가 없습니다: " + frameIndex);
        }
        byte[] reference = bytes(sender, FrameEvidenceMessage::referenceFrame);
        byte[] transmitted = bytes(sender, FrameEvidenceMessage::transmittedFrame);
        byte[] received = bytes(receiver, FrameEvidenceMessage::receivedFrame);
        byte[] reencoded = bytes(receiver, FrameEvidenceMessage::reencodedFrame);
        return new FrameEvidenceDetail(
                summary(frameIndex, sender, receiver),
                reference,
                transmitted,
                received,
                reencoded,
                differencePositions(reference, transmitted),
                differencePositions(transmitted, received),
                differencePositions(reference, reencoded));
    }

    public byte[] jsonArtifact(UUID sessionId) {
        try {
            return json.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(details(sessionId));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("프레임 증거 JSON 생성에 실패했습니다.", error);
        }
    }

    public List<FrameEvidenceDetail> details(UUID sessionId) {
        return summaries(sessionId).stream()
                .map(item -> detail(sessionId, item.frameIndex()))
                .toList();
    }

    public byte[] csvArtifact(UUID sessionId) {
        StringBuilder csv = new StringBuilder(
                "FrameIndex,DecoderCompleted,FullyDecoded,SB2CrcValid,SB3CrcValid,SB4CrcValid,"
                        + "SB2DecisionChanges,SB3DecisionChanges,SB4DecisionChanges,UsedForGrawReassembly,FailureReason,"
                        + "ReferenceToTransmittedBits,"
                        + "TransmittedToReceivedBits,ReferenceToReencodedBits,"
                        + "ReferenceSha256,TransmittedSha256,ReceivedSha256,ReencodedSha256,Interpretation\r\n");
        for (FrameEvidenceSummary item : summaries(sessionId)) {
            csv.append(item.frameIndex()).append(',')
                    .append(item.decoderCompleted()).append(',')
                    .append(item.decodeSucceeded()).append(',')
                    .append(item.sb2CrcValid()).append(',')
                    .append(item.sb3CrcValid()).append(',')
                    .append(item.sb4CrcValid()).append(',')
                    .append(item.sb2DecisionChanges()).append(',')
                    .append(item.sb3DecisionChanges()).append(',')
                    .append(item.sb4DecisionChanges()).append(',')
                    .append(item.usedForGrawReassembly()).append(',')
                    .append(cell(item.failureReason())).append(',')
                    .append(number(item.referenceToTransmittedDifferences())).append(',')
                    .append(number(item.transmittedToReceivedDifferences())).append(',')
                    .append(number(item.referenceToReencodedDifferences())).append(',')
                    .append(cell(item.referenceSha256())).append(',')
                    .append(cell(item.transmittedSha256())).append(',')
                    .append(cell(item.receivedSha256())).append(',')
                    .append(cell(item.reencodedSha256())).append(',')
                    .append(cell(item.interpretation())).append("\r\n");
        }
        return bom(csv.toString());
    }

    private Map<Integer, EnumMap<AgentRole, FrameEvidenceMessage>> group(UUID sessionId) {
        Map<Integer, EnumMap<AgentRole, FrameEvidenceMessage>> grouped = new HashMap<>();
        for (FrameEvidenceEntity entity : repository.findAll(sessionId)) {
            grouped.computeIfAbsent(
                            entity.frameIndex(),
                            ignored -> new EnumMap<>(AgentRole.class))
                    .put(entity.role(), entity.evidence());
        }
        return grouped;
    }

    private static FrameEvidenceSummary summary(
            int frameIndex,
            FrameEvidenceMessage sender,
            FrameEvidenceMessage receiver) {
        byte[] reference = bytes(sender, FrameEvidenceMessage::referenceFrame);
        byte[] transmitted = bytes(sender, FrameEvidenceMessage::transmittedFrame);
        byte[] received = bytes(receiver, FrameEvidenceMessage::receivedFrame);
        byte[] reencoded = bytes(receiver, FrameEvidenceMessage::reencodedFrame);
        Integer injected = differenceCount(reference, transmitted);
        Integer transport = differenceCount(transmitted, received);
        Integer recovered = differenceCount(reference, reencoded);
        return new FrameEvidenceSummary(
                frameIndex,
                sender != null,
                receiver != null,
                receiver != null && receiver.decodeSucceeded(),
                receiver != null && receiver.decoderCompleted(),
                receiver != null && receiver.sb2CrcValid(),
                receiver != null && receiver.sb3CrcValid(),
                receiver != null && receiver.sb4CrcValid(),
                receiver == null ? 0 : receiver.sb2DecisionChanges(),
                receiver == null ? 0 : receiver.sb3DecisionChanges(),
                receiver == null ? 0 : receiver.sb4DecisionChanges(),
                receiver != null && receiver.usedForGrawReassembly(),
                receiver == null ? null : receiver.failureReason(),
                hash(reference),
                hash(transmitted),
                hash(received),
                hash(reencoded),
                injected,
                transport,
                recovered,
                sender == null ? List.of() : sender.injectedBitPositions(),
                intentionalSyncDamage(sender, receiver),
                interpretation(
                        injected,
                        transport,
                        recovered,
                        sender,
                        receiver));
    }

    private static String interpretation(
            Integer injected,
            Integer transport,
            Integer recovered,
            FrameEvidenceMessage sender,
            FrameEvidenceMessage receiver) {
        if (receiver == null) {
            return "Receiver 증거를 기다리는 중입니다.";
        }
        if (intentionalSyncDamage(sender, receiver)) {
            return "Test D에서 동기 패턴을 의도적으로 손상해 제외한 프레임입니다. "
                    + "재인코딩 자료가 없는 것이 정상이며 다음 프레임의 동기 복구 결과를 확인하세요.";
        }
        if (!receiver.decoderCompleted()) {
            return "AFS Decoder가 처리를 완료하지 못했습니다. 원인: "
                    + defaultReason(receiver.failureReason());
        }
        if (!receiver.decodeSucceeded()) {
            String use = receiver.usedForGrawReassembly()
                    ? "SB3·SB4가 정상이라 GRAW 재조립에는 사용됐습니다."
                    : "GRAW 재조립에서는 제외됐습니다.";
            return "Decoder 처리는 완료됐지만 완전 복호에 실패했습니다. 원인: "
                    + defaultReason(receiver.failureReason()) + ". " + use;
        }
        if (recovered == 0) {
            if (injected != null && injected > 0) {
                return "주입된 오류가 있었지만 복호화 후 기준 프레임과 완전히 같아졌습니다.";
            }
            return "기준·송신·수신·복호화 검증 프레임이 동일합니다.";
        }
        if (transport != null && transport > 0) {
            return "송신 이후 수신 단계에서도 비트 차이가 발생했고 최종 기준과 일치하지 않습니다.";
        }
        return "수신 프레임은 확보했지만 복호화 후 기준 프레임과 차이가 남았습니다.";
    }

    private static String defaultReason(String value) {
        return value == null || value.isBlank()
                ? "상세 원인 없음"
                : value;
    }

    /** 동기 패턴 68비트 안의 의도적 손상 때문에 복호화에서 제외된 Test D 프레임인지 판별한다. */
    private static boolean intentionalSyncDamage(
            FrameEvidenceMessage sender,
            FrameEvidenceMessage receiver) {
        return sender != null
                && receiver != null
                && !receiver.decodeSucceeded()
                && !sender.injectedBitPositions().isEmpty()
                && sender.injectedBitPositions().stream()
                        .allMatch(position -> position >= 0 && position < 68);
    }

    private static void validate(FrameEvidenceMessage evidence) {
        for (byte[] frame : List.of(
                nullable(evidence.referenceFrame()),
                nullable(evidence.transmittedFrame()),
                nullable(evidence.receivedFrame()),
                nullable(evidence.reencodedFrame()))) {
            if (frame.length != 0 && frame.length != AFS_FRAME_BYTES) {
                throw new IllegalArgumentException("AFS 프레임 증거는 정확히 750 byte여야 합니다.");
            }
        }
        if (evidence.frameIndex() < 0) {
            throw new IllegalArgumentException("프레임 번호는 0 이상이어야 합니다.");
        }
    }

    private static byte[] nullable(byte[] value) {
        return value == null ? new byte[0] : value;
    }

    private static byte[] bytes(
            FrameEvidenceMessage value,
            Function<FrameEvidenceMessage, byte[]> getter) {
        return value == null ? null : getter.apply(value);
    }

    private static String hash(byte[] value) {
        return value == null
                ? null
                : Hashing.hex(Hashing.sha256Digest().digest(value));
    }

    private static Integer differenceCount(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != right.length) {
            return null;
        }
        int count = 0;
        for (int index = 0; index < left.length; index++) {
            count += Integer.bitCount((left[index] ^ right[index]) & 0xff);
        }
        return count;
    }

    private static List<Integer> differencePositions(byte[] left, byte[] right) {
        if (left == null || right == null || left.length != right.length) {
            return List.of();
        }
        List<Integer> positions = new ArrayList<>();
        for (int byteIndex = 0; byteIndex < left.length; byteIndex++) {
            int difference = (left[byteIndex] ^ right[byteIndex]) & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                if ((difference & (1 << (7 - bit))) != 0) {
                    positions.add(byteIndex * 8 + bit);
                }
            }
        }
        return List.copyOf(positions);
    }

    private static String number(Integer value) {
        return value == null ? "" : value.toString();
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

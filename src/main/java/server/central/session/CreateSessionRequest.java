package server.central.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.jackson.Jacksonized;

import server.shared.model.LnisModels.AfsSettings;
import server.shared.model.LnisModels.TestOptions;

import java.util.UUID;

/** Sender/Receiver Agent, GRAW 입력과 Test A~D 옵션을 결합한 세션 생성 API 요청이다. */
@Value
@Builder
@Jacksonized
@Accessors(fluent = true)
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
public class CreateSessionRequest {
    /** AFS 프레임을 생성해 관리 채널로 보낼 Sender Agent의 고유 ID다. */
    @NotBlank
    String senderAgentId;

    /** AFS 프레임을 수신하고 GRAW를 복원할 Receiver Agent의 고유 ID다. */
    @NotBlank
    String receiverAgentId;

    /** 완료 검증된 H2·파일 입력 버퍼의 UUID다. 미완료 입력은 시험에 사용할 수 없다. */
    @NotNull
    UUID inputId;

    /** SB2 ephemeris에 사용할 AFS PRN 설정이며 생략하면 PRN 1이다. */
    @Valid
    AfsSettings afs;

    /** 시험 종류, 오류 주입량, 난수 Seed와 판정 임계값 설정이다. */
    @Valid
    @NotNull
    TestOptions options;

    public CreateSessionRequest(
            /** AFS 프레임을 생성해 관리 채널로 보낼 Sender Agent의 고유 ID다. */
            @NotBlank String senderAgentId,
            /** AFS 프레임을 수신하고 AFS/GRAW를 복원할 Receiver Agent의 고유 ID다. */
            @NotBlank String receiverAgentId,
            /** 완료 검증된 H2·파일 입력 버퍼의 UUID다. 미완료 입력은 시험에 사용할 수 없다. */
            @NotNull UUID inputId,
            /** SB2 ephemeris에 사용할 AFS PRN 설정이며 생략하면 PRN 1이다. */
            @Valid AfsSettings afs,
            /** 시험 종류, 오류 주입량, 난수 Seed와 판정 임계값 설정이다. */
            @Valid @NotNull TestOptions options)
    {
        afs = afs == null ? new AfsSettings(1) : afs;

        this.senderAgentId = senderAgentId;
        this.receiverAgentId = receiverAgentId;
        this.inputId = inputId;
        this.afs = afs;
        this.options = options;
    }
}

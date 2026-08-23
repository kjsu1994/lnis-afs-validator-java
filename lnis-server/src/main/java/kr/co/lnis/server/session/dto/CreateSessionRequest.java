package kr.co.lnis.server.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import kr.co.lnis.common.model.LnisModels.TestOptions;
import kr.co.lnis.common.model.LnisModels.TransportSettings;

/** 두 Agent, 입력, UDP 전송 조건과 시험 옵션을 결합한 세션 생성 요청이다. */
public record CreateSessionRequest(
        @NotBlank String senderAgentId,
        @NotBlank String receiverAgentId,
        @NotNull UUID inputId,
        @Valid @NotNull TransportSettings transport,
        @Valid @NotNull TestOptions options) {}

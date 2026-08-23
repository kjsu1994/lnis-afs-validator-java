package kr.co.lnis.server.input.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import kr.co.lnis.protocol.model.LnisModels.InputKind;

/** 업로드 또는 GNSS 수집용 입력 버퍼 생성 요청이다. */
public record CreateInputRequest(
        @NotBlank String fileName,
        @PositiveOrZero long size,
        InputKind kind) {}

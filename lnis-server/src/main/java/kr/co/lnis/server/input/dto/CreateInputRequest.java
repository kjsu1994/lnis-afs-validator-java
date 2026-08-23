package kr.co.lnis.server.input.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import kr.co.lnis.protocol.model.LnisModels.InputKind;

/** GRAW 업로드 또는 GNSS 수집 데이터를 받을 Redis 입력 버퍼 생성 요청이다. */
public record CreateInputRequest(
        /** 원본 파일 표시 이름이다. 경로가 아니라 다운로드·화면 표시용 이름만 전달한다. */
        @NotBlank String fileName,
        /** 클라이언트가 전송할 것으로 선언한 전체 크기이며 단위는 byte다. */
        @PositiveOrZero long size,
        /** 파일 업로드와 실시간 GNSS 수집 중 입력 생성 경로를 구분한다. */
        InputKind kind) {}

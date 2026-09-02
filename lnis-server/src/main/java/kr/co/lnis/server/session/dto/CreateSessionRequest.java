package kr.co.lnis.server.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import kr.co.lnis.protocol.model.LnisModels.AfsSettings;
import kr.co.lnis.protocol.model.LnisModels.TestOptions;
import kr.co.lnis.protocol.model.LnisModels.TransportSettings;

/** Sender/Receiver Agent, GRAW 입력, UDP 조건과 Test A~E 옵션을 결합한 세션 생성 API 요청이다. */
@lombok.Value
@lombok.Builder
@lombok.extern.jackson.Jacksonized
@lombok.experimental.Accessors(fluent = true)
@com.fasterxml.jackson.annotation.JsonAutoDetect(
    fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
public class CreateSessionRequest {
  /** AFS 프레임을 생성하고 UDP로 보낼 Sender Agent의 고유 ID다. */
  @NotBlank String senderAgentId;

  /** UDP 프레임을 수신하고 AFS/GRAW를 복원할 Receiver Agent의 고유 ID다. */
  @NotBlank String receiverAgentId;

  /** 완료 검증된 Redis 입력 버퍼의 UUID다. 미완료 입력은 시험에 사용할 수 없다. */
  @NotNull UUID inputId;

  /** SB2 ephemeris에 사용할 AFS PRN 설정이며 생략하면 PRN 1이다. */
  @Valid AfsSettings afs;

  /** 주소·포트·반복 송신·타임아웃 등 UDP 전송 계층 설정이다. */
  @Valid @NotNull TransportSettings transport;

  /** 시험 종류, 오류 주입량, 난수 Seed와 판정 임계값 설정이다. */
  @Valid @NotNull TestOptions options;

  public CreateSessionRequest(
      /** AFS 프레임을 생성하고 UDP로 보낼 Sender Agent의 고유 ID다. */
      @NotBlank String senderAgentId,
      /** UDP 프레임을 수신하고 AFS/GRAW를 복원할 Receiver Agent의 고유 ID다. */
      @NotBlank String receiverAgentId,
      /** 완료 검증된 Redis 입력 버퍼의 UUID다. 미완료 입력은 시험에 사용할 수 없다. */
      @NotNull UUID inputId,
      /** SB2 ephemeris에 사용할 AFS PRN 설정이며 생략하면 PRN 1이다. */
      @Valid AfsSettings afs,
      /** 주소·포트·반복 송신·타임아웃 등 UDP 전송 계층 설정이다. */
      @Valid @NotNull TransportSettings transport,
      /** 시험 종류, 오류 주입량, 난수 Seed와 판정 임계값 설정이다. */
      @Valid @NotNull TestOptions options) {
    afs = afs == null ? new AfsSettings(1) : afs;

    this.senderAgentId = senderAgentId;
    this.receiverAgentId = receiverAgentId;
    this.inputId = inputId;
    this.afs = afs;
    this.transport = transport;
    this.options = options;
  }
}

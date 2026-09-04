package server.central.frameevidence.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import server.protocol.model.AgentProtocol.FrameEvidenceMessage;

/** 프레임 원문과 진단값을 하나의 H2 BLOB으로 손실 없이 변환한다. */
@Converter
public class FrameEvidenceMessageConverter
    implements AttributeConverter<FrameEvidenceMessage, byte[]> {
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  @Override
  public byte[] convertToDatabaseColumn(FrameEvidenceMessage value) {
    if (value == null) return null;
    try {
      return JSON.writeValueAsBytes(value);
    } catch (Exception error) {
      throw new IllegalArgumentException("프레임 증거를 DB 형식으로 변환하지 못했습니다.", error);
    }
  }

  @Override
  public FrameEvidenceMessage convertToEntityAttribute(byte[] value) {
    if (value == null) return null;
    try {
      return JSON.readValue(value, FrameEvidenceMessage.class);
    } catch (Exception error) {
      throw new IllegalArgumentException("DB의 프레임 증거를 복원하지 못했습니다.", error);
    }
  }
}

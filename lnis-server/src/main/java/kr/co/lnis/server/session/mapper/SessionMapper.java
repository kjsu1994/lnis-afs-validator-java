package kr.co.lnis.server.session.mapper;

import kr.co.lnis.protocol.model.LnisModels.SessionSnapshot;
import kr.co.lnis.server.session.entity.TestSessionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
/** 저장 엔티티를 외부 조회용 세션 스냅샷으로 변환한다. */
public interface SessionMapper {
    @Mapping(target = "txResult", ignore = true)
    @Mapping(target = "rxResult", ignore = true)
    SessionSnapshot toSnapshot(TestSessionEntity entity);
}

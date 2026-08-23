package kr.co.lnis.server.mapper;

import kr.co.lnis.common.model.LnisModels.SessionSnapshot;
import kr.co.lnis.server.entity.RedisEntities.TestSessionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface SessionMapper {
    @Mapping(target = "txResult", ignore = true)
    @Mapping(target = "rxResult", ignore = true)
    SessionSnapshot toSnapshot(TestSessionEntity entity);
}


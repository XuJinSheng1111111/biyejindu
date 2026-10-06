package mapper;

import org.apache.ibatis.annotations.Param;

public interface StudyPlanCompetitionMapper {

    int insertIgnore(
            @Param("accountId") Integer accountId,
            @Param("competitionId") Long competitionId
    );

    int delete(
            @Param("accountId") Integer accountId,
            @Param("competitionId") Long competitionId
    );
}
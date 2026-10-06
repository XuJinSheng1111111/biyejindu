package mapper;

import org.apache.ibatis.annotations.Param;
import pojo.StudyPlanTask;

import java.util.List;

public interface StudyPlanTaskMapper {

    List<StudyPlanTask> selectByAccountId(@Param("accountId") Integer accountId);

    StudyPlanTask selectByIdAndAccount(
            @Param("id") Long id,
            @Param("accountId") Integer accountId
    );

    StudyPlanTask selectBySource(
            @Param("accountId") Integer accountId,
            @Param("sourceType") String sourceType,
            @Param("sourceId") Long sourceId
    );

    int insert(StudyPlanTask task);

    int update(StudyPlanTask task);

    int updateDone(
            @Param("id") Long id,
            @Param("accountId") Integer accountId,
            @Param("done") Boolean done
    );

    int delete(
            @Param("id") Long id,
            @Param("accountId") Integer accountId
    );
}
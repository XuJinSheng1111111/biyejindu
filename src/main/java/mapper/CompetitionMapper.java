package mapper;

import org.apache.ibatis.annotations.Param;
import pojo.Competition;

import java.util.List;

public interface CompetitionMapper {

    List<Competition> selectPage(
            @Param("keyword") String keyword,
            @Param("compLevel") String compLevel,
            @Param("compStatus") String compStatus,
            @Param("year") String year,
            @Param("month") String month,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    long countPage(
            @Param("keyword") String keyword,
            @Param("compLevel") String compLevel,
            @Param("compStatus") String compStatus,
            @Param("year") String year,
            @Param("month") String month
    );

    List<Competition> selectRecommendCandidates();

    Competition selectById(@Param("id") Long id);
}
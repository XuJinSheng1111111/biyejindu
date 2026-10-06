package mapper;

import org.apache.ibatis.annotations.Param;
import pojo.AiSummary;

import java.util.List;

public interface AiSummaryMapper {

    List<AiSummary> selectAllByStudentInfoId(@Param("studentInfoId") Integer studentInfoId);

    int deleteByStudentInfoId(@Param("studentInfoId") Integer studentInfoId);

    /**
     * 单学期总结或全学期总结统一写入。
     * 唯一键冲突时覆盖旧文本。
     */
    int upsert(AiSummary summary);

    /**
     * 按学生、学年、学期和总结类型查询。
     * 全学期总结约定使用 schoolYear=ALL、term=ALL、summaryType=all。
     */
    AiSummary selectOne(
            @Param("studentInfoId") Integer studentInfoId,
            @Param("schoolYear") String schoolYear,
            @Param("term") String term,
            @Param("summaryType") String summaryType
    );
}
package mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import pojo.StudentScore;

import java.util.List;

public interface StudentScoreMapper {

    List<StudentScore> selectByCondition(
            @Param("startYear") String startYear,
            @Param("endYear") String endYear,
            @Param("term") String term,
            @Param("courseName") String courseName,
            @Param("studentInfoId") int studentInfoId
    );

    int batchInsert(@Param("scoreList") List<StudentScore> scoreList);

    List<StudentScore> selectAllByStudentInfoId(@Param("studentInfoId") int studentInfoId);

    List<StudentScore> selectTermListByStudentInfoId(@Param("studentInfoId") int studentInfoId);

    List<StudentScore> selectByStudentAndTerm(
            @Param("schoolYear") String schoolYear,
            @Param("term") String term,
            @Param("studentInfoId") int studentInfoId
    );

    int deleteByStudentInfoId(@Param("studentInfoId") int studentInfoId);

    @Select("select * from student_score where course_no = #{courseNo} and student_info_id = #{studentInfoId} ")
    StudentScore selectBycourseNo(
            @Param("courseNo") String courseNo,
            @Param("studentInfoId") int studentInfoId
    );

}

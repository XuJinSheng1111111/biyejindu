package mapper;

import org.apache.ibatis.annotations.Param;
import pojo.File;

import java.util.List;

public interface FileHistoryMapper {

    int insert(File file);

    List<File> selectByStudentInfoId(@Param("studentInfoId") int studentInfoId);

    int deleteByStudentInfoId(@Param("studentInfoId") int studentInfoId);
}

package mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import pojo.StudentInfo;

public interface StudentInfoMapper {

    /**
     * 注册用户时初始化空白学生档案
     */
    @Insert("insert into student_info (account_id) values (#{accountId})")
    int insertEmpty(@Param("accountId") int accountId);/**
     * 根据account_id查询用户信息
     */
    @Select("select s.*, u.email from student_info s left join account u on u.id = s.account_id where s.account_id = #{accountId}")
    StudentInfo selectById(int accountId);

    /**
     * 更新用户信息
     */
    int update(StudentInfo studentInfo);

    /**
     * 根据id返回头像路径
     */
    @Select("select avatar_url from student_info where account_id = #{accountId}")
    String selectAvatarById(int accountId);

    /**
     * 根据id和头像路径 更新用户头像
     */
    int updateAvatarURLById(
            @Param("accountId") int accountId,
            @Param("avatarUrl") String avatarUrl
    );

    /**
     * 根据id返回背景图片路径
     */
    @Select("select cover_url from student_info where account_id = #{accountId}")
    String selectCoverById(int accountId);

    /**
     * 根据id和背景图片路径 更新用户背景
     */
    @Update("update student_info set cover_url = #{coverUrl} where account_id = #{accountId}")
    int updateCoverURLById(
            @Param("accountId") int accountId,
            @Param("coverUrl") String coverUrl
    );

}

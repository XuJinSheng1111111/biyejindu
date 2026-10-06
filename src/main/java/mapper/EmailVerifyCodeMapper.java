package mapper;

import org.apache.ibatis.annotations.Param;
import pojo.EmailVerifyCode;

public interface EmailVerifyCodeMapper {
    int insert(EmailVerifyCode emailVerifyCode);

    EmailVerifyCode selectLatestByEmail(@Param("email") String email);

    int countTodayByEmail(@Param("email") String email);

    int markUsed(@Param("id") Long id);

    int disableOldCode(@Param("email") String email);
}
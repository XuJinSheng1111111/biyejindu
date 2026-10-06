package mapper;

import org.apache.ibatis.annotations.Param;
import pojo.Account;

public interface AccountMapper {

    int insert(Account account);

    Account selectById(@Param("id") Integer id);

    Account selectByUsername(@Param("username") String username);

    Account selectByEmail(@Param("email") String email);

    int updatePwdById(Account account);

    int updateUsernameById(
            @Param("id") int id,
            @Param("username") String username
    );

    int incrementLoginFailure(@Param("id") Integer id);

    int resetLoginSecurity(@Param("id") Integer id);

    int deleteById(@Param("id") Integer id);
}
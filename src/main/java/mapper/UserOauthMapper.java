package mapper;

import org.apache.ibatis.annotations.Param;
import pojo.UserOauth;

public interface UserOauthMapper {
    UserOauth selectByPlatformAndOpenid(
            @Param("platform") String platform,
            @Param("openid") String openid
    );

    UserOauth selectByUserAndPlatform(
            @Param("userId") Integer userId,
            @Param("platform") String platform
    );

    int insert(UserOauth userOauth);
}
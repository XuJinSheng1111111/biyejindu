package mapper;

import org.apache.ibatis.annotations.Param;
import pojo.UserSettings;

public interface UserSettingsMapper {

    UserSettings selectByAccountId(@Param("accountId") Integer accountId);

    int insertIfAbsent(UserSettings settings);

    int update(UserSettings settings);

    int updateGuideCompleted(
            @Param("accountId") Integer accountId,
            @Param("guideCompleted") Boolean guideCompleted
    );
}
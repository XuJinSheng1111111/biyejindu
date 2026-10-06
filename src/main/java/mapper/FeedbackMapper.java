package mapper;

import org.apache.ibatis.annotations.Param;
import pojo.Feedback;

import java.util.List;

public interface FeedbackMapper {

    int insert(Feedback feedback);

    List<Feedback> selectByAccountId(@Param("accountId") Integer accountId);
}
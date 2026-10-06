package pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserOauth {
    private Long id;
    private Integer userId;
    private String platform;
    private String openid;
    private String nickname;
    private String avatar;
    private String email;
    private String bindTime;
}
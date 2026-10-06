package pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerifyCode {
    private Long id;
    private String email;
    private String code;
    private String expireTime;
    private Integer used;
    private String createTime;
}
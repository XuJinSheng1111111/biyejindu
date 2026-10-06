package pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    private Integer id;
    private String username;
    private String password;
    private String email;
    private String createTime;
    private Short status;
    private Integer loginFailCount;
    private Short sliderRequired;
}
package pojo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class StudentInfo {
    private Integer id;
    private Integer accountId;
    private String email;
    private String  stuId;
//    @JsonProperty("nickname")
    private String realName;
    private String gender;
    private String birthday;
    private String className;
    private String major;
    private String phone;
    private String enrollYear;
    private String schoolName;
    private String avatarUrl;
    private String signature;
    private String coverUrl;
}

package dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileDto {
    private Integer id;
    private String username;
    private String stuId;
    private String realName;
    private String gender;
    private String birthday;
    private String phone;
    private String email;
    private String major;
    private String className;
    private String enrollYear;
    private String signature;
}

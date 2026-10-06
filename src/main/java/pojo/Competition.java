package pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Competition {
    private Long id;
    private String compName;
    private String organizer;
    private String compLevel;
    private String compStatus;
    private String compTime;
    private String registerStart;
    private String registerEnd;
    private String officialUrl;
    private String compDesc;
    private String compRequirement;
    private String tags;
    private Integer status;
}
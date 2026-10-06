package pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StudyPlanTask {
    private Long id;
    private Integer accountId;
    private String title;
    private String content;
    private String category;
    private String priority;
    private String deadline;
    private Boolean done;
    private String sourceType;
    private Long sourceId;
    private String createTime;
    private String updateTime;
}
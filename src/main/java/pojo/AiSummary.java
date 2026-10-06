package pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiSummary {
    private Integer id;
    private Integer studentInfoId;
    private String schoolYear;
    private String term;
    private String summaryText;
    private String summaryType;
    private String createTime;
    private String updateTime;
}
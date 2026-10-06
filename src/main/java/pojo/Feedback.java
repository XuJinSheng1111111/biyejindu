package pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Feedback {
    private Long id;
    private Integer accountId;
    private String type;
    private String content;
    private String contact;
    private Integer status;
    private String reply;
    private String createTime;
    private String replyTime;
}
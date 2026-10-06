package pojo;

import lombok.Data;

/**
 * 用户个性化显示设置，与 account 一对一关联。
 */
@Data
public class UserSettings {
    private Long id;
    private Integer accountId;
    private String theme;
    private String themeColor;
    private String fontSize;
    private Boolean sidebarCollapsed;
    private Boolean guideCompleted;
    private String createTime;
    private String updateTime;
}

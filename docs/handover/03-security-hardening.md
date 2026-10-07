# 任务 3：安全审阅与加固交接

> 历史说明：本文记录 1.0 原学生系统的安全加固，文中的 8 项测试是当时快照，不是当前“毕业进度小管家”基线。当前基线与交付边界以根目录 `README.md`、`AGENTS.md` 和生产安全检查表为准。

## 1. 交付范围

- 分支：`security/task-3-hardening`
- 目标：审阅现有学生成长管理系统的安全风险，修复可直接落地的问题，并补充自动化测试。
- 本任务不改变成绩单固定字段业务规则，也不实现后台可视化管理；这两部分属于后续任务 4。

## 2. 已修复问题

### 账号与会话

- 登录失败按邮箱和来源地址限流，连续失败后要求滑块验证。
- 对不存在账号执行等时密码校验，降低账号枚举和时序侧信道风险。
- 登录和 Gitee 绑定均拒绝停用账号。
- 受保护请求会重新读取数据库账号状态，后台停用或删除账号后旧会话立即失效。
- Gitee 绑定失败会计入账号失败次数，并增加独立限流。
- 取消永久登录，只保留 7 天和 30 天有效期。
- 登录后更换会话标识，减少会话固定风险。

### 请求与浏览器防护

- 登录态和待绑定 OAuth 会话启用 CSRF（跨站请求伪造）令牌校验。
- 登录过滤器改为精确公开路由，避免整个账号接口目录被匿名放行。
- 增加内容类型、框架嵌入、来源策略、权限策略、HSTS 和基础 CSP 响应头。
- 滑块挑战不再向客户端返回答案；令牌使用安全随机数、恒定时间比较且验证后失效。
- 滑块和邮箱验证码接口增加来源地址限流。

### 文件与外部输入

- 成绩文件仅接收真实 `xls`/`xlsx`，限制 5MB、2 万行，并启用 Apache POI 压缩炸弹保护。
- `xlsx` 额外限制 ZIP 条目数、单条目解压量和总解压量，超限请求返回明确错误。
- 拒绝 Excel 公式单元格，避免解析公式带来的资源消耗与内容执行风险。
- 图片上传校验真实格式、扩展名、尺寸、帧数和像素总量；限制 5MB。
- 动画 GIF 逐帧校验宽高并累计实际像素数，避免利用小首帧绕过限制。
- 图片保存和删除使用规范化路径并限制在指定上传目录内。
- 数据库更新失败时删除新文件；更新成功后才删除旧文件，避免数据与文件状态不一致。
- 天气参数增加格式、长度和数值边界校验；上游请求参数编码、响应大小限制、错误信息不再回传内部细节。
- 竞赛官网链接只允许带主机名的绝对 `http`/`https` 地址。

### 构建与配置

- 移除固定旧版 Tomcat 的第三方 Maven 插件，改为外部 Tomcat 部署。
- README 要求 Tomcat 9.0.122 或更新的安全修复版本。
- 示例 MySQL 连接启用服务端身份校验，关闭公钥自动获取。
- 运行时不再回退到明文数据库连接；缺少数据库地址、账号或密码时拒绝初始化。
- 根日志级别由 `DEBUG` 调整为 `INFO`。
- 固定 Maven Surefire 测试插件版本为 3.6.0。

## 3. 验证结果

- 环境：Eclipse Temurin JDK 21.0.12.1、Apache Maven 3.9.16。
- 命令：`mvn -B clean package`
- 自动化测试：8 项通过，0 失败，0 错误，0 跳过。
- 打包：成功生成 `target/student_system.war`。
- 依赖审查：解析 43 个 Maven 依赖，并通过 OSV 漏洞数据库批量查询；本次查询命中 0 项已知漏洞。
- 代码检查：`git diff --check` 通过。
- 独立审阅：另一位队友发现的限流表容量、停用账号旧会话、GIF 后续帧、Excel 总解压量、上传超限状态码及数据库明文默认值均已修复；最终复审未发现阻断项，同意提交。

> OSV 零命中不等于系统不存在漏洞；它只表示本次依赖版本查询未匹配到 OSV 已公开记录。

## 4. 新增测试

- `SecurityUtilTest`：安全令牌、恒定时间比较、外部链接过滤。
- `RequestRateLimiterTest`：失败计数、封禁、重置，以及容量饱和时失效关闭且不淘汰有效记录。
- `SliderCaptchaServiceTest`：错误值拒绝、正确值通过、单次使用。
- `UploadServiceTest`：上传目录规范化与目录穿越拒绝。

## 5. 尚需部署环境验证

- 当前没有生产 MySQL、邮件、Gitee OAuth 密钥和 Tomcat 环境，因此未执行真实登录、邮件发送、OAuth 回调、数据库写入及浏览器端到端测试。
- 当前限流器是单节点内存实现；多实例部署应迁移到 Redis 等共享存储。
- 当前滑块是轻量交互校验，不是专业行为验证码；公网高风险环境应接入成熟验证码服务。
- 项目仍使用 `javax.servlet`；升级到 Tomcat 10/11 前必须完成 Jakarta 命名空间迁移。
- Gitee 用户信息接口仍沿用原项目的访问令牌传参方式；部署日志和反向代理必须禁止记录查询参数。

## 6. 部署要求

1. 使用 Tomcat 9.0.122 或更新的 9.0.x 安全修复版本。
2. 使用 HTTPS，并确认反向代理正确传递安全连接状态，否则 HSTS 与 Secure Cookie 不会生效。
3. 配置真实数据库证书校验参数，不得直接照抄示例密码。
4. 部署后执行登录失败、停用账号、OAuth 绑定、CSRF、图片上传、成绩导入、天气查询的端到端回归。
5. 监控 401、403、413、428、429 与 5xx 响应，按实际流量调整限流阈值。

## 7. 审阅重点

- 检查现有前端 Axios 是否在所有登录态写请求中自动携带 `X-XSRF-TOKEN`。
- 检查反向代理下来源地址是否可信，避免所有用户被识别为同一地址或伪造地址。
- 检查真实 Tomcat 写入上传目录的权限、持久化位置与备份策略。
- 检查 Gitee OAuth 回调地址、`state` 校验和日志脱敏。

## 8. 依据

- OWASP CSRF Prevention Cheat Sheet：<https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html>
- OWASP File Upload Cheat Sheet：<https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html>
- OWASP Input Validation Cheat Sheet：<https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html>
- Apache Tomcat 9 安全公告：<https://tomcat.apache.org/security-9>
- Apache POI 安全公告：<https://poi.apache.org/security.html>

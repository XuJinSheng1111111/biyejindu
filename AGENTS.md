# 项目协作说明

## 输出与沟通

- 全程使用规范简体中文；首次出现的标准缩写应说明中文含义。
- 不把计划、已实现、已测试、已部署、已视觉验收混为一谈。
- 没有证据的运行状态写“待确认”或“未验证”，不得补造结果。
- 用户明确禁止控制电脑；不得使用图形界面或浏览器自动化。真实手机和浏览器视觉验收由用户执行。

## 项目目标

“毕业进度小管家”接收人才培养方案和个人成绩文件，在服务器本地解析为可编辑的板块与课程数据，计算毕业学分进度。结果仅供个人核对，最终以学校审核为准。

## 代码与文档入口

- 前端：`src/main/webapp/index.html`、`src/main/webapp/credit-admin.html`、`src/main/webapp/static/js/credit-audit.js`、`src/main/webapp/static/css/credit-audit.css`
- 后端：`src/main/java/servlet/CreditAuditServlet.java`、`src/main/java/service/CreditAuditService.java`、`src/main/java/service/DeepSeekVerificationService.java`、`src/main/java/service/DeepSeekConfigService.java`
- 部署：`deploy/install-biyejindu.sh`、`deploy/update-biyejindu.sh`、`deploy/rollback-biyejindu.sh`
- 安全清单：`docs/安全/生产安全与部署检查表.md`
- 复现入口：`项目学习与复现全指南.md`
- 项目复盘：`项目全维度复盘与能力提升手册.md`
- 项目经历：`毕业进度小管家_网站项目经历.md`

## 不可破坏的边界

- 不提交真实成绩、培养方案原件、数据库密码、后台密钥、OAuth（开放授权）密钥、日志、备份、WAR 和发布 ZIP。
- 只允许接入 DeepSeek 官方接口；不得新增其他第三方人工智能服务，不得发送成绩原文件、培养方案原文件或姓名、学号、联系方式等身份信息。
- 不降低 8 MiB 单文件、17 MiB 请求、压缩展开量、表格规模、并发、超时和限流边界，除非同时给出容量测算与回归测试。
- 不修改或重启简历网站 `xjs-portfolio.service`，不占用 `127.0.0.1:4174`。
- 新站保持独立账号、目录、数据、服务、端口 `127.0.0.1:4180` 和 Nginx 配置。
- 不把构建成功当作生产上线，不把响应式源码当作真机验收。

## 关键设计决策

1. 个人成绩原文件只在单次请求中处理，不持久化；培养方案只保存结构化规则和 SHA-256 摘要。
2. 文件解析进入固定 2 线程、12 队列的有界执行器，单任务最长 110 秒。
3. ZIP/Office 同时限制压缩比、条目数、单条目和累计展开体积；表格和文本另有限额。
4. DeepSeek 仅接收脱敏片段与结构化学业字段；主请求 40 秒，瞬时失败时最多执行一次 55 秒恢复请求，总任务仍受 110 秒上限约束。
5. 毕业最低 165 学分由培养方案内五个计分板块动态解析；素质课程是独立完成条件，不混入 165 学分。
6. 轻量服务器不使用双 Java 蓝绿实例，避免 1.6 GiB 内存下的额外竞争；采用单实例原子替换、健康检查和失败回滚。
7. 更新脚本只重启 `biyejindu.service`，不重载 Nginx、不升级 Node.js/Java/Tomcat、不触碰简历站文件。

## 复现与验证

```bash
mvn clean test
mvn clean package
```

当前证据基线：33 项 Maven 测试、8 项前端计算回归通过；`target/student_system.war` 构建成功。本地 Tomcat 前台、后台与真实成绩 ZIP 接口已返回 HTTP 200，DeepSeek 核对已完成。任何功能代码变化都应重新运行测试和构建。文档变化至少执行链接检查和 `git diff --check`。

## 当前交付边界

- 已验证：源码检查、33 项后端测试、8 项前端计算回归、WAR 构建、本地 HTTP 与真实成绩 ZIP 接口、发布包摘要。
- 已实现但待线上验证：服务资源上限、独立端口、Nginx 限流、HTTPS、原子更新与自动回滚。
- 待用户验收：真实手机的视觉、触控、上传、编辑和结果流程。

## 下一步优先级

1. 在服务器执行首次安装并保存后台密钥，验证两个 systemd 服务与两个本机端口。
2. 验证 HTTPS、恶意上传拒绝、内存/Swap/临时目录/日志增长和回滚路径。
3. 由用户在真实手机完成首页、上传、编辑、重算和结果区视觉验收。

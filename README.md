# 毕业进度小管家

面向学生的毕业学分自查网站。用户上传人才培养方案和个人成绩文件后，系统在服务器本地解析课程、学分与板块归属，允许人工修正，再实时计算毕业学分进度。

> 自查结果仅供个人核对，最终结果以学校审核为准。

## 核心能力

- 单页完成学校信息、培养方案、成绩文件、人工复核和学分结果。
- 支持 PDF、Word、Excel、HTML、CSV、TXT，成绩网页可连同资源目录压缩为 ZIP 上传。
- 成绩课程与培养方案板块均可在首次解析后修改并重新计算。
- 解析和计算均在本服务器本地完成，不调用第三方人工智能接口。
- 个人成绩原文件仅在单次请求中处理，不写入数据库或公开目录。
- 后台可维护培养方案库、查看匿名运营数据和处理反馈。
- 针对手机、平板和桌面浏览器提供响应式布局。

## 技术栈

- 后端：Java 21、Servlet 4、MyBatis、Maven WAR
- 运行容器：Apache Tomcat 9
- 前端：原生 HTML、CSS、JavaScript
- 文档解析：Apache POI、Apache PDFBox
- 生产入口：Nginx 反向代理与 HTTPS

## 目录

```text
student_system-master/
├─ src/                  应用源码与自动化测试
├─ deploy/               安装、回滚和服务器部署说明
├─ docs/                 架构、安全、质量与交接文档
├─ 源文件/               本地私有回归样例，不进入公开仓库
├─ target/               Maven 构建产物，不进入仓库
├─ pom.xml               Maven 项目配置
├─ db.example.properties 数据库配置示例
├─ CHANGELOG.md          变更记录
└─ README.md             项目入口说明
```

完整文档导航见 [`docs/README.md`](docs/README.md)。

## 本地构建

要求 Java 21 和 Maven 3.9 或更高版本：

```bash
mvn clean test
mvn clean package
```

构建产物位于 `target/student_system.war`，部署目标为 Tomcat 9。

## 配置原则

- 真实数据库、邮箱、OAuth（开放授权）等凭据只通过服务器环境或未跟踪的本地配置提供。
- 仓库只保留 `db.example.properties` 这类无密钥模板。
- Maven 构建明确排除 `src/main/resources` 下的真实 `.properties`，防止本地配置被打进 WAR。
- `源文件/`、日志、截图、备份、构建物和发布压缩包均被 `.gitignore` 排除。
- 公开站点只暴露毕业学分自查所需路由；旧学生系统页面不通过 Nginx 对外开放。

## 生产部署

生产域名：`biyejindu.jinshengxu.com.cn`

请按 [`deploy/服务器部署说明.md`](deploy/服务器部署说明.md) 操作。首次安装脚本会把新站点作为独立系统服务运行在 `127.0.0.1:4180`；后续使用 `deploy/update-biyejindu.sh` 原子更新，仅重启毕业进度服务，不修改现有简历网站的 `127.0.0.1:4174` 服务。

## 安全边界

- 单个文件最大 8 MB，请求总量在应用、Tomcat 和 Nginx 三层限制。
- ZIP 和 Office 压缩格式设置展开体积、压缩比、条目数量和表格规模上限。
- 解析任务使用有界线程池、排队上限、超时和频率限制。
- 上传文件进行扩展名、签名、结构和内容联合校验。
- 管理接口使用独立强密钥，并进行同源请求与失败限速校验。
- 不支持成绩截图识别，避免将个人成绩图片发送给第三方服务。

详细实现见 [`docs/安全/生产安全与部署检查表.md`](docs/安全/生产安全与部署检查表.md)。

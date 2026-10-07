#!/usr/bin/env bash
set -Eeuo pipefail

# 毕业进度小管家生产部署脚本。
# 目标：不修改现有简历网站，只新增独立的 Java/Tomcat 服务与 Nginx 虚拟主机。

APP_NAME="biyejindu"
DOMAIN="biyejindu.jinshengxu.com.cn"
APP_PORT="4180"
TOMCAT_VERSION="9.0.122"
PACKAGE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WAR_SOURCE="${PACKAGE_DIR}/student_system.war"
STAMP="$(date +%Y%m%d-%H%M%S)"

APP_HOME="/opt/${APP_NAME}"
RUNTIME_DIR="${APP_HOME}/runtime"
TOMCAT_HOME="${RUNTIME_DIR}/apache-tomcat-${TOMCAT_VERSION}"
TOMCAT_LINK="${APP_HOME}/tomcat"
CATALINA_BASE="/var/lib/${APP_NAME}/tomcat"
DATA_DIR="/var/lib/${APP_NAME}/data"
CONFIG_DIR="/etc/${APP_NAME}"
ENV_FILE="${CONFIG_DIR}/${APP_NAME}.env"
SERVICE_FILE="/etc/systemd/system/${APP_NAME}.service"
NGINX_FILE="/etc/nginx/conf.d/${APP_NAME}.conf"
NGINX_ZONE_FILE="/etc/nginx/conf.d/02-${APP_NAME}-zones.conf"
BACKUP_DIR="/var/backups/${APP_NAME}/${STAMP}"
LE_WEBROOT="/var/www/letsencrypt"

log() { printf '\n[%s] %s\n' "$(date '+%F %T')" "$*"; }
die() { printf '\n部署终止：%s\n' "$*" >&2; exit 1; }

if [[ "$(id -u)" -ne 0 ]]; then
  die "请使用 sudo bash install-biyejindu.sh 运行。"
fi

[[ -f "${WAR_SOURCE}" ]] || die "未找到 ${WAR_SOURCE}，请完整解压部署包后再运行。"
command -v nginx >/dev/null 2>&1 || die "未检测到 Nginx，服务器环境与审计报告不一致。"
command -v certbot >/dev/null 2>&1 || die "未检测到 Certbot，服务器环境与审计报告不一致。"

log "检查现有简历网站，部署过程中不会修改它"
systemctl is-active --quiet xjs-portfolio.service || die "现有简历网站服务未运行，请先检查 xjs-portfolio.service。"
ss -lnt 2>/dev/null | grep -q '127.0.0.1:4174' || die "现有简历网站未监听 127.0.0.1:4174。"
nginx -t

log "创建可回滚备份"
install -d -m 0700 "${BACKUP_DIR}"
for file in "${SERVICE_FILE}" "${NGINX_FILE}" "${NGINX_ZONE_FILE}" "${ENV_FILE}" \
            "${CATALINA_BASE}/conf/server.xml" "${CATALINA_BASE}/webapps/ROOT.war"; do
  if [[ -e "${file}" ]]; then
    target="${BACKUP_DIR}${file}"
    install -d -m 0700 "$(dirname "${target}")"
    cp -a "${file}" "${target}"
  fi
done

log "安装 Java 21 与基础工具"
dnf install -y curl tar gzip openssl java-21-openjdk-headless || \
  dnf install -y curl tar gzip openssl java-21-openjdk

JAVA_BIN="$(readlink -f "$(command -v java)")"
JAVA_HOME="$(dirname "$(dirname "${JAVA_BIN}")")"
JAVA_MAJOR="$(java -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')"
[[ "${JAVA_MAJOR}" == "21" ]] || die "需要 Java 21，当前检测到 Java ${JAVA_MAJOR:-未知}。"

log "确保轻量服务器具备 2 GiB 交换空间，降低突发内存不足风险"
if ! swapon --show --noheadings | grep -q .; then
  if [[ ! -e /swapfile ]]; then
    fallocate -l 2G /swapfile || dd if=/dev/zero of=/swapfile bs=1M count=2048 status=progress
    chmod 0600 /swapfile
    mkswap /swapfile
  elif [[ "$(blkid -p -s TYPE -o value /swapfile 2>/dev/null || true)" != "swap" ]]; then
    die "/swapfile 已存在但不是交换文件，为避免覆盖未知数据，部署已停止。"
  fi
  chmod 0600 /swapfile
  swapon /swapfile
  grep -qE '^/swapfile[[:space:]]' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
cat > /etc/sysctl.d/90-${APP_NAME}.conf <<'SYSCTL'
vm.swappiness=10
SYSCTL
sysctl --system >/dev/null

log "下载并校验 Apache Tomcat ${TOMCAT_VERSION}"
install -d -m 0755 "${RUNTIME_DIR}"
if [[ ! -x "${TOMCAT_HOME}/bin/catalina.sh" ]]; then
  temp_dir="$(mktemp -d)"
  trap 'rm -rf "${temp_dir:-}"' EXIT
  archive="apache-tomcat-${TOMCAT_VERSION}.tar.gz"
  base_url="https://dlcdn.apache.org/tomcat/tomcat-9/v${TOMCAT_VERSION}/bin"
  curl --fail --location --proto '=https' --tlsv1.2 \
    "${base_url}/${archive}" -o "${temp_dir}/${archive}"
  curl --fail --location --proto '=https' --tlsv1.2 \
    "${base_url}/${archive}.sha512" -o "${temp_dir}/${archive}.sha512"
  (cd "${temp_dir}" && sha512sum -c "${archive}.sha512")
  tar -xzf "${temp_dir}/${archive}" -C "${RUNTIME_DIR}"
fi
ln -sfn "${TOMCAT_HOME}" "${TOMCAT_LINK}"

log "创建最小权限运行账号和持久化目录"
if ! id "${APP_NAME}" >/dev/null 2>&1; then
  useradd --system --home-dir "/var/lib/${APP_NAME}" --shell /sbin/nologin "${APP_NAME}"
fi

# Tomcat runtime 由 root 管理，但运行用户必须能够遍历目录、读取 JAR 并执行 shell 脚本。
# 不使用 0777；只授予 biyejindu 组运行所需的最小权限。
chown -R root:"${APP_NAME}" "${TOMCAT_HOME}"
find "${TOMCAT_HOME}" -type d -exec chmod 0750 {} +
find "${TOMCAT_HOME}" -type f -exec chmod 0640 {} +
find "${TOMCAT_HOME}/bin" -type f -name '*.sh' -exec chmod 0750 {} +

install -d -o root -g "${APP_NAME}" -m 0750 "${CONFIG_DIR}"
install -d -o "${APP_NAME}" -g "${APP_NAME}" -m 0750 \
  "${DATA_DIR}" "${CATALINA_BASE}/logs" "${CATALINA_BASE}/temp" "${CATALINA_BASE}/work"
install -d -o root -g "${APP_NAME}" -m 0750 "${CATALINA_BASE}/conf" "${CATALINA_BASE}/webapps"

cp -a "${TOMCAT_HOME}/conf/." "${CATALINA_BASE}/conf/"
find "${CATALINA_BASE}/conf" -type d -exec chmod 0750 {} +
find "${CATALINA_BASE}/conf" -type f -exec chmod 0640 {} +
chown -R root:"${APP_NAME}" "${CATALINA_BASE}/conf"

# HostConfig 启动时需要该目录存在；只开放这一处运行时写权限。
install -d -o root -g "${APP_NAME}" -m 0750 "${CATALINA_BASE}/conf/Catalina"
install -d -o "${APP_NAME}" -g "${APP_NAME}" -m 0750 "${CATALINA_BASE}/conf/Catalina/localhost"

cat > "${CATALINA_BASE}/conf/server.xml" <<'SERVERXML'
<?xml version="1.0" encoding="UTF-8"?>
<Server port="-1" shutdown="SHUTDOWN">
  <Listener className="org.apache.catalina.startup.VersionLoggerListener" />
  <Listener className="org.apache.catalina.core.JreMemoryLeakPreventionListener" />
  <Listener className="org.apache.catalina.mbeans.GlobalResourcesLifecycleListener" />
  <Listener className="org.apache.catalina.core.ThreadLocalLeakPreventionListener" />
  <Service name="Catalina">
    <Connector address="127.0.0.1" port="4180" protocol="HTTP/1.1"
               connectionTimeout="10000" keepAliveTimeout="10000"
               maxThreads="32" minSpareThreads="4" acceptCount="20" maxConnections="64"
               maxPostSize="18874368" maxSavePostSize="4096" maxSwallowSize="18874368"
               maxParameterCount="40" maxPartCount="8"
               allowTrace="false" xpoweredBy="false" server="web" />
    <Engine name="Catalina" defaultHost="localhost">
      <Host name="localhost" appBase="webapps" unpackWARs="false"
            autoDeploy="false" deployOnStartup="true" deployXML="false">
        <Valve className="org.apache.catalina.valves.RemoteIpValve"
               internalProxies="127\.0\.0\.1|::1"
               remoteIpHeader="x-forwarded-for" protocolHeader="x-forwarded-proto" />
        <Valve className="org.apache.catalina.valves.ErrorReportValve"
               showReport="false" showServerInfo="false" />
        <Valve className="org.apache.catalina.valves.AccessLogValve"
               directory="logs" prefix="access" suffix=".log" maxDays="14"
               pattern="%a %t &quot;%r&quot; %s %b %D" />
      </Host>
    </Engine>
  </Service>
</Server>
SERVERXML
chmod 0640 "${CATALINA_BASE}/conf/server.xml"
chown root:"${APP_NAME}" "${CATALINA_BASE}/conf/server.xml"

log "配置运行密钥与 Java 内存边界"
if [[ ! -f "${ENV_FILE}" ]]; then
  admin_key="$(openssl rand -hex 32)"
  settings_key="$(openssl rand -base64 48 | tr -d '\n')"
  cat > "${ENV_FILE}" <<ENVFILE
JAVA_HOME=${JAVA_HOME}
CREDIT_AUDIT_ADMIN_KEY=${admin_key}
CREDIT_AUDIT_DATA_DIR=${DATA_DIR}
CREDIT_AUDIT_SETTINGS_KEY=${settings_key}
DEEPSEEK_API_KEY=
JAVA_OPTS="-Xms128m -Xmx512m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true -Dfile.encoding=UTF-8 -Djava.io.tmpdir=${CATALINA_BASE}/temp"
ENVFILE
fi
if ! grep -q '^CREDIT_AUDIT_SETTINGS_KEY=' "${ENV_FILE}"; then
  settings_key="$(openssl rand -base64 48 | tr -d '\n')"
  printf '\nCREDIT_AUDIT_SETTINGS_KEY=%s\n' "${settings_key}" >> "${ENV_FILE}"
fi
if ! grep -q '^DEEPSEEK_API_KEY=' "${ENV_FILE}"; then
  printf 'DEEPSEEK_API_KEY=\n' >> "${ENV_FILE}"
fi
chmod 0640 "${ENV_FILE}"
chown root:"${APP_NAME}" "${ENV_FILE}"

log "部署只读应用包"
install -o root -g "${APP_NAME}" -m 0640 "${WAR_SOURCE}" "${CATALINA_BASE}/webapps/ROOT.war"

cat > "${SERVICE_FILE}" <<SERVICE
[Unit]
Description=毕业进度小管家
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${APP_NAME}
Group=${APP_NAME}
UMask=0027
Environment=CATALINA_HOME=${TOMCAT_LINK}
Environment=CATALINA_BASE=${CATALINA_BASE}
EnvironmentFile=${ENV_FILE}
WorkingDirectory=${CATALINA_BASE}
ExecStart=${TOMCAT_LINK}/bin/catalina.sh run
Restart=on-failure
RestartSec=5s
TimeoutStartSec=90s
TimeoutStopSec=30s
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true
ReadWritePaths=/var/lib/${APP_NAME}
MemoryMax=700M
MemorySwapMax=512M
TasksMax=96
LimitNOFILE=8192

[Install]
WantedBy=multi-user.target
SERVICE

systemctl daemon-reload
systemctl enable --now "${APP_NAME}.service"

log "等待应用在本机端口启动"
ready=0
for _ in $(seq 1 45); do
  if curl --fail --silent --show-error --max-time 3 "http://127.0.0.1:${APP_PORT}/" >/dev/null; then
    ready=1
    break
  fi
  sleep 2
done
if [[ "${ready}" -ne 1 ]]; then
  systemctl status "${APP_NAME}.service" --no-pager || true
  journalctl -u "${APP_NAME}.service" -n 80 --no-pager || true
  die "应用未能在 ${APP_PORT} 端口正常启动。备份位于 ${BACKUP_DIR}。"
fi

log "写入证书签发前的 HTTP 配置"
install -d -m 0755 "${LE_WEBROOT}/.well-known/acme-challenge"
cat > "${NGINX_ZONE_FILE}" <<'NGINXZONE'
limit_req_zone $binary_remote_addr zone=biyejindu_upload:10m rate=6r/m;
limit_req_zone $binary_remote_addr zone=biyejindu_api:10m rate=30r/m;
limit_conn_zone $binary_remote_addr zone=biyejindu_conn:10m;
NGINXZONE

cat > "${NGINX_FILE}" <<NGINXHTTP
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN};

    location ^~ /.well-known/acme-challenge/ {
        root ${LE_WEBROOT};
        try_files \$uri =404;
    }

    location / {
        return 200 '域名已经到达毕业进度小管家服务器，正在配置 HTTPS。';
        add_header Content-Type 'text/plain; charset=utf-8';
    }
}
NGINXHTTP

nginx -t
systemctl reload nginx

log "检查域名解析并申请独立 HTTPS 证书"
getent ahostsv4 "${DOMAIN}" | grep -q . || die "域名尚未解析。请先添加 ${DOMAIN} 的 A 记录，再重新运行本脚本。"
certbot certonly --webroot -w "${LE_WEBROOT}" -d "${DOMAIN}" \
  --non-interactive --agree-tos --register-unsafely-without-email --keep-until-expiring \
  --deploy-hook "nginx -t >/dev/null 2>&1 && systemctl reload nginx"

log "写入最终 HTTPS 反向代理和公网路由白名单"
cat > "${NGINX_FILE}" <<NGINXFINAL
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN};

    location ^~ /.well-known/acme-challenge/ {
        root ${LE_WEBROOT};
        try_files \$uri =404;
    }

    location / { return 301 https://\$host\$request_uri; }
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    server_name ${DOMAIN};

    ssl_certificate /etc/letsencrypt/live/${DOMAIN}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${DOMAIN}/privkey.pem;
    # 不依赖 Certbot 某些安装方式才会生成的 options-ssl-nginx.conf。
    ssl_protocols TLSv1.2 TLSv1.3;

    access_log /var/log/nginx/${APP_NAME}-access.log;
    error_log /var/log/nginx/${APP_NAME}-error.log warn;

    client_max_body_size 18m;
    client_body_timeout 60s;
    client_header_timeout 15s;
    keepalive_timeout 30s;
    limit_conn biyejindu_conn 12;

    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;

    location = /api/credit-audit/parse {
        limit_req zone=biyejindu_upload burst=4 nodelay;
        proxy_request_buffering on;
        proxy_pass http://127.0.0.1:${APP_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 5s;
        proxy_read_timeout 120s;
        proxy_send_timeout 45s;
    }

    location ^~ /api/credit-audit/ {
        limit_req zone=biyejindu_api burst=20 nodelay;
        proxy_pass http://127.0.0.1:${APP_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_connect_timeout 5s;
        proxy_read_timeout 45s;
        proxy_send_timeout 30s;
    }

    location = / {
        proxy_pass http://127.0.0.1:${APP_PORT}/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }

    location = /index.html {
        proxy_pass http://127.0.0.1:${APP_PORT}/index.html;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }

    location = /credit-admin.html {
        proxy_pass http://127.0.0.1:${APP_PORT}/credit-admin.html;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        add_header Cache-Control "no-store" always;
    }

    location ^~ /static/ {
        proxy_pass http://127.0.0.1:${APP_PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        expires 1h;
    }

    location = /favicon.ico {
        proxy_pass http://127.0.0.1:${APP_PORT}/favicon.ico;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }

    location / { return 404; }
}
NGINXFINAL

nginx -t
systemctl reload nginx

log "执行部署后核验"
curl --fail --silent --show-error --max-time 10 "https://${DOMAIN}/" >/dev/null
curl --fail --silent --show-error --max-time 10 "https://${DOMAIN}/api/credit-audit/plans" >/dev/null
systemctl is-active --quiet xjs-portfolio.service || die "核验时发现简历网站服务异常。"
curl --fail --silent --show-error --max-time 5 "http://127.0.0.1:4174/" >/dev/null || \
  die "核验时发现简历网站本机端口异常。"

admin_key="$(sed -n 's/^CREDIT_AUDIT_ADMIN_KEY=//p' "${ENV_FILE}")"
printf '\n部署成功。\n'
printf '前端地址：https://%s/\n' "${DOMAIN}"
printf '后台地址：https://%s/credit-admin.html\n' "${DOMAIN}"
printf '后台初始密码：%s\n' "${admin_key}"
printf '回滚备份：%s\n' "${BACKUP_DIR}"
printf '请立即登录后台修改初始密码，并把新密码保存到密码管理器。\n'

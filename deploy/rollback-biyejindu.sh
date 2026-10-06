#!/usr/bin/env bash
set -Eeuo pipefail

# 只撤销毕业进度小管家的公网入口和服务，不修改简历网站、证书、Swap 或已保存业务数据。

APP_NAME="biyejindu"
STAMP="$(date +%Y%m%d-%H%M%S)"
SAVE_DIR="/var/backups/${APP_NAME}/manual-rollback-${STAMP}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "请使用 sudo bash rollback-biyejindu.sh 运行。" >&2
  exit 1
fi

install -d -m 0700 "${SAVE_DIR}"
for file in "/etc/nginx/conf.d/${APP_NAME}.conf" \
            "/etc/nginx/conf.d/02-${APP_NAME}-zones.conf" \
            "/etc/systemd/system/${APP_NAME}.service"; do
  if [[ -e "${file}" ]]; then
    cp -a "${file}" "${SAVE_DIR}/"
  fi
done

systemctl disable --now "${APP_NAME}.service" 2>/dev/null || true
rm -f "/etc/nginx/conf.d/${APP_NAME}.conf" "/etc/nginx/conf.d/02-${APP_NAME}-zones.conf"
nginx -t
systemctl reload nginx
systemctl daemon-reload

systemctl is-active --quiet xjs-portfolio.service || {
  echo "警告：简历网站服务当前不是运行状态，请单独检查。" >&2
  exit 1
}

echo "毕业进度小管家已停止并撤销公网入口。"
echo "业务数据仍保留在 /var/lib/${APP_NAME}/data。"
echo "撤销前配置副本位于 ${SAVE_DIR}。"


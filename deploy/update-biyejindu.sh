#!/usr/bin/env bash
set -Eeuo pipefail

# 仅更新毕业进度小管家，不修改简历网站、Nginx、证书或系统运行时。

APP_NAME="biyejindu"
APP_PORT="4180"
RESUME_SERVICE="xjs-portfolio.service"
RESUME_PORT="4174"
PACKAGE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WAR_SOURCE="${PACKAGE_DIR}/student_system.war"
WAR_TARGET="/var/lib/${APP_NAME}/tomcat/webapps/ROOT.war"
BACKUP_ROOT="/var/backups/${APP_NAME}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="${BACKUP_ROOT}/${STAMP}"
LOCK_FILE="/run/lock/${APP_NAME}-update.lock"

log() { printf '\n[%s] %s\n' "$(date '+%F %T')" "$*"; }
die() { printf '\n更新终止：%s\n' "$*" >&2; exit 1; }

if [[ "$(id -u)" -ne 0 ]]; then
  die "请使用 sudo bash update-biyejindu.sh 运行。"
fi

[[ -f "${WAR_SOURCE}" ]] || die "未找到 ${WAR_SOURCE}。"
[[ -f "${WAR_TARGET}" ]] || die "尚未完成首次部署，缺少 ${WAR_TARGET}。"
command -v jar >/dev/null 2>&1 || die "未找到 jar 工具，请确认 Java 21 已安装。"
command -v curl >/dev/null 2>&1 || die "未找到 curl。"

exec 9>"${LOCK_FILE}"
flock -n 9 || die "已有更新任务正在运行，请稍后再试。"

log "核验两个服务的隔离边界"
systemctl is-active --quiet "${RESUME_SERVICE}" || die "简历网站服务当前不健康，停止更新。"
ss -lnt 2>/dev/null | grep -q "127.0.0.1:${RESUME_PORT}" || die "简历网站未监听 ${RESUME_PORT}，停止更新。"
systemctl is-active --quiet "${APP_NAME}.service" || die "毕业进度服务当前不健康，请先排查。"
ss -lnt 2>/dev/null | grep -q "127.0.0.1:${APP_PORT}" || die "毕业进度服务未监听 ${APP_PORT}。"

log "检查新应用包结构"
jar tf "${WAR_SOURCE}" | grep -qx 'index.html' || die "新包缺少 index.html。"
jar tf "${WAR_SOURCE}" | grep -q '^WEB-INF/classes/servlet/CreditAuditServlet.class$' || \
  die "新包缺少毕业学分核验接口。"
log "备份当前毕业进度版本"
install -d -m 0700 "${BACKUP_DIR}"
cp -a "${WAR_TARGET}" "${BACKUP_DIR}/ROOT.war"
sha256sum "${WAR_SOURCE}" > "${BACKUP_DIR}/new-war.sha256"
sha256sum "${WAR_TARGET}" > "${BACKUP_DIR}/previous-war.sha256"

log "原子替换应用包，只重启毕业进度服务"
install -o root -g "${APP_NAME}" -m 0640 "${WAR_SOURCE}" "${WAR_TARGET}.next"
mv -f "${WAR_TARGET}.next" "${WAR_TARGET}"
systemctl restart "${APP_NAME}.service"

ready=0
for _ in $(seq 1 45); do
  if curl --fail --silent --show-error --max-time 3 \
      "http://127.0.0.1:${APP_PORT}/api/credit-audit/plans" >/dev/null; then
    ready=1
    break
  fi
  sleep 2
done

if [[ "${ready}" -ne 1 ]]; then
  log "新版本健康检查失败，自动恢复旧版本"
  install -o root -g "${APP_NAME}" -m 0640 "${BACKUP_DIR}/ROOT.war" "${WAR_TARGET}.rollback"
  mv -f "${WAR_TARGET}.rollback" "${WAR_TARGET}"
  systemctl restart "${APP_NAME}.service"
  for _ in $(seq 1 30); do
    if curl --fail --silent --show-error --max-time 3 \
        "http://127.0.0.1:${APP_PORT}/api/credit-audit/plans" >/dev/null; then
      break
    fi
    sleep 2
  done
  systemctl is-active --quiet "${RESUME_SERVICE}" || die "旧版本已恢复，但简历网站状态异常，请立即检查。"
  die "新版本未通过健康检查，已恢复旧版本。日志：journalctl -u ${APP_NAME} -n 100 --no-pager"
fi

log "执行更新后双站点核验"
systemctl is-active --quiet "${APP_NAME}.service" || die "毕业进度服务未运行。"
systemctl is-active --quiet "${RESUME_SERVICE}" || die "简历网站服务异常。"
curl --fail --silent --show-error --max-time 5 "http://127.0.0.1:${RESUME_PORT}/" >/dev/null || \
  die "简历网站本机健康检查失败。"

printf '\n更新成功。\n'
printf '本次只重启了：%s.service\n' "${APP_NAME}"
printf '旧版本备份：%s/ROOT.war\n' "${BACKUP_DIR}"
printf '简历网站服务：未重启、未改配置、健康检查通过\n'

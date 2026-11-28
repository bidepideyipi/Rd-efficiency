#!/usr/bin/env bash
# 在 jar 所在目录启动，使 ./application.yml 与 ./data-standard.db 生效。
# 用法：
#   ./start.sh                         # 前台
#   ./start.sh -d                      # 后台，日志默认 ../logs/dev-efficiency.log
#   ./start.sh -d -l /var/log/mig      # 后台并指定日志目录
#   ./start.sh --log-dir ../logs -- --server.port=8081
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

LOG_DIR="../logs"
DAEMON=0
JAVA_ARGS=()

usage() {
  cat <<'EOF'
用法: ./start.sh [选项] [-- JVM/Spring 参数...]

选项:
  -d, --daemon, --background   后台运行（nohup）
  -l, --log-dir DIR            日志目录（默认 ../logs）
  -h, --help                   显示帮助

后台时写入:
  {log-dir}/dev-efficiency.log
  {log-dir}/dev-efficiency.pid
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -l|--log-dir)
      LOG_DIR="${2:?缺少日志目录参数}"
      shift 2
      ;;
    -d|--daemon|--background)
      DAEMON=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      JAVA_ARGS+=("$@")
      break
      ;;
    -*)
      echo "未知选项: $1（用 -- 分隔 Spring 参数，或见 --help）" >&2
      exit 1
      ;;
    *)
      JAVA_ARGS+=("$1")
      shift
      ;;
  esac
done

JAR="$(ls -1 dev-efficiency-*.jar 2>/dev/null | head -n 1 || true)"
if [[ -z "${JAR}" ]]; then
  echo "未找到 dev-efficiency-*.jar，请先 mvn package 并复制到本目录" >&2
  exit 1
fi
if [[ ! -f application.yml ]]; then
  echo "缺少同目录 application.yml（外挂配置）" >&2
  exit 1
fi

mkdir -p "${LOG_DIR}"
# 相对路径按 jar 目录解析为绝对路径，便于看日志
LOG_DIR="$(cd "${LOG_DIR}" && pwd)"
LOG_FILE="${LOG_DIR}/dev-efficiency.log"
PID_FILE="${LOG_DIR}/dev-efficiency.pid"

if [[ "${DAEMON}" -eq 1 ]]; then
  if [[ -f "${PID_FILE}" ]]; then
    old_pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
    if [[ -n "${old_pid}" ]] && kill -0 "${old_pid}" 2>/dev/null; then
      echo "已在运行: pid=${old_pid}（${PID_FILE}）" >&2
      exit 1
    fi
  fi
  # Spring Boot 自动加载 file:./application.yml；SQLite 默认 ./data-standard.db
  nohup java -jar "${JAR}" "${JAVA_ARGS[@]}" >>"${LOG_FILE}" 2>&1 &
  echo $! >"${PID_FILE}"
  echo "started pid=$(cat "${PID_FILE}")"
  echo "log=${LOG_FILE}"
  exit 0
fi

exec java -jar "${JAR}" "${JAVA_ARGS[@]}"

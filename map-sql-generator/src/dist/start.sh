#!/usr/bin/env bash
# 在 jar 所在目录启动，使 ./application.yml 与 ./data-standard.db 生效
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

JAR="$(ls -1 map-sql-generator-*.jar 2>/dev/null | head -n 1 || true)"
if [[ -z "${JAR}" ]]; then
  echo "未找到 map-sql-generator-*.jar，请先 mvn package 并复制到本目录" >&2
  exit 1
fi
if [[ ! -f application.yml ]]; then
  echo "缺少同目录 application.yml（外挂配置）" >&2
  exit 1
fi

# Spring Boot 自动加载 file:./application.yml；SQLite 默认 ./data-standard.db（首次自动建表）
exec java -jar "$JAR" "$@"

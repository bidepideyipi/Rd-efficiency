#!/usr/bin/env bash
# 将介质中的 cnccq-jms-base 安装到本地 Maven 仓库（Central 无此包）
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/cnccq-jms-base-V2.0.5.0.jar"
POM="$DIR/cnccq-jms-base-V2.0.5.0.pom"
if [[ ! -f "$JAR" || ! -f "$POM" ]]; then
  echo "缺少 $JAR 或 $POM" >&2
  exit 1
fi
mvn -q install:install-file \
  -Dfile="$JAR" \
  -DpomFile="$POM" \
  -DgroupId=cn.cfit.cnccq.jms \
  -DartifactId=cnccq-jms-base \
  -Dversion=V2.0.5.0 \
  -Dpackaging=jar
echo "installed cn.cfit.cnccq.jms:cnccq-jms-base:V2.0.5.0"

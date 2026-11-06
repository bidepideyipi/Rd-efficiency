#!/bin/bash
set -euo pipefail

BEGIN_TM="${1:?beginTm required}"
END_TM="${2:?endTm required}"
TM_COLUMN="${3:-TRANDATE}"
TABLE_NAME="${4:-IBPS_PAYMENTINIT}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF="${SCRIPT_DIR}/export-job.conf"

if [[ ! -f "${CONF}" ]]; then
  echo "config not found: ${CONF}" >&2
  exit 1
fi

# 读取 key = value。只认整行注释，值中的 # 与 = 保留。
conf_get() {
  local key="$1"
  local val
  val="$(awk -v k="$key" '
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*$/ { next }
    {
      eq = index($0, "=")
      if (eq == 0) next
      key = substr($0, 1, eq - 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
      if (key != k) next
      val = substr($0, eq + 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", val)
      print val
      exit
    }
  ' "${CONF}")"
  if [[ -z "${val}" ]]; then
    echo "missing or empty config key: ${key} (${CONF})" >&2
    exit 1
  fi
  printf '%s' "${val}"
}

SQL_DIR="$(conf_get sqlDir)"
OUT_DIR="$(conf_get outDir)"
CONNECT="$(conf_get connect)"

TPL="${SQL_DIR}/${TABLE_NAME}.sql"
RUN_SQL="/tmp/${TABLE_NAME}_run.sql"
OUT="${OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}.csv"

case "${TM_COLUMN}" in
  TRANDATE|TRANTIME|WORKDATE|SENDTIME) ;;
  *) echo "invalid tmColumn: ${TM_COLUMN}" >&2; exit 1 ;;
esac

if [[ ! -f "${TPL}" ]]; then
  echo "SQL template not found: ${TPL}" >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"

sed -e "s/{{beginTm}}/${BEGIN_TM}/g" \
    -e "s/{{endTm}}/${END_TM}/g" \
    -e "s/{{tmColumn}}/${TM_COLUMN}/g" \
    "${TPL}" > "${RUN_SQL}"

sql -s "${CONNECT}" <<EOF
SET SQLFORMAT csv
SET HEADING ON
WHENEVER SQLERROR EXIT FAILURE
SPOOL ${OUT}
@${RUN_SQL}
SPOOL OFF
EXIT
EOF

echo "exported: ${OUT}"

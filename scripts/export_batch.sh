#!/bin/bash
# 按 export-job.conf 的文件列表 × 时间片，串行调用 export_by_map.sh。
# 不使用环境变量。时间窗左闭右开：[sliceBegin, sliceEnd)。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF="${SCRIPT_DIR}/export-job.conf"
ONE_SHOT="${SCRIPT_DIR}/export_by_map.sh"

if [[ ! -f "${CONF}" ]]; then
  echo "config not found: ${CONF}" >&2
  exit 1
fi
if [[ ! -f "${ONE_SHOT}" ]]; then
  echo "missing ${ONE_SHOT}" >&2
  exit 1
fi

trim() {
  printf '%s' "$1" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

SQL_DIR=""
OUT_DIR=""
TM_COLUMN="TRANDATE"
START=""
END=""
INTERVAL=""
ON_ERROR="stop"
FILES=()
FILES_SET=0

while IFS= read -r raw || [[ -n "${raw}" ]]; do
  line="$(trim "${raw}")"
  if [[ -z "${line}" || "${line}" == \#* ]]; then
    continue
  fi
  if [[ "${line}" == *=* ]]; then
    key="$(trim "${line%%=*}")"
    val="$(trim "${line#*=}")"
    case "${key}" in
      sqlDir) SQL_DIR="${val}" ;;
      outDir) OUT_DIR="${val}" ;;
      tmColumn) TM_COLUMN="${val}" ;;
      start) START="${val}" ;;
      end) END="${val}" ;;
      interval) INTERVAL="${val}" ;;
      onError) ON_ERROR="${val}" ;;
      files)
        FILES_SET=1
        if [[ -n "${val}" ]]; then
          FILES+=("${val}")
        fi
        ;;
      connect) ;;
      *) echo "unknown config key: ${key}" >&2; exit 1 ;;
    esac
    continue
  fi
  if [[ "${FILES_SET}" -eq 1 ]]; then
    FILES+=("${line}")
  else
    echo "unexpected config line: ${line}" >&2
    exit 1
  fi
done < "${CONF}"

if [[ -z "${SQL_DIR}" || -z "${OUT_DIR}" ]]; then
  echo "sqlDir and outDir are required (${CONF})" >&2
  exit 1
fi
if [[ -z "${START}" || -z "${END}" || -z "${INTERVAL}" ]]; then
  echo "start, end and interval are required (${CONF})" >&2
  exit 1
fi
if [[ "${ON_ERROR}" != "stop" && "${ON_ERROR}" != "continue" ]]; then
  echo "invalid onError: ${ON_ERROR} (stop|continue)" >&2
  exit 1
fi
if [[ ! "${INTERVAL}" =~ ^([1-9][0-9]*)d$ ]]; then
  echo "invalid interval: ${INTERVAL} (use Nd, e.g. 1d)" >&2
  exit 1
fi
INTERVAL_N="${BASH_REMATCH[1]}"

if [[ ! "${START}" =~ ^[0-9]{8}$ && ! "${START}" =~ ^[0-9]{14}$ ]]; then
  echo "invalid start: ${START} (YYYYMMDD or YYYYMMDDHH24MISS)" >&2
  exit 1
fi
if [[ "${#START}" -ne "${#END}" || ! "${END}" =~ ^[0-9]+$ ]]; then
  echo "start and end must share format YYYYMMDD or YYYYMMDDHH24MISS" >&2
  exit 1
fi

# 同长度数字串按字典序即时间序；不用 LC_ALL，避免影响子进程字符集。
ts_lt() {
  [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | LC_ALL=C sort | head -n 1)" == "$1" ]]
}

if ! ts_lt "${START}" "${END}"; then
  echo "start must be earlier than end (${START} >= ${END})" >&2
  exit 1
fi

add_interval() {
  local ts="$1"
  local n="$2"
  local out
  if date -d "2024-01-01" +%Y%m%d >/dev/null 2>&1; then
    local spec fmt
    if [[ "${#ts}" -eq 8 ]]; then
      spec="${ts:0:4}-${ts:4:2}-${ts:6:2}"
      fmt="+%Y%m%d"
    else
      spec="${ts:0:4}-${ts:4:2}-${ts:6:2} ${ts:8:2}:${ts:10:2}:${ts:12:2}"
      fmt="+%Y%m%d%H%M%S"
    fi
    out="$(date -d "${spec} + ${n} day" "${fmt}")"
  elif [[ "${#ts}" -eq 8 ]]; then
    out="$(date -j -f "%Y%m%d" -v+"${n}d" "${ts}" "+%Y%m%d")"
  else
    out="$(date -j -f "%Y%m%d%H%M%S" -v+"${n}d" "${ts}" "+%Y%m%d%H%M%S")"
  fi
  printf '%s' "${out}"
}

check_column() {
  case "$1" in
    TRANDATE|TRANTIME|WORKDATE|SENDTIME) ;;
    *) echo "invalid tmColumn: $1" >&2; exit 1 ;;
  esac
}

check_column "${TM_COLUMN}"

if [[ "${FILES_SET}" -eq 0 || ${#FILES[@]} -eq 0 ]]; then
  FILES=()
  shopt -s nullglob
  for path in "${SQL_DIR}"/*.sql; do
    FILES+=("$(basename "${path}")")
  done
  shopt -u nullglob
fi

if [[ ${#FILES[@]} -eq 0 ]]; then
  echo "no sql files in ${SQL_DIR}" >&2
  exit 1
fi

# 先校验名单，避免导出到一半才发现缺文件。
RESOLVED_TABLES=()
RESOLVED_COLUMNS=()
for entry in "${FILES[@]}"; do
  table="$(trim "${entry%%|*}")"
  table="${table%.sql}"
  col="${TM_COLUMN}"
  if [[ "${entry}" == *"|"* ]]; then
    col="$(trim "${entry#*|}")"
  fi
  if [[ -z "${table}" || "${table}" == */* || "${table}" == *..* ]]; then
    echo "invalid table name: ${entry}" >&2
    exit 1
  fi
  check_column "${col}"
  if [[ ! -f "${SQL_DIR}/${table}.sql" ]]; then
    echo "SQL template not found: ${SQL_DIR}/${table}.sql" >&2
    exit 1
  fi
  RESOLVED_TABLES+=("${table}")
  RESOLVED_COLUMNS+=("${col}")
done

mkdir -p "${OUT_DIR}/_batch"
MANIFEST="${OUT_DIR}/_batch/export-manifest.txt"
: > "${MANIFEST}"

failed=0
i=0
while [[ "${i}" -lt ${#RESOLVED_TABLES[@]} ]]; do
  table="${RESOLVED_TABLES[$i]}"
  col="${RESOLVED_COLUMNS[$i]}"
  cursor="${START}"
  while ts_lt "${cursor}" "${END}"; do
    next="$(add_interval "${cursor}" "${INTERVAL_N}")"
    if [[ "${next}" == "${cursor}" ]]; then
      echo "interval did not advance from ${cursor}" >&2
      exit 1
    fi
    if ts_lt "${END}" "${next}"; then
      next="${END}"
    fi
    csv="${OUT_DIR}/${table}_${cursor}_${next}.csv"
    echo "${table} ${cursor} ${next} ${col}"
    if "${ONE_SHOT}" "${cursor}" "${next}" "${col}" "${table}"; then
      printf '%s,%s,%s,%s,OK\n' "${table}" "${cursor}" "${next}" "${csv}" >> "${MANIFEST}"
    else
      printf '%s,%s,%s,%s,FAIL\n' "${table}" "${cursor}" "${next}" "${csv}" >> "${MANIFEST}"
      failed=1
      if [[ "${ON_ERROR}" == "stop" ]]; then
        echo "stopped after failure, manifest: ${MANIFEST}" >&2
        exit 1
      fi
    fi
    cursor="${next}"
  done
  i=$((i + 1))
done

echo "manifest: ${MANIFEST}"
if [[ "${failed}" -ne 0 ]]; then
  exit 1
fi

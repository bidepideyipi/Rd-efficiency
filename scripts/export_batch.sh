#!/bin/bash
# 按 export-job.conf 的 tables（表名=时间列）× 时间片，串行调用 export_by_map.sh。
# 不使用环境变量。时间窗左闭右开：[sliceBegin, sliceEnd)。
# 每张表必须在 tables 段显式配置时间列，无全局 tmColumn 默认值。
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

is_global_key() {
  case "$1" in
    sqlDir|outDir|start|end|interval|onError|tables|\
    connect|tgtConnect|ctlCharset|loadMode|sqlldrErrors|sqlldrRows|sqlldrDirect)
      return 0 ;;
    files|tmColumn)
      echo "config key '${1}' is removed; use tables section: 表名 = 时间列" >&2
      exit 1 ;;
    *) return 1 ;;
  esac
}

check_column() {
  case "$1" in
    TRANDATE|TRANTIME|WORKDATE|SENDTIME) ;;
    *) echo "invalid tmColumn: $1 (TRANDATE|TRANTIME|WORKDATE|SENDTIME)" >&2; exit 1 ;;
  esac
}

SQL_DIR=""
OUT_DIR=""
START=""
END=""
INTERVAL=""
ON_ERROR="stop"
TABLES_SET=0
RESOLVED_TABLES=()
RESOLVED_COLUMNS=()

add_table_mapping() {
  local table="$1"
  local col="$2"
  table="$(trim "${table}")"
  col="$(trim "${col}")"
  table="${table%.sql}"
  if [[ -z "${table}" || "${table}" == */* || "${table}" == *..* ]]; then
    echo "invalid table name: ${table}" >&2
    exit 1
  fi
  if [[ -z "${col}" ]]; then
    echo "tmColumn required for table: ${table}" >&2
    exit 1
  fi
  check_column "${col}"
  RESOLVED_TABLES+=("${table}")
  RESOLVED_COLUMNS+=("${col}")
}

while IFS= read -r raw || [[ -n "${raw}" ]]; do
  line="$(trim "${raw}")"
  if [[ -z "${line}" || "${line}" == \#* ]]; then
    continue
  fi
  if [[ "${line}" != *=* ]]; then
    echo "unexpected config line (want key = value): ${line}" >&2
    exit 1
  fi

  key="$(trim "${line%%=*}")"
  val="$(trim "${line#*=}")"

  if is_global_key "${key}"; then
    case "${key}" in
      sqlDir) SQL_DIR="${val}"; TABLES_SET=0 ;;
      outDir) OUT_DIR="${val}"; TABLES_SET=0 ;;
      start) START="${val}"; TABLES_SET=0 ;;
      end) END="${val}"; TABLES_SET=0 ;;
      interval) INTERVAL="${val}"; TABLES_SET=0 ;;
      onError) ON_ERROR="${val}"; TABLES_SET=0 ;;
      tables)
        TABLES_SET=1
        if [[ -n "${val}" ]]; then
          echo "put table mappings on following lines, not on 'tables =' itself: ${val}" >&2
          exit 1
        fi
        ;;
      connect|tgtConnect|ctlCharset|loadMode|sqlldrErrors|sqlldrRows|sqlldrDirect)
        TABLES_SET=0
        ;;
    esac
    continue
  fi

  if [[ "${TABLES_SET}" -eq 1 ]]; then
    add_table_mapping "${key}" "${val}"
  else
    echo "unknown config key: ${key}" >&2
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
if [[ ! "${INTERVAL}" =~ ^([1-9][0-9]*)m$ ]]; then
  echo "invalid interval: ${INTERVAL} (use Nm, e.g. 1m)" >&2
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

ts_lt() {
  [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | LC_ALL=C sort | head -n 1)" == "$1" ]]
}

if ! ts_lt "${START}" "${END}"; then
  echo "start must be earlier than end (${START} >= ${END})" >&2
  exit 1
fi

# 按自然月推进。start/end 建议落在月初（…01），避免月末日溢出（如 1/31 + 1m）。
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
    out="$(date -d "${spec} + ${n} month" "${fmt}")"
  elif [[ "${#ts}" -eq 8 ]]; then
    out="$(date -j -f "%Y%m%d" -v+"${n}m" "${ts}" "+%Y%m%d")"
  else
    out="$(date -j -f "%Y%m%d%H%M%S" -v+"${n}m" "${ts}" "+%Y%m%d%H%M%S")"
  fi
  printf '%s' "${out}"
}

if [[ ${#RESOLVED_TABLES[@]} -eq 0 ]]; then
  echo "tables section is required; each line: 表名 = 时间列" >&2
  exit 1
fi

i=0
while [[ "${i}" -lt ${#RESOLVED_TABLES[@]} ]]; do
  table="${RESOLVED_TABLES[$i]}"
  if [[ ! -f "${SQL_DIR}/${table}.sql" ]]; then
    echo "SQL template not found: ${SQL_DIR}/${table}.sql" >&2
    exit 1
  fi
  i=$((i + 1))
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
    dat="${OUT_DIR}/${table}_${cursor}_${next}.dat"
    ctl="${OUT_DIR}/${table}_${cursor}_${next}.ctl"
    echo "${table} ${cursor} ${next} ${col}"
    if "${ONE_SHOT}" "${cursor}" "${next}" "${col}" "${table}"; then
      printf '%s,%s,%s,%s,%s,OK\n' "${table}" "${cursor}" "${next}" "${dat}" "${ctl}" >> "${MANIFEST}"
    else
      printf '%s,%s,%s,%s,%s,FAIL\n' "${table}" "${cursor}" "${next}" "${dat}" "${ctl}" >> "${MANIFEST}"
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

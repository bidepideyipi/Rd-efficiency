#!/bin/bash
# 按 export-job.conf 的 tables 串行调用 export_by_map.sh。
# 不使用环境变量。时间窗左闭右开：[sliceBegin, sliceEnd)。
# 配置时间列的表按时间片执行；时间列留空的表不拼时间条件，并在完整范围内只执行一次。
#
# 用法：
#   ./export_batch.sh
#       conf 的 start/end/interval × tables，按月分片
#   ./export_batch.sh yyyyMMdd
#       指定日 [D, D+1) × conf tables，不再分片
#   ./export_batch.sh yyyyMMdd "tableName = tmColumn"
#       指定日 + 指定表，不再批量、不再分片
set -euo pipefail

EXPORT_BATCH_STARTED="${SECONDS}"
print_export_batch_elapsed() {
  local rc=$?
  local elapsed=$((SECONDS - EXPORT_BATCH_STARTED))
  printf 'export batch total elapsed: %02d:%02d:%02d (%d seconds)\n' \
    "$((elapsed / 3600))" "$(((elapsed % 3600) / 60))" "$((elapsed % 60))" "${elapsed}"
  return "${rc}"
}
trap print_export_batch_elapsed EXIT

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF="${SCRIPT_DIR}/export-job.conf"
ONE_SHOT="${SCRIPT_DIR}/export_by_map.sh"

ONE_DAY="${1:-}"
ONE_TABLE_SPEC="${2:-}"

if [[ ! -f "${CONF}" ]]; then
  echo "config not found: ${CONF}" >&2
  exit 1
fi
if [[ ! -f "${ONE_SHOT}" ]]; then
  echo "missing ${ONE_SHOT}" >&2
  exit 1
fi

if [[ -n "${ONE_TABLE_SPEC}" && -z "${ONE_DAY}" ]]; then
  echo "usage: $0 [yyyyMMdd [\"tableName = tmColumn\"]]" >&2
  exit 1
fi
if [[ -n "${ONE_DAY}" && ! "${ONE_DAY}" =~ ^[0-9]{8}$ ]]; then
  echo "invalid date arg: ${ONE_DAY} (need yyyyMMdd)" >&2
  exit 1
fi

trim() {
  printf '%s' "$1" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'
}

is_global_key() {
  case "$1" in
    sqlDir|outDir|start|end|interval|onError|tables|\
    connect|tgtConnect|ctlCharset|loadMode|sqlldrErrors|sqlldrDirect)
      return 0 ;;
    files|tmColumn)
      echo "config key '${1}' is removed; use tables section: 表名 = 时间列" >&2
      exit 1 ;;
    *) return 1 ;;
  esac
}

check_column() {
  # 不限定列名集合；仅校验可嵌入 s.{{tmColumn}} 的标识符。
  if [[ ! "$1" =~ ^[A-Za-z][A-Za-z0-9_#$]*$ ]]; then
    echo "invalid tmColumn: $1 (need Oracle identifier)" >&2
    exit 1
  fi
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
  if [[ -n "${col}" ]]; then
    check_column "${col}"
  fi
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
      connect|tgtConnect|ctlCharset|loadMode|sqlldrErrors|sqlldrDirect)
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
if [[ "${ON_ERROR}" != "stop" && "${ON_ERROR}" != "continue" ]]; then
  echo "invalid onError: ${ON_ERROR} (stop|continue)" >&2
  exit 1
fi

ts_lt() {
  [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | LC_ALL=C sort | head -n 1)" == "$1" ]]
}

# 按日推进（单日导出算 end = begin+1）。
add_days() {
  local ts="$1"
  local n="$2"
  local out
  if date -d "2024-01-01" +%Y%m%d >/dev/null 2>&1; then
    local spec="${ts:0:4}-${ts:4:2}-${ts:6:2}"
    out="$(date -d "${spec} + ${n} day" "+%Y%m%d")"
  else
    out="$(date -j -f "%Y%m%d" -v+"${n}d" "${ts}" "+%Y%m%d")"
  fi
  printf '%s' "${out}"
}

# 按自然月推进。start/end 建议落在月初（…01），避免月末日溢出（如 1/31 + 1m）。
add_months() {
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

SINGLE_SLICE=0
INTERVAL_N=""

if [[ -n "${ONE_DAY}" ]]; then
  SINGLE_SLICE=1
  START="${ONE_DAY}"
  END="$(add_days "${ONE_DAY}" 1)"
else
  if [[ -z "${START}" || -z "${END}" || -z "${INTERVAL}" ]]; then
    echo "start, end and interval are required (${CONF})" >&2
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
  if ! ts_lt "${START}" "${END}"; then
    echo "start must be earlier than end (${START} >= ${END})" >&2
    exit 1
  fi
fi

if [[ -n "${ONE_TABLE_SPEC}" ]]; then
  if [[ "${ONE_TABLE_SPEC}" != *=* ]]; then
    echo "invalid table spec: ${ONE_TABLE_SPEC} (want: tableName = tmColumn)" >&2
    exit 1
  fi
  RESOLVED_TABLES=()
  RESOLVED_COLUMNS=()
  add_table_mapping "${ONE_TABLE_SPEC%%=*}" "${ONE_TABLE_SPEC#*=}"
elif [[ ${#RESOLVED_TABLES[@]} -eq 0 ]]; then
  echo "tables section is required; each line: 表名 = 时间列（整表导出可留空）" >&2
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
ERROR_LOG="${OUT_DIR}/_batch/export-error.log"
: > "${MANIFEST}"
: > "${ERROR_LOG}"

# 执行单片导出：成功/失败写 manifest；失败时追加错误日志（含 ORA/SP2 原因）。
run_export_slice() {
  local table="$1"
  local begin="$2"
  local end="$3"
  local col="$4"
  local dat ctl slice_log tmp rc spool_err
  dat="${OUT_DIR}/${table}_${begin}_${end}.dat"
  ctl="${OUT_DIR}/${table}_${begin}_${end}.ctl"
  slice_log="${OUT_DIR}/${table}_${begin}_${end}.log"
  echo "${table} ${begin} ${end} ${col}"
  tmp="$(mktemp)"
  set +e
  "${ONE_SHOT}" "${begin}" "${end}" "${col}" "${table}" >"${tmp}" 2>&1
  rc=$?
  set -e
  cat "${tmp}"
  if [[ "${rc}" -eq 0 ]]; then
    rm -f "${tmp}"
    printf '%s,%s,%s,%s,%s,OK\n' "${table}" "${begin}" "${end}" "${dat}" "${ctl}" >> "${MANIFEST}"
    return 0
  fi
  printf '%s,%s,%s,%s,%s,FAIL\n' "${table}" "${begin}" "${end}" "${dat}" "${ctl}" >> "${MANIFEST}"
  {
    echo "==== $(date '+%Y-%m-%d %H:%M:%S') FAIL table=${table} begin=${begin} end=${end} tmColumn=${col} rc=${rc} ===="
    if [[ -s "${tmp}" ]]; then
      echo "-- export_by_map output --"
      cat "${tmp}"
    else
      echo "-- export_by_map output: (empty) --"
    fi
    # 再从单片 .log / spool .dat 抽原因（旧版 by_map 或 TERMOUT OFF 时 stdout 为空）
    if [[ -f "${slice_log}" && -s "${slice_log}" ]]; then
      echo "-- slice log: ${slice_log} --"
      cat "${slice_log}"
    fi
    if [[ -f "${dat}" ]]; then
      spool_err="$(grep -E 'ORA-[0-9]{5}|SP2-[0-9]{4}|ERROR at line' "${dat}" 2>/dev/null | head -n 50 || true)"
      if [[ -n "${spool_err}" ]]; then
        echo "-- spool/dat Oracle errors: ${dat} --"
        printf '%s\n' "${spool_err}"
      elif [[ ! -s "${tmp}" ]]; then
        echo "-- spool/dat tail (no ORA/SP2 matched): ${dat} --"
        tail -n 80 "${dat}" 2>/dev/null || true
      fi
    else
      echo "-- spool/dat missing: ${dat} --"
    fi
    echo
  } >> "${ERROR_LOG}"
  rm -f "${tmp}"
  failed=1
  if [[ "${ON_ERROR}" == "stop" ]]; then
    echo "stopped after failure, manifest: ${MANIFEST}" >&2
    echo "error log: ${ERROR_LOG}" >&2
    exit 1
  fi
  return 0
}

failed=0
i=0
while [[ "${i}" -lt ${#RESOLVED_TABLES[@]} ]]; do
  table="${RESOLVED_TABLES[$i]}"
  col="${RESOLVED_COLUMNS[$i]}"
  if [[ "${SINGLE_SLICE}" -eq 1 || -z "${col}" ]]; then
    run_export_slice "${table}" "${START}" "${END}" "${col}"
  else
    cursor="${START}"
    while ts_lt "${cursor}" "${END}"; do
      next="$(add_months "${cursor}" "${INTERVAL_N}")"
      if [[ "${next}" == "${cursor}" ]]; then
        echo "interval did not advance from ${cursor}" >&2
        exit 1
      fi
      if ts_lt "${END}" "${next}"; then
        next="${END}"
      fi
      run_export_slice "${table}" "${cursor}" "${next}" "${col}"
      cursor="${next}"
    done
  fi
  i=$((i + 1))
done

echo "manifest: ${MANIFEST}"
if [[ "${failed}" -ne 0 ]]; then
  echo "error log: ${ERROR_LOG}" >&2
  exit 1
fi

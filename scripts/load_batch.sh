#!/bin/bash
# 批量装载：优先按 export-manifest.txt 中 OK 行调用 load_by_map.sh；
# 若清单不存在，则按 export-job.conf 的 tables × 时间片扫描已有 .ctl。
# 不使用环境变量。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF="${SCRIPT_DIR}/export-job.conf"
ONE_SHOT="${SCRIPT_DIR}/load_by_map.sh"

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

SQL_DIR=""
OUT_DIR=""
START=""
END=""
INTERVAL=""
ON_ERROR="stop"
TABLES_SET=0
RESOLVED_TABLES=()

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
    table="$(trim "${key}")"
    table="${table%.sql}"
    if [[ -z "${table}" || "${table}" == */* || "${table}" == *..* ]]; then
      echo "invalid table name: ${key}" >&2
      exit 1
    fi
    # 装载回退扫片不需要时间列值，但仍要求配置行完整。
    if [[ -z "$(trim "${val}")" ]]; then
      echo "tmColumn required for table: ${table}" >&2
      exit 1
    fi
    RESOLVED_TABLES+=("${table}")
  else
    echo "unknown config key: ${key}" >&2
    exit 1
  fi
done < "${CONF}"

if [[ -z "${OUT_DIR}" ]]; then
  echo "outDir is required (${CONF})" >&2
  exit 1
fi
if [[ "${ON_ERROR}" != "stop" && "${ON_ERROR}" != "continue" ]]; then
  echo "invalid onError: ${ON_ERROR} (stop|continue)" >&2
  exit 1
fi

ts_lt() {
  [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | LC_ALL=C sort | head -n 1)" == "$1" ]]
}

# 按自然月推进。start/end 建议落在月初（…01），避免月末日溢出。
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

mkdir -p "${OUT_DIR}/_batch"
EXPORT_MANIFEST="${OUT_DIR}/_batch/export-manifest.txt"
LOAD_MANIFEST="${OUT_DIR}/_batch/load-manifest.txt"
: > "${LOAD_MANIFEST}"

JOBS=() # 每项：table|begin|end

if [[ -f "${EXPORT_MANIFEST}" ]]; then
  while IFS= read -r raw || [[ -n "${raw}" ]]; do
    line="$(trim "${raw}")"
    [[ -z "${line}" || "${line}" == \#* ]] && continue
    status="${line##*,}"
    [[ "${status}" != "OK" ]] && continue
    IFS=',' read -r table begin end _dat _ctl _status <<< "${line}"
    table="$(trim "${table}")"
    begin="$(trim "${begin}")"
    end="$(trim "${end}")"
    if [[ -z "${table}" || -z "${begin}" || -z "${end}" ]]; then
      echo "bad manifest line: ${line}" >&2
      exit 1
    fi
    JOBS+=("${table}|${begin}|${end}")
  done < "${EXPORT_MANIFEST}"
  echo "jobs from export-manifest: ${#JOBS[@]}"
else
  if [[ -z "${START}" || -z "${END}" || -z "${INTERVAL}" ]]; then
    echo "export-manifest not found; need start/end/interval to scan slices (${CONF})" >&2
    exit 1
  fi
  if [[ ! "${INTERVAL}" =~ ^([1-9][0-9]*)m$ ]]; then
    echo "invalid interval: ${INTERVAL} (use Nm, e.g. 1m)" >&2
    exit 1
  fi
  INTERVAL_N="${BASH_REMATCH[1]}"
  if [[ ! "${START}" =~ ^[0-9]{8}$ && ! "${START}" =~ ^[0-9]{14}$ ]]; then
    echo "invalid start: ${START}" >&2
    exit 1
  fi
  if [[ "${#START}" -ne "${#END}" || ! "${END}" =~ ^[0-9]+$ ]]; then
    echo "start and end must share format" >&2
    exit 1
  fi
  if ! ts_lt "${START}" "${END}"; then
    echo "start must be earlier than end" >&2
    exit 1
  fi
  if [[ ${#RESOLVED_TABLES[@]} -eq 0 ]]; then
    echo "tables section is required when export-manifest is missing" >&2
    exit 1
  fi

  for table in "${RESOLVED_TABLES[@]}"; do
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
      ctl="${OUT_DIR}/${table}_${cursor}_${next}.ctl"
      if [[ -f "${ctl}" ]]; then
        JOBS+=("${table}|${cursor}|${next}")
      fi
      cursor="${next}"
    done
  done
  echo "jobs from conf tables (existing .ctl): ${#JOBS[@]}"
fi

if [[ ${#JOBS[@]} -eq 0 ]]; then
  echo "no load jobs" >&2
  exit 1
fi

failed=0
for job in "${JOBS[@]}"; do
  table="${job%%|*}"
  rest="${job#*|}"
  begin="${rest%%|*}"
  end="${rest##*|}"
  ctl="${OUT_DIR}/${table}_${begin}_${end}.ctl"
  echo "${table} ${begin} ${end}"
  if "${ONE_SHOT}" "${begin}" "${end}" "${table}"; then
    printf '%s,%s,%s,%s,OK\n' "${table}" "${begin}" "${end}" "${ctl}" >> "${LOAD_MANIFEST}"
  else
    printf '%s,%s,%s,%s,FAIL\n' "${table}" "${begin}" "${end}" "${ctl}" >> "${LOAD_MANIFEST}"
    failed=1
    if [[ "${ON_ERROR}" == "stop" ]]; then
      echo "stopped after failure, manifest: ${LOAD_MANIFEST}" >&2
      exit 1
    fi
  fi
done

echo "manifest: ${LOAD_MANIFEST}"
if [[ "${failed}" -ne 0 ]]; then
  exit 1
fi

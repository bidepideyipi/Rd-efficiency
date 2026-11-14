#!/bin/bash
# 按 test-job.conf 的 tables × 时间片，串行调用 test_by_map.sh。
# 用法：
#   ./test_batch.sh
#   ./test_batch.sh yyyyMMdd
#   ./test_batch.sh yyyyMMdd "tableName = tmColumn"
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF="${SCRIPT_DIR}/test-job.conf"
ONE_SHOT="${SCRIPT_DIR}/test_by_map.sh"

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

trim() { printf '%s' "$1" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'; }

check_column() {
  if [[ ! "$1" =~ ^[A-Za-z][A-Za-z0-9_#$]*$ ]]; then
    echo "invalid tmColumn: $1 (need Oracle identifier)" >&2
    exit 1
  fi
}

START=""
END=""
INTERVAL=""
ON_ERROR="stop"
TEST_OUT_DIR=""
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
  [[ -z "${line}" || "${line}" == \#* ]] && continue
  if [[ "${line}" != *=* ]]; then
    echo "unexpected config line (want key = value): ${line}" >&2
    exit 1
  fi
  key="$(trim "${line%%=*}")"
  val="$(trim "${line#*=}")"

  case "${key}" in
    testSqlDir|connect|tgtConnect|maxDiffLines)
      TABLES_SET=0
      ;;
    testOutDir) TEST_OUT_DIR="${val}"; TABLES_SET=0 ;;
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
      continue
      ;;
    *)
      if [[ "${TABLES_SET}" -eq 1 ]]; then
        add_table_mapping "${key}" "${val}"
        continue
      fi
      echo "unknown config key: ${key}" >&2
      exit 1
      ;;
  esac
done < "${CONF}"

if [[ "${ON_ERROR}" != "stop" && "${ON_ERROR}" != "continue" ]]; then
  echo "invalid onError: ${ON_ERROR} (stop|continue)" >&2
  exit 1
fi

ts_lt() {
  [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | LC_ALL=C sort | head -n 1)" == "$1" ]]
}

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

add_months() {
  local ts="$1"
  local n="$2"
  local out
  if date -d "2024-01-01" +%Y%m%d >/dev/null 2>&1; then
    local spec="${ts:0:4}-${ts:4:2}-${ts:6:2}"
    out="$(date -d "${spec} + ${n} month" "+%Y%m%d")"
  else
    out="$(date -j -f "%Y%m%d" -v+"${n}m" "${ts}" "+%Y%m%d")"
  fi
  printf '%s' "${out}"
}

if [[ -n "${ONE_TABLE_SPEC}" ]]; then
  if [[ "${ONE_TABLE_SPEC}" != *=* ]]; then
    echo "invalid table spec: ${ONE_TABLE_SPEC} (want: tableName = tmColumn)" >&2
    exit 1
  fi
  RESOLVED_TABLES=()
  RESOLVED_COLUMNS=()
  add_table_mapping "${ONE_TABLE_SPEC%%=*}" "${ONE_TABLE_SPEC#*=}"
fi

if [[ ${#RESOLVED_TABLES[@]} -eq 0 ]]; then
  echo "no tables configured" >&2
  exit 1
fi

SLICES_BEGIN=()
SLICES_END=()
if [[ -n "${ONE_DAY}" ]]; then
  if [[ ! "${ONE_DAY}" =~ ^[0-9]{8}$ ]]; then
    echo "invalid date arg: ${ONE_DAY} (need yyyyMMdd)" >&2
    exit 1
  fi
  SLICES_BEGIN+=("${ONE_DAY}")
  SLICES_END+=("$(add_days "${ONE_DAY}" 1)")
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
  if ! ts_lt "${START}" "${END}"; then
    echo "start must be earlier than end (${START} >= ${END})" >&2
    exit 1
  fi
  CUR="${START}"
  while ts_lt "${CUR}" "${END}"; do
    NXT="$(add_months "${CUR}" "${INTERVAL_N}")"
    if ! ts_lt "${NXT}" "${END}"; then
      NXT="${END}"
    fi
    SLICES_BEGIN+=("${CUR}")
    SLICES_END+=("${NXT}")
    CUR="${NXT}"
  done
fi

if [[ -z "${TEST_OUT_DIR}" ]]; then
  TEST_OUT_DIR="${SCRIPT_DIR}/../out/test"
fi
mkdir -p "${TEST_OUT_DIR}"
SUMMARY="${TEST_OUT_DIR}/batch_summary.tsv"
printf 'sheet\tbegin\tend\toutcome\texit\n' > "${SUMMARY}"

for i in "${!RESOLVED_TABLES[@]}"; do
  TABLE="${RESOLVED_TABLES[$i]}"
  COL="${RESOLVED_COLUMNS[$i]}"
  for j in "${!SLICES_BEGIN[@]}"; do
    B="${SLICES_BEGIN[$j]}"
    E="${SLICES_END[$j]}"
    set +e
    "${ONE_SHOT}" "${B}" "${E}" "${COL}" "${TABLE}"
    RC=$?
    set -e
    OUTCOME=PASS
    [[ "${RC}" -ne 0 ]] && OUTCOME=FAIL
    printf '%s\t%s\t%s\t%s\t%s\n' "${TABLE}" "${B}" "${E}" "${OUTCOME}" "${RC}" >> "${SUMMARY}"
    if [[ "${RC}" -ne 0 && "${ON_ERROR}" == "stop" ]]; then
      echo "stopped on error: ${TABLE} ${B} ${E} rc=${RC}" >&2
      exit "${RC}"
    fi
  done
done

echo "summary: ${SUMMARY}"

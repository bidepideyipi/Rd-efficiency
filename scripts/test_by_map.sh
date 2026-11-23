#!/bin/bash
# 单表单时间窗：执行 test/{Sheet} 下成对 SQL，比对源/目标结果，写 verify.result。
# 用法：
#   ./test_by_map.sh <beginTm> <endTm> <srcTmColumn> <tgtTmColumn> <Sheet名>
# 兼容旧用法：./test_by_map.sh <beginTm> <endTm> <tmColumn> <Sheet名>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF="${SCRIPT_DIR}/test-job.conf"

if [[ "$#" -ne 4 && "$#" -ne 5 ]]; then
  echo "usage: $0 <beginTm> <endTm> <srcTmColumn> [tgtTmColumn] <Sheet>" >&2
  exit 2
fi

BEGIN_TM="$1"
END_TM="$2"
SRC_TM_COLUMN="$3"
if [[ "$#" -eq 5 ]]; then
  TGT_TM_COLUMN="$4"
  TABLE_NAME="$5"
else
  TGT_TM_COLUMN="$3"
  TABLE_NAME="$4"
fi
TABLE_NAME="${TABLE_NAME%.sql}"

if [[ ! -f "${CONF}" ]]; then
  echo "config not found: ${CONF}" >&2
  exit 2
fi

trim() { printf '%s' "$1" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//'; }

TEST_SQL_DIR=""
TEST_OUT_DIR=""
CONNECT=""
TGT_CONNECT=""
MAX_DIFF_LINES=50
BY_DAY_CHECK=false

while IFS= read -r raw || [[ -n "${raw}" ]]; do
  line="$(trim "${raw}")"
  [[ -z "${line}" || "${line}" == \#* ]] && continue
  [[ "${line}" != *=* ]] && continue
  key="$(trim "${line%%=*}")"
  val="$(trim "${line#*=}")"
  case "${key}" in
    testSqlDir) TEST_SQL_DIR="${val}" ;;
    testOutDir) TEST_OUT_DIR="${val}" ;;
    connect) CONNECT="${val}" ;;
    tgtConnect) TGT_CONNECT="${val}" ;;
    maxDiffLines) MAX_DIFF_LINES="${val}" ;;
    byDayCheck) BY_DAY_CHECK="${val}" ;;
    tables) break ;;
  esac
done < "${CONF}"

if [[ -z "${TEST_SQL_DIR}" || -z "${TEST_OUT_DIR}" || -z "${CONNECT}" || -z "${TGT_CONNECT}" ]]; then
  echo "testSqlDir/testOutDir/connect/tgtConnect required in ${CONF}" >&2
  exit 2
fi
for column in "${SRC_TM_COLUMN}" "${TGT_TM_COLUMN}"; do
  if [[ ! "${column}" =~ ^[A-Za-z][A-Za-z0-9_#$]*$ ]]; then
    echo "invalid time column: ${column}" >&2
    exit 2
  fi
done
if [[ "${BY_DAY_CHECK}" != true && "${BY_DAY_CHECK}" != false ]]; then
  echo "invalid byDayCheck: ${BY_DAY_CHECK} (true|false)" >&2
  exit 2
fi

SHEET_DIR="${TEST_SQL_DIR}/${TABLE_NAME}"
if [[ ! -d "${SHEET_DIR}" ]]; then
  echo "test SQL dir not found: ${SHEET_DIR}" >&2
  exit 2
fi

SLICE_DIR="${TEST_OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}"
mkdir -p "${SLICE_DIR}"
RESULT="${SLICE_DIR}/verify.result"
DIFF="${SLICE_DIR}/verify.diff"
: > "${DIFF}"
RUN_LOG="${SLICE_DIR}/run.log"
: > "${RUN_LOG}"

AMOUNT_CHECK=false
if [[ -f "${SHEET_DIR}/_meta.json" ]] && grep -q '"amountCheck": true' "${SHEET_DIR}/_meta.json"; then
  AMOUNT_CHECK=true
fi

run_sql() {
  local side="$1" sql_file="$2" out_dat="$3"
  local connect="${CONNECT}"
  local side_column="${SRC_TM_COLUMN}"
  if [[ "${side}" == "tgt" ]]; then
    connect="${TGT_CONNECT}"
    side_column="${TGT_TM_COLUMN}"
  fi
  local run_sql tmp_log started elapsed rc
  run_sql="${SLICE_DIR}/_run_${side}_$(basename "${sql_file}")"
  sed -e "s/{{beginTm}}/${BEGIN_TM}/g" \
      -e "s/{{endTm}}/${END_TM}/g" \
      -e "s/{{srcTmColumn}}/${SRC_TM_COLUMN}/g" \
      -e "s/{{tgtTmColumn}}/${TGT_TM_COLUMN}/g" \
      -e "s/{{tmColumn}}/${side_column}/g" \
      "${sql_file}" > "${run_sql}"
  tmp_log="${RUN_LOG}.tmp"
  rm -f "${tmp_log}" "${out_dat}"
  started="$(date +%s)"
  if sqlplus -s "${connect}" <<EOF >"${tmp_log}" 2>&1
SET ECHO OFF
SET FEEDBACK OFF
SET HEADING OFF
SET PAGESIZE 0
SET LINESIZE 32767
SET TRIMSPOOL ON
SET TRIMOUT ON
SET TERMOUT OFF
SET COLSEP ''
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT SQL.SQLCODE
SPOOL ${out_dat}
@${run_sql}
SPOOL OFF
EXIT
EOF
  then
    elapsed=$(( $(date +%s) - started ))
    printf 'side=%s sql=%s status=PASS elapsed=%ss\n' \
      "${side}" "$(basename "${sql_file}")" "${elapsed}" >> "${RUN_LOG}"
    rm -f "${tmp_log}"
  else
    rc=$?
    elapsed=$(( $(date +%s) - started ))
    printf 'side=%s sql=%s status=ERROR exit=%s elapsed=%ss\n' \
      "${side}" "$(basename "${sql_file}")" "${rc}" "${elapsed}" >> "${RUN_LOG}"
    if [[ -s "${tmp_log}" ]]; then
      cat "${tmp_log}" >> "${RUN_LOG}"
    fi
    rm -f "${tmp_log}" "${out_dat}"
    echo "sqlplus failed: ${side} $(basename "${sql_file}") (see ${RUN_LOG})" >&2
    return 2
  fi
  # 去掉空行
  if [[ -f "${out_dat}" ]]; then
    grep -v '^[[:space:]]*$' "${out_dat}" > "${out_dat}.n" || true
    mv "${out_dat}.n" "${out_dat}"
  else
    : > "${out_dat}"
  fi
  return 0
}

pair_run() {
  local base="$1"
  local src="${SHEET_DIR}/${base}_src.sql"
  local tgt="${SHEET_DIR}/${base}_tgt.sql"
  if [[ ! -f "${src}" || ! -f "${tgt}" ]]; then
    echo "required SQL pair missing: ${src} / ${tgt}" >&2
    return 2
  fi
  run_sql src "${src}" "${SLICE_DIR}/${base}.src.dat" || return 2
  run_sql tgt "${tgt}" "${SLICE_DIR}/${base}.tgt.dat" || return 2
}

OVERALL=PASS
EXIT_CODE=0
INFRA_ERROR=false
declare -a LINES=()

fail() {
  OVERALL=FAIL
  if [[ "${EXIT_CODE}" -eq 0 ]]; then
    EXIT_CODE=1
  fi
}

infrastructure_error() {
  OVERALL=FAIL
  EXIT_CODE=2
  INFRA_ERROR=true
}

append_diff() {
  local section="$1"
  shift
  echo "== ${section} ==" >> "${DIFF}"
  local n=0
  for row in "$@"; do
    n=$((n + 1))
    if [[ "${n}" -gt "${MAX_DIFF_LINES}" ]]; then
      echo "... truncated ..." >> "${DIFF}"
      break
    fi
    echo "${row}" >> "${DIFF}"
  done
}

append_file_diff() {
  local section="$1" src="$2" tgt="$3"
  local rows=()
  while IFS= read -r row; do
    rows+=("${row}")
  done < <(diff -u "${src}" "${tgt}" | head -n "${MAX_DIFF_LINES}" || true)
  append_diff "${section}" "${rows[@]}"
}

# 02_hash 每行：NULL_FLAG<0x01>ROW_MD5<0x01>PK_KEY。
# PK_KEY 已由 SQL 按序输出，因此可在本地一次扫描派生 T5，无需额外查询数据库。
derive_keys() {
  local input_file="$1" output_file="$2"
  awk '{
    line=$0
    sub(/^[^\001]*\001/, "", line)
    sub(/^[^\001]*\001/, "", line)
    print line
  }' "${input_file}" > "${output_file}"
}

count_null_keys() {
  awk -F "$(printf '\001')" '$1 == "1" { n++ } END { print n + 0 }' "$1"
}

count_duplicate_groups() {
  awk '
    NR == 1 { previous=$0; next }
    $0 == previous {
      if (!in_duplicate) { duplicate_groups++ }
      in_duplicate=1
      next
    }
    { previous=$0; in_duplicate=0 }
    END { print duplicate_groups + 0 }
  ' "$1"
}

count_distinct_keys() {
  awk '
    NR == 1 { distinct=1; previous=$0; next }
    $0 != previous { distinct++; previous=$0 }
    END { print distinct + 0 }
  ' "$1"
}

# --- T1 total ---
T1_TOTAL=SKIP
if pair_run 01_count_total; then
  SRC_LINE="$(head -n1 "${SLICE_DIR}/01_count_total.src.dat" 2>/dev/null || true)"
  TGT_LINE="$(head -n1 "${SLICE_DIR}/01_count_total.tgt.dat" 2>/dev/null || true)"
  SRC_CNT="${SRC_LINE%%$'\x01'*}"
  TGT_CNT="${TGT_LINE%%$'\x01'*}"
  [[ -z "${SRC_CNT}" ]] && SRC_CNT=0
  [[ -z "${TGT_CNT}" ]] && TGT_CNT=0
  if [[ "${AMOUNT_CHECK}" == true ]]; then
    SRC_AMT="${SRC_LINE#*$'\x01'}"
    TGT_AMT="${TGT_LINE#*$'\x01'}"
    if [[ "${SRC_CNT}" == "${TGT_CNT}" && "${SRC_AMT}" == "${TGT_AMT}" ]]; then
      T1_TOTAL=PASS
    else
      T1_TOTAL=FAIL
      fail
      append_diff T1_total "CNT src=${SRC_CNT} tgt=${TGT_CNT} AMT src=${SRC_AMT} tgt=${TGT_AMT}"
    fi
    LINES+=("T1_total=${T1_TOTAL} cnt_src=${SRC_CNT} cnt_tgt=${TGT_CNT} amt_src=${SRC_AMT} amt_tgt=${TGT_AMT}")
  else
    if [[ "${SRC_CNT}" == "${TGT_CNT}" ]]; then
      T1_TOTAL=PASS
    else
      T1_TOTAL=FAIL
      fail
      append_diff T1_total "CNT src=${SRC_CNT} tgt=${TGT_CNT}"
    fi
    LINES+=("T1_total=${T1_TOTAL} cnt_src=${SRC_CNT} cnt_tgt=${TGT_CNT}")
  fi
else
  infrastructure_error
  LINES+=("T1_total=ERROR")
fi

# --- T1 byday ---
T1_BYDAY=SKIP
if [[ "${INFRA_ERROR}" == true ]]; then
  LINES+=("T1_byday=SKIP reason=prior_error")
elif [[ "${BY_DAY_CHECK}" != true ]]; then
  LINES+=("T1_byday=SKIP reason=disabled")
elif [[ -f "${SHEET_DIR}/01_count_byday_src.sql" \
        || -f "${SHEET_DIR}/01_count_byday_tgt.sql" ]]; then
  if pair_run 01_count_byday; then
    if cmp -s "${SLICE_DIR}/01_count_byday.src.dat" "${SLICE_DIR}/01_count_byday.tgt.dat"; then
      T1_BYDAY=PASS
    else
      T1_BYDAY=FAIL
      fail
      append_file_diff T1_byday \
        "${SLICE_DIR}/01_count_byday.src.dat" "${SLICE_DIR}/01_count_byday.tgt.dat"
    fi
    LINES+=("T1_byday=${T1_BYDAY}")
  else
    infrastructure_error
    LINES+=("T1_byday=ERROR")
  fi
else
  LINES+=("T1_byday=SKIP reason=not_generated")
fi

# T4 bundled
if [[ "${AMOUNT_CHECK}" == true ]]; then
  if [[ "${T1_TOTAL}" == PASS ]]; then
    LINES+=("T4_amount=PASS bundled_in=T1_total")
  elif [[ "${T1_TOTAL}" == FAIL ]]; then
    LINES+=("T4_amount=FAIL bundled_in=T1_total")
  else
    LINES+=("T4_amount=SKIP")
  fi
else
  LINES+=("T4_amount=SKIP")
fi

# --- T2 ---
if [[ "${INFRA_ERROR}" == true ]]; then
  LINES+=("T2_hash=SKIP reason=prior_error")
  LINES+=("T3_derived=SKIP")
  LINES+=("T5_dup=SKIP reason=prior_error")
  LINES+=("T5_null=SKIP reason=prior_error")
  LINES+=("T5_stat=SKIP reason=prior_error")
  LINES+=("T5_set=SKIP reason=prior_error")
elif [[ -f "${SHEET_DIR}/02_hash_src.sql" \
        || -f "${SHEET_DIR}/02_hash_tgt.sql" ]]; then
  if [[ ! -f "${SHEET_DIR}/02_hash_src.sql" \
        || ! -f "${SHEET_DIR}/02_hash_tgt.sql" ]] \
      || ! grep -q 'rowFormat: NULL_FLAG' "${SHEET_DIR}/02_hash_src.sql" \
      || ! grep -q 'rowFormat: NULL_FLAG' "${SHEET_DIR}/02_hash_tgt.sql"; then
    echo "outdated 02_hash SQL format for ${TABLE_NAME}; regenerate test SQL" >&2
    infrastructure_error
    LINES+=("T2_hash=ERROR reason=outdated_sql")
    LINES+=("T3_derived=SKIP")
    LINES+=("T5_dup=SKIP reason=hash_error")
    LINES+=("T5_null=SKIP reason=hash_error")
    LINES+=("T5_stat=SKIP reason=hash_error")
    LINES+=("T5_set=SKIP reason=hash_error")
  elif pair_run 02_hash; then
    SRC_HASH="${SLICE_DIR}/02_hash.src.dat"
    TGT_HASH="${SLICE_DIR}/02_hash.tgt.dat"
    SRC_KEYS="${SLICE_DIR}/02_hash.src.keys"
    TGT_KEYS="${SLICE_DIR}/02_hash.tgt.keys"
    derive_keys "${SRC_HASH}" "${SRC_KEYS}"
    derive_keys "${TGT_HASH}" "${TGT_KEYS}"

    if cmp -s "${SLICE_DIR}/02_hash.src.dat" "${SLICE_DIR}/02_hash.tgt.dat"; then
      LINES+=("T2_hash=PASS")
      LINES+=("T3_derived=PASS reason=T2_PASS")
    else
      fail
      append_file_diff T2_hash "${SRC_HASH}" "${TGT_HASH}"
      LINES+=("T2_hash=FAIL")
      LINES+=("T3_derived=SKIP")
    fi

    SRC_NULL="$(count_null_keys "${SRC_HASH}")"
    TGT_NULL="$(count_null_keys "${TGT_HASH}")"
    if [[ "${SRC_NULL}" == 0 && "${TGT_NULL}" == 0 ]]; then
      LINES+=("T5_null=PASS")
    else
      fail
      LINES+=("T5_null=FAIL src=${SRC_NULL} tgt=${TGT_NULL}")
      append_diff T5_null "null_pk_cnt src=${SRC_NULL} tgt=${TGT_NULL}"
    fi

    SRC_DUP="$(count_duplicate_groups "${SRC_KEYS}")"
    TGT_DUP="$(count_duplicate_groups "${TGT_KEYS}")"
    if [[ "${SRC_DUP}" == 0 && "${TGT_DUP}" == 0 ]]; then
      LINES+=("T5_dup=PASS")
    else
      fail
      LINES+=("T5_dup=FAIL src_groups=${SRC_DUP} tgt_groups=${TGT_DUP}")
      append_diff T5_dup "duplicate_groups src=${SRC_DUP} tgt=${TGT_DUP}"
    fi

    SRC_ROWS="$(wc -l < "${SRC_KEYS}" | tr -d '[:space:]')"
    TGT_ROWS="$(wc -l < "${TGT_KEYS}" | tr -d '[:space:]')"
    SRC_DISTINCT="$(count_distinct_keys "${SRC_KEYS}")"
    TGT_DISTINCT="$(count_distinct_keys "${TGT_KEYS}")"
    if [[ "${SRC_ROWS}" == "${SRC_DISTINCT}" \
          && "${TGT_ROWS}" == "${TGT_DISTINCT}" \
          && "${SRC_ROWS}" == "${TGT_ROWS}" \
          && "${SRC_ROWS}" == "${SRC_CNT}" \
          && "${TGT_ROWS}" == "${TGT_CNT}" ]]; then
      LINES+=("T5_stat=PASS cnt=${SRC_ROWS} dist=${SRC_DISTINCT}")
    else
      fail
      LINES+=("T5_stat=FAIL src=${SRC_ROWS}/${SRC_DISTINCT} tgt=${TGT_ROWS}/${TGT_DISTINCT} t1=${SRC_CNT}/${TGT_CNT}")
      append_diff T5_stat \
        "rows/distinct src=${SRC_ROWS}/${SRC_DISTINCT} tgt=${TGT_ROWS}/${TGT_DISTINCT} T1=${SRC_CNT}/${TGT_CNT}"
    fi

    if cmp -s "${SRC_KEYS}" "${TGT_KEYS}"; then
      LINES+=("T5_set=PASS")
    else
      fail
      LINES+=("T5_set=FAIL")
      append_file_diff T5_set "${SRC_KEYS}" "${TGT_KEYS}"
    fi
  else
    infrastructure_error
    LINES+=("T2_hash=ERROR")
    LINES+=("T3_derived=SKIP")
    LINES+=("T5_dup=SKIP reason=hash_error")
    LINES+=("T5_null=SKIP reason=hash_error")
    LINES+=("T5_stat=SKIP reason=hash_error")
    LINES+=("T5_set=SKIP reason=hash_error")
  fi
else
  LINES+=("T2_hash=SKIP reason=no_primary_key")
  LINES+=("T3_derived=SKIP")
  LINES+=("T5_dup=SKIP reason=no_primary_key")
  LINES+=("T5_null=SKIP reason=no_primary_key")
  LINES+=("T5_stat=SKIP reason=no_primary_key")
  LINES+=("T5_set=SKIP reason=no_primary_key")
fi

{
  echo "sheet=${TABLE_NAME}"
  echo "beginTm=${BEGIN_TM}"
  echo "endTm=${END_TM}"
  echo "srcTmColumn=${SRC_TM_COLUMN}"
  echo "tgtTmColumn=${TGT_TM_COLUMN}"
  echo "byDayCheck=${BY_DAY_CHECK}"
  echo "amountCheck=${AMOUNT_CHECK}"
  echo "overall=${OVERALL}"
  echo ""
  for L in "${LINES[@]}"; do
    echo "${L}"
  done
  echo ""
  echo "diff_file=verify.diff"
  echo "exit_code=${EXIT_CODE}"
} > "${RESULT}"

echo "${TABLE_NAME} ${BEGIN_TM} ${END_TM} overall=${OVERALL} exit=${EXIT_CODE}"
exit "${EXIT_CODE}"

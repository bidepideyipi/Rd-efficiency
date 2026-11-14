#!/bin/bash
# 单表单时间窗：执行 test/{Sheet} 下成对 SQL，比对源/目标结果，写 verify.result。
# 用法：./test_by_map.sh <beginTm> <endTm> <tmColumn> <Sheet名>
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF="${SCRIPT_DIR}/test-job.conf"

BEGIN_TM="${1:?beginTm required}"
END_TM="${2:?endTm required}"
TM_COLUMN="${3:?tmColumn required}"
TABLE_NAME="${4:?sheet/table name required}"
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
    tables) break ;;
  esac
done < "${CONF}"

if [[ -z "${TEST_SQL_DIR}" || -z "${TEST_OUT_DIR}" || -z "${CONNECT}" || -z "${TGT_CONNECT}" ]]; then
  echo "testSqlDir/testOutDir/connect/tgtConnect required in ${CONF}" >&2
  exit 2
fi
if [[ ! "${TM_COLUMN}" =~ ^[A-Za-z][A-Za-z0-9_#$]*$ ]]; then
  echo "invalid tmColumn: ${TM_COLUMN}" >&2
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

AMOUNT_CHECK=false
if [[ -f "${SHEET_DIR}/_meta.json" ]] && grep -q '"amountCheck": true' "${SHEET_DIR}/_meta.json"; then
  AMOUNT_CHECK=true
fi

run_sql() {
  local side="$1" sql_file="$2" out_dat="$3"
  local connect="${CONNECT}"
  [[ "${side}" == "tgt" ]] && connect="${TGT_CONNECT}"
  local run_sql="${SLICE_DIR}/_run_${side}_$(basename "${sql_file}")"
  sed -e "s/{{beginTm}}/${BEGIN_TM}/g" \
      -e "s/{{endTm}}/${END_TM}/g" \
      -e "s/{{tmColumn}}/${TM_COLUMN}/g" \
      "${sql_file}" > "${run_sql}"
  # shellcheck disable=SC2086
  if ! sqlplus -s ${connect} <<EOF >"${RUN_LOG}.tmp" 2>&1
SET ECHO OFF
SET FEEDBACK OFF
SET HEADING OFF
SET PAGESIZE 0
SET LINESIZE 32767
SET TRIMSPOOL ON
SET TRIMOUT ON
SET COLSEP ''
SPOOL ${out_dat}
@${run_sql}
SPOOL OFF
EXIT
EOF
  then
    cat "${RUN_LOG}.tmp" >> "${RUN_LOG}"
    echo "sqlplus failed: ${side} ${sql_file}" >&2
    return 2
  fi
  cat "${RUN_LOG}.tmp" >> "${RUN_LOG}" || true
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
    return 0
  fi
  run_sql src "${src}" "${SLICE_DIR}/${base}.src.dat" || return 2
  run_sql tgt "${tgt}" "${SLICE_DIR}/${base}.tgt.dat" || return 2
}

OVERALL=PASS
EXIT_CODE=0
declare -a LINES=()

fail() {
  OVERALL=FAIL
  EXIT_CODE=1
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
  EXIT_CODE=2
  OVERALL=FAIL
  LINES+=("T1_total=ERROR")
fi

# --- T1 byday ---
T1_BYDAY=SKIP
if [[ -f "${SHEET_DIR}/01_count_byday_src.sql" ]]; then
  if pair_run 01_count_byday; then
    # 简化：整文件 cmp；失败再列前几行
    if cmp -s "${SLICE_DIR}/01_count_byday.src.dat" "${SLICE_DIR}/01_count_byday.tgt.dat"; then
      T1_BYDAY=PASS
    else
      T1_BYDAY=FAIL
      fail
      _diff_rows=()
      while IFS= read -r _r; do _diff_rows+=("${_r}"); done < <(diff -u "${SLICE_DIR}/01_count_byday.src.dat" "${SLICE_DIR}/01_count_byday.tgt.dat" | head -n "${MAX_DIFF_LINES}" || true)
      append_diff T1_byday "${_diff_rows[@]}"
    fi
    LINES+=("T1_byday=${T1_BYDAY}")
  else
    EXIT_CODE=2
    OVERALL=FAIL
    LINES+=("T1_byday=ERROR")
  fi
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
T2=SKIP
T3=SKIP
if [[ -f "${SHEET_DIR}/02_hash_src.sql" ]]; then
  if pair_run 02_hash; then
    if cmp -s "${SLICE_DIR}/02_hash.src.dat" "${SLICE_DIR}/02_hash.tgt.dat"; then
      T2=PASS
      T3=PASS
      LINES+=("T2_hash=PASS")
      LINES+=("T3_derived=PASS reason=T2_PASS")
    else
      T2=FAIL
      T3=SKIP
      fail
      _diff_rows=()
      while IFS= read -r _r; do _diff_rows+=("${_r}"); done < <(diff -u "${SLICE_DIR}/02_hash.src.dat" "${SLICE_DIR}/02_hash.tgt.dat" | head -n "${MAX_DIFF_LINES}" || true)
      append_diff T2_hash "${_diff_rows[@]}"
      LINES+=("T2_hash=FAIL")
      LINES+=("T3_derived=SKIP")
    fi
  else
    EXIT_CODE=2
    OVERALL=FAIL
    LINES+=("T2_hash=ERROR")
    LINES+=("T3_derived=SKIP")
  fi
else
  LINES+=("T2_hash=SKIP")
  LINES+=("T3_derived=SKIP")
fi

# --- T5 ---
check_empty_dup() {
  local base="$1" label="$2"
  if [[ ! -f "${SHEET_DIR}/${base}_src.sql" ]]; then
    LINES+=("${label}=SKIP")
    return 0
  fi
  if ! pair_run "${base}"; then
    EXIT_CODE=2
    OVERALL=FAIL
    LINES+=("${label}=ERROR")
    return 0
  fi
  local sc tc
  sc="$(wc -l < "${SLICE_DIR}/${base}.src.dat" | tr -d ' ')"
  tc="$(wc -l < "${SLICE_DIR}/${base}.tgt.dat" | tr -d ' ')"
  if [[ "${sc}" == 0 && "${tc}" == 0 ]]; then
    LINES+=("${label}=PASS")
  else
    fail
    LINES+=("${label}=FAIL src_rows=${sc} tgt_rows=${tc}")
    append_diff "${label}" "src_rows=${sc} tgt_rows=${tc}"
  fi
}

check_empty_dup 05_pk_dup T5_dup

if [[ -f "${SHEET_DIR}/05_pk_null_src.sql" ]]; then
  if pair_run 05_pk_null; then
    SN="$(head -n1 "${SLICE_DIR}/05_pk_null.src.dat" 2>/dev/null || echo 0)"
    TN="$(head -n1 "${SLICE_DIR}/05_pk_null.tgt.dat" 2>/dev/null || echo 0)"
    [[ -z "${SN}" ]] && SN=0
    [[ -z "${TN}" ]] && TN=0
    if [[ "${SN}" == 0 && "${TN}" == 0 ]]; then
      LINES+=("T5_null=PASS")
    else
      fail
      LINES+=("T5_null=FAIL src=${SN} tgt=${TN}")
      append_diff T5_null "null_pk_cnt src=${SN} tgt=${TN}"
    fi
  else
    EXIT_CODE=2
    OVERALL=FAIL
    LINES+=("T5_null=ERROR")
  fi
fi

if [[ -f "${SHEET_DIR}/05_pk_stat_src.sql" ]]; then
  if pair_run 05_pk_stat; then
    SS="$(head -n1 "${SLICE_DIR}/05_pk_stat.src.dat" || true)"
    TS="$(head -n1 "${SLICE_DIR}/05_pk_stat.tgt.dat" || true)"
    SC="${SS%%$'\x01'*}"
    SD="${SS#*$'\x01'}"
    TC="${TS%%$'\x01'*}"
    TD="${TS#*$'\x01'}"
    if [[ "${SC}" == "${SD}" && "${TC}" == "${TD}" && "${SC}" == "${TC}" ]]; then
      LINES+=("T5_stat=PASS cnt=${SC} dist=${SD}")
    else
      fail
      LINES+=("T5_stat=FAIL src=${SS} tgt=${TS}")
      append_diff T5_stat "src=${SS} tgt=${TS}"
    fi
  else
    EXIT_CODE=2
    OVERALL=FAIL
    LINES+=("T5_stat=ERROR")
  fi
fi

if [[ -f "${SHEET_DIR}/05_pk_set_src.sql" ]]; then
  if pair_run 05_pk_set; then
    if cmp -s "${SLICE_DIR}/05_pk_set.src.dat" "${SLICE_DIR}/05_pk_set.tgt.dat"; then
      LINES+=("T5_set=PASS")
    else
      fail
      LINES+=("T5_set=FAIL")
      _diff_rows=()
      while IFS= read -r _r; do _diff_rows+=("${_r}"); done < <(diff -u "${SLICE_DIR}/05_pk_set.src.dat" "${SLICE_DIR}/05_pk_set.tgt.dat" | head -n "${MAX_DIFF_LINES}" || true)
      append_diff T5_set "${_diff_rows[@]}"
    fi
  else
    EXIT_CODE=2
    OVERALL=FAIL
    LINES+=("T5_set=ERROR")
  fi
fi

{
  echo "sheet=${TABLE_NAME}"
  echo "beginTm=${BEGIN_TM}"
  echo "endTm=${END_TM}"
  echo "tmColumn=${TM_COLUMN}"
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

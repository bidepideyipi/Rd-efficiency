#!/bin/bash
# 单表单窗：对 export_by_map.sh 产出的 .ctl 执行 sqlldr。
# 只读同目录 export-job.conf，不使用环境变量。
set -euo pipefail

BEGIN_TM="${1:?beginTm required}"
END_TM="${2:?endTm required}"
TABLE_NAME="${3:-IBPS_PAYMENTINIT}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF="${SCRIPT_DIR}/export-job.conf"

if [[ ! -f "${CONF}" ]]; then
  echo "config not found: ${CONF}" >&2
  exit 1
fi

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

conf_get_optional() {
  local key="$1"
  local default="$2"
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
    printf '%s' "${default}"
  else
    printf '%s' "${val}"
  fi
}

OUT_DIR="$(conf_get outDir)"
TGT_CONNECT="$(conf_get tgtConnect)"
SQLLDR_ERRORS="$(conf_get_optional sqlldrErrors -1)"
SQLLDR_DIRECT="$(conf_get_optional sqlldrDirect false)"

if [[ "${SQLLDR_ERRORS}" != "-1" && ! "${SQLLDR_ERRORS}" =~ ^[0-9]+$ ]]; then
  echo "invalid sqlldrErrors: ${SQLLDR_ERRORS} (need -1 or non-negative integer)" >&2
  exit 1
fi
case "${SQLLDR_DIRECT}" in
  true|false|TRUE|FALSE|yes|no|YES|NO) ;;
  *) echo "invalid sqlldrDirect: ${SQLLDR_DIRECT} (true|false)" >&2; exit 1 ;;
esac

CTL="${OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}.ctl"
DAT="${OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}.dat"
LOG="${OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}.log"
BAD="${OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}.bad"

if [[ ! -f "${CTL}" ]]; then
  echo "control file not found: ${CTL}" >&2
  exit 1
fi
if [[ ! -f "${DAT}" ]]; then
  echo "data file not found: ${DAT}" >&2
  exit 1
fi

SQLLDR_ERRORS_ARG="${SQLLDR_ERRORS}"
if [[ "${SQLLDR_ERRORS}" == "-1" ]]; then
  # SQL*Loader 不支持 ERRORS=-1。新格式按 1E 1F 1E 0A 形成逻辑记录；
  # 旧格式仍以物理行计数。将记录数用作错误上限，确保全部拒绝也处理到 EOF。
  if grep -Fq "\"STR X'1E1F1E0A'\"" "${CTL}"; then
    if [[ -s "${DAT}" ]]; then
      TRAILER_HEX="$(tail -c 4 "${DAT}" | od -An -tx1 | tr -d '[:space:]')"
      if [[ "${TRAILER_HEX}" != "1e1f1e0a" ]]; then
        echo "data file does not end with record terminator X'1E1F1E0A': ${DAT}" >&2
        exit 1
      fi
    fi
    RECORD_MARKER=$'\036\037\036'
    SQLLDR_ERRORS_ARG="$(
      { LC_ALL=C grep -aFo "${RECORD_MARKER}" "${DAT}" || true; } \
        | LC_ALL=C wc -l | tr -d '[:space:]'
    )"
  else
    SQLLDR_ERRORS_ARG="$(LC_ALL=C awk 'END { print NR + 0 }' "${DAT}")"
  fi
  if [[ ! "${SQLLDR_ERRORS_ARG}" =~ ^[0-9]+$ || "${SQLLDR_ERRORS_ARG}" -gt 2147483647 ]]; then
    echo "data record count is outside supported sqlldr ERRORS range: ${SQLLDR_ERRORS_ARG}" >&2
    exit 1
  fi
fi

if ! command -v sqlldr >/dev/null 2>&1; then
  echo "sqlldr not found in PATH" >&2
  exit 1
fi

DIRECT_ARG="false"
case "${SQLLDR_DIRECT}" in
  true|TRUE|yes|YES) DIRECT_ARG="true" ;;
esac

SQLLDR_STDOUT="$(mktemp)"
cleanup() {
  rm -f "${SQLLDR_STDOUT}"
}
trap cleanup EXIT

print_limited_file() {
  local file="$1"
  local limit="$2"
  awk -v limit="${limit}" '
    NR <= limit { print }
    END {
      if (NR > limit) {
        printf "... (%d more lines omitted)\n", NR - limit
      }
    }
  ' "${file}"
}

# 避免上一次运行遗留的 bad 文件被误认为本次拒绝记录。
rm -f "${BAD}"

echo "loading: ${TABLE_NAME} ${BEGIN_TM} ${END_TM}"
if [[ "${SQLLDR_ERRORS}" == "-1" ]]; then
  echo "sqlldrErrors=-1: process to EOF (records=${SQLLDR_ERRORS_ARG})"
fi
set +e
sqlldr userid="${TGT_CONNECT}" \
  control="${CTL}" \
  log="${LOG}" \
  bad="${BAD}" \
  errors="${SQLLDR_ERRORS_ARG}" \
  direct="${DIRECT_ARG}" >"${SQLLDR_STDOUT}" 2>&1
SQLLDR_RC=$?
set -e

if [[ -s "${SQLLDR_STDOUT}" ]]; then
  print_limited_file "${SQLLDR_STDOUT}" 200
fi

# -1 模式下，SQL*Loader 仍会把 ORA-00001 计入 rejected 并返回 EX_WARN(2)。
# 只有“所有 data errors 均且仅为 ORA-00001”时将其视为幂等重复；其他错误仍失败。
DUPLICATE_ONLY=0
if [[ "${SQLLDR_ERRORS}" == "-1" && "${DIRECT_ARG}" == "false" \
      && "${SQLLDR_RC}" -eq 2 && -f "${LOG}" ]]; then
  REJECTED_RECORDS="$(awk '
    /Rows? not loaded due to data errors/ { print $1; found = 1; exit }
    END { if (!found) print 0 }
  ' "${LOG}")"
  DUPLICATE_ERRORS="$(awk '/ORA-00001/ { n++ } END { print n + 0 }' "${LOG}")"
  OTHER_ORA_ERRORS="$(grep -Eo 'ORA-[0-9]{5}' "${LOG}" 2>/dev/null \
    | grep -vc '^ORA-00001$' || true)"
  OTHER_NOT_LOADED="$(awk '
    /Rows? not loaded/ && $0 !~ /due to data errors/ { n += $1 }
    END { print n + 0 }
  ' "${LOG}")"

  if [[ "${REJECTED_RECORDS}" =~ ^[0-9]+$ \
        && "${REJECTED_RECORDS}" -gt 0 \
        && "${REJECTED_RECORDS}" -eq "${DUPLICATE_ERRORS}" \
        && "${OTHER_ORA_ERRORS}" -eq 0 \
        && "${OTHER_NOT_LOADED}" -eq 0 ]]; then
    DUPLICATE_ONLY=1
  fi
fi

if [[ "${DUPLICATE_ONLY}" -eq 1 ]]; then
  echo "loaded with duplicate primary/unique keys ignored: ${REJECTED_RECORDS}"
  echo "bad:     ${BAD}"
  echo "log:     ${LOG}"
  exit 0
fi

if [[ "${SQLLDR_RC}" -ne 0 ]]; then
  echo "sqlldr failed: ${TABLE_NAME} ${BEGIN_TM} ${END_TM} rc=${SQLLDR_RC}" >&2
  if [[ -f "${LOG}" ]]; then
    echo "sqlldr log (${LOG}):" >&2
    # 优先错误相关行；再附尾部上下文（含 rejected / ORA 摘要）
    grep -E 'ORA-[0-9]+|SP2-[0-9]+|SQL\*Loader-|Error |ERROR |rejected|Rejected|not loaded|CRITICAL' \
      "${LOG}" 2>/dev/null | awk '
        NR <= 100 { print }
        END { if (NR > 100) printf "... (%d more matching lines omitted)\n", NR - 100 }
      ' >&2 || true
    echo "---- sqlldr log tail ----" >&2
    tail -n 80 "${LOG}" >&2 || true
  else
    echo "sqlldr log missing: ${LOG}" >&2
  fi
  if [[ -f "${BAD}" ]]; then
    echo "bad file: ${BAD} (first 20 lines)" >&2
    head -n 20 "${BAD}" >&2 || true
  fi
  exit "${SQLLDR_RC}"
fi

echo "loaded:  ${CTL}"
echo "log:     ${LOG}"

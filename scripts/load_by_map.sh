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
SQLLDR_ERRORS="$(conf_get_optional sqlldrErrors 100)"
SQLLDR_ROWS="$(conf_get_optional sqlldrRows 5000)"
SQLLDR_DIRECT="$(conf_get_optional sqlldrDirect false)"

if [[ ! "${SQLLDR_ERRORS}" =~ ^[0-9]+$ ]]; then
  echo "invalid sqlldrErrors: ${SQLLDR_ERRORS}" >&2
  exit 1
fi
if [[ ! "${SQLLDR_ROWS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "invalid sqlldrRows: ${SQLLDR_ROWS}" >&2
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

if ! command -v sqlldr >/dev/null 2>&1; then
  echo "sqlldr not found in PATH" >&2
  exit 1
fi

DIRECT_ARG="false"
case "${SQLLDR_DIRECT}" in
  true|TRUE|yes|YES) DIRECT_ARG="true" ;;
esac

echo "loading: ${TABLE_NAME} ${BEGIN_TM} ${END_TM}"
sqlldr userid="${TGT_CONNECT}" \
  control="${CTL}" \
  log="${LOG}" \
  bad="${BAD}" \
  errors="${SQLLDR_ERRORS}" \
  rows="${SQLLDR_ROWS}" \
  direct="${DIRECT_ARG}"

echo "loaded:  ${CTL}"
echo "log:     ${LOG}"

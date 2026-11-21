#!/bin/bash
# 单表单窗：替换占位符 → sqlplus SPOOL 无表头、0x01 字段分隔、
# X'1E1F1E0A' 记录分隔的 .dat → 生成 sqlldr 控制文件 .ctl。
# 只读同目录 export-job.conf，不使用环境变量。
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

# 可选键；缺省或空时用默认值。
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

# 读取生成器写入的 "-- exportColumns: A,B,C"，用于校验实际 SELECT 或解析失败时回退。
read_export_columns_header() {
  local sql_file="$1"
  awk '
    /^--[[:space:]]*exportColumns:/ {
      sub(/^--[[:space:]]*exportColumns:[[:space:]]*/, "")
      gsub(/[[:space:]]+$/, "")
      print
      exit
    }
  ' "${sql_file}"
}

# 读取目标 NUMBER 字段列表；这些字段导出时使用固定小数点和显式 TO_CHAR 格式。
read_export_number_columns_header() {
  local sql_file="$1"
  awk '
    /^--[[:space:]]*exportNumberColumns:/ {
      sub(/^--[[:space:]]*exportNumberColumns:[[:space:]]*/, "")
      gsub(/[[:space:]]+$/, "")
      print
      exit
    }
  ' "${sql_file}"
}

# 与 Java SheetNames.resolveTableName 一致：去掉 Sheet 末位一位数字 → 物理表名。
resolve_table_name() {
  local n="$1"
  if [[ "${#n}" -gt 1 && "${n}" =~ [0-9]$ ]]; then
    printf '%s' "${n:0:$((${#n} - 1))}"
  else
    printf '%s' "${n}"
  fi
}

# 优先读 SQL 头 -- targetTable:；否则对文件名（Sheet）做 resolve。
extract_target_table() {
  local sql_file="$1"
  local stem
  stem="$(basename "${sql_file}" .sql)"
  local from_sql
  from_sql="$(awk '
    /^--[[:space:]]*targetTable:/ {
      sub(/^--[[:space:]]*targetTable:[[:space:]]*/, "")
      gsub(/[[:space:]]+$/, "")
      print
      exit
    }
  ' "${sql_file}")"
  if [[ -n "${from_sql}" ]]; then
    printf '%s' "${from_sql}"
  else
    resolve_table_name "${stem}"
  fi
}

SQL_DIR="$(conf_get sqlDir)"
OUT_DIR="$(conf_get outDir)"
CONNECT="$(conf_get connect)"
# sqlldr 数据文件字符集；本项目跳板机导出为 GBK，对应 Oracle 名称 ZHS16GBK。
CTL_CHARSET="$(conf_get_optional ctlCharset ZHS16GBK)"
# APPEND | TRUNCATE | REPLACE | INSERT（sqlldr 装载方式）
LOAD_MODE="$(conf_get_optional loadMode APPEND)"

case "${LOAD_MODE}" in
  APPEND|TRUNCATE|REPLACE|INSERT) ;;
  *) echo "invalid loadMode: ${LOAD_MODE} (APPEND|TRUNCATE|REPLACE|INSERT)" >&2; exit 1 ;;
esac

# TABLE_NAME = Sheet/sql 文件名（可含末位编号）；物理装载表名见 TARGET_TABLE。
TPL="${SQL_DIR}/${TABLE_NAME}.sql"
EXPORT_RENDERER="${SCRIPT_DIR}/render_export_sql.awk"
RUN_SQL="/tmp/${TABLE_NAME}_run.sql"
EXPORT_SQL="/tmp/${TABLE_NAME}_export.sql"
INNER_SQL="/tmp/${TABLE_NAME}_inner.sql"
EXPORT_COLUMNS_FILE="$(mktemp "/tmp/${TABLE_NAME}_export_columns.XXXXXX")"
trap 'rm -f "${EXPORT_COLUMNS_FILE}"' EXIT
# 产物文件名保留 Sheet 名，避免 HVPS_EXTPAYMENT1/2 等同物理表互相覆盖。
OUT_DAT="${OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}.dat"
OUT_CTL="${OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}.ctl"
OUT_LOG="${OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}.log"
OUT_BAD="${OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}.bad"

# 任意源表时间列均可；仅校验为可嵌入 s.{{tmColumn}} 的标识符。
if [[ ! "${TM_COLUMN}" =~ ^[A-Za-z][A-Za-z0-9_#$]*$ ]]; then
  echo "invalid tmColumn: ${TM_COLUMN} (need Oracle identifier)" >&2
  exit 1
fi

if [[ ! -f "${TPL}" ]]; then
  echo "SQL template not found: ${TPL}" >&2
  exit 1
fi
if [[ ! -f "${EXPORT_RENDERER}" ]]; then
  echo "export SQL renderer not found: ${EXPORT_RENDERER}" >&2
  exit 1
fi

TARGET_TABLE="$(extract_target_table "${TPL}")"
if [[ ! "${TARGET_TABLE}" =~ ^[A-Za-z][A-Za-z0-9_#$]*$ ]]; then
  echo "invalid targetTable: ${TARGET_TABLE}" >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"

sed -e "s/{{beginTm}}/${BEGIN_TM}/g" \
    -e "s/{{endTm}}/${END_TM}/g" \
    -e "s/{{tmColumn}}/${TM_COLUMN}/g" \
    "${TPL}" > "${RUN_SQL}"

# 去掉末尾分号与空行，作为子查询体。
# SQL*Plus 默认空行即结束语句。
sed -e 's/;[[:space:]]*$//' -e '/^[[:space:]]*$/d' "${RUN_SQL}" > "${INNER_SQL}"

if ! grep -Eq '^--[[:space:]]*exportNumberColumns:' "${TPL}"; then
  echo "missing -- exportNumberColumns header in ${TPL}; regenerate map SQL with the current generator" >&2
  exit 1
fi

NUMBER_COLUMNS=()
EXPORT_NUM_COLS_HDR="$(read_export_number_columns_header "${TPL}")"
if [[ -n "${EXPORT_NUM_COLS_HDR}" ]]; then
  IFS=',' read -r -a _num_col_arr <<< "${EXPORT_NUM_COLS_HDR}"
  for col in "${_num_col_arr[@]}"; do
    col="$(printf '%s' "${col}" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    if [[ ! "${col}" =~ ^[A-Za-z][A-Za-z0-9_#$]*$ ]]; then
      echo "invalid exportNumberColumns entry in ${TPL}: ${col}" >&2
      exit 1
    fi
    NUMBER_COLUMNS+=("${col}")
  done
fi
NUMBER_COLUMNS_CSV="$(IFS=','; printf '%s' "${NUMBER_COLUMNS[*]}")"

# 直接改写原 SELECT 的顶层表达式，不增加外层查询，也不预声明列数。
: > "${EXPORT_COLUMNS_FILE}"
if ! awk -v columns_file="${EXPORT_COLUMNS_FILE}" \
    -v number_columns_csv="${NUMBER_COLUMNS_CSV}" \
    -f "${EXPORT_RENDERER}" "${INNER_SQL}" > "${EXPORT_SQL}"; then
  echo "failed to render direct export SQL from ${TPL}" >&2
  echo "innerSql: ${INNER_SQL}" >&2
  exit 1
fi

HEADER_COLUMNS=()
EXPORT_COLS_HDR="$(read_export_columns_header "${TPL}")"
if [[ -n "${EXPORT_COLS_HDR}" ]]; then
  IFS=',' read -r -a _col_arr <<< "${EXPORT_COLS_HDR}"
  for col in "${_col_arr[@]}"; do
    col="$(printf '%s' "${col}" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [[ -n "${col}" ]] && HEADER_COLUMNS+=("${col}")
  done
fi

COLUMNS=()
while IFS= read -r col; do
  [[ -n "${col}" ]] && COLUMNS+=("${col}")
done < "${EXPORT_COLUMNS_FILE}"

if [[ ${#COLUMNS[@]} -eq 0 ]]; then
  echo "no top-level AS columns found in ${TPL}" >&2
  exit 1
fi

if [[ ${#HEADER_COLUMNS[@]} -gt 0
      && "${HEADER_COLUMNS[*]}" != "${COLUMNS[*]}" ]]; then
  echo "warning: exportColumns header does not match actual SELECT aliases in ${TPL}; using SELECT aliases" >&2
  echo "  header (${#HEADER_COLUMNS[@]}): ${HEADER_COLUMNS[*]}" >&2
  echo "  select (${#COLUMNS[@]}): ${COLUMNS[*]}" >&2
fi

for number_col in "${NUMBER_COLUMNS[@]}"; do
  number_col_found=0
  for col in "${COLUMNS[@]}"; do
    if [[ "${number_col}" == "${col}" ]]; then
      number_col_found=1
      break
    fi
  done
  if [[ "${number_col_found}" -ne 1 ]]; then
    echo "exportNumberColumns entry is not an actual SELECT alias in ${TPL}: ${number_col}" >&2
    exit 1
  fi
done

for col in "${COLUMNS[@]}"; do
  if [[ ! "${col}" =~ ^[A-Za-z][A-Za-z0-9_#$]*$ ]]; then
    echo "invalid export column in ${TPL}: ${col} (need Oracle identifier)" >&2
    exit 1
  fi
done

# TERMOUT OFF：正常行只进 SPOOL（.dat）；ORA/SP2 也进 .dat，终端常为空。
# 把 sqlplus stdout+stderr 落到 .log；失败时再从 .dat 抽出 ORA/SP2。
# 先清掉旧 .dat，避免上次失败残留的 ORA 文本误导本次报错。
: > "${OUT_LOG}"
rm -f "${OUT_DAT}"
set +e
sqlplus -s "${CONNECT}" <<EOF >>"${OUT_LOG}" 2>&1
SET ECHO OFF
SET FEEDBACK OFF
SET HEADING OFF
SET PAGESIZE 0
SET LINESIZE 32767
SET RECSEP OFF
SET LONG 1000000
SET LONGCHUNKSIZE 1000000
SET TRIMSPOOL ON
SET TRIMOUT ON
SET TERMOUT OFF
SET VERIFY OFF
SET NULL ''
SET SQLBLANKLINES ON
WHENEVER SQLERROR EXIT FAILURE
SPOOL ${OUT_DAT}
@${EXPORT_SQL}
SPOOL OFF
EXIT
EOF
SQLPLUS_RC=$?
set -e

if [[ "${SQLPLUS_RC}" -ne 0 ]]; then
  # 先读出 sqlplus 捕获内容，再写 .log，避免 tee 截断同一文件导致内容丢失
  SQLPLUS_CAPTURE=""
  if [[ -s "${OUT_LOG}" ]]; then
    SQLPLUS_CAPTURE="$(cat "${OUT_LOG}")"
  fi
  SPOOL_ERR=""
  if [[ -f "${OUT_DAT}" ]]; then
    # 不锚行首：SPOOL 里 ORA 前可能有空格/回车
    SPOOL_ERR="$(grep -E 'ORA-[0-9]{5}|SP2-[0-9]{4}|ERROR at line' "${OUT_DAT}" 2>/dev/null | head -n 50 || true)"
  fi
  {
    echo "sqlplus export failed: ${TABLE_NAME} ${BEGIN_TM} ${END_TM} rc=${SQLPLUS_RC}"
    echo "exportSql: ${EXPORT_SQL}"
    echo "spool: ${OUT_DAT}"
    echo "extracted columns (${#COLUMNS[@]}): ${COLUMNS[*]}"
    echo "export mode: direct SELECT rewrite (no outer query)"
    if [[ -n "${SQLPLUS_CAPTURE}" ]]; then
      echo "---- sqlplus stdout/stderr ----"
      printf '%s\n' "${SQLPLUS_CAPTURE}"
    else
      echo "---- sqlplus stdout/stderr: (empty) ----"
    fi
    if [[ -n "${SPOOL_ERR}" ]]; then
      echo "---- sqlplus errors from spool ----"
      printf '%s\n' "${SPOOL_ERR}"
    elif [[ -f "${OUT_DAT}" ]]; then
      echo "---- spool file tail (no ORA/SP2 matched) ----"
      tail -n 80 "${OUT_DAT}" 2>/dev/null || true
    else
      echo "spool file missing: ${OUT_DAT}"
    fi
  } | tee "${OUT_LOG}" >&2
  exit "${SQLPLUS_RC}"
fi

# 生成 sqlldr 控制文件：列序 = 映射 AS 序；空串按 NULL。
# INTO TABLE 用物理表名（Sheet 去末位数字 / SQL 头 targetTable），与对比逻辑一致。
{
  echo "-- generated by export_by_map.sh"
  echo "-- sheet: ${TABLE_NAME}"
  echo "-- targetTable: ${TARGET_TABLE}"
  echo "LOAD DATA"
  echo "CHARACTERSET ${CTL_CHARSET}"
  # 每条 SELECT 输出末尾为 1E 1F 1E，SQL*Plus 再补 LF，组成逻辑记录结束符。
  # 这样字段值中的普通 CR/LF 会保留为字段内容，不会拆成新记录。
  echo "INFILE '${OUT_DAT}' \"STR X'1E1F1E0A'\""
  echo "BADFILE '${OUT_BAD}'"
  echo "DISCARDFILE '${OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}.dsc'"
  echo "${LOAD_MODE} INTO TABLE ${TARGET_TABLE}"
  echo "FIELDS TERMINATED BY X'01'"
  echo "TRAILING NULLCOLS"
  echo "("
  last_idx=$((${#COLUMNS[@]} - 1))
  for i in "${!COLUMNS[@]}"; do
    col="${COLUMNS[$i]}"
    if [[ "${i}" -eq "${last_idx}" ]]; then
      echo "  ${col} CHAR(4000) NULLIF ${col}=BLANKS"
    else
      echo "  ${col} CHAR(4000) NULLIF ${col}=BLANKS,"
    fi
  done
  echo ")"
} > "${OUT_CTL}"

echo "exported: ${OUT_DAT}"
echo "control:  ${OUT_CTL}"
echo "targetTable: ${TARGET_TABLE}"
echo "load tip: sqlldr user/pass@TGT control=${OUT_CTL} log=${OUT_LOG}"

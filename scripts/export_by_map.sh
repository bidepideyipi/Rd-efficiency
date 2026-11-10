#!/bin/bash
# 单表单窗：替换占位符 → sqlplus SPOOL 无表头 0x01 .dat → 生成 sqlldr 控制文件 .ctl。
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

# 从 map SQL 提取 ") AS COL" 目标列名，顺序即 SELECT / .dat / .ctl 列序。
extract_columns() {
  local sql_file="$1"
  awk '
    /^[[:space:]]*--/ { next }
    {
      line = $0
      while (match(line, /\) AS [A-Za-z_][A-Za-z0-9_]*/)) {
        token = substr(line, RSTART, RLENGTH)
        sub(/^\) AS /, "", token)
        print token
        line = substr(line, RSTART + RLENGTH)
      }
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
# sqlldr 控制文件字符集；按目标库常用 AL32UTF8，可在 conf 覆盖。
CTL_CHARSET="$(conf_get_optional ctlCharset AL32UTF8)"
# APPEND | TRUNCATE | REPLACE | INSERT（sqlldr 装载方式）
LOAD_MODE="$(conf_get_optional loadMode APPEND)"

case "${LOAD_MODE}" in
  APPEND|TRUNCATE|REPLACE|INSERT) ;;
  *) echo "invalid loadMode: ${LOAD_MODE} (APPEND|TRUNCATE|REPLACE|INSERT)" >&2; exit 1 ;;
esac

# TABLE_NAME = Sheet/sql 文件名（可含末位编号）；物理装载表名见 TARGET_TABLE。
TPL="${SQL_DIR}/${TABLE_NAME}.sql"
RUN_SQL="/tmp/${TABLE_NAME}_run.sql"
EXPORT_SQL="/tmp/${TABLE_NAME}_export.sql"
INNER_SQL="/tmp/${TABLE_NAME}_inner.sql"
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
# SQL*Plus 默认空行即结束语句，否则会报 SP2-0042 unknown command ") q"。
sed -e 's/;[[:space:]]*$//' -e '/^[[:space:]]*$/d' "${RUN_SQL}" > "${INNER_SQL}"

COLUMNS=()
while IFS= read -r col; do
  [[ -n "${col}" ]] && COLUMNS+=("${col}")
done < <(extract_columns "${RUN_SQL}")

if [[ ${#COLUMNS[@]} -eq 0 ]]; then
  echo "no AS columns found in ${TPL}" >&2
  exit 1
fi

# 拼成单列：NVL(TO_CHAR(q.COL), '') || CHR(1) || ...，避免 NULL 传导与 COLSEP 垫空格。
CONCAT_EXPR=""
i=0
for col in "${COLUMNS[@]}"; do
  piece="NVL(TO_CHAR(q.${col}), '')"
  if [[ "${i}" -eq 0 ]]; then
    CONCAT_EXPR="${piece}"
  else
    CONCAT_EXPR="${CONCAT_EXPR} || CHR(1) || ${piece}"
  fi
  i=$((i + 1))
done

{
  echo "SELECT ${CONCAT_EXPR}"
  echo "FROM ("
  cat "${INNER_SQL}"
  echo ") q"
  echo ";"
} > "${EXPORT_SQL}"

sqlplus -s "${CONNECT}" <<EOF
SET ECHO OFF
SET FEEDBACK OFF
SET HEADING OFF
SET PAGESIZE 0
SET LINESIZE 32767
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

# 生成 sqlldr 控制文件：列序 = 映射 AS 序；空串按 NULL。
# INTO TABLE 用物理表名（Sheet 去末位数字 / SQL 头 targetTable），与对比逻辑一致。
{
  echo "-- generated by export_by_map.sh"
  echo "-- sheet: ${TABLE_NAME}"
  echo "-- targetTable: ${TARGET_TABLE}"
  echo "LOAD DATA"
  echo "CHARACTERSET ${CTL_CHARSET}"
  echo "INFILE '${OUT_DAT}'"
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

#!/bin/bash
set -euo pipefail

BEGIN_TM="${1:?beginTm required}"
END_TM="${2:?endTm required}"
TM_COLUMN="${3:-TRANDATE}"
TABLE_NAME="${4:-IBPS_PAYMENTINIT}"
EXPORT_DIR="${EXPORT_DIR:-$HOME/mig/sql/map}"

TPL="${EXPORT_DIR}/${TABLE_NAME}.sql"
RUN_SQL="/tmp/${TABLE_NAME}_run.sql"
OUT_DIR="${OUT_DIR:-$HOME/mig/out}"
OUT="${OUT_DIR}/${TABLE_NAME}_${BEGIN_TM}_${END_TM}.csv"

case "${TM_COLUMN}" in
  TRANDATE|TRANTIME|WORKDATE|SENDTIME) ;;
  *) echo "invalid tmColumn: ${TM_COLUMN}" >&2; exit 1 ;;
esac

if [[ ! -f "${TPL}" ]]; then
  echo "SQL template not found: ${TPL}" >&2
  exit 1
fi

mkdir -p "${OUT_DIR}"

sed -e "s/{{beginTm}}/${BEGIN_TM}/g" \
    -e "s/{{endTm}}/${END_TM}/g" \
    -e "s/{{tmColumn}}/${TM_COLUMN}/g" \
    "${TPL}" > "${RUN_SQL}"

sql -s "${ORACLE_USER_PASS:?set ORACLE_USER_PASS=user/pass@SERVICE}" <<EOF
SET SQLFORMAT csv
SET HEADING ON
WHENEVER SQLERROR EXIT FAILURE
SPOOL ${OUT}
@${RUN_SQL}
SPOOL OFF
EXIT
EOF

echo "exported: ${OUT}"

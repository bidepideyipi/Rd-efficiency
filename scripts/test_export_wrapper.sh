#!/bin/bash
# 回归测试：导出 SQL 直接改写原表达式，不增加外层查询。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMP_DIR="$(mktemp -d)"
TABLE_NAME="ZZ_EXPORT_WRAPPER_TEST_$$"
EXPORT_SQL="${TMP_DIR}/captured-export.sql"
cleanup() {
  rc=$?
  trap - EXIT
  if [[ "${rc}" -ne 0 ]]; then
    [[ -f "${EXPORT_SQL}" ]] && cat "${EXPORT_SQL}" >&2
    [[ -f "${TMP_DIR}/stderr.log" ]] && cat "${TMP_DIR}/stderr.log" >&2
  fi
  rm -rf "${TMP_DIR}"
  exit "${rc}"
}
trap cleanup EXIT

mkdir -p "${TMP_DIR}/bin" "${TMP_DIR}/sql" "${TMP_DIR}/out"
cp "${ROOT_DIR}/export_by_map.sh" "${TMP_DIR}/export_by_map.sh"
cp "${ROOT_DIR}/render_export_sql.awk" "${TMP_DIR}/render_export_sql.awk"

cat > "${TMP_DIR}/export-job.conf" <<EOF
sqlDir = ${TMP_DIR}/sql
outDir = ${TMP_DIR}/out
connect = test/test@SOURCE
tgtConnect = test/test@TARGET
EOF

cat > "${TMP_DIR}/sql/${TABLE_NAME}.sql" <<EOF
-- targetTable: ${TABLE_NAME}
-- 故意放一个过期列，验证脚本以实际 SELECT 列数为准。
-- exportColumns: TRADECODE,CRTDATE,COMPLEXCOL,STALE_COLUMN
-- exportNumberColumns: CRTDATE
SELECT
  (b.tradecode) AS TRADECODE,
  (a.transdt) AS CRTDATE,
  (CASE WHEN a.note = 'x,--('
    THEN (SELECT MAX(x.code) FROM source_x x WHERE x.id = a.id)
    ELSE 'a,b' END) AS COMPLEXCOL
FROM source_a a
LEFT JOIN source_b b ON b.id = a.id
WHERE a.transdt >= '{{beginTm}}'
  AND a.transdt < '{{endTm}}';
EOF

cat > "${TMP_DIR}/bin/sqlplus" <<'EOF'
#!/bin/bash
set -euo pipefail
capture="${FAKE_SQLPLUS_CAPTURE:?}"
cat > "${capture}"
spool="$(awk '/^SPOOL / { print substr($0, 7); exit }' "${capture}")"
export_sql="$(awk '/^@/ { print substr($0, 2); exit }' "${capture}")"
cp "${export_sql}" "${FAKE_EXPORT_SQL_CAPTURE:?}"
: > "${spool}"
EOF
chmod +x "${TMP_DIR}/bin/sqlplus" "${TMP_DIR}/export_by_map.sh"

FAKE_SQLPLUS_CAPTURE="${TMP_DIR}/sqlplus-input.sql" \
FAKE_EXPORT_SQL_CAPTURE="${EXPORT_SQL}" \
  PATH="${TMP_DIR}/bin:${PATH}" \
  "${TMP_DIR}/export_by_map.sh" 20260101 20260102 TRANSDT "${TABLE_NAME}" \
  >"${TMP_DIR}/stdout.log" 2>"${TMP_DIR}/stderr.log"

CTL="${TMP_DIR}/out/${TABLE_NAME}_20260101_20260102.ctl"

grep -q '^SET RECSEP OFF$' "${TMP_DIR}/sqlplus-input.sql"
grep -q '^WHENEVER OSERROR EXIT FAILURE$' "${TMP_DIR}/sqlplus-input.sql"
grep -q '^WHENEVER SQLERROR EXIT FAILURE$' "${TMP_DIR}/sqlplus-input.sql"
grep -q '^SELECT$' "${EXPORT_SQL}"
grep -q "NVL(TO_CHAR((b.tradecode)), '')" "${EXPORT_SQL}"
grep -Fq "TO_CHAR((a.transdt), 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''')" "${EXPORT_SQL}"
grep -Fq "'^\\.', '0.'" "${EXPORT_SQL}"
grep -Fq "'^-\\.', '-0.'" "${EXPORT_SQL}"
grep -q "AS COMPLEXCOL" "${TMP_DIR}/sql/${TABLE_NAME}.sql"
grep -q "ELSE 'a,b' END)), '')" "${EXPORT_SQL}"
grep -q '  || CHR(30) || CHR(31) || CHR(30)' "${EXPORT_SQL}"
if [[ "$(grep -c 'CHR(30)' "${EXPORT_SQL}")" -ne 1 ]]; then
  echo "custom record marker must be appended exactly once" >&2
  exit 1
fi
grep -q '^FROM source_a a$' "${EXPORT_SQL}"
if grep -q 'WITH export_rows\|C0001\|q\.TRADECODE\|q\.CRTDATE' "${EXPORT_SQL}"; then
  echo "outer wrapper leaked into direct export SQL" >&2
  exit 1
fi
grep -q '  TRADECODE CHAR(4000)' "${CTL}"
grep -q '  CRTDATE CHAR(4000)' "${CTL}"
grep -q '  COMPLEXCOL CHAR(4000)' "${CTL}"
grep -q "\"STR X'1E1F1E0A'\"" "${CTL}"
if grep -q 'STALE_COLUMN' "${CTL}"; then
  echo "stale header column leaked into control file" >&2
  exit 1
fi
grep -q 'exportColumns header does not match actual SELECT aliases' "${TMP_DIR}/stderr.log"

echo "export wrapper regression test: OK"

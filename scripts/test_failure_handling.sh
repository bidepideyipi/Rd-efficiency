#!/bin/bash
# 回归测试：测试 SQL 缺失/执行失败不能误报 PASS；continue 模式最终返回非 0。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

mkdir -p "${TMP_DIR}/bin" "${TMP_DIR}/sql/T" "${TMP_DIR}/out"
cp "${ROOT_DIR}/test_by_map.sh" "${TMP_DIR}/test_by_map.sh"
cp "${ROOT_DIR}/test_batch.sh" "${TMP_DIR}/test_batch.sh"
chmod +x "${TMP_DIR}/test_by_map.sh" "${TMP_DIR}/test_batch.sh"

cat > "${TMP_DIR}/test-job.conf" <<EOF
testSqlDir = ${TMP_DIR}/sql
testOutDir = ${TMP_DIR}/out
connect = test/test@SOURCE
tgtConnect = test/test@TARGET
start = 20260101
end = 20260201
interval = 1m
onError = continue
byDayCheck = false
tables =
  T = TRANSDT,WORKDATE
  U = WORKDATE
EOF

cat > "${TMP_DIR}/sql/T/02_hash_tgt.sql" <<'EOF'
SELECT 1 FROM target_only;
EOF

set +e
"${TMP_DIR}/test_by_map.sh" 20260101 20260201 TRANSDT WORKDATE T \
  >"${TMP_DIR}/missing.out" 2>&1
rc=$?
set -e
if [[ "${rc}" -ne 2 ]]; then
  echo "missing required SQL pair returned rc=${rc}, expected 2" >&2
  cat "${TMP_DIR}/missing.out" >&2
  exit 1
fi
grep -q 'required SQL pair missing' "${TMP_DIR}/missing.out"
grep -q '^overall=FAIL$' "${TMP_DIR}/out/T_20260101_20260201/verify.result"
grep -q '^T1_total=ERROR$' "${TMP_DIR}/out/T_20260101_20260201/verify.result"
grep -q '^T2_hash=SKIP reason=prior_error$' "${TMP_DIR}/out/T_20260101_20260201/verify.result"

cat > "${TMP_DIR}/sql/T/01_count_total_src.sql" <<'EOF'
SELECT 1 FROM missing_source;
EOF
cat > "${TMP_DIR}/sql/T/01_count_total_tgt.sql" <<'EOF'
SELECT 1 FROM missing_target;
EOF
cat > "${TMP_DIR}/bin/sqlplus" <<'EOF'
#!/bin/bash
set -euo pipefail
input="$(cat)"
if grep -q '^WHENEVER OSERROR EXIT FAILURE$' <<<"${input}" \
    && grep -q '^WHENEVER SQLERROR EXIT SQL.SQLCODE$' <<<"${input}"; then
  if [[ "${2:-}" == *@TARGET ]]; then
    echo 'ORA-00942: table or view does not exist'
    exit 1
  fi
  spool="$(awk '$1 == "SPOOL" { print $2; exit }' <<<"${input}")"
  printf '0\n' > "${spool}"
fi
exit 0
EOF
chmod +x "${TMP_DIR}/bin/sqlplus"
rm -rf "${TMP_DIR}/out/T_20260101_20260201"

set +e
PATH="${TMP_DIR}/bin:${PATH}" \
  "${TMP_DIR}/test_by_map.sh" 20260101 20260201 TRANSDT WORKDATE T \
  >"${TMP_DIR}/sql-error.out" 2>&1
rc=$?
set -e
if [[ "${rc}" -ne 2 ]]; then
  echo "SQL execution error returned rc=${rc}, expected 2" >&2
  cat "${TMP_DIR}/sql-error.out" >&2
  exit 1
fi
grep -q 'ORA-00942' "${TMP_DIR}/out/T_20260101_20260201/run.log"

# 成功查询的结果不能灌入 run.log；旧 {{tmColumn}} 也要按源/目标分别替换。
rm -f "${TMP_DIR}/sql/T/02_hash_tgt.sql"
cat > "${TMP_DIR}/sql/T/01_count_total_src.sql" <<'EOF'
SELECT COUNT(*) FROM SOURCE_TABLE s
WHERE s.{{tmColumn}} >= '{{beginTm}}' AND s.{{tmColumn}} < '{{endTm}}';
EOF
cat > "${TMP_DIR}/sql/T/01_count_total_tgt.sql" <<'EOF'
SELECT COUNT(*) FROM TARGET_TABLE t
WHERE t.{{tmColumn}} >= '{{beginTm}}' AND t.{{tmColumn}} < '{{endTm}}';
EOF
cat > "${TMP_DIR}/sql/T/02_hash_src.sql" <<'EOF'
-- rowFormat: NULL_FLAG<0x01>ROW_MD5<0x01>PK_KEY
SELECT 'source rows' FROM dual;
EOF
cat > "${TMP_DIR}/sql/T/02_hash_tgt.sql" <<'EOF'
-- rowFormat: NULL_FLAG<0x01>ROW_MD5<0x01>PK_KEY
SELECT 'target rows' FROM dual;
EOF
cat > "${TMP_DIR}/bin/sqlplus" <<'EOF'
#!/bin/bash
set -euo pipefail
input="$(cat)"
spool="$(awk '$1 == "SPOOL" { print $2; exit }' <<<"${input}")"
run_file="$(awk '/^@/ { sub(/^@/, ""); print; exit }' <<<"${input}")"
if [[ "$(basename "${run_file}")" == *02_hash* ]]; then
  printf '0\001HASH1\001PK1\n0\001HASH2\001PK2\n' > "${spool}"
else
  printf '2\n' > "${spool}"
fi
cat "${run_file}" >> "${FAKE_RUN_SQL_CAPTURE:?}"
for _ in 1 2 3 4 5; do
  echo "NOISY_QUERY_ROW"
done
EOF
chmod +x "${TMP_DIR}/bin/sqlplus"
rm -rf "${TMP_DIR}/out/T_20260101_20260201"

FAKE_RUN_SQL_CAPTURE="${TMP_DIR}/run-sql.capture" PATH="${TMP_DIR}/bin:${PATH}" \
  "${TMP_DIR}/test_by_map.sh" 20260101 20260201 TRANSDT WORKDATE T \
  >"${TMP_DIR}/success.out" 2>&1
grep -q 's.TRANSDT' "${TMP_DIR}/run-sql.capture"
grep -q 't.WORKDATE' "${TMP_DIR}/run-sql.capture"
if grep -q 'NOISY_QUERY_ROW' "${TMP_DIR}/out/T_20260101_20260201/run.log"; then
  echo "successful query output leaked into run.log" >&2
  exit 1
fi
[[ "$(grep -c 'status=PASS' "${TMP_DIR}/out/T_20260101_20260201/run.log")" -eq 4 ]]
grep -q '^T2_hash=PASS$' "${TMP_DIR}/out/T_20260101_20260201/verify.result"
grep -q '^T5_dup=PASS$' "${TMP_DIR}/out/T_20260101_20260201/verify.result"
grep -q '^T5_null=PASS$' "${TMP_DIR}/out/T_20260101_20260201/verify.result"
grep -q '^T5_stat=PASS cnt=2 dist=2$' "${TMP_DIR}/out/T_20260101_20260201/verify.result"
grep -q '^T5_set=PASS$' "${TMP_DIR}/out/T_20260101_20260201/verify.result"

cat > "${TMP_DIR}/test_by_map.sh" <<'EOF'
#!/bin/bash
printf '%s\n' "$*" >> "${FAKE_TEST_CALLS:?}"
exit 1
EOF
chmod +x "${TMP_DIR}/test_by_map.sh"

set +e
FAKE_TEST_CALLS="${TMP_DIR}/calls" "${TMP_DIR}/test_batch.sh" \
  >"${TMP_DIR}/batch.out" 2>&1
rc=$?
set -e
if [[ "${rc}" -ne 1 ]]; then
  echo "test_batch continue mode returned rc=${rc}, expected 1" >&2
  cat "${TMP_DIR}/batch.out" >&2
  exit 1
fi
grep -qx '20260101 20260201 TRANSDT WORKDATE T' "${TMP_DIR}/calls"
grep -qx '20260101 20260201 WORKDATE WORKDATE U' "${TMP_DIR}/calls"
[[ "$(wc -l < "${TMP_DIR}/calls" | tr -d '[:space:]')" -eq 2 ]]
grep -q $'^T\t20260101\t20260201\tFAIL\t1$' "${TMP_DIR}/out/batch_summary.tsv"
grep -q $'^U\t20260101\t20260201\tFAIL\t1$' "${TMP_DIR}/out/batch_summary.tsv"

echo "failure handling regression test: OK"

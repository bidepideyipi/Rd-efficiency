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
tables =
  T = TRANSDT
  U = WORKDATE
EOF

cat > "${TMP_DIR}/sql/T/02_hash_tgt.sql" <<'EOF'
SELECT 1 FROM target_only;
EOF

set +e
"${TMP_DIR}/test_by_map.sh" 20260101 20260201 TRANSDT T \
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
grep -q '^T2_hash=ERROR$' "${TMP_DIR}/out/T_20260101_20260201/verify.result"

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
  echo 'ORA-00942: table or view does not exist'
  exit 1
fi
exit 0
EOF
chmod +x "${TMP_DIR}/bin/sqlplus"
rm -rf "${TMP_DIR}/out/T_20260101_20260201"

set +e
PATH="${TMP_DIR}/bin:${PATH}" \
  "${TMP_DIR}/test_by_map.sh" 20260101 20260201 TRANSDT T \
  >"${TMP_DIR}/sql-error.out" 2>&1
rc=$?
set -e
if [[ "${rc}" -ne 2 ]]; then
  echo "SQL execution error returned rc=${rc}, expected 2" >&2
  cat "${TMP_DIR}/sql-error.out" >&2
  exit 1
fi
grep -q 'ORA-00942' "${TMP_DIR}/out/T_20260101_20260201/run.log"

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
grep -qx '20260101 20260201 TRANSDT T' "${TMP_DIR}/calls"
grep -qx '20260101 20260201 WORKDATE U' "${TMP_DIR}/calls"
[[ "$(wc -l < "${TMP_DIR}/calls" | tr -d '[:space:]')" -eq 2 ]]
grep -q $'^T\t20260101\t20260201\tFAIL\t1$' "${TMP_DIR}/out/batch_summary.tsv"
grep -q $'^U\t20260101\t20260201\tFAIL\t1$' "${TMP_DIR}/out/batch_summary.tsv"

echo "failure handling regression test: OK"

#!/bin/bash
# 回归测试：sqlldrErrors=-1 处理到 EOF，仅 ORA-00001 可按幂等重复成功。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "${TMP_DIR}"
}
trap cleanup EXIT

mkdir -p "${TMP_DIR}/bin" "${TMP_DIR}/out"
cp "${ROOT_DIR}/load_by_map.sh" "${TMP_DIR}/load_by_map.sh"

cat > "${TMP_DIR}/export-job.conf" <<EOF
outDir = ${TMP_DIR}/out
tgtConnect = test/test@TARGET
sqlldrErrors = -1
sqlldrDirect = false
EOF

cat > "${TMP_DIR}/bin/sqlldr" <<'EOF'
#!/bin/bash
set -euo pipefail

log=""
bad=""
errors=""
for arg in "$@"; do
  case "${arg}" in
    log=*) log="${arg#log=}" ;;
    bad=*) bad="${arg#bad=}" ;;
    errors=*) errors="${arg#errors=}" ;;
  esac
done

printf '%s\n' "${errors}" > "${FAKE_SQLLDR_ERRORS_CAPTURE:?}"
: > "${bad}"

case "${FAKE_SQLLDR_MODE:?}" in
  duplicate)
    cat > "${log}" <<'LOG'
Record 1: Rejected - Error on table T.
ORA-00001: unique constraint violated

  2 Rows successfully loaded.
  1 Row not loaded due to data errors.
  0 Rows not loaded because all WHEN clauses were failed.
  0 Rows not loaded because all fields were null.
LOG
    echo "SQL*Loader completed with warnings"
    exit 2
    ;;
  not_null)
    cat > "${log}" <<'LOG'
Record 1: Rejected - Error on table T.
ORA-01400: cannot insert NULL

  2 Rows successfully loaded.
  1 Row not loaded due to data errors.
  0 Rows not loaded because all WHEN clauses were failed.
  0 Rows not loaded because all fields were null.
LOG
    echo "SQL*Loader completed with warnings"
    exit 2
    ;;
  *)
    exit 3
    ;;
esac
EOF
chmod +x "${TMP_DIR}/bin/sqlldr" "${TMP_DIR}/load_by_map.sh"

TABLE_NAME="T"
BEGIN_TM="20260101"
END_TM="20260201"
PREFIX="${TMP_DIR}/out/${TABLE_NAME}_${BEGIN_TM}_${END_TM}"
# 三条逻辑记录；第一条字段内含 LF，因此物理行数为 4。
printf 'a\nb\036\037\036\nc\036\037\036\nd\036\037\036\n' > "${PREFIX}.dat"
cat > "${PREFIX}.ctl" <<EOF
LOAD DATA
INFILE '${PREFIX}.dat' "STR X'1E1F1E0A'"
EOF

FAKE_SQLLDR_MODE=duplicate \
FAKE_SQLLDR_ERRORS_CAPTURE="${TMP_DIR}/errors.arg" \
PATH="${TMP_DIR}/bin:${PATH}" \
  "${TMP_DIR}/load_by_map.sh" "${BEGIN_TM}" "${END_TM}" "${TABLE_NAME}" \
  > "${TMP_DIR}/duplicate.out" 2>&1

grep -qx '3' "${TMP_DIR}/errors.arg"
grep -q 'duplicate primary/unique keys ignored: 1' "${TMP_DIR}/duplicate.out"

set +e
FAKE_SQLLDR_MODE=not_null \
FAKE_SQLLDR_ERRORS_CAPTURE="${TMP_DIR}/errors.arg" \
PATH="${TMP_DIR}/bin:${PATH}" \
  "${TMP_DIR}/load_by_map.sh" "${BEGIN_TM}" "${END_TM}" "${TABLE_NAME}" \
  > "${TMP_DIR}/not-null.out" 2>&1
rc=$?
set -e

if [[ "${rc}" -eq 0 ]]; then
  echo "ORA-01400 was incorrectly accepted" >&2
  cat "${TMP_DIR}/not-null.out" >&2
  exit 1
fi
grep -q 'ORA-01400' "${TMP_DIR}/not-null.out"

echo "load_by_map regression test: OK"

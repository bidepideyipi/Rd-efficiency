# 跳板机导出 / 装载操作说明

在已安装 **sqlplus**（导出）与 **sqlldr**（装载）的跳板机上使用。本目录文件放在一起，不读取环境变量。

| 文件 | 作用 |
|------|------|
| `export-job.conf` | 路径、源/目标连接、表清单、时间范围、sqlldr 参数 |
| `export_by_map.sh` | 单表单窗导出 → `.dat` + `.ctl` |
| `render_export_sql.awk` | 将 map SELECT 直接改写为 `0x01` 分隔导出 SQL |
| `export_batch.sh` | 表清单 × 按自然月切分，逐次导出 |
| `load_by_map.sh` | 单表单窗装载：对已有 `.ctl` 执行 sqlldr |
| `load_batch.sh` | 批量装载：优先按导出清单 OK 行执行 |
| `test_export_wrapper.sh` | 不连接数据库，回归验证导出 SQL 不增加外层查询 |
| `test_load_by_map.sh` | 使用假 sqlldr 回归验证 `-1` 与错误分类 |
| `test_failure_handling.sh` | 回归验证缺失/失败测试 SQL 不误报 PASS，continue 最终返回非 0 |
| `test-job.conf` | 测试 SQL 目录、源/目标连接、表清单、时间片 |
| `test_by_map.sh` | 单表单窗：执行成对测试 SQL → 比对 → `verify.result` |
| `test_batch.sh` | 表清单 × 时间片，逐次调用 `test_by_map.sh` |

```bash
chmod +x export_by_map.sh export_batch.sh load_by_map.sh load_batch.sh test_by_map.sh test_batch.sh
which sqlplus
which sqlldr
```

## 1. 改配置

编辑同目录的 `export-job.conf`。`connect` / `tgtConnect` 含口令，填入真实值后不要提交到仓库。

```ini
sqlDir = /opt/mig/sql/map
outDir = /opt/mig/out
connect = user/password@SOURCE
tgtConnect = user/password@TGT_SERVICE
ctlCharset = ZHS16GBK
loadMode = APPEND
sqlldrErrors = -1
sqlldrDirect = false

start = 20240301
end = 20270301
interval = 1m
onError = stop

tables =
  IBPS_PAYMENTINIT = TRANDATE
  HVPS_PAYMENTINIT = WORKDATE
  IBPS_MOBILEINFO =
```

| 键 | 谁用 | 说明 |
|----|------|------|
| `sqlDir` | export_batch / load_batch 回退扫片 | SQL 模板目录，`{表名}.sql` |
| `outDir` | 全部 | `.dat` / `.ctl` / 日志目录 |
| `connect` | `export_by_map.sh` | 源库 sqlplus 连接串 |
| `tgtConnect` | `load_by_map.sh` | 目标库 sqlldr 连接串 |
| `ctlCharset` | `export_by_map.sh` | `.dat` 的实际字符集，写入 `.ctl`；默认 `ZHS16GBK`（GBK） |
| `loadMode` | `export_by_map.sh` | 写入 `.ctl`：`APPEND` / `TRUNCATE` / `REPLACE` / `INSERT` |
| `sqlldrErrors` | `load_by_map.sh` | 可选，默认 `-1`：按 `.dat` 记录数处理到 EOF；非负整数则使用 SQL*Loader 原生错误上限 |
| `sqlldrDirect` | `load_by_map.sh` | 可选，默认 `false` |
| `start` / `end` | export_batch；load_batch 无清单时 | 左闭右开 |
| `interval` | 同上 | 只按自然月，如 `1m`；`start`/`end` 建议月初 |
| `onError` | export_batch / load_batch | `stop` / `continue` |
| `tables` | export_batch；load_batch 无清单时 | **每表一行** `表名 = 时间列`；无需时间范围时留空 |

时间列名为任意 Oracle 标识符（不限集合）。`表名 =` 表示不拼时间范围，并在整个
`start`/`end` 范围内只执行一次，避免每个时间片重复导出整表。已移除全局
`tmColumn` 与 `files` / `表名|列名` 写法。

多数据来源 Sheet（如 `HVPS_EXTPAYMENT1`）：`tables` 左侧写 **Sheet/sql 文件名**；`.dat`/`.ctl` 文件名同 Sheet；`.ctl` 的 `INTO TABLE` 为物理表名（去末位数字，与生成器 `SheetNames.resolveTableName` / SQL 头 `-- targetTable:` 一致）。

## 2. 批量导出

```bash
# conf 的 start/end/interval × tables，按月分片
./export_batch.sh

# 指定日 [D, D+1)，conf 全部表，不再分片
./export_batch.sh 20240315

# 指定日 + 指定表，不再批量、不再分片
./export_batch.sh 20240315 "IBPS_HOSTBOOKINIT_HIS = DEALDATE"
```

产物示例：

```text
{表名}_20240301_20240401.dat
{表名}_20240301_20240401.ctl
{outDir}/_batch/export-manifest.txt
{outDir}/_batch/export-error.log   # 仅失败片有内容
```

清单行：`表名,片开始,片结束,dat路径,ctl路径,OK|FAIL`。
失败片的 sqlplus / 脚本输出追加到 `export-error.log`。
脚本退出时会输出整体耗时，例如：`export batch total elapsed: 01:23:45 (5025 seconds)`。

## 3. 单表单窗导出

```bash
./export_by_map.sh <beginTm> <endTm> [tmColumn] [表名]
# 整表导出（显式空时间列）
./export_by_map.sh 20240301 20270301 "" IBPS_MOBILEINFO
```

## 4. 单表单窗装载

```bash
./load_by_map.sh <beginTm> <endTm> [表名]
```

示例：

```bash
./load_by_map.sh 20240301 20240302 IBPS_PAYMENTINIT
# → sqlldr userid=${tgtConnect} control=.../IBPS_PAYMENTINIT_20240301_20240302.ctl
```

需已存在同前缀的 `.dat` 与 `.ctl`。日志 / 坏数据：同前缀 `.log` / `.bad`。

`sqlldrErrors=-1` 是脚本语义，不会把 `-1` 直接传给 SQL*Loader。脚本按 `.dat` 的
物理记录数设置 `errors`，确保处理到 EOF。若拒绝记录全部且仅由 `ORA-00001`
主键/唯一键冲突造成，则按幂等重复返回成功；`ORA-01400`、`ORA-12899`、格式错误、
discard 或全字段为空等情况仍返回失败。该模式不会回滚已经成功装载的记录。

## 5. 批量装载

```bash
/opt/mig/scripts/load_batch.sh
```

优先读 `{outDir}/_batch/export-manifest.txt` 中状态为 `OK` 的行，逐片调用 `load_by_map.sh`。  
若清单不存在，则按 conf 的 `tables` × 时间片，对已存在的 `.ctl` 装载。

装载清单：`{outDir}/_batch/load-manifest.txt`  
行格式：`表名,片开始,片结束,ctl路径,OK|FAIL`。
失败片的 sqlldr / 脚本输出追加到 `{outDir}/_batch/load-error.log`。
脚本退出时会输出整体耗时，例如：`load batch total elapsed: 00:18:06 (1086 seconds)`。

## 6. 迁移结果测试（Excel → 测试 SQL）

先在 Web「Excel → 测试 SQL」生成 `{testSqlDir}/{Sheet}/01_count_*`、`02_hash_*`，再在跳板机执行：

```bash
# 编辑 test-job.conf：testSqlDir / testOutDir / connect / tgtConnect / tables
# tables 单列表示源/目标同名；不同名写：Sheet = 源时间列,目标时间列；无需时间范围时留空
./test_batch.sh
./test_batch.sh 20240315
./test_batch.sh 20240315 "IBPS_PAYMENTINIT = TRANSDT,WORKDATE"
```

单表单窗：

```bash
./test_by_map.sh <beginTm> <endTm> <srcTmColumn> <tgtTmColumn> <Sheet名>
# 整表测试（源/目标时间列同时留空）
./test_by_map.sh 20240301 20270301 "" "" IBPS_MOBILEINFO
```

每片产出 `{testOutDir}/{Sheet}_{begin}_{end}/verify.result`（overall=PASS|FAIL）与可选 `verify.diff`。默认只执行 T1 总量和 T2 行明细共 4 次 SQL；T5 重复键、空键、统计和集合检查从 T2 结果派生。配置 `byDayCheck=true` 时额外执行源/目标按日统计。`run.log` 仅记录每条 SQL 的状态、耗时和失败输出。设计见 `docs/excel-to-test-sql-design.md` §6.5。

## 7. 占位符与文件约定

| 占位符 | 替换成 |
|--------|--------|
| `{{beginTm}}` | 本片开始（含） |
| `{{endTm}}` | 本片结束（不含） |
| `{{srcTmColumn}}` | 源库时间列名（测试 SQL） |
| `{{tgtTmColumn}}` | 目标库时间列名（测试 SQL） |
| `{{tmColumn}}` | 旧测试 SQL兼容占位符；按执行端替换 |

| 项 | 约定 |
|----|------|
| `.dat` | 无表头；字段分隔符 `0x01`；记录结束符 `0x1E 0x1F 0x1E 0x0A` |
| `.ctl` | `STR X'1E1F1E0A'` + `FIELDS TERMINATED BY X'01'`，列序 = map SQL 的 `) AS 列名` |
| 导出改写 | 直接拼接原 SELECT 顶层表达式，不增加外层查询 |
| 装载 | `load_*.sh` → `sqlldr` |

`export_by_map.sh` 使用 `render_export_sql.awk` 把原 SELECT 的顶层表达式直接改写为
`NVL(TO_CHAR(表达式), '') || CHR(1) ...`，不再增加 CTE/外层查询，也不预声明列数。
实际 `AS 列名` 用于 `.ctl` 列序；头部 `-- exportColumns:` 只做一致性校验。
生成器另写 `-- exportNumberColumns:`；其中的目标 NUMBER 字段使用显式 `TM9`
格式和 `NLS_NUMERIC_CHARACTERS='.,'`，并把 `.01` / `-.01` 规范为
`0.01` / `-0.01`。旧 map SQL 缺少该头时导出会失败，须先重新生成 map SQL。

每条导出记录末尾追加 `CHR(30)||CHR(31)||CHR(30)`，SQL*Plus 再追加 LF，
组成 `X'1E1F1E0A'` 逻辑记录结束符。字段内普通 CR/LF 会原样保留。
该控制字节序列为格式保留值；旧 `.dat` 不兼容新 `.ctl`，升级后须重新导出。

## 8. 失败时看哪里

- 导出失败：`export-manifest` 中 `FAIL`；`export-error.log` 含 ORA/SP2（从 spool 抽出）及同前缀 `.log`。
- 装载失败：`load-manifest` 中 `FAIL`；`load-error.log` 含 sqlldr 摘要 / 日志尾部；同前缀 `.log` / `.bad`。
- 测试失败：对应片目录 `verify.result` / `verify.diff`；`batch_summary.tsv`。
- `onError = stop` 时停在失败片；`continue` 时继续，进程最终仍非 0。

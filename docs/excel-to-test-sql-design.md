# Excel 映射 → 测试 SQL 生成器设计文档

| 项 | 内容 |
|----|------|
| 版本 | V1.0 |
| 日期 | 2026-11-14 |
| 标准文档 | `数据迁移中间表映射关系（跨行支付平台）V0.4.xlsx`（与 map SQL 同一输入） |
| 关联设计 | [map-sql-generator-design.md](map-sql-generator-design.md)（Excel → 迁移 SELECT） |
| 关联大纲 | `scripts/gen_migration_test_outline_xmind.py` / 测试大纲 XMind（六项验证点） |
| 目标 | 读取映射 Excel，按 Sheet 生成可时间分片执行的**源库 / 目标库对照测试 SQL** |
| 使用方式 | **Web 界面为主**（复用上传与表选择）；CLI / Shell 执行比对为辅 |
| 产出约定 | **一个 Sheet 对应一套测试 SQL 文件**（源侧 + 目标侧成对）；文件落在 `{exportDir}/test/{Sheet}/` |
| 运行时 | 跳板机：Shell 替换占位符 → **sqlplus** 分别打源库与目标库 → 结果文件 diff 判定 Pass/Fail |
| 非目标 | 不代替业务功能测试；不在 Web 内直连库自动跑测；不覆盖生僻字展示抽查、协议业务连续性人工项 |

---

## 1. 背景与目标

跨行支付平台中间表迁移已有：

1. **Excel → map SQL**：按映射规则生成源库 `SELECT`，供导出 / sqlldr 装载。
2. **测试大纲 XMind**：按表罗列六项验证点（记录数、字段规则、乱码截断、生僻字、主键、协议连续性）。

缺口：大纲是人工执行说明，缺少与 map SQL **同源规则**、可按时间片批跑的测试脚本。本模块补齐：

> **Web / CLI：读同一份 Excel → 复用映射解析与字段表达式 → 生成测试 SQL 包**  
> 跳板机按时间片对源库、目标库各执行一次，对比 count / sum / 行指纹 / 主键集合。

与 map SQL 的契约对齐：

| 契约 | 约定 |
|------|------|
| 时间占位符 | `{{beginTm}}` / `{{endTm}}` / `{{tmColumn}}` / `{{tmFormat}}`（生成器不替换） |
| 时间窗语义 | 左闭右开：`>= begin` 且 `< end` |
| 物理表名 | `SheetNames.resolveTableName`（Sheet 末位数字去掉） |
| 源侧过滤 / JOIN | 与 map SQL 同一套 `wherePredicate` + `leftJoins` |
| 源侧字段表达式 | 复用 `SelectSqlRenderer.renderField`（规则 / 类型 / 长度同源） |

---

## 2. 与既有产物的关系

### 2.1 测试大纲六项 → 本模块覆盖

| 大纲项 | 本模块 | 口径 |
|--------|--------|------|
| ① 指定时间区间历史库与目录库记录数一致 | **T1** | 生成并比对 COUNT（可选按日 GROUP BY） |
| ② 迁移字段结果与迁移规则一致 | **T2** | 全量非 `00` 字段行级 MD5 指纹比对 |
| ③ 关键字段无乱码或截断 | **T3（派生）** | T2 通过则视为通过；V1 不单独门禁 |
| ④ 生僻字迁移一致 | 不生成 | 仍属人工/大纲抽查 |
| ⑤ 迁移数据主键约束完整 | **T5** | GROUP BY 唯一性 + 主键集合跨库一致 |
| ⑥ 协议类表业务连续性 | 不生成 | 业务回放，非 SQL 对照 |

另增：

| 项 | 本模块 | 口径 |
|----|--------|------|
| 金额汇总 | **T4** | 表名匹配 `*PAYMENT*` / `*HOST*` 且存在 `AMOUNT` 时，在 T1 同语句内 SUM |

### 2.2 与 Excel → SQL 的复用边界

```text
                    ┌─────────────────────────────────────┐
                    │     同一份 V0.4 映射 Excel            │
                    └─────────────────┬───────────────────┘
                                      │
                                      ▼
                    ┌─────────────────────────────────────┐
                    │ ExcelMappingReader + MappingValidator│
                    │ TableMapping / FieldMapping          │
                    └───────────┬─────────────┬───────────┘
                                │             │
                ┌───────────────┘             └───────────────┐
                ▼                                             ▼
┌───────────────────────────┐               ┌───────────────────────────────┐
│ SelectSqlRenderer         │               │ TestSqlRenderer（新增）        │
│ → {Sheet}.sql 迁移 SELECT │               │ → test/{Sheet}/*_{src,tgt}.sql │
└───────────────────────────┘               └───────────────────────────────┘
```

**硬规则：测试源侧字段表达式必须调用与迁移 SELECT 相同的 `renderField` 逻辑**，禁止在测试模块另写一套 CASE/SUBSTR/TO_NUMBER 规则，避免「迁对了但测的是另一套规则」。

---

## 3. 输入约定

### 3.1 Excel

与 [map-sql-generator-design.md](map-sql-generator-design.md) §2 完全一致（表头第 3 行、数据第 4 行起、跳过封面/说明等）。

### 3.2 主键解析（测试专用增强）

map SQL 模型仅把「是否可空」存为 `nullable`（`PK` / `N` / `Y`），不单独存主键列表。测试模块解析主键顺序：

1. **优先**：本 Sheet 所有 `nullable == PK`（忽略大小写）的目标栏位，按 Excel 行序（序号）排列。
2. **回退**：解析 Sheet 第 1 行标题中的 `主键：…`（与 `gen_migration_test_outline_xmind.py` 同正则口径：`表名…主键：col1,col2…`），按 `,` / `，` / `、` 拆分，规范为大写标识符。
3. **仍空**：该表 T2 行键、T5 标记为 **ERROR（阻塞）**，不生成可执行哈希/主键脚本；T1/T4 仍可生成。

复合主键：拼接顺序 = 上述列表顺序；文档与 `_meta.json` 中显式列出。

### 3.3 时间列

与导出一致：不在 Excel 内写死全局时间列；运行时由 `export-job.conf`（或测试 conf）的 `tables` 段提供：

```text
{Sheet名或物理表名} = {时间列标识符}
```

源侧时间谓词挂在主表别名上：`{alias}.{{tmColumn}}`。  
目标侧时间谓词挂在目标表别名（默认 `t`）上：`t.{{tmColumn}}`。  
**约定：同一 Sheet 源/目标使用同一 `{{tmColumn}}` 占位符名**；若目标物理列名与源不同，由 conf 按表配置的列名在替换时写入（与现网导出一致：每表一行时间列）。

### 3.4 无业务 WHERE 的表

若 map SQL 因「库表关联条件」为空而不拼时间窗（见 `SelectSqlRenderer.renderWhere`），测试 SQL **同样不拼时间窗**，并在 `_meta.json` 标记 `timeWindow=false`，Shell 侧对该表跳过按片循环或整表只跑一次（实现阶段与 map 行为对齐，避免假阴性）。

---

## 4. 校验项规格（定稿口径）

### 4.1 判定总表

| ID | 名称 | 源侧 | 目标侧 | Pass 条件 |
|----|------|------|--------|-----------|
| T1 | 记录数一致 | COUNT（+ 可选按日） | COUNT（+ 可选按日） | 片合计相等；若启用按日则每日相等 |
| T2 | 字段规则 MD5 | PK + row_md5（规则表达式） | PK + row_md5（目标列） | 片级聚合指纹相等；失败时 PK 对齐明细可定位 |
| T3 | 乱码/截断 | — | — | **派生**：T2 Pass ⇒ T3 Pass |
| T4 | 金额 SUM | 与 T1 同语句 SUM(amount_expr) | SUM(AMOUNT) | 仅触发表；count 与 sum 均一致 |
| T5 | 主键完整 | 重复键 / 空键 / PK 清单 | 同左 | 无重复、无空键；源 PK 集合 = 目标 PK 集合 |

任一门禁项 Fail ⇒ 该 Sheet 该时间片 Fail。T3 不单独计 Fail。

---

### 4.2 T1 — 指定时间区间记录数一致

**目的：** 验证时间片内无漏迁、多迁、重复迁（数量维度）。

**源侧骨架：**

```sql
-- 01_count_src.sql
SELECT
  /* 可选按日：*/ TO_CHAR({alias}.{{tmColumn}}, 'YYYYMMDD') AS DT,  -- 或 SUBSTR 字符串列
  COUNT(*) AS CNT
  /* T4 触发时追加：, NVL(SUM( ({amount_expr}) ), 0) AS AMT_SUM */
FROM {主表} {alias}
{LEFT JOIN …}
WHERE {alias}.{{tmColumn}} >= '{{beginTm}}'
  AND {alias}.{{tmColumn}} <  '{{endTm}}'
  AND ( {wherePredicate} )
GROUP BY TO_CHAR({alias}.{{tmColumn}}, 'YYYYMMDD')   -- 可选；片级合计版可省略 GROUP BY
ORDER BY 1;
```

**目标侧骨架：**

```sql
-- 01_count_tgt.sql
SELECT
  TO_CHAR(t.{{tmColumn}}, 'YYYYMMDD') AS DT,
  COUNT(*) AS CNT
  /* T4：, NVL(SUM(t.AMOUNT), 0) AS AMT_SUM */
FROM {物理目标表} t
WHERE t.{{tmColumn}} >= '{{beginTm}}'
  AND t.{{tmColumn}} <  '{{endTm}}'
GROUP BY TO_CHAR(t.{{tmColumn}}, 'YYYYMMDD')
ORDER BY 1;
```

**说明：**

- `timePredicate=to_date` 时与 map SQL 相同，用 `TO_DATE('{{beginTm}}','{{tmFormat}}')`。
- 按日维度默认开启（`dayBucket=true`）；也可生成无 GROUP BY 的片级合计版（`01_count_*_total.sql`），由配置二选一或两份都出。**V1 默认：同时产出「片合计」与「按日」两段查询（可用 UNION 注释分隔，或两个 statement 文件后缀 `_total` / `_byday`）。**
- 判定：源 CNT == 目标 CNT；启用按日时按 `DT` 外连接比对，缺日或不等均 Fail。

---

### 4.3 T2 — 迁移字段结果与规则一致（MD5）

**目的：** 验证目标落库值等于「按映射规则从源算出的值」，而非仅数量一致。

#### 4.3.1 纳入指纹的字段

| 规则 | 说明 |
|------|------|
| 纳入 | 全部映射类型 **非 `00-不迁移`** 的目标栏位（01 / 02 / 03 / 04 / 05） |
| 不纳入 | `00-不迁移` |
| 不另挑列 | V1 **不做**人工白名单；避免漏测码值转换与加工映射 |
| 列序 | 与 Excel 映射行序（即 map SELECT 列序）一致，保证两侧 `\|\|` 顺序相同 |

#### 4.3.2 规范化与行指纹

对每一列表达式 `expr`（源 = `renderField` 内层表达式；目标 = `t.{目标栏位}`）：

```text
norm(expr) = NVL( TRIM( TO_CHAR( expr ) ) , CHR(1) )
```

- `TRIM`：消除定长 CHAR 补空格导致的假失败（若业务要求保留尾空格，可后续改为仅 RTRIM 或关闭 TRIM；V1 默认 TRIM）。
- `TO_CHAR`：统一 NUMBER / DATE / 字符，避免类型差。
- `CHR(1)`：NULL 与空串以外的哨兵；空串经 TRIM 后仍为 `''`，与 NULL（变 CHR(1)）可区分。

行指纹：

```text
row_md5 = STANDARD_HASH(
    norm(c1) || CHR(0) || norm(c2) || CHR(0) || … || norm(cn),
    'MD5'
)
```

行键（复合主键）：

```text
pk_key = norm(pk1) || CHR(0) || norm(pk2) || …
```

主键列的 `expr`：源侧用该 PK 目标栏位对应的 map 表达式；目标侧用 `t.PK_COL`。

#### 4.3.3 两级输出

**级别 A — 快速 Pass/Fail（片级）：**

对 `ORDER BY pk_key` 后的有序 `row_md5` 列表再聚合，例如：

```sql
SELECT
  COUNT(*) AS CNT,
  STANDARD_HASH(
    LISTAGG(row_md5, ',') WITHIN GROUP (ORDER BY pk_key),
    'MD5'
  ) AS SLICE_MD5
FROM ( /* 行指纹子查询 */ );
```

> **注意：** `LISTAGG` 有长度限制（ORA-01489）。大表 V1 采用 **文件比对策略**：sqlplus 导出 `pk_key || 0x01 || row_md5` 有序文本，Shell 对源/目标文件做 `cmp` / `md5sum`；`LISTAGG` 片级哈希仅作小表可选加速，`_meta.json` 中 `hashMode=file|listagg`。

**级别 B — 失败诊断：**

导出明细：

```text
pk_key <0x01> row_md5
```

按 `pk_key` 对齐：仅源有 / 仅目标有 / 双方 row_md5 不同 → 写入差异报告。

#### 4.3.4 源 / 目标 SQL 形态示意

```sql
-- 02_hash_src.sql（示意）
SELECT
  /* pk_key */,
  STANDARD_HASH(
      NVL(TRIM(TO_CHAR( ({expr1}) )), CHR(1)) || CHR(0) ||
      NVL(TRIM(TO_CHAR( ({expr2}) )), CHR(1)) || CHR(0) ||
      …
    , 'MD5') AS ROW_MD5
FROM {主表} {alias}
{JOINs}
WHERE …时间窗… AND ( {wherePredicate} )
ORDER BY 1;
```

```sql
-- 02_hash_tgt.sql
SELECT
  /* pk_key from t.pk… */,
  STANDARD_HASH(
      NVL(TRIM(TO_CHAR( t.COL1 )), CHR(1)) || CHR(0) ||
      NVL(TRIM(TO_CHAR( t.COL2 )), CHR(1)) || CHR(0) ||
      …
    , 'MD5') AS ROW_MD5
FROM {物理目标表} t
WHERE …时间窗…
ORDER BY 1;
```

跨库**不**做 DB Link 联查；一律「各跑各的 → 比文件」。

---

### 4.4 T3 — 关键字段无乱码或截断（派生）

**定稿：T2 Pass ⇒ T3 Pass；V1 不单独生成失败门禁 SQL。**

| 论点 | 说明 |
|------|------|
| 乱码 | 字符被替换为 `?` / U+FFFD 等会改变 `TO_CHAR` 结果 → row_md5 变化 → T2 Fail |
| 非预期截断 | 目标短于规则应得值 → md5 变 → T2 Fail |
| 规则内 SUBSTR | 映射规则故意截断时，源侧 `expr` 已含 SUBSTR，目标应得截断后值；T2 Pass 表示「按规则正确」，**不是**缺陷 |
| 生僻字展示 | 码位一致属 T2；前端展示 / 导出文件属大纲人工项，本模块不覆盖 |

可选诊断（非门禁，实现阶段可不做）：对字符列输出 `LENGTH` / `LENGTHB` 分布，便于人工看截断边界。V1 文档保留为后续增强，**不进入 Pass/Fail**。

---

### 4.5 T4 — 金额汇总校验

**触发条件（同时满足）：**

1. 物理目标表名（`resolveTableName` 之后）匹配正则：`(?i).*(PAYMENT|HOST).*`
2. 映射字段中存在目标栏位名 **精确等于** `AMOUNT`（忽略大小写）；**不含** `FEEAMOUNT`、`PAYAMOUNT` 等（后续若扩展，走配置白名单 `amountColumns`）

**行为：**

- 不另建文件；在 **T1** 的 `01_count_src.sql` / `01_count_tgt.sql` 中追加 `AMT_SUM` 列。
- 源：`NVL(SUM( ({amount 的 map 表达式}) ), 0)`
- 目标：`NVL(SUM(t.AMOUNT), 0)`

**判定：** `CNT` 与 `AMT_SUM` 均与对侧一致；任一侧不一致 ⇒ Fail。  
按日模式下逐日比 `AMT_SUM`；片合计模式比总 sum。

未触发 T4 的表：`_meta.json` 记 `amountCheck=false`，COUNT SQL 不含 SUM。

---

### 4.6 T5 — 迁移数据主键约束完整

**目的：** 唯一性、非空、与源抽取结果集主键集合一致。

#### 4.6.1 目标侧（约束与唯一）

```sql
-- 05_pk_tgt.sql 片段 1：重复键
SELECT pk1, pk2, …, COUNT(*) AS CNT
FROM {物理目标表} t
WHERE …时间窗…
GROUP BY pk1, pk2, …
HAVING COUNT(*) > 1;

-- 片段 2：空键
SELECT COUNT(*) AS NULL_PK_CNT
FROM {物理目标表} t
WHERE …时间窗…
  AND ( pk1 IS NULL OR TRIM(TO_CHAR(pk1)) IS NULL
     OR … );

-- 片段 3：总数 vs 去重数
SELECT COUNT(*) AS CNT,
       COUNT(DISTINCT pk_key) AS DIST_CNT
FROM ( SELECT norm(pk1)||CHR(0)||… AS pk_key FROM … );
```

Pass：`片段1` 无行；`NULL_PK_CNT=0`；`CNT=DIST_CNT`。

这与「按主键 GROUP，每个 group 仅一条，且总行数与去重行数相同」等价。

#### 4.6.2 源侧（抽取结果集唯一）

对 map 主键表达式做同样的 `GROUP BY … HAVING COUNT(*)>1` 与 `CNT vs DIST_CNT`。  
用于发现：JOIN 放大导致源结果集主键重复（装载前即坏）。

#### 4.6.3 跨库主键集合

导出有序主键清单：

```text
pk_key
```

源集合文件 vs 目标集合文件：`cmp` 或差集。  
与 T1 互补：能发现「COUNT 相同但主键集合不同」（错迁/串键）。

---

## 5. 产出物形态

```text
{exportDir}/
  test/
    {Sheet名}/                 # 与 map SQL 文件名口径一致（可含末位数字）
      01_count_src.sql
      01_count_tgt.sql
      02_hash_src.sql
      02_hash_tgt.sql
      05_pk_src.sql
      05_pk_tgt.sql
      _meta.json
    _runner/                   # 实现阶段落入 scripts/；设计期约定行为
      test-job.conf            # 可复用/扩展 export-job.conf
      test_by_map.sh           # 单表单窗：两侧 sqlplus + diff
      test_batch.sh            # tables × 时间片
```

`_meta.json` 最小字段：

```json
{
  "sheet": "IBPS_PAYMENTINIT",
  "targetTable": "IBPS_PAYMENTINIT",
  "sourceTable": "A10TIBPSDETAILLOG",
  "primaryKeys": ["AGENTSERIALNO", "…"],
  "hashColumns": ["AGENTSERIALNO", "AMOUNT", "…"],
  "amountCheck": true,
  "timeWindow": true,
  "hashMode": "file",
  "dayBucket": true
}
```

覆盖策略：默认同名覆盖；与 map 生成共用「覆盖已存在文件」选项语义。

---

## 6. 时间分片与 Shell 执行契约

### 6.1 占位符

| 占位符 | 含义 | 替换方 |
|--------|------|--------|
| `{{beginTm}}` | 时间下界（含） | Shell |
| `{{endTm}}` | 时间上界（不含） | Shell |
| `{{tmColumn}}` | 时间过滤列名 | Shell（来自 conf `tables`） |
| `{{tmFormat}}` | `TO_DATE` 格式（若启用） | Shell 或生成期写死 |

与 map SQL / `export_by_map.sh` **同一套名称与语义**，便于同一 conf、同一时间片矩阵。

### 6.2 配置

推荐复用 `scripts/export-job.conf` 的连接与时间片段，并增加测试目录键，例如：

```ini
testSqlDir = /opt/mig/sql/map/test
testOutDir = /opt/mig/out/test
connect = user/password@SOURCE
tgtConnect = user/password@TGT_SERVICE
start = 20240301
end = 20270301
interval = 1m
onError = stop

tables =
  IBPS_PAYMENTINIT = TRANDATE
  HVPS_PAYMENTINIT = WORKDATE
```

| 键 | 说明 |
|----|------|
| `testSqlDir` | 测试 SQL 根目录（含各 `{Sheet}/`） |
| `testOutDir` | 结果与差异报告目录 |
| `connect` / `tgtConnect` | 源 / 目标 sqlplus 连接（目标侧用 sqlplus 查数，不用 sqlldr） |
| `tables` | `Sheet名 = 时间列`（与导出相同；多源 Sheet 写带编号名） |

### 6.3 单表单窗流程（`test_by_map.sh`）

```text
1. 校验参数 beginTm / endTm / tmColumn / Sheet
2. sed 替换各 *_src.sql → 临时 SQL → sqlplus connect → SPOOL *.src.dat
3. sed 替换各 *_tgt.sql → sqlplus tgtConnect → SPOOL *.tgt.dat
4. 按 §6.5 读取 .dat、逐项比对、写 verify.result / verify.diff
5. 退出码：PASS=0 / FAIL=1 / 基础设施错误=2；batch 的 onError=stop 遇非 0 即停
```

细节（SPOOL 格式、分列、各检查比对算法、报告字段）见 **§6.5**。

### 6.4 批量（`test_batch.sh`）

与 `export_batch.sh` 对齐：`start/end/interval` × `tables` 串行调用 `test_by_map.sh`；支持指定单日、单表调试参数。

### 6.5 结果读取、比对与验证报告（定稿）

§6.3 只给流程骨架；本节规定：**sqlplus 如何落盘、脚本如何读结果、如何比、如何写验证结论**。

#### 6.5.1 执行与落盘约定（与 export 同风格）

`test_by_map.sh` 对每个检查文件执行时，sqlplus 会话固定：

```text
SET ECHO OFF FEEDBACK OFF HEADING OFF PAGESIZE 0
SET LINESIZE 32767 TRIMSPOOL ON TRIMOUT ON
SET COLSEP ''          -- 不依赖空格分列；字段在 SQL 内用 CHR(1) 拼接
SPOOL {path}
@{run.sql}
SPOOL OFF
```

原则（对齐 `export_by_map.sh`）：

- **无表头、无反馈行、无分页装饰**，SPOOL 文件尽量「一行一条业务结果」。
- 多列结果在 SQL 内拼成单列：`col1 || CHR(1) || col2 || …`，避免 COLSEP 垫空格导致解析漂移。
- 源 / 目标成对文件命名：

```text
{testOutDir}/{Sheet}_{beginTm}_{endTm}/
  01_count_total.src.dat
  01_count_total.tgt.dat
  01_count_byday.src.dat
  01_count_byday.tgt.dat
  02_hash.src.dat
  02_hash.tgt.dat
  05_pk_dup.src.dat
  05_pk_dup.tgt.dat
  05_pk_null.src.dat
  05_pk_null.tgt.dat
  05_pk_stat.src.dat
  05_pk_stat.tgt.dat
  05_pk_set.src.dat
  05_pk_set.tgt.dat
  verify.result          # 本片总判定
  verify.diff            # 失败时的差异明细（可追加多段）
  run.log                # sqlplus 退出码与耗时
```

空结果集：SPOOL 文件存在且长度为 0（或仅换行）视为「0 行」，对 T5 重复键检查是 **Pass**；对 T1 片合计若两侧皆空则 CNT=0 仍可比。

#### 6.5.2 各检查项：结果行格式 → 读取 → 比对算法

| 检查 | `.dat` 每行格式（`0x01` = CHR(1)） | 读取方式 | 比对 / Pass 条件 |
|------|-----------------------------------|----------|------------------|
| T1 片合计 | `CNT` 或 `CNT 0x01 AMT_SUM` | 读首条非空行，按 `0x01` split；缺列按 `_meta.amountCheck` 判定是否必填 | `src.CNT == tgt.CNT`；若 `amountCheck` 则再比 `AMT_SUM`（数值按十进制字符串规范化后再比，去尾随无用 0） |
| T1 按日 | `DT 0x01 CNT` 或 `DT 0x01 CNT 0x01 AMT_SUM` | 载入为 `Map<DT, (CNT,AMT)>`（Shell：`awk -F $'\x01'`） | 以 DT 做外连接：仅一侧有日、或 CNT/AMT 不等 → Fail；两侧 DT 集合与值全等 → Pass |
| T2 行指纹 | `pk_key 0x01 row_md5`（已 `ORDER BY pk_key`） | 不解析业务语义；整文件字节级或按行对齐 | **首选** `cmp -s src tgt`（全等即 Pass）。Fail 时再 `diff` / `awk` 按 `pk_key` 对齐，写出：仅源有 / 仅目标有 / md5 不同（各最多截断前 N 行，默认 N=50，写入 `verify.diff`） |
| T5 重复键 | `pk… 0x01 CNT`（HAVING 结果） | 统计非空行数 | 源文件与目标文件均须 **0 行** |
| T5 空键 | `NULL_PK_CNT` | 读首行整数 | 源、目标均 `== 0` |
| T5 统计 | `CNT 0x01 DIST_CNT` | split 两列 | 各自 `CNT == DIST_CNT`；再要求 `src.CNT == tgt.CNT`（与 T1 交叉校验，不一致记 WARN 或 Fail，V1 记 **Fail**） |
| T5 PK 集合 | 每行一个 `pk_key`（已排序） | 同 T2 | `cmp -s`；Fail 时差集写入 `verify.diff` |

**实现载体：** V1 以 Shell + `awk`/`cmp`/`diff` 为主（跳板机无额外依赖）；若结果极大，T2/T5 集合比对可改为对两侧文件分别 `md5sum` 先做快速否决，不等再跑行级 diff。

#### 6.5.3 判定汇总与 `verify.result` 格式

每个时间片、每个 Sheet 写一份机器可读 + 人可读的结果文件（UTF-8 文本）：

```text
# verify.result
sheet=IBPS_PAYMENTINIT
beginTm=20240301
endTm=20240401
tmColumn=TRANDATE
amountCheck=true
overall=FAIL

T1_total=PASS cnt_src=1000 cnt_tgt=1000 amt_src=12345.67 amt_tgt=12345.67
T1_byday=FAIL mismatch_days=2 first_dt=20240315 src_cnt=10 tgt_cnt=9
T2_hash=PASS rows=1000
T3_derived=PASS reason=T2_PASS
T4_amount=PASS bundled_in=T1_total
T5_dup=PASS
T5_null=PASS
T5_stat=PASS cnt=1000 dist=1000
T5_set=FAIL only_src=3 only_tgt=1

diff_file=verify.diff
exit_code=1
```

规则：

| 项 | 规则 |
|----|------|
| `overall` | 门禁项（T1、T2、T4 若触发、T5）任一 FAIL ⇒ `FAIL`；否则 `PASS` |
| T3 | 不跑 SQL；`T2=PASS` ⇒ `T3_derived=PASS`；`T2=FAIL` ⇒ `T3_derived=SKIP`（不单独判 Fail） |
| T4 | 无独立 dat；写在 T1 行内；未触发则 `T4_amount=SKIP` |
| 控制台 | `test_by_map.sh` 结束时打印 `overall` 一行摘要；`test_batch.sh` 追加到 `{testOutDir}/batch_summary.tsv`：`sheet begin end overall exit_code` |
| 退出码 | `PASS=0`；`FAIL=1`；sqlplus/文件缺失等基础设施错误 `=2`（batch 的 `onError=stop` 遇非 0 即停） |

#### 6.5.4 `verify.diff` 内容约定（Fail 时）

分段追加，便于人工定位：

```text
== T1_byday ==
DT=20240315 CNT src=10 tgt=9 AMT src=100.00 tgt=90.00
DT=20240316 only_in=src CNT=5

== T2_hash ==
pk=... only_in=src
pk=... md5_src=aaa md5_tgt=bbb

== T5_set ==
pk=... only_in=tgt
```

超过 `maxDiffLines`（conf，默认 50）后写 `... truncated ...`，避免巨文件。

#### 6.5.5 读取伪代码（`test_by_map.sh` 核心）

```text
run_side(side, sqlFile, outDat):
  sed 替换占位符 → run.sql
  sqlplus -s ${connect|tgtConnect} 按 6.5.1 设置并 SPOOL outDat
  若 sqlplus 非 0 → overall 基础设施错误 exit 2

compare_t1_total(src, tgt, amountCheck):
  解析首行 → 比 CNT；amountCheck 则比 AMT_SUM

compare_t1_byday(src, tgt, amountCheck):
  建两个关联数组；遍历并集 DT

compare_file_eq(src, tgt, label):
  cmp -s → PASS；否则 diff 对齐写 verify.diff → FAIL

compare_t5(...):
  dup 文件空；null 计数为 0；stat CNT==DIST；set 文件 cmp

write verify.result；exit 0|1|2
```

---

## 7. Web / API 草图

### 7.1 页面

在现有左侧菜单增加 **「Excel → 测试 SQL」**：

| 区域 | 功能 |
|------|------|
| 上传 | 复用既有 Excel upload / sheet 列表 |
| 输出目录 | 默认 `{mig.default-export-dir}/test` 或与 map 同根下的 `test/` |
| 表选择 | 同 map 生成勾选 |
| 选项 | 覆盖；是否写按日 COUNT；hashMode |
| 操作 | 「生成测试 SQL」 |
| 结果 | 每表文件列表、主键解析结果、是否金额表、WARN/ERROR |

### 7.2 API

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/generate-test` | body: `{ uploadId, tables[], overwrite, … }` → 测试生成报告 |
| `GET` | `/api/test-files` | 列出 `test/` 下已生成文件（可选） |
| `GET` | `/api/test-files/{sheet}/{name}` | 下载单个测试 SQL（可选） |

上传、导出目录配置 **复用** 现有 `/api/excel/upload`、`/api/config/export-dir`，不新建上传通道。

---

## 8. 模块划分（实现预告）

本轮仅设计；实现阶段建议包结构：

```text
com.mig.mapsql
  ├── core.excel.ExcelMappingReader          # 复用；增强：读标题行主键（或 TestPkResolver 单独读）
  ├── core.sql.SelectSqlRenderer             # 复用 renderField / WHERE 骨架
  ├── core.testsql.TestPkResolver            # PK 列解析
  ├── core.testsql.TestSqlRenderer           # 渲染 T1/T2/T5 成对 SQL
  ├── core.testsql.AmountCheckPolicy         # PAYMENT|HOST + AMOUNT 判定
  ├── core.service.TestGenerateService       # 批量落盘 + _meta.json
  └── web.…                                  # generate-test API + 前端面板
```

| 类 | 职责 |
|----|------|
| `TestPkResolver` | `nullable=PK` → 标题行回退 |
| `AmountCheckPolicy` | 表名正则 + `AMOUNT` 列存在性 |
| `TestSqlRenderer` | 调用 `SelectSqlRenderer` 取表达式；拼 COUNT/HASH/PK SQL |
| `TestGenerateService` | 读表 → 校验 → 渲染 → 写 `test/{Sheet}/` |

**对 `SelectSqlRenderer` 的复用边界：**

- 允许抽取 package-visible 的 `renderField` / 表达式-only API（例如 `renderFieldExpression` 不带 `AS`），避免测试 SQL 再解析 `AS` 别名。
- WHERE / JOIN 片段与 map 共用同一 `TableMapping` 状态；目标侧 WHERE 仅时间窗，**不含**源业务谓词与 JOIN。

---

## 9. 风险与约束

| 风险 | 处理 |
|------|------|
| 大表行级 MD5 成本高 | 默认 `hashMode=file`；按时间片执行；可选先跑 T1/T4，Fail 则跳过 T2 |
| `LISTAGG` 溢出 | 片级 LISTAGG 仅小表；大表禁止依赖 LISTAGG |
| 无业务 WHERE → 无时间窗 | 与 map 一致；`_meta.timeWindow=false`；Shell 整表单次或跳过分片 |
| 源/目标时间列语义不一致 | conf 每表显式时间列；文档要求与导出配置同一套 |
| TRIM 改变「需保留尾空格」的 01 直迁字段 | V1 默认 TRIM；若验收发现假失败，改为可配置 `normalizeTrim=true\|false` |
| 主键无法解析 | T2/T5 ERROR；T1/T4 仍可生成 |
| 多源 Sheet 编号 | 目录与文件名用 Sheet 名；目标 SQL 的 `FROM` 用物理表名 |
| 字符集 | 跳板机 / 客户端与库侧按迁移约定（如 GB18030 / AL32UTF8）；乱码首先应在 T2 暴露 |

---

## 10. 非目标（V1）

- 不在 Web 内选择数据源执行测试并出 UI 报告（仅生成 SQL + 约定 Shell 跑法）。
- 不生成生僻字样本构造脚本、协议业务回放脚本。
- 不替代 map SQL 生成；不写 `INSERT`/`MERGE`。
- 不对 `FEEAMOUNT` 等扩展金额列自动 SUM（除非配置白名单）。
- 不把 T3 设为独立 Fail 门禁。

---

## 11. 验收标准（设计落地后实现阶段）

- [ ] 同一 Excel，map SQL 与测试源侧字段表达式一致（抽测 IBPS_PAYMENTINIT / HVPS_PAYMENTINIT）
- [ ] 生成物含成对 `*_src.sql` / `*_tgt.sql`，占位符可被现有 sed 规则替换
- [ ] `*PAYMENT*`/`*HOST*` 且含 `AMOUNT` 的表，COUNT SQL 含 `AMT_SUM`
- [ ] 无 `AMOUNT` 或不匹配表名的表不含 SUM
- [ ] `_meta.json` 正确反映 PK、hash 列、amountCheck、timeWindow
- [ ] 文档中的 T2⇒T3 派生口径与测试大纲说明可对读

---

## 12. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| V1.0 | 2026-11-14 | 初稿：T1–T5 定稿、MD5 全量非 00 字段、T3 派生、金额触发规则、产出与 Shell/Web 草图 |
| V1.1 | 2026-11-14 | 补 §6.5：sqlplus 落盘格式、结果读取、各检查比对算法、`verify.result` / `verify.diff` 与退出码 |

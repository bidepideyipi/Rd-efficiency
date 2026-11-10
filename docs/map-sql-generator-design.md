# Excel 映射 → map.sql 生成器（Java）设计文档

| 项 | 内容 |
|----|------|
| 版本 | V1.10 |
| 日期 | 2026-11-09 |
| 标准文档 | `数据迁移中间表映射关系（跨行支付平台）V0.4.xlsx`（以 Sheet 结构/列定义为准） |
| 目标 | 读取映射 Excel，生成可被 Shell 参数化执行的 SELECT SQL |
| 使用方式 | **Web 界面为主**（上传 Excel、配置导出目录）；CLI 为辅 |
| 产出约定 | **一张目标表对应一个 `.sql` 文件**，文件名 = 目标表名（Sheet 名） |
| 关联支持 | **单源表 WHERE** 与 **主表 + LEFT JOIN 副表**（见 §2A.4 / §2A.5） |
| 运行时导出 | Linux 跳板机：Shell 替换占位符 + **sqlplus `SPOOL`** → 无表头、`0x01` 定界 `.dat` + **sqlldr `.ctl`** |
| 运行时装载 | 目标侧 **sqlldr**：`sqlldr ... control={表}_{begin}_{end}.ctl` |
| 非目标 | 不负责 `expdp`/搬数；不建中间表/视图；不直接连库导数据；**不导出 JSON / CSV** |

---

## 1. 背景与目标

跨行支付平台中间表迁移：字段映射以官方 Excel **V0.4** 为唯一业务标准。  
本工程提供：

> **Web / CLI：读 Excel → 校验 → 按表生成 SQL 文件**  
> 生成时必须处理：**规则说明优先、源/目标类型转换、长度不一致、库表关联条件进入 WHERE**。

为方便业务与实施人员使用，提供 **Web 界面**：

1. 上传映射 Excel（`.xlsx`）
2. 配置 SQL 导出目录（服务端可写路径）
3. 选择要生成的目标表（Sheet），或一键全部生成
4. 生成结果：**每个目标表一个 `{表名}.sql`**，写入导出目录

生成后的 SQL 仍由跳板机 Shell：

1. 替换 `{{beginTm}}` / `{{endTm}}` / `{{tmColumn}}`
2. **sqlplus**（源库）：`SPOOL` → 无表头、字段分隔符 `CHR(1)`（`0x01`）的 `.dat`
3. 按同一映射列序生成 **sqlldr 控制文件** `.ctl`
4. 目标侧 **sqlldr** 装载：`sqlldr user/pass@TGT control=….ctl`

列序合约：数据文件第 N 列 = `{表名}.sql` 中 SELECT 第 N 项（Excel 映射行序 / `AS` 目标列名）= `.ctl` 列清单顺序，**不以**目标表 DDL 物理列序为准。不用 `|` 作分隔符（源文本可能含 `|`）。

生成器产出的 map SQL 必须是纯 SELECT，**禁止**：`CREATE TABLE` / `VIEW` / `INSERT` / `MERGE` / `expdp`。（`.dat` / `.ctl` 由导出脚本产出，不属于生成器输出。）

---

## 2. 输入：Excel 约定（以 V0.4 为准）

### 2.1 工作簿

| 项 | 约定 |
|----|------|
| 标准文件 | `数据迁移中间表映射关系（跨行支付平台）V0.4.xlsx` |
| 业务 Sheet | 一个目标表一个 Sheet，Sheet 名 = 目标表名（如 `IBPS_PAYMENTINIT`） |
| 跳过 Sheet | `封面` / `文档说明` / `目录` / `修订记录` 及名称含「说明」者 |
| 表头行 | 第 3 行（第 1 行标题、第 2 行分组） |
| 数据行 | 第 4 行起；「序号」为空则跳过 |

### 2.2 列定义（24 列）

| 索引 | 列名 | 侧 | 用途 |
|------|------|----|------|
| 0 | 序号 | — | 行号；空则跳过 |
| 1 | 表名 | 目标 | 目标表名 |
| 2 | 栏位 | 目标 | 目标列名 → `AS` 别名 |
| 3 | 栏位简述 | 目标 | 注释 |
| 4 | 是否可空 | 目标 | PK/N/Y |
| 5 | **数据类型** | **目标** | 目标类型；与源类型不同时要转换 |
| 6 | **长度** | **目标** | 目标长度/精度（如 `22` 或 `22,2`）；与源不一致要处理 |
| 7 | 是否落标 | 目标 | 预留 |
| 8 | 取值范围 | 目标 | 说明 |
| 9 | 业务描述/例子 | 目标 | 注释 |
| 10 | 备注 | 目标 | 注释 |
| 11 | **映射类型** | — | 策略开关 |
| 12 | 源系统 | 源 | 注释 |
| 13 | **源表表名** | 源 | `FROM` 主表 |
| 14 | **源表栏位** | 源 | 源列 |
| 15 | 栏位名称 | 源 | 注释 |
| 16 | **数据类型** | **源** | 源类型 |
| 17 | **长度** | **源** | 源长度/精度 |
| 18 | **默认值** | 源/映射 | `02-默认值` |
| 19 | 取值范围 | 源 | 码值说明 |
| 20 | 码值代码 | 源 | 预留 |
| 21 | **规则说明** | 映射 | **非空则优先作为转换表达式** |
| 22 | **库表关联条件** | 表级 | **解析后并入 WHERE** |
| 23 | 备注 | — | 注释 |

### 2.3 映射类型

| 映射类型 | 策略 | 必填 |
|----------|------|------|
| `00-不迁移` | 不出现在 SELECT | — |
| `01-直接迁移` | 以源列为基；再套规则/类型/长度处理 | 源表栏位 |
| `02-默认值` | 字面量（按目标类型加引号） | 默认值 |
| `03-码值转换` | 必须用规则说明 | 规则说明非空 |
| `04-类型转换` | 源→目标类型转换（样例多为 `VARCHAR2`→`NUMBER`） | 源表栏位；规则非空则用规则 |
| `05-加工映射` | 优先规则说明；否则源列 + 类型/长度处理 | 规则说明（强烈建议） |

---

## 2A. 字段表达式生成优先级（核心）

对每一行**非 `00`** 字段，按以下顺序得到 `expr`，最后输出：

```text
({expr}) AS {目标栏位}
```

### 优先级（高 → 低）

```text
1. 「规则说明」非空
      → expr = 规则说明原文（去首尾空白、去末尾分号，外加括号）
      → 不再自动做类型/长度包装（长度由规则自行保证，如已 substr）

2. 映射类型 = 02-默认值
      → expr = literal(默认值, 目标类型)

3. 否则以源列为基础：base = {表别名}.{源表栏位}
      （主表默认别名 `s`；LEFT JOIN 副表按 §2A.5 分配别名，如 `b`）
      3.1 若源类型 ≠ 目标类型（或映射类型=04）→ 套类型转换
      3.2 若源长度与目标长度不一致 → 套长度处理
      → expr = 处理后的表达式
```

> **硬规则：只要「规则说明」不为空，一律按该列表达式生成，无视映射类型是 01/03/04/05。**  
> 依据：现网 `IBPS_PAYMENTINIT` 中 `01-直接迁移` 也可能填写 `substr(...)`；`03`/`05` 更是以规则为准。

### 2A.1 规则说明

- 内容须为可嵌入 SELECT 列表的 **Oracle 表达式**（`CASE` / `SUBSTR` / 函数等）。
- 生成器 **原样嵌入**，不做语义编译。
- 若规则说明为自然语言（如「部分要用3位英文转换6位数字」）而非 SQL → **ERROR**（或配置为 WARN 并跳过该列），避免生成非法 SQL。
- 启发式：含 `case`/`when`/`substr`/`decode`/`||`/`(` 等视为 SQL；纯中文长句视为非 SQL。

### 2A.2 数据类型不同时的转换

比较列：目标「数据类型」(index 5) vs 源「数据类型」(index 16)。  
规范化：`trim` + `upper`；忽略空白差异。

| 源 → 目标 | 默认生成（无规则说明时） | 说明 |
|-----------|--------------------------|------|
| **`VARCHAR2` → `CHAR`**（字符型互转） | **不转换**，按 VARCHAR2 直迁 | 忽略类型差，仅走长度等规则 |
| `VARCHAR2`/`CHAR` → `NUMBER` | `TO_NUMBER(TRIM(s.COL))` | Oracle 中 `''` 即 NULL，勿用 `NULLIF(...,'')`；对应 `04-类型转换` 样例金额字段 |
| `NUMBER` → `VARCHAR2`/`CHAR` | `TO_CHAR(s.COL)` | |
| `DATE` → `VARCHAR2` | `TO_CHAR(s.COL, '{dateFormat}')` | 默认 `YYYYMMDDHH24MISS`，可配置 |
| `VARCHAR2` → `DATE` | `TO_DATE(s.COL, '{dateFormat}')` | |
| 相同类型 | 不套类型函数 | 进入长度处理 |
| 未识别组合 | **ERROR**，要求补「规则说明」 | |

`04-类型转换`：即使文档已标明类型转换，仍走同一套转换表；有规则说明则规则优先。

**样例（IBPS_PAYMENTINIT）：**  
`AMOUNT`：源 `VARCHAR2(15)` → 目标 `NUMBER(22,2)`，无规则说明 →

```sql
(TO_NUMBER(TRIM(s.AMOUNT))) AS AMOUNT
```

### 2A.3 长度不一致时的处理

比较列：目标「长度」(6) vs 源「长度」(17)。  
解析：

- 纯数字 `22` → 字符长度 22  
- `22,2` → `NUMBER` 精度/标度（字符截断不适用；类型转换后由 `TO_NUMBER` 负责）  
- 空：不做长度处理  

| 情形 | 处理 |
|------|------|
| 双方均为字符型，且 **源长度 > 目标长度** | 仅当实际长度超目标长才截：`CASE WHEN LENGTH(base) > n THEN SUBSTR(base, 1, n) ELSE base END`（NULL/短串原样） |
| 双方均为字符型，且 **源长度 < 目标长度** | **不补空格**（默认直迁）；meta 记 INFO |
| 目标更短但存在规则说明 | 不自动截断（信任规则说明，不告警） |
| `NUMBER` 长度形如 `22,2` | 不按 `SUBSTR`；依赖 `TO_NUMBER`；非法数字运行期失败 |
| 截断策略配置 | `lengthOverflow=substr\|error\|warn`，默认 `substr` |

**组合顺序（无规则说明时）：**

```text
base = s.SRC_COL
base = applyTypeCast(base)      -- 若类型不同
base = applyLength(base)        -- 若字符型且源长 > 目标长
```

例：源 `VARCHAR2(20)` → 目标 `VARCHAR2(15)`：

```sql
(SUBSTR(s.FUNCTION2, 1, 15)) AS MSGTYPE
```

例：源 `VARCHAR2(16)` → 目标 `VARCHAR2(22)`：直迁，不 Pad。

### 2A.4 库表关联条件分类与解析

扫描本 Sheet 所有数据行的「库表关联条件」(index 22)，对**非空值去重**后按形态分类（**允许同时存在多类，不再一律视为冲突**）：

| 形态 | 识别特征（忽略大小写） | 归入 |
|------|------------------------|------|
| A. 主表过滤 | `SELECT * FROM {主表} WHERE {predicate}` | `wherePredicates` |
| B. 纯谓词 | 不含 `JOIN` / 不以 `SELECT` 开头的条件片段 | `wherePredicates`（与 A 合并去重） |
| C. **LEFT JOIN** | 含 `LEFT JOIN` / `LEFT OUTER JOIN` | `leftJoins` |
| D. 其他 JOIN | `INNER JOIN` / `JOIN` 等 | V1.4 **WARN 或 ERROR**（可配）；默认先支持 LEFT JOIN |

**冲突规则调整：**

- 同一形态内：内容规范化后相同 → 合并；**同形态多条语义不同** → `joinConditionConflict=error`（默认）。
- **不同形态并存合法**：例如主表 WHERE + LEFT JOIN 副表（`HVPS_PAYMENTINIT`）。
- 旧规则「任意多条非空关联条件即冲突」**废弃**。

#### A/B → WHERE 谓词

若形如 `SELECT * FROM {表名} WHERE {predicate}`，提取 `{predicate}`。  
主表名应与频次最高的「源表表名」一致（不一致 WARN）。

谓词默认**原样嵌入**（现网多为无别名列名）；可选 `qualifyJoinColumns=true` 再加 `s.`。

#### C → LEFT JOIN 子句

识别并规范化为：

```sql
LEFT JOIN {副表} {别名} ON {onPredicate}
```

Excel 常见写法示例（`HVPS_PAYMENTINIT`）：

```text
left join TA0LTXNSSNINFO b on b.srcsysssn=a.srcsysssn
```

规范化约定：

| 项 | 约定 |
|----|------|
| 副表名 | 从 JOIN 子句解析；并与字段行「源表表名」交叉校验 |
| 别名 | 优先用 Excel 已写别名（如 `b`）；未写则按副表顺序自动分配 `b`,`c`,…（避开主表别名 `s`） |
| ON 条件 | 原样保留；若出现主表别名 `a`，渲染时 **替换为主表别名 `s`**（或配置 `primaryAlias`） |
| 多条 LEFT JOIN | 按首次出现顺序输出；同别名且表名/ON/类型相同则合并；**同别名冲突（不同表，或同表不同 ON）时自动改别名**并 WARN；字段按「源表+ON」匹配最终别名 |

### 2A.5 多源表与 LEFT JOIN（主从结构）

#### 主表判定

- 统计本 Sheet 各「源表表名」出现次数（跳过空值、`00-不迁移` 可不计入）。
- **出现次数最多者为主表** `primarySourceTable`，别名默认 `s`。
- 其余源表视为**副表**，必须能在「库表关联条件」的 LEFT JOIN 中找到对应表名，否则 ERROR：`副表 XXX 未配置 LEFT JOIN`。

> 不再因「存在多个源表表名」直接失败；改为「多源表必须有 JOIN 定义」。

#### 字段列引用别名

无规则说明时：

```text
alias = (field.sourceTable == primary) ? "s" : joinAliasOf(field.sourceTable)
base  = alias + "." + field.sourceColumn
```

有规则说明时：规则原文优先；若规则使用 `a.` 指主表，生成前可将独立标识符前的 `a.` 替换为 `s.`（保守：仅替换 `a.` 前缀，不改写字符串字面量内内容——V1.4 可用简单替换，复杂场景靠映射规范）。

#### 最终 SQL 骨架（单表 / 带 LEFT JOIN）

**仅主表（如 IBPS_PAYMENTINIT）：**

```sql
FROM {主表} s
WHERE s.{{tmColumn}} >= '{{beginTm}}'
  AND s.{{tmColumn}} <  '{{endTm}}'
  AND ( {wherePredicate} )   -- 可选
```

**主表 + LEFT JOIN（如 HVPS_PAYMENTINIT）：**

```sql
FROM {主表} s
LEFT JOIN {副表} b ON {onPredicate}   -- a. → s. 已替换
WHERE s.{{tmColumn}} >= '{{beginTm}}'
  AND s.{{tmColumn}} <  '{{endTm}}'
  AND ( {wherePredicate} )            -- 可选，来自 select…where
```

**HVPS_PAYMENTINIT 现网素材 → 期望产出示意：**

```text
-- 关联条件 1
select * from a08thvtrx where cmtno!='hvps.142.001.01';
-- 关联条件 2
left join TA0LTXNSSNINFO b on b.srcsysssn=a.srcsysssn
-- 字段：多数源表 A08THVTRX；少量源表 TA0LTXNSSNINFO
```

```sql
FROM A08THVTRX s
LEFT JOIN TA0LTXNSSNINFO b ON b.srcsysssn = s.srcsysssn
WHERE s.{{tmColumn}} >= '{{beginTm}}'
  AND s.{{tmColumn}} <  '{{endTm}}'
  AND ( cmtno != 'hvps.142.001.01' )
;
```

**IBPS_PAYMENTINIT（仅 WHERE，无 JOIN）：**

```text
SELECT * FROM A10TIBPSDETAILLOG
WHERE FUNCTION2 IN (...) and FUNCTION1 not in (...)
```

```sql
FROM A10TIBPSDETAILLOG s
WHERE s.{{tmColumn}} >= '{{beginTm}}'
  AND s.{{tmColumn}} <  '{{endTm}}'
  AND (
    FUNCTION2 IN ('ibps.101.001.01','ibps.101.001.02','ibps.103.001.01','ibps.103.001.02')
    and FUNCTION1 not in ('A10M41','A10F55')
  )
```

若「库表关联条件」全空：仅时间窗；单源表直出。

#### 暂不支持（V1.4 范围外）

- `INNER JOIN` / 逗号多表 / `FULL JOIN`（可后续扩展）
- 同一 SQL 多主表 UNION
- 子查询 JOIN、三维以上复杂关联（可先 ERROR 提示人工改规则说明）

---


## 3. 输出：`{目标表名}.sql` 形态

### 3.1 文件命名（一张目标表一个 SQL 文件）

导出目录由 Web/CLI 配置，例如 `/opt/mig/sql/map`：

```text
{exportDir}/
  IBPS_PAYMENTINIT.sql      # 主产物：文件名 = 目标表名 = Sheet 名
  IBPS_PAYMENTFINAL.sql
  HVPS_PAYMENTINIT.sql
  ...
  _meta/                    # 可选
    IBPS_PAYMENTINIT.json   # 源表、列清单、校验告警
    generate-report.json    # 本次任务汇总（成功/失败表、耗时）
```

约定：

| 项 | 规则 |
|----|------|
| 文件名 | `{目标表名}.sql`，与 Sheet 名一致 |
| 一对一 | 禁止多表写进同一文件；禁止一表拆多个 SQL |
| 覆盖策略 | 默认同名覆盖；Web 可勾选「覆盖已存在文件」 |
| 非法文件名 | Sheet 名含路径分隔符等 → ERROR，不落盘 |
### 3.2 SQL 模板（生成器写出的固定骨架）

```sql
-- Generated by map-sql-generator
-- sourceExcel: 数据迁移中间表映射关系（跨行支付平台）V0.4.xlsx
-- targetTable: IBPS_PAYMENTINIT
-- sourceTable: A10TIBPSDETAILLOG
-- DO NOT EDIT manually unless necessary

SELECT
  -- 01 + 源短于目标：直迁
  (s.TRACE) AS AGENTSERIALNO,
  -- 03 + 规则说明非空：用规则
  (case when flag1='0' then '1' else '2' end) AS MBFLAG,
  -- 05 + 规则说明
  (substr(trantime,1,8)) AS CHANNELDATE,
  -- 01 + 源长于目标：截断
  (SUBSTR(s.FUNCTION2, 1, 15)) AS MSGTYPE,
  -- 02 默认值
  ('800001') AS CLEARBRNO,
  -- 04 类型转换 VARCHAR2 → NUMBER
  (TO_NUMBER(TRIM(s.AMOUNT))) AS AMOUNT,
  ...
FROM A10TIBPSDETAILLOG s
WHERE s.{{tmColumn}} >= '{{beginTm}}'
  AND s.{{tmColumn}} <  '{{endTm}}'
  AND (
    FUNCTION2 IN ('ibps.101.001.01','ibps.101.001.02','ibps.103.001.01','ibps.103.001.02')
    and FUNCTION1 not in ('A10M41','A10F55')
  )
;
```

### 3.3 必须保留的 Shell 占位符

| 占位符 | 含义 | 替换方 |
|--------|------|--------|
| `{{beginTm}}` | 时间下界（含） | Shell `sed`/`envsubst` |
| `{{endTm}}` | 时间上界（不含） | Shell |
| `{{tmColumn}}` | 时间过滤列名 | Shell |

约定：

- 生成器 **原样输出** 这三个占位符，不在 Java 侧替换。
- `{{tmColumn}}` 只出现在标识符位置：`s.{{tmColumn}}`。
- 时间值默认按字符串比较；若配置 `timePredicate=to_date`，则生成：

```sql
WHERE s.{{tmColumn}} >= TO_DATE('{{beginTm}}', '{{tmFormat}}')
  AND s.{{tmColumn}} <  TO_DATE('{{endTm}}', '{{tmFormat}}')
```

`{{tmFormat}}` 可作为第四占位符，或在配置里写死为 `YYYYMMDDHH24MISS`。

### 3.4 表级过滤与 JOIN

详见 **§2A.4 / §2A.5**：

- WHERE 谓词 + 时间窗  
- 可选多个 `LEFT JOIN`  
- 同形态冲突策略仍由 `joinConditionConflict` 控制（默认 `error`）

---

## 4. 总体架构

```text
                    ┌─────────────────────────────────────┐
                    │           Web UI (浏览器)            │
                    │  上传 Excel / 选表 / 配导出目录 / 生成 │
                    └─────────────────┬───────────────────┘
                                      │ HTTP
                                      ▼
┌─────────────┐     ┌─────────────────────────────────────┐
│ Excel (.xlsx)│────▶│ map-sql-generator (Spring Boot)     │
└─────────────┘     │  excel → validate → sql render      │
                    │  + REST API + 静态前端               │
                    └─────────────────┬───────────────────┘
                                      │ 每表一个文件
                                      ▼
                    ┌─────────────────────────────────────┐
                    │ {exportDir}/{目标表名}.sql            │
                    └─────────────────┬───────────────────┘
                                      ▼
                    ┌─────────────────────────────────────┐
                    │ Shell：替换 {{...}} → sqlplus SPOOL .dat │
                    │ 生成 .ctl → 目标侧 sqlldr control=….ctl  │
                    └─────────────────────────────────────┘

CLI 复用同一套 core 引擎，不经过 Web。
```

### 4.1 模块划分

| 模块 | 职责 |
|------|------|
| `core/excel` | 解析 xlsx，按 Sheet 产出 `TableMapping` |
| `core/model` | `FieldMapping` / `TableMapping` / `MappingType` |
| `core/validate` | 列完整性、类型、规则、标识符、关联条件 |
| `core/sql` | 渲染单表 SELECT → 写入 `{表名}.sql` |
| `core/service` | `GenerateService`：批量生成、报告 |
| `web` | 上传、配置、触发生成、进度/结果 API + 前端页 |
| `cli` | 命令行入口（复用 `GenerateService`） |

### 4.2 推荐包结构

```text
com.mig.mapsql
  ├── MapSqlWebApplication          # Spring Boot 启动
  ├── cli.MapSqlCliApp              # 可选 CLI
  ├── config.GeneratorProperties
  ├── core.excel.ExcelMappingReader
  ├── core.model.*
  ├── core.validate.MappingValidator
  ├── core.sql.SelectSqlRenderer
  ├── core.service.GenerateService
  ├── web.controller.GenerateController
  ├── web.controller.ConfigController
  └── web.dto.*
resources/
  static/                           # 简易 Web 页（或独立前端构建产物）
```

---

## 4A. Web 界面设计

### 4A.1 页面功能

| 区域 | 功能 |
|------|------|
| 上传 | 选择/拖拽 `.xlsx`；展示文件名、大小；限制扩展名与大小（如 ≤ 50MB） |
| 输出目录 | 文本框配置本机任意可写路径（如 `/opt/mig/sql/map`）；可检测是否可写；不做路径白名单限制 |
| 表选择 | 解析后列出业务 Sheet（目标表）；支持全选 / 多选；默认全选业务表 |
| 选项 | 覆盖已存在文件；失败是否中断整批；是否写 `_meta` |
| 操作 | 「解析预览」「生成 SQL」 |
| 结果 | 成功/失败表清单、每表输出路径、WARN/ERROR 摘要；支持打包下载已生成 SQL（可选） |

### 4A.2 交互流程

```text
1. 用户填写导出目录 →（可选）点「检测目录」
2. 上传 Excel → 服务端临时保存 → 解析 Sheet 列表返回前端
3. 用户勾选目标表 → 点「生成 SQL」
4. 服务端对每张表：校验 → 渲染 → 写入 {exportDir}/{表名}.sql
5. 返回 GenerateReport（成功数、失败数、文件列表、告警）
```

### 4A.3 API（草案）

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/config/export-dir` | 设置/校验导出目录（body: `{ "path": "..." }`） |
| `GET` | `/api/config/export-dir` | 获取当前导出目录与可写状态 |
| `POST` | `/api/excel/upload` | `multipart` 上传；返回 `uploadId` + sheet 列表 |
| `GET` | `/api/excel/{uploadId}/sheets` | 业务表列表（已过滤封面/说明等） |
| `POST` | `/api/generate` | body: `{ uploadId, tables:[], overwrite, stopOnError }` → 报告 |
| `GET` | `/api/generate/{jobId}` | 异步任务状态（表多时可异步；V1 可同步） |
| `GET` | `/api/files` | 列出导出目录下已有 `.sql` |
| `GET` | `/api/files/{tableName}` | 下载单个 `{tableName}.sql` |

### 4A.4 安全与约束

| 项 | 约定 |
|----|------|
| 输出目录 | 不限制白名单；仅校验路径可创建、可写 |
| 上传 | 仅 `.xlsx`；病毒/宏不做深度扫描（内网工具假设）；临时文件定期清理 |
| 鉴权 | V1 内网可无登录；预留 Basic/网关鉴权钩子 |
| 并发 | 同一 `exportDir` 写同名表时加文件锁或拒绝并发生成 |

### 4A.5 前端形态

- V1：Spring Boot 提供单页（原生 HTML + 少量 JS，或 Thymeleaf），无独立重型前端。  
- 不要求炫酷视觉；表单清晰、结果可读即可。

---

## 5. 核心设计细节

### 5.1 领域模型（逻辑）

```text
TableMapping
  targetTable: String
  primarySourceTable: String    # 主表（出现次数最多的源表）
  sourceSystem: String
  wherePredicate: String?       # 主表过滤（原 joinPredicate）
  leftJoins: List<LeftJoinSpec> # LEFT JOIN 列表
  fields: List<FieldMapping>
  tableAliasIndex: Map<sourceTable, alias>  # 主表→s，副表→b/c…

LeftJoinSpec
  tableName: String
  alias: String
  onPredicate: String           # 已将 a. 规范为主表别名 s.
  raw: String                   # Excel 原文

FieldMapping
  seq: int
  targetColumn: String
  targetType / targetLength
  sourceType / sourceLength
  nullable / pk
  mappingType: ENUM
  sourceTable: String?          # 决定列前缀别名
  sourceColumn: String?
  defaultValue: String?
  ruleExpression: String?
  comment: String?
```

### 5.2 字段表达式渲染（实现 §2A）

伪代码：

```text
function renderField(f):
  if f.mappingType == 00: return skip

  if isNotBlank(f.ruleExpression):
     assertLooksLikeSql(f.ruleExpression)   # 否则 ERROR
     expr = normalizeRule(f.ruleExpression) # trim、去尾分号
     return "(" + expr + ") AS " + f.targetColumn

  if f.mappingType == 02:
     return "(" + literal(f.defaultValue, f.targetType) + ") AS " + f.targetColumn

  require sourceColumn
  alias = tableAliasIndex[f.sourceTable]    # 主表 s / 副表 b…
  base = alias + "." + f.sourceColumn
  if typeDiffers(f):
     base = applyTypeCast(base, f.sourceType, f.targetType)
  if charLengthOverflow(f):                 # LENGTH(base) > n 才 SUBSTR
     base = caseWhenLengthGt(base, f.targetLength)
  return "(" + base + ") AS " + f.targetColumn
```

`literal` 规则：

- `VARCHAR2`/`CHAR`/`NVARCHAR2` → 单引号转义  
- `NUMBER` → 不加引号（校验为数字）  
- 文本 `NULL`（忽略大小写）→ `NULL`

### 5.3 规则说明规范化

1. 去首尾空白、末尾分号  
2. 外层由渲染统一加括号  
3. **不**自动改写列名前缀（与现网 `flag1`/`FUNCTION2` 写法一致）  
4. 可选 `ruleColumnPrefix=s`（默认关）

### 5.4 校验规则（失败则非 0 退出）

| 级别 | 规则 |
|------|------|
| ERROR | 目标栏位空；映射类型未知 |
| ERROR | 无法判定主表（无任何源表表名） |
| ERROR | 副源表存在，但「库表关联条件」中无对应 `LEFT JOIN` |
| ERROR | 同形态关联条件多条且内容冲突（两个不同 WHERE，或**同一 JOIN 别名**下表名/ON/类型不一致） |
| ERROR | `01`/`04` 在规则为空时缺源表栏位；`02` 缺默认值；`03` 缺规则说明 |
| ERROR | 规则说明非空但判定为非 SQL（自然语言）且策略要求失败 |
| ERROR | 类型不同且无规则、又不在内置转换表中 |
| ERROR | 目标/源列名非法标识符 |
| ERROR | SELECT 列表为空 |
| WARN | `05` 规则空、已降级为源列+类型/长度处理 |
| WARN | LEFT JOIN 原文使用别名 `a` 已改写为 `s` |
| WARN | 出现非 LEFT 的 JOIN 形态（暂不支持） |
| WARN | PK 为 `00-不迁移` |
| INFO | 源长短于目标（直迁不 Pad） |

> **已取消：**「同表多个源表表名 ⇒ 直接 ERROR」。多源表改为走 LEFT JOIN 校验。

---

## 6. 启动方式与配置

### 6.1 Web 启动（主推）

```bash
java -jar map-sql-generator.jar
# 默认 http://localhost:8080
# 浏览器打开 → 上传 Excel → 填写导出目录 → 生成
```

`application.yml` 示例：

```yaml
server:
  port: 8080
mig:
  default-export-dir: /opt/mig/sql/map
  upload-max-size: 50MB
  upload-temp-dir: /tmp/mapsql-upload
```

### 6.2 CLI（辅）

```bash
java -jar map-sql-generator.jar --cli \
  --excel /path/mapping.xlsx \
  --out-dir /opt/mig/sql/map \
  --sheet ALL \
  --overwrite true
```

单表：

```bash
java -jar map-sql-generator.jar --cli \
  --excel /path/mapping.xlsx \
  --out-dir /opt/mig/sql/map \
  --sheet IBPS_PAYMENTINIT
```

产出：`/opt/mig/sql/map/IBPS_PAYMENTINIT.sql`（不是子目录 `map.sql`）。

### 6.3 生成相关配置项

| 键 | 默认 | 说明 |
|----|------|------|
| `headerRow` | 3 | 表头行号（1-based） |
| `sourceAlias` | `s` | FROM 别名 |
| `timePredicate` | `string` | `string` \| `to_date` |
| `tmFormat` | `YYYYMMDDHH24MISS` | 时间/`TO_DATE`/`TO_CHAR` 格式 |
| `lengthOverflow` | `substr` | `substr` \| `error` \| `warn` |
| `dateFormat` | `YYYYMMDDHH24MISS` | 日期类型互转 |
| `includeHeaderComment` | true | 列注释 |
| `skipMappingTypes` | `00-不迁移` | |
| `joinConditionConflict` | `error` | 多行「库表关联条件」冲突 |
| `requireSqlRuleWhenPresent` | true | 规则非空必须像 SQL |
| `charset` | `UTF-8` | 输出编码 |
| `mig.default-export-dir` | — | 页面默认 SQL 输出目录 |

---

## 7. 与 Shell 导出 / 装载的衔接（合约）

Java **只保证** `{表名}.sql` 合约；Shell 负责参数替换与 **sqlplus 导出**；目标侧用 **sqlldr** 装载。现网导出客户端为 **sqlplus**（不用 SQLcl）。

### 7.1 标准导出脚本（唯一推荐形态）

跳板机需已安装 **sqlplus**（导出）与 **sqlldr**（装载），均在 `PATH` 中。产物为：

- **无表头**、字段分隔符 **`CHR(1)`（`0x01`）** 的 `.dat`
- 同序的 sqlldr 控制文件 `.ctl`

**不生成** `.csv` / `.json` / `_load.sql`。选用 `0x01` 而非 `|`：源文本可能含 `|`，定界符冲突会导致错列。

路径与连接只读脚本同目录的 `export-job.conf`，不使用环境变量：

| 键 | 含义 |
|----|------|
| `sqlDir` | SQL 模板目录，文件为 `{表名}.sql` |
| `outDir` | `.dat` / `.ctl` 输出目录 |
| `connect` | 源库 sqlplus 连接串 `user/password@SERVICE` |
| `tgtConnect` | 目标库 sqlldr 连接串（`load_by_map.sh`） |
| `ctlCharset` | 可选，默认 `AL32UTF8`；写入 `.ctl` 的 `CHARACTERSET` |
| `loadMode` | 可选，默认 `APPEND`；`APPEND` / `TRUNCATE` / `REPLACE` / `INSERT` |
| `sqlldrErrors` / `sqlldrRows` / `sqlldrDirect` | 可选；传给 sqlldr |

时间窗与表名仍由命令行传入（单表、单窗）：

```bash
./export_by_map.sh 20240101 20240102 TRANDATE IBPS_PAYMENTINIT
# → {outDir}/IBPS_PAYMENTINIT_20240101_20240102.dat
# → {outDir}/IBPS_PAYMENTINIT_20240101_20240102.ctl
```

`export_by_map.sh` 行为：

1. 替换占位符；从 map SQL 提取 `) AS 列名` 作为列序  
2. 包成子查询，用 `NVL(TO_CHAR(q.列), '') || CHR(1) || ...` 拼单列后 `SPOOL` → `.dat`  
3. 按同一列序生成 `.ctl`（`INFILE` 指向该 `.dat`，`FIELDS TERMINATED BY X'01'`）

导出会话：无表头、`PAGESIZE 0`、`LINESIZE` 足够大、`TRIMSPOOL ON`。须避免折行截断。

### 7.2 导入目标库（sqlldr 脚本）

单片：

```bash
./load_by_map.sh 20240101 20240102 IBPS_PAYMENTINIT
# 等价于：
# sqlldr userid=${tgtConnect} control=.../IBPS_PAYMENTINIT_20240101_20240102.ctl \
#   log=....log bad=....bad errors=${sqlldrErrors} rows=${sqlldrRows} direct=${sqlldrDirect}
```

批量（优先消费 `export-manifest.txt` 的 OK 行）：

```bash
./load_batch.sh
# → {outDir}/_batch/load-manifest.txt
```

`export-job.conf` 装载相关键：`tgtConnect`（必填）、`sqlldrErrors` / `sqlldrRows` / `sqlldrDirect`（可选）。

`.ctl` 要点（列序 = map SQL 的 `AS` 顺序，与表 DDL 物理顺序无关）：

```text
LOAD DATA
CHARACTERSET AL32UTF8
INFILE '/opt/mig/out/IBPS_PAYMENTINIT_20240101_20240102.dat'
BADFILE '...bad'
APPEND INTO TABLE IBPS_PAYMENTINIT
FIELDS TERMINATED BY X'01'
TRAILING NULLCOLS
(
  AGENTSERIALNO CHAR(4000) NULLIF AGENTSERIALNO=BLANKS,
  MBFLAG CHAR(4000) NULLIF MBFLAG=BLANKS,
  ...
)
```

约定：空字段按 `NULLIF …=BLANKS` 置 NULL；值以字符读入，由 Oracle 隐式转换到 NUMBER/DATE 等目标类型（日期格式依赖会话/`NLS_DATE_FORMAT`，必要时在 ctl 为日期列加 `DATE '…'` 掩码——属表级定制，默认脚本统一 `CHAR`）。

合约：

1. `.dat` 无表头；第 N 字段对应 SELECT 第 N 个 `AS` 目标列。  
2. `.ctl` 列清单顺序必须与该列序一致。  
3. 装载走 **sqlldr**；不再使用 INSERT 脚本 / 外部表。

### 7.3 多文件与日期分片

`export_batch.sh` 读同一份 `export-job.conf`，按文件列表 × 时间片串行调用 `export_by_map.sh`。不使用环境变量。

| 键 | 含义 |
|----|------|
| `start` / `end` | 总范围，左闭右开；`YYYYMMDD` 或 `YYYYMMDDHH24MISS` |
| `interval` | 只按自然月：`Nm`，如 `1m`；`start`/`end` 建议月初 |
| `onError` | `stop` 立即停；`continue` 记失败后继续，结束时仍返回非 0 |
| `tables` | **每表必配时间列**：后续行 `表名 = 时间列`（任意 Oracle 标识符）。无全局默认；已移除 `tmColumn` / `files` |

```bash
./export_batch.sh
# → {outDir}/{表名}_{sliceBegin}_{sliceEnd}.dat
# → {outDir}/{表名}_{sliceBegin}_{sliceEnd}.ctl
# → {outDir}/_batch/export-manifest.txt
#    行格式：表名,片开始,片结束,dat路径,ctl路径,OK|FAIL
```

| 约定 | 说明 |
|------|------|
| 导出方式 | 客户端拉数（sqlplus），单线程，按时间窗切批 |
| 装载方式 | sqlldr 按片执行对应 `.ctl` |
| 单次建议 | 控制在百万～千万行，或单文件约数 GB 内 |
| 不做的事 | 单次 SPOOL 硬导 1 亿行；不导出 CSV/JSON；不按天切分 |

### 7.4 合约约束

1. 三个占位符名称固定，大小写敏感：`{{beginTm}}` / `{{endTm}}` / `{{tmColumn}}`。  
2. `{表名}.sql` 以单条 `SELECT ... ;` 为主（可有注释）；SELECT 列表顺序 = Excel 非 `00` 映射行序 = `.dat` 字段序 = `.ctl` 列序。  
3. Shell 侧对 `{{tmColumn}}` 做标识符格式校验后再替换（不限定列名集合）。  
4. 运行时：**sqlplus** 导出无表头 **`0x01` `.dat`**；**sqlldr** 按同序 **`.ctl`** 装载。  
5. 跳板机统一 `NLS_LANG` / `.ctl` `CHARACTERSET`，避免中文乱码。  
6. 分隔符不用 `|`（源数据可能含 `|`）。

---

## 8. 技术选型

| 组件 | 选型 | 理由 |
|------|------|------|
| JDK | **1.8+**（按 1.8 编译） | 兼容现网 JVM |
| 应用框架 | Spring Boot **2.7.x** | Web + 配置；支持 Java 8 |
| Excel | EasyExcel 或 Apache POI | 优先 EasyExcel |
| Web API | Spring MVC REST | 上传与生成 |
| 前端 | 服务端静态页（HTML/JS）或 Thymeleaf | 轻量，满足内网工具 |
| CLI | picocli 或 Spring ApplicationRunner | 复用 core |
| 测试 | JUnit 5 + MockMvc | SQL 快照 + API |
| 日志 | SLF4J + Logback | |

不引入 JDBC（V1 不连库验证 SQL）。

---

## 9. 测试策略

| 类型 | 内容 |
|------|------|
| 单元 | 各 `MappingType` 片段渲染；literal 转义；占位符保留 |
| 快照 | 以 V0.4 `IBPS_PAYMENTINIT` 为 fixture，对比 `IBPS_PAYMENTINIT.sql` |
| 单测 | 规则非空优先；`04` VARCHAR2→NUMBER；源长>目标长 `SUBSTR`；关联条件提取 WHERE |
| 负例 | 缺源列、自然语言规则、类型无法转换、关联条件冲突 |
| 契约 | 含 `{{beginTm}}`/`{{endTm}}`/`{{tmColumn}}`；WHERE 含关联条件谓词 |

---

## 10. 交付物与目录建议

```text
map-sql-generator/
  pom.xml
  README.md
  src/main/java/com/mig/mapsql/...
  src/main/resources/
    application.yml
    static/index.html             # Web 界面
  src/test/resources/fixtures/
    IBPS_PAYMENTINIT_sample.xlsx
    IBPS_PAYMENTINIT_expected.sql
  docs/
    map-sql-generator-design.md
```

运维侧：

```text
scripts/
  export-job.conf                 # 路径、源/目标连接、文件列表、时间范围与 sqlldr 参数
  export_by_map.sh                # 单表单窗：sqlplus SPOOL .dat + 生成 sqlldr .ctl
  export_batch.sh                 # 多文件 × 时间片，逐次调用 export_by_map.sh
  load_by_map.sh                  # 单表单窗：sqlldr 装载对应 .ctl
  load_batch.sh                   # 按 export-manifest OK 行（或 conf 扫片）逐次装载
```

---

## 11. 实现阶段

| 阶段 | 内容 | 完成标准 |
|------|------|----------|
| P0 | core：读 Excel + 规则优先 + 单表生成 `{表名}.sql` | CLI 能产出单文件 |
| P1 | 类型/长度/`03/04/05`/关联条件 WHERE | `IBPS_PAYMENTINIT.sql` 快照通过 |
| P2 | **Web：上传、导出目录、选表、批量生成、结果展示** | 浏览器可完成全流程 |
| P3 | **LEFT JOIN / 多源表别名**（§2A.4–2A.5） | `HVPS_PAYMENTINIT.sql` 可生成且含 LEFT JOIN |
| P4 | meta 报告、打包下载、鉴权钩子、全册批量 | 按需 |

---

## 12. 风险与约束

1. **规则说明质量依赖人工**：错误 CASE 会原样进 SQL，运行期才失败。  
2. **大结果集导出性能**：本工具不解决；分批靠 Shell 传时间窗。  
3. **Excel 多行「库表关联条件」不一致**：必须在校验期暴露。  
4. **字符集**：`.dat` / `.ctl` `CHARACTERSET` 与 Oracle / sqlplus / sqlldr 字符集需运维统一，生成器只保证 UTF-8 源 SQL。  
5. **不生成中间表**：任何「落库再导」需求不在本工程范围。  
6. **依赖 sqlplus + sqlldr**：源导出用 sqlplus；目标装载用 sqlldr（Instant Client Tools 等）。  
7. **定界与折行**：字段内若出现 `0x01` 或嵌入换行会错列；`LINESIZE` / `TRIMSPOOL` 配置不当会导致截断，须在试点表验证。  
8. **超长字段**：`.ctl` 默认 `CHAR(4000)`；若单字段可能更长，需按表调大或改 `CHAR`/`VARCHARC`。

---

## 13. 验收标准

- [ ] Web 可上传 V0.4 Excel，配置导出目录，勾选表后生成
- [ ] **一张目标表对应一个** `{导出目录}/{表名}.sql`
- [ ] **规则说明非空**的列使用该规则
- [ ] **类型不同 / 长度溢出 / 库表关联条件** 按 §2A 处理
- [ ] WHERE 含时间占位符与关联条件谓词
- [ ] **支持 LEFT JOIN**：`HVPS_PAYMENTINIT` 等主从表可生成，副表字段使用正确别名
- [ ] 多源表不再因「源表不一致」误杀；缺 JOIN 定义才 ERROR
- [ ] 输出目录可任意配置（可写即可）；同名覆盖策略可用
- [ ] CLI 与 Web 共用同一生成引擎；Shell + sqlplus 产出 `0x01` `.dat` 与同序 `.ctl`；目标侧 sqlldr 可装载

---

## 14. 一句话范围

**Web 上传 Excel、配置输出目录；按 V0.4 规则一表一 SQL。**  
**规则优先；类型/长度处理；WHERE + 可选 LEFT JOIN；跳板机 sqlplus 导出无表头 `0x01` `.dat` + `.ctl`，目标侧 sqlldr 装载。**

---

## 15. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| V1.0 | 2026-03-26 | 初稿：Excel → map.sql；Shell 合约初版（sqlplus） |
| V1.1 | 2026-03-26 | 运行时导出定为 SQLcl `SET SQLFORMAT csv` + SPOOL；明确不采用 JSON |
| V1.2 | 2026-03-27 | 对齐 V0.4：规则说明优先、类型转换、长度处理、关联条件→WHERE；`04-类型转换` |
| V1.3 | 2026-03-27 | 增加 Web 界面：上传 Excel、配置导出目录、一表一 `{表名}.sql`；Spring Boot 架构 |
| V1.4 | 2026-11-03 | **支持 LEFT JOIN / 多源表**：关联条件分类（WHERE vs JOIN）；主表频次判定；副表别名；取消「多源表直接失败」 |
| V1.5 | 2026-11-09 | 运行时改回 **sqlplus**：无表头 `0x01` `.dat`；列序=映射/SELECT；明确不用 `|`、不用 CSV |
| V1.6 | 2026-11-09 | 装载曾改为 `.dat` → `INSERT` 脚本 → sqlplus `@`（过渡） |
| V1.7 | 2026-11-09 | 确认可用 **sqlldr**：装载改为 `.dat` + `.ctl`；去掉 `_load.sql` / INSERT 脚本路径 |
| V1.8 | 2026-11-09 | 增加 **`load_by_map.sh` / `load_batch.sh`**；conf 增加 `tgtConnect` 与 sqlldr 运行参数 |
| V1.9 | 2026-11-09 | 批量配置改为 **`tables`：每表一行 `表名 = 时间列`**；移除全局 `tmColumn` / `files` |
| V1.10 | 2026-11-09 | JOIN 按别名去重；同表多关联可用不同别名；同别名冲突（含同表不同 ON）**自动改别名**；字段按源表+ON 匹配最终别名 |

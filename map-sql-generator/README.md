# 研发效能工具（map-sql-generator）

本机研发效能套件（Spring Boot），当前包含：

1. **数据迁移SQL映射**：将「数据迁移中间表映射关系」Excel 生成 Oracle `SELECT`（一表一 `{表名}.sql`）
2. **测试用例SQL映射**：同一 Excel 生成源/目标对照测试 SQL（记录数、字段 MD5、金额 SUM、主键）；落盘 `{exportDir}/test/{Sheet}/`
3. **目标库与Excel一致比对**：OceanBase（Oracle 模式）基表结构 vs 映射 Excel 左侧目标栏位；类型不同标黄改写，库多则追加，Excel 多则标红
4. **落标一致比对**：按 SQLite 数标规则扫描 OceanBase 基表，生成 ALTER SQL
5. **多环境数据库一致比对**：左库基线对齐右库 DDL + 数标规则
6. **错误码标准参考**：厂商错误码映射银行规范码入库 SQLite，按分类筛选 + 错误码/说明模糊查询

要求：**JDK 8+**（工程按 1.8 编译；Spring Boot 2.7.18）。

## 启动 Web

```bash
cd map-sql-generator
mvn spring-boot:run
```

浏览器打开：http://localhost:8080

左侧菜单切换工具。

---

## 工具 1：数据迁移SQL映射

1. 配置 SQL 文件输出目录（默认 `~/mig/sql/map`）
2. 上传 Excel（`.xlsx`）
3. 勾选目标表 → 生成

### 配置

`src/main/resources/application.yml`：

- `mig.default-export-dir`：页面默认输出目录
- `mig.datasources`：OceanBase Oracle 模式数据源列表（`id` / `jdbcUrl` / `schema` / `enabled`）

### 生成规则摘要

- 规则说明非空且像 SQL → 优先使用
- 类型不同 → 自动转换（如 `VARCHAR2`→`NUMBER`）
- 字符型源长 > 目标长 → `SUBSTR`
- 「库表关联条件」解析进 `WHERE`，并支持 `LEFT JOIN` / `INNER JOIN`（含 `select * from … inner join …` 同格写法）
- 时间条件保留 `{{beginTm}}` / `{{endTm}}` / `{{tmColumn}}`

跳板机：**sqlplus** 导出 `.dat` + `.ctl`（`export_*.sh`），**sqlldr** 装载（`load_*.sh`）。编辑 `scripts/export-job.conf`（含 `tgtConnect`）。说明见 `scripts/README.md` 与 `docs/map-sql-generator-design.md` §7。

---

## 工具 1b：测试用例SQL映射

1. 配置输出根目录（与 map SQL 相同；产物写入其下 `test/`）
2. 上传同一份映射 Excel
3. 勾选目标表，生成测试 SQL

设计见 `docs/excel-to-test-sql-design.md`。跳板机比对：`scripts/test-job.conf` + `test_by_map.sh` / `test_batch.sh`。

API：`POST /api/generate-test`（请求体与 `/api/generate` 相同）

---

## 工具 2：目标库与Excel一致比对

1. 在 `application.yml` 配置 `mig.datasources`，将目标库 `enabled: true`
2. 打开「目标库与Excel一致比对」：选择数据源，上传 V0.4 映射 Excel，下载标注结果
3. 规则（以库为准，对齐 Excel **左侧目标栏位**）：
   - 数据类型/长度不同 → 改写并 **黄色** 标注
   - 库有、Excel 无 → **追加**行（备注 `【DB新增】`）
   - Excel 有、库无 → **红色** 标注，备注提醒人工删除
4. 汇总见结果文件中的 `_DB_EXCEL_DIFF` Sheet

API：`GET /api/datasources`、`POST /api/datasources/test`、`POST /api/excel-db-diff`

---

## 工具 3：落标一致比对

按 SQLite 数标规则扫描 OceanBase（Oracle 模式）基表，生成 ALTER SQL。

规则类型：

- `TYPE`：按数据类型族匹配，如 `CHAR` → `VARCHAR2({n})`，`CLOB` → `VARCHAR2(400)`（`{n}`/`{p}`/`{s}` 取自当前列）
- `COLUMN`：字段名全匹配（忽略大小写），如 `AMOUNT` → `NUMBER(22,2)`

CHAR / CLOB / NUMBER 等不能直接 `MODIFY` 时，生成**四步重建**：

1. `ALTER TABLE ... ADD (col_MIGTMP ...)`
2. `COMMENT ON COLUMN ...`（复制原 comment）
3. `UPDATE ... SET col_MIGTMP = ...`（拷贝原始值）
4. `DROP COLUMN` 旧字段 + `RENAME COLUMN` 临时字段改回原名

### 配置

```yaml
mig:
  standard-db-path: ${user.home}/mig/data-standard.db
```

首次启动自动建表并写入默认规则（CHAR / CLOB / AMOUNT）。

### API

- `GET/POST/PUT/DELETE /api/standard/rules`
- `POST /api/standard/scan` — `{ "datasourceId": "..." }` → 命中列表 + SQL 文本
- `POST /api/standard/scan/export` — 下载 `.sql`

---

## 工具 5：错误码标准参考

将 `docs/厂商错误码映射银行规范错误码表.xlsx`（内置副本 `classpath:errorcode/error-code-standard.xlsx`）导入 SQLite 表 `error_code_standard`（与数标共用 `mig.standard-db-path`）。

### 页面用法

1. 打开「错误码标准参考」
2. 按分类（业务类1 / 系统类2 / 结果未知类3）筛选
3. 「错误码」模糊匹配完整错误码、厂商原码、常量名；「错误说明」模糊匹配银行错误说明

### API

- `GET /api/errorcode/summary` — 合计与分类统计
- `GET /api/errorcode/categories` — 分类列表
- `GET /api/errorcode?category=&code=&message=` — 查询

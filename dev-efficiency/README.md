# 研发效能工具（dev-efficiency）

本机研发效能套件（Spring Boot），当前包含：

1. **数据迁移SQL映射**：将「数据迁移中间表映射关系」Excel 生成 Oracle `SELECT`（一表一 `{表名}.sql`）
2. **测试用例SQL映射**：同一 Excel 生成源/目标对照测试 SQL（记录数、字段 MD5、金额 SUM、主键）；落盘 `{exportDir}/test/{Sheet}/`
3. **目标库与Excel一致比对**：OceanBase（Oracle 模式）基表结构 vs 映射 Excel 左侧目标栏位；类型不同标黄改写，库多则追加，Excel 多则标红
4. **落标一致比对**：按 SQLite 数标规则扫描 OceanBase 基表，生成 ALTER SQL
5. **多环境数据库一致比对**：左库基线对齐右库 DDL + 数标规则
6. **错误码标准参考**：厂商错误码映射银行规范码入库 SQLite，按分类筛选 + 错误码/说明模糊查询
7. **IBM MQ 快速消费**：网页配置 IP/端口/队列管理器/通道/来账·往账队列（SQLite），选定后开始/停止持续消费
8. **CNCCQ 快速消费**：同来账/往账模型，无通道；依赖本地介质 `lib/cnccq-jms-base-V2.0.5.0.jar`（`mvn validate` 或 `lib/install-cnccq.sh` 装入本地仓）

要求：**JDK 8+**（工程按 1.8 编译；Spring Boot 2.7.18）。

## 启动 Web（开发）

```bash
cd dev-efficiency
# 首次需装 CNCCQ 介质到本地仓（亦可依赖 validate 阶段自动 install-file）
./lib/install-cnccq.sh
mvn spring-boot:run
```

浏览器打开：http://localhost:8080

左侧菜单切换工具。

## 打包运行（外挂配置 + SQLite）

```bash
cd dev-efficiency
mvn -DskipTests package
```

产物目录：`target/dist/`

| 文件 | 说明 |
|------|------|
| `dev-efficiency-*.jar` | 可执行胖 jar（**不含** `application.yml`） |
| `application.yml` | 外挂配置，与 jar 同目录；改端口/数据源后重启即可 |
| `data-standard.db` | 由 `mig.standard-db-path` 指定，默认 `./data-standard.db`；可不预置，首次启动自动建表 |
| `start.sh` | 启动脚本：`-d` 后台，`-l` 指定日志目录（默认 `../logs`） |

```bash
cd target/dist
./start.sh              # 前台
./start.sh -d           # 后台 → ../logs/dev-efficiency.log
./start.sh -d -l /var/log/mig
```

Spring Boot 会自动加载同目录 `application.yml`。也可显式指定：

```bash
java -jar dev-efficiency-1.0.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:./application.yml \
  --mig.standard-db-path=./data-standard.db
```

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
- 时间列：Excel 备注含 `{{时间分区}}` 的行取其**源表栏位**写入 SQL；无标记则不拼时间区间

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
   - 类型/长度不一致 → 标黄并改写 Excel 类型/长度为目标库值
   - 库有、Excel 无 → 在 Sheet 末追加行（仅填目标栏位）
   - Excel 有、库无 → 该行标红

---

## 工具 3 / 4 / 5 / 6

见页面左侧菜单；数标、错误码、IBM MQ / CNCCQ 配置落在 `mig.standard-db-path` 指向的 SQLite。

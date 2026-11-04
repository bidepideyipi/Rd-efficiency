# 研发效能工具（map-sql-generator）

本机研发效能套件（Spring Boot），当前包含两个工具：

1. **Excel → SQL**：将「数据迁移中间表映射关系」Excel 生成 Oracle `SELECT`（一表一 `{表名}.sql`）
2. **代码扫描 dealcode**：扫描工程目录下 `.fc`，提取 `paramList` 中的 `dealcode` / `dealmsg`，输出 CSV（明细后附 dealcode 总览统计）

要求：**JDK 8+**（工程按 1.8 编译；Spring Boot 2.7.18）。

## 启动 Web

```bash
cd map-sql-generator
mvn spring-boot:run
```

浏览器打开：http://localhost:8080

左侧菜单切换工具。

---

## 工具 1：Excel → SQL

1. 配置 SQL 文件输出目录（默认 `~/mig/sql/map`）
2. 上传 Excel（`.xlsx`）
3. 勾选目标表 → 生成

### 配置

`src/main/resources/application.yml`：

- `mig.default-export-dir`：页面默认输出目录

### 生成规则摘要

- 规则说明非空且像 SQL → 优先使用
- 类型不同 → 自动转换（如 `VARCHAR2`→`NUMBER`）
- 字符型源长 > 目标长 → `SUBSTR`
- 「库表关联条件」解析进 `WHERE`，并支持 `LEFT JOIN` / `INNER JOIN`（含 `select * from … inner join …` 同格写法）
- 时间条件保留 `{{beginTm}}` / `{{endTm}}` / `{{tmColumn}}`

跳板机导出 CSV 见仓库 `scripts/export_by_map.sh`。

---

## 工具 2：代码扫描 dealcode

扫描指定工程根目录下所有 `.fc` 文件：

- 优先解析 `<Key>paramList</Key>` 后的内层 `<Arg>` 文本
- 否则用正则配对 `["dealcode","..."]` 与 `["dealmsg","..."]`
- 相对根目录：**一级目录 = 服务名**，**二级目录 = 模块名**（更深层级忽略）

### 页面用法

1. 填写工程根目录（可用仓库内 `example/` 验证）
2. 检测目录 → 开始扫描
3. 预览 CSV → 下载 `dealcode-scan.csv`（UTF-8 BOM，便于 Excel）

### CSV 结构

明细区：

```text
服务名,模块名,dealcode,dealmsg
```

空行后为总览（按 dealcode 字母升序）：

```text
总览
dealcode,数量
A00000,1
A000000,2
FIBC009,1
```

样例（根目录为 `example`）：

| 相对路径 | 服务名 | 模块名 |
|----------|--------|--------|
| `demo1.fc` | （空） | （空） |
| `BEPS/BEPSBusiCheckService/scene/.../x.fc` | `BEPS` | `BEPSBusiCheckService` |

### API

- `GET /api/scan/dealcode/config` — 默认工程目录
- `POST /api/scan/dealcode/check-dir` — `{ "path": "..." }`
- `POST /api/scan/dealcode` — `{ "projectDir": "..." }` → JSON + CSV 预览
- `POST /api/scan/dealcode/export` — 同上，返回 CSV 附件

### 配置

```yaml
mig:
  scan:
    default-project-dir: ""   # 可选，预填页面工程根目录
```

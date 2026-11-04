# map-sql-generator

将「数据迁移中间表映射关系 V0.4」Excel 生成 Oracle `SELECT` SQL。  
**一张目标表对应一个 `{表名}.sql` 文件**。

要求：**JDK 8+**（工程按 1.8 编译；Spring Boot 2.7.18）。

## 启动 Web

```bash
cd map-sql-generator
mvn spring-boot:run
```

浏览器打开：http://localhost:8080

1. 配置 SQL 文件输出目录（默认 `~/mig/sql/map`，任意本机可写路径）
2. 上传 Excel
3. 勾选目标表 → 生成

## 配置

`src/main/resources/application.yml`：

- `mig.default-export-dir`：页面默认输出目录

## 生成规则摘要

- 规则说明非空且像 SQL → 优先使用
- 类型不同 → 自动转换（如 `VARCHAR2`→`NUMBER`）
- 字符型源长 > 目标长 → `SUBSTR`
- 「库表关联条件」解析进 `WHERE`
- 时间条件保留 `{{beginTm}}` / `{{endTm}}` / `{{tmColumn}}`

## 跳板机导出 CSV

见仓库 `scripts/export_by_map.sh`。

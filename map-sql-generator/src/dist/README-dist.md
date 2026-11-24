# 研发效能工具 — 发布目录

本目录为可运行包，**配置与 SQLite 均在 jar 外**：

| 文件 | 说明 |
|------|------|
| `map-sql-generator-*.jar` | 应用（不含 `application.yml`） |
| `application.yml` | 外挂配置（端口、数据源、`mig.standard-db-path` 等） |
| `data-standard.db` | SQLite（数标 / 错误码 / MQ 配置）；可不预置，首次启动自动创建建表 |
| `start.sh` / `start.bat` | 在本目录启动（保证相对路径正确） |

## 启动

```bash
# Linux / macOS
./start.sh

# Windows
start.bat

# 或手动（须在本目录执行）
java -jar map-sql-generator-1.0.0-SNAPSHOT.jar
```

浏览器：http://localhost:8080

## 外挂说明

- 修改数据源、端口等：编辑同目录 `application.yml` 后重启即可，无需重打 jar。
- 更换 / 备份 SQLite：调整 `mig.standard-db-path`（默认 `./data-standard.db`），或直接替换该文件。
- 也可用绝对路径，例如：`standard-db-path: /data/mig/data-standard.db`

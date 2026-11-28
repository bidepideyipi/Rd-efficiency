# 研发效能工具 — 发布目录

本目录为可运行包，**配置与 SQLite 均在 jar 外**：

| 文件 | 说明 |
|------|------|
| `dev-efficiency-*.jar` | 应用（不含 `application.yml`） |
| `application.yml` | 外挂配置（端口、数据源、`mig.standard-db-path` 等） |
| `data-standard.db` | SQLite（数标 / 错误码 / MQ 配置）；可不预置，首次启动自动建表 |
| `start.sh` | 启动脚本（前台 / 后台，日志目录可配） |

## 启动

```bash
# 前台
./start.sh

# 后台（日志默认 ../logs/dev-efficiency.log）
./start.sh -d

# 指定日志目录
./start.sh -d -l /var/log/dev-efficiency

# 透传 Spring 参数
./start.sh -d -- --server.port=8081
```

浏览器：http://localhost:8080

## 外挂说明

- 修改数据源、端口等：编辑同目录 `application.yml` 后重启即可，无需重打 jar。
- 更换 / 备份 SQLite：调整 `mig.standard-db-path`（默认 `./data-standard.db`），或直接替换该文件。
- 也可用绝对路径，例如：`standard-db-path: /data/mig/data-standard.db`

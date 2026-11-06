# 跳板机导出操作说明

在已安装 SQLcl 的跳板机上，把映射生成的 `{表名}.sql` 按时间窗导出为 CSV。本目录三个文件放在一起使用，不读取环境变量。

| 文件 | 作用 |
|------|------|
| `export-job.conf` | 路径、连接、表清单、时间范围 |
| `export_by_map.sh` | 一张表、一个时间窗 |
| `export_batch.sh` | 按表清单 × 按天切分，逐次调用上面的脚本 |

SQLcl 可执行文件名须为 `sql`，且在 `PATH` 中。

```bash
chmod +x export_by_map.sh export_batch.sh
which sql
```

## 1. 改配置

编辑同目录的 `export-job.conf`。`connect` 含数据库口令，填入真实值后不要提交到仓库。

```ini
sqlDir = /opt/mig/sql/map
outDir = /opt/mig/out
connect = user/password@SERVICE

tmColumn = TRANDATE
start = 20240301
end = 20270301
interval = 1d
onError = stop

files =
  IBPS_PAYMENTINIT
  HVPS_PAYMENTINIT
```

| 键 | 谁用 | 说明 |
|----|------|------|
| `sqlDir` | 两个脚本 | SQL 模板目录。文件名必须是 `{表名}.sql` |
| `outDir` | 两个脚本 | CSV 输出目录，不存在时会创建 |
| `connect` | `export_by_map.sh` | SQLcl 连接串，格式 `user/password@SERVICE` |
| `tmColumn` | `export_batch.sh` | 默认时间列，用来替换 SQL 中的 `{{tmColumn}}` |
| `start` / `end` | `export_batch.sh` | 总范围，左闭右开。`YYYYMMDD` 或 `YYYYMMDDHH24MISS`，两边格式必须相同，且 start 早于 end |
| `interval` | `export_batch.sh` | 只按天，如 `1d`、`7d`。不支持按小时 |
| `onError` | `export_batch.sh` | `stop`：失败立即停。`continue`：继续后面的分片，全部结束后仍返回非 0 |
| `files` | `export_batch.sh` | 要导出的表。可写表名或 `{表名}.sql` |

`files` 整段删掉，或只留 `files =` 且下面没有表名时，导出 `sqlDir` 目录下全部 `*.sql`（不含子目录）。

某一张表的时间列和 `tmColumn` 不同时，在表名后加竖线：

```ini
files =
  IBPS_PAYMENTINIT
  HVPS_PAYMENTINIT|WORKDATE
```

上面第二行只把 `HVPS_PAYMENTINIT.sql` 里的 `{{tmColumn}}` 换成 `WORKDATE`，其它表仍用 `tmColumn`。允许的时间列：`TRANDATE`、`TRANTIME`、`WORKDATE`、`SENDTIME`。

## 2. 批量导出

在任意目录执行均可，脚本按自身所在目录找配置。

```bash
/opt/mig/scripts/export_batch.sh
```

按「先一张表的全部分片，再下一张表」串行执行。每片左闭右开。间隔大于剩余天数时，最后一片的上界截到 `end`。

`start = 20240301`、`end = 20240304`、`interval = 1d` 时，每张表得到：

```text
{表名}_20240301_20240302.csv
{表名}_20240302_20240303.csv
{表名}_20240303_20240304.csv
```

标准输出每一行是：`表名 片开始 片结束 时间列`。清单在每次启动时重写：

```text
{outDir}/_batch/export-manifest.txt
```

每行格式：`表名,片开始,片结束,csv路径,OK|FAIL`。

开始前会检查清单里的 SQL 是否都存在，缺文件则一片都不导。

## 3. 单表单窗

不读 `start`、`end`、`interval`、`files`，时间窗和表名由参数传入：

```bash
./export_by_map.sh <beginTm> <endTm> [tmColumn] [表名]
```

示例：

```bash
./export_by_map.sh 20240301 20240302 TRANDATE IBPS_PAYMENTINIT
```

省略后两个参数时，时间列为 `TRANDATE`，表名为 `IBPS_PAYMENTINIT`。仍从 `export-job.conf` 读取 `sqlDir`、`outDir`、`connect`。

输出文件：`{outDir}/{表名}_{beginTm}_{endTm}.csv`。

## 4. SQL 里被替换的占位符

生成器写出的 SQL 保留这三个占位符，导出时由脚本替换：

| 占位符 | 替换成 |
|--------|--------|
| `{{beginTm}}` | 本片开始时间（含） |
| `{{endTm}}` | 本片结束时间（不含） |
| `{{tmColumn}}` | 时间列名 |

替换后的语句写到 `/tmp/{表名}_run.sql`，再交给 SQLcl：`SET SQLFORMAT csv` 后 `SPOOL`。CSV 第一行是表头。

## 5. 失败时看哪里

- 配置缺键、间隔不是 `Nd`、时间列不在白名单、SQL 文件不存在：启动时报错并退出。
- SQLcl 执行失败：该行在清单中记为 `FAIL`。`onError = stop` 时停在这一片；`continue` 时继续，进程最终仍以非 0 退出。
- 中文乱码时，在跳板机上统一 SQLcl / `NLS_LANG` 字符集后再重跑对应时间片。同名 CSV 会被覆盖。

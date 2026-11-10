# 跳板机导出 / 装载操作说明

在已安装 **sqlplus**（导出）与 **sqlldr**（装载）的跳板机上使用。本目录文件放在一起，不读取环境变量。

| 文件 | 作用 |
|------|------|
| `export-job.conf` | 路径、源/目标连接、表清单、时间范围、sqlldr 参数 |
| `export_by_map.sh` | 单表单窗导出 → `.dat` + `.ctl` |
| `export_batch.sh` | 表清单 × 按自然月切分，逐次导出 |
| `load_by_map.sh` | 单表单窗装载：对已有 `.ctl` 执行 sqlldr |
| `load_batch.sh` | 批量装载：优先按导出清单 OK 行执行 |

```bash
chmod +x export_by_map.sh export_batch.sh load_by_map.sh load_batch.sh
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
ctlCharset = AL32UTF8
loadMode = APPEND
sqlldrErrors = 100
sqlldrRows = 5000
sqlldrDirect = false

start = 20240301
end = 20270301
interval = 1m
onError = stop

tables =
  IBPS_PAYMENTINIT = TRANDATE
  HVPS_PAYMENTINIT = WORKDATE
```

| 键 | 谁用 | 说明 |
|----|------|------|
| `sqlDir` | export_batch / load_batch 回退扫片 | SQL 模板目录，`{表名}.sql` |
| `outDir` | 全部 | `.dat` / `.ctl` / 日志目录 |
| `connect` | `export_by_map.sh` | 源库 sqlplus 连接串 |
| `tgtConnect` | `load_by_map.sh` | 目标库 sqlldr 连接串 |
| `ctlCharset` | `export_by_map.sh` | 可选，默认 `AL32UTF8` |
| `loadMode` | `export_by_map.sh` | 写入 `.ctl`：`APPEND` / `TRUNCATE` / `REPLACE` / `INSERT` |
| `sqlldrErrors` | `load_by_map.sh` | 可选，默认 `100` |
| `sqlldrRows` | `load_by_map.sh` | 可选，默认 `5000` |
| `sqlldrDirect` | `load_by_map.sh` | 可选，默认 `false` |
| `start` / `end` | export_batch；load_batch 无清单时 | 左闭右开 |
| `interval` | 同上 | 只按自然月，如 `1m`；`start`/`end` 建议月初 |
| `onError` | export_batch / load_batch | `stop` / `continue` |
| `tables` | export_batch；load_batch 无清单时 | **每表一行** `表名 = 时间列`（必填，无全局默认） |

时间列允许值：`TRANDATE`、`TRANTIME`、`WORKDATE`、`SENDTIME`。已移除全局 `tmColumn` 与 `files` / `表名|列名` 写法。

## 2. 批量导出

```bash
/opt/mig/scripts/export_batch.sh
```

产物示例：

```text
{表名}_20240301_20240401.dat
{表名}_20240301_20240401.ctl
{outDir}/_batch/export-manifest.txt
```

清单行：`表名,片开始,片结束,dat路径,ctl路径,OK|FAIL`。

## 3. 单表单窗导出

```bash
./export_by_map.sh <beginTm> <endTm> [tmColumn] [表名]
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

## 5. 批量装载

```bash
/opt/mig/scripts/load_batch.sh
```

优先读 `{outDir}/_batch/export-manifest.txt` 中状态为 `OK` 的行，逐片调用 `load_by_map.sh`。  
若清单不存在，则按 conf 的 `files` × 时间片，对已存在的 `.ctl` 装载。

装载清单：`{outDir}/_batch/load-manifest.txt`  
行格式：`表名,片开始,片结束,ctl路径,OK|FAIL`。

## 6. 占位符与文件约定

| 占位符 | 替换成 |
|--------|--------|
| `{{beginTm}}` | 本片开始（含） |
| `{{endTm}}` | 本片结束（不含） |
| `{{tmColumn}}` | 时间列名 |

| 项 | 约定 |
|----|------|
| `.dat` | 无表头，分隔符 `0x01` |
| `.ctl` | `FIELDS TERMINATED BY X'01'`，列序 = map SQL 的 `) AS 列名` |
| 装载 | `load_*.sh` → `sqlldr` |

## 7. 失败时看哪里

- 导出失败：`export-manifest` 中 `FAIL`；sqlplus / `NLS_LANG`。
- 装载失败：`load-manifest` 中 `FAIL`；同前缀 `.log` / `.bad`。
- `onError = stop` 时停在失败片；`continue` 时继续，进程最终仍非 0。

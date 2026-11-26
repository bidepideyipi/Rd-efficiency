#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""根据《数据迁移中间表映射关系（跨行支付平台）V0.5.xlsx》生成 XMind 8 测试大纲。

与已产出的测试 SQL（map-sql-generator → test/{Sheet}/）对齐：
  T1 记录数 → 01_count_total_* / 01_count_byday_*
  T2 字段规则 → 02_hash_* ；码值哨兵 '#' → 03_code_sentinel_*（目标库）
  T3 乱码截断 → 由 T2 派生（不单独出 SQL）
  T4 金额汇总 → 触发表在 T1 同语句内 SUM（_meta.amountCheck）
  T5 主键完整 → 05_pk_*
  协议连续性 → 仅 IBPS_PROTOCOLINFO / IBPS_MOBILEINFO / BEPS_PTCBOOK*，人工验证
  生僻字 → 不列入测试范围

输出为 XMind 8 原生格式（zip + content.xml）。
多数据来源目录项（如 HVPS_EXTPAYMENT）展开为实际 Sheet（HVPS_EXTPAYMENT1…），
与测试 SQL 目录粒度一致。
"""

import os
import re
import sys
import json
import uuid
import zipfile
import datetime
from xml.sax.saxutils import escape

import openpyxl

SRC = "数据迁移中间表映射关系（跨行支付平台）V0.5.xlsx"
OUT = "数据迁移测试大纲-跨行支付平台-V0.5.xmind"
DEFAULT_TEST_SQL_DIR = os.path.expanduser("~/mig/sql/map/test")

PLATFORMS = [
    ("IBPS", "超网", "一"),
    ("HVPS", "大额", "二"),
    ("BEPS", "小额", "三"),
    ("POP", "报文", "四"),
    ("SAPS", "清算账户", "五"),
    ("CBPS", "城银清", "六"),
    ("APS", "基础数据", "七"),
]

# 码值转换未命中分支的约定哨兵值（防遗漏）；目标库出现则 Fail
CODE_SENTINEL = "#"

MAPPING_TYPES = [
    ("01-直接迁移", "源表栏位值原样写入目标栏位。逐行逐字段比对（含前导零、首尾空格、定长补位），不得 trim 或改写。"),
    ("02-默认值", "目标值恒等于映射行的“默认值”列，与源值无关。核对 NULL/空串是否按规则填充，默认值不得被其它逻辑覆盖。"),
    ("03-码值转换",
     "按“码值代码”及规则说明转换。约定 CASE/映射的 else 默认值统一为 '%s'，防止未覆盖码值静默落库。"
     "测试 SQL 对每个码值目标栏位执行：COUNT(*) WHERE 栏位 = '%s'；记录数不为 0 则失败，并登记主键与原码。"
     % (CODE_SENTINEL, CODE_SENTINEL)),
    ("04-类型转换", "按强制类型转换规则（number→char、int 日期→date 等）。核对格式、精度、四舍五入/截断方向与空值处理。"),
    ("05-加工映射", "按“规则说明”中的 CASE 分支、SUBSTR、多条件赋值逐条复算。构造覆盖每个分支的边界数据，分支全覆盖。"),
    ("00-不迁移", "目标栏位按约定不落值。核对目标值为 NULL 或约定默认值，且无源数据被误写入。"),
]

# 协议类表：仅此三张（BEPS_PTCBOOK 展开为编号 Sheet）需要「协议类表验证业务连续性」
AGREEMENT_TABLES = {
    "IBPS_PROTOCOLINFO": (
        "协议号 PROTOCOLNO 为唯一主键，协议类型 PROTOCOLTYPE、协议状态 PROTOCOLSTATUS（1-已签约/2-已撤销）、协议日期 PROTOCOLDATE 需按源一致。",
        "迁移后按存量协议发起一笔协议支付（授权支付/账户信息查询），验证签约校验、限额与协议有效期判定正常。",
    ),
    "IBPS_MOBILEINFO": (
        "复合主键 PHONENO+IDNO+IDTYPE+ACCOUNTNO 需唯一，签约状态 SIGNSTATUS、默认账户属性 DFTACCTTYPE、开户行 ACCBANK 与源一致，手机号—证件—账号绑定关系不断裂。",
        "迁移后用存量绑定关系发起手机号码支付，验证签约状态校验、默认账户选取与解绑功能正常。",
    ),
    "BEPS_PTCBOOK1": (
        "复合主键 CONTRACTTYPE+PROTOCOLNO+SIGNBANKNO（及 ACCOUNTNO，以 Sheet 主键为准）需唯一，协议状态 PROTOCOLSTATUS、签约日期/时间/流水与源一致；同一协议号跨签约行不得串号。",
        "迁移后以存量代收/代付协议发起一笔定期借记或定期贷记业务，验证协议有效性、业务种类与限额校验正常。",
    ),
    "BEPS_PTCBOOK2": (
        "复合主键 CONTRACTTYPE+PROTOCOLNO+SIGNBANKNO（及 ACCOUNTNO，以 Sheet 主键为准）需唯一，协议状态 PROTOCOLSTATUS、签约日期/时间/流水与源一致；同一协议号跨签约行不得串号。",
        "迁移后以存量代收/代付协议发起一笔定期借记或定期贷记业务，验证协议有效性、业务种类与限额校验正常。",
    ),
}

NAME_KEYS = ("名称", "户名", "姓名", "地址", "摘要", "附言", "用途")
TEXT_KEYS = NAME_KEYS + ("备注", "行名", "信息", "内容", "报文", "原因", "说明", "理由", "描述")
CHAR_TYPES = ("CHAR", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2", "CLOB", "TEXT")

BATCH_TXT = {
    "完整": "完整（存量 + 增量）",
    "投产前迁移": "投产前迁移（历史表一次性迁移）",
}

SKIP_SHEETS = {"封面", "文档说明", "目录", "修订记录"}


def clean(v):
    if v is None:
        return ""
    s = str(v).replace("\r", " ").replace("\n", " ")
    return re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", s).strip()


def resolve_table_name(sheet_name):
    """与 Java SheetNames.resolveTableName 一致：去掉末尾一位数字。"""
    n = (sheet_name or "").strip()
    if len(n) > 1 and n[-1].isdigit():
        return n[:-1]
    return n


def expand_catalog_to_sheets(logical, sheet_names):
    """目录逻辑表名 → 实际 Sheet 列表（含多源编号与 NAME{n}_HIS）。"""
    if logical in sheet_names:
        return [logical]
    found = []
    for s in sheet_names:
        if re.match(r"^" + re.escape(logical) + r"\d+$", s):
            found.append(s)
        elif re.match(r"^" + re.escape(logical) + r"\d+_HIS$", s):
            found.append(s)
    if found:
        def sort_key(x):
            m = re.search(r"(\d+)(_HIS)?$", x[len(logical):] if x.startswith(logical) else x)
            if not m:
                return (0, 0, x)
            return (int(m.group(1)), 1 if m.group(2) else 0, x)
        return sorted(found, key=sort_key)
    if logical + "_HIS" in sheet_names:
        return [logical + "_HIS"]
    return []


def load_test_meta(test_sql_dir):
    """读取已产出测试 SQL 的 _meta.json，按 Sheet 名索引。"""
    meta = {}
    if not test_sql_dir or not os.path.isdir(test_sql_dir):
        return meta
    for name in os.listdir(test_sql_dir):
        path = os.path.join(test_sql_dir, name, "_meta.json")
        if not os.path.isfile(path):
            continue
        try:
            with open(path, "r", encoding="utf-8") as f:
                meta[name] = json.load(f)
        except (OSError, ValueError, json.JSONDecodeError):
            continue
    return meta


def read_sheet_fields(ws):
    head = clean(ws.cell(1, 1).value)
    m = re.search(r"表名[:：](.*?)([A-Za-z0-9_]+)\s*主键[:：]\s*(.*)$", head)
    if m:
        cn = m.group(1).strip()
        pk = m.group(3).strip()
    else:
        cn, pk = "", head.split("主键")[-1].lstrip("：: ") if "主键" in head else ""

    fields, seen = [], set()
    for row in ws.iter_rows(min_row=4, max_row=ws.max_row, values_only=True):
        col = clean(row[2])
        if not col or col.lower() in seen:
            continue
        seen.add(col.lower())
        fields.append({
            "col": col,
            "desc": clean(row[3]),
            "pk": clean(row[4]),
            "type": clean(row[5]).upper(),
            "len": clean(row[6]),
            "map": clean(row[11]),
            "src": clean(row[13]),
            "srccol": clean(row[14]),
        })
    return cn, pk, fields


def read_source(path):
    wb = openpyxl.load_workbook(path, data_only=True)
    cat = wb["目录"]
    catalog = []
    for row in cat.iter_rows(min_row=3, max_row=cat.max_row, values_only=True):
        if row[1]:
            catalog.append({
                "table": clean(row[1]),
                "cn": clean(row[2]),
                "desc": clean(row[3]),
                "migrate": clean(row[4]),
                "batch": clean(row[5]),
                "old": clean(row[7]),
                "oldaux": clean(row[8]),
            })

    sheet_names = set(wb.sheetnames)
    items = []
    meta = {}
    for it in catalog:
        sheets = expand_catalog_to_sheets(it["table"], sheet_names)
        if not sheets:
            continue
        for sheet in sheets:
            ws = wb[sheet]
            cn, pk, fields = read_sheet_fields(ws)
            physical = resolve_table_name(sheet)
            # 多源 Sheet：中文名后标注源编号，便于与测试 SQL 目录对应
            disp_cn = cn or it["cn"]
            if sheet != it["table"] and sheet[-1].isdigit():
                disp_cn = "%s（源%d）" % (it["cn"] or disp_cn, int(sheet[-1]))
            elif sheet != it["table"] and re.search(r"\d+_HIS$", sheet):
                n = re.search(r"(\d+)_HIS$", sheet).group(1)
                disp_cn = "%s（源%s/_HIS）" % (it["cn"] or disp_cn, n)
            elif sheet.endswith("_HIS") and sheet != it["table"]:
                disp_cn = "%s（历史）" % (it["cn"] or disp_cn)

            entry = {
                "table": sheet,
                "logical": it["table"],
                "physical": physical,
                "cn": disp_cn,
                "desc": it["desc"],
                "migrate": it["migrate"],
                "batch": it["batch"],
                "old": it["old"],
            }
            items.append(entry)
            meta[sheet] = {
                "cn": disp_cn,
                "pk": pk,
                "fields": fields,
                "srcs": sorted({f["src"] for f in fields if f["src"]}),
                "batch": it["batch"],
                "desc": it["desc"],
                "old": it["old"],
                "migrate": it["migrate"],
                "logical": it["table"],
                "physical": physical,
            }
    return items, meta


def pk_fields(pk):
    return [p.strip() for p in re.split(r",|，|、", pk) if p.strip()]


def date_field(fields):
    for f in fields:
        if "平台日期" in f["desc"]:
            return f["col"], f["desc"]
    for f in fields:
        if f["col"].upper() == "WORKDATE":
            return f["col"], f["desc"] or "平台日期"
    for f in fields:
        if "日期" in f["desc"] or "DATE" in f["col"].upper():
            return f["col"], f["desc"]
    return "", ""


def field_len(f):
    try:
        return int(float(str(f["len"]).split(",")[0]))
    except (TypeError, ValueError):
        return -1


def long_field(fields):
    best = None
    for f in fields:
        if not any(t in f["type"] for t in CHAR_TYPES):
            continue
        n = field_len(f)
        if n < 0:
            continue
        if best is None or n > best[1]:
            best = (f["col"], n)
    return best


def sql_dir_note(sheet, tm):
    if not tm:
        return ("测试 SQL：尚未生成（目录 test/%s/ 不存在）。"
                "请用 map-sql-generator 按 V0.5 映射重新产出后再执行自动对照。" % sheet)
    files = tm.get("files") or []
    return ("测试 SQL 目录：test/%s/\n"
            "已生成文件：%s\n"
            "主键（_meta）：%s\n"
            "指纹栏位数：%d；amountCheck=%s；timeWindow=%s；dayBucket=%s"
            % (sheet,
               "、".join(files) if files else "—",
               ",".join(tm.get("primaryKeys") or []) or "—",
               len(tm.get("hashColumns") or []),
               tm.get("amountCheck"),
               tm.get("timeWindow"),
               tm.get("dayBucket")))


def build(items, meta, stats, test_meta):
    nodes_intro = [
        ("依据文档",
         "《数据迁移中间表映射关系（跨行支付平台）V0.5.xlsx》：每个中间表以英文表名建 sheet，"
         "含目标中间表定义（前 11 列）与源数据映射规则（12–23 列）；目录 sheet 登记中文名、是否迁移、迁移批次、源表与辅助表。"
         "多数据来源目录项展开为编号 Sheet（与测试 SQL 目录一致）。", None),
        ("测试项代号说明（T1–T5）",
         "下文标题中的 T1、T2、T3、T4、T5 是测试 SQL 门禁代号（与 excel-to-test-sql-design / test/{Sheet}/*.sql 一致）。"
         "展开本节点查看每项含义、判定与对应文件。协议连续性无 T 代号，属人工项。",
         [
             ("T1｜记录数一致",
              "含义：指定时间区间内，源库（历史）与目标库（目录）记录数一致，默认按天比对。\n"
              "判定：片合计 COUNT 相等；启用按日时每一天 COUNT 相等。\n"
              "对应 SQL：01_count_total_{src,tgt}.sql 、01_count_byday_{src,tgt}.sql\n"
              "大纲验证点：第 1 项。", None),
             ("T2｜字段规则一致（行指纹）",
              "含义：非「00-不迁移」栏位按映射规则计算后的目标值，与目标库落库值一致（行级 MD5 指纹）。\n"
              "判定：源/目标 02_hash 结果一致；失败时按主键定位差异字段。\n"
              "对应 SQL：02_hash_{src,tgt}.sql\n"
              "码值补充：03-码值转换栏位 else 约定为 '%s'；目标库 COUNT(栏位='%s')≠0 则失败"
              "（建议 03_code_sentinel_tgt.sql）。\n"
              "大纲验证点：第 2 项。"
              % (CODE_SENTINEL, CODE_SENTINEL), None),
             ("T3｜无乱码或截断",
              "含义：关键字符栏位无乱码、半截汉字或静默截断。\n"
              "判定：由 T2 派生——T2 Pass 则本项 Pass；T2 Fail 时优先用指纹差异定位乱码/截断字段。\n"
              "对应 SQL：无独立文件。\n"
              "大纲验证点：第 3 项。", None),
             ("T4｜金额汇总一致",
              "含义：支付/主机类触发表在核对记录数的同时核对金额合计。\n"
              "触发条件：表名匹配 *PAYMENT* / *HOST* 且存在 AMOUNT（_meta.amountCheck=true）。\n"
              "判定：与 T1 同语句内 SUM 源侧金额表达式 = 目标 SUM(AMOUNT)。\n"
              "对应 SQL：写在 T1 的 01_count_* 中，不单独编号文件。\n"
              "大纲验证点：挂在第 1 项（标题含「+T4金额」时表示本表触发）。", None),
             ("T5｜主键约束完整",
              "含义：主键唯一、非空，且源/目标主键集合一致。\n"
              "判定：无重复键、无空键；05_pk_set 源集 = 目标集。\n"
              "对应 SQL：05_pk_dup_* 、05_pk_null_* 、05_pk_stat_* 、05_pk_set_*（各含 src/tgt）。\n"
              "大纲验证点：第 4 项。", None),
             ("无 T 代号｜协议业务连续性（人工）",
              "含义：仅 IBPS_PROTOCOLINFO、IBPS_MOBILEINFO、BEPS_PTCBOOK 三张协议表，验证签约状态与业务可续用。\n"
              "判定：人工回放/抽查，通过/不通过/阻塞；不生成测试 SQL。\n"
              "大纲验证点：第 5 项（仅上述表出现）。", None),
             ("不测｜生僻字",
              "生僻字迁移一致不列入本轮测试范围。", None),
         ]),
        ("覆盖范围",
         "接入平台 %d 个；目录迁移项展开后 Sheet %d 个，其中“是否迁移=是” %d 个纳入本轮验证；"
         "“是否迁移=否” %d 个按基础数据导入口径单独登记。"
         "每张迁移表按上方 T1–T5 代号执行对应门禁（见「测试项代号说明」）。"
         % (stats["platforms"], stats["sheets"], stats["migrate_yes"], stats["migrate_no"]), None),
        ("规模概览",
         "迁移 Sheet %d 张；目标栏位合计 %d 个；映射类型分布：%s。协议业务连续性人工验证表 %d 张。"
         "已匹配测试 SQL _meta %d 张；尚缺测试 SQL %d 张。"
         % (stats["migrate_yes"], stats["fields"],
            "、".join("%s %d 个" % (k, v) for k, v in stats["dist"].items()),
            stats["agreement"],
            stats["test_hit"], stats["test_miss"]), None),
        ("与测试 SQL 文件名对照",
         "T1 → 01_count_total_{src,tgt}.sql + 01_count_byday_{src,tgt}.sql\n"
         "T2 → 02_hash_{src,tgt}.sql ；码值哨兵 → 03_code_sentinel_tgt.sql（COUNT='%s'）\n"
         "T3 → 无独立文件（随 T2）\n"
         "T4 → 并入 T1 的 01_count_*（SUM）\n"
         "T5 → 05_pk_{dup,null,stat,set}_{src,tgt}.sql\n"
         "跳板机：Shell 替换 {{beginTm}}/{{endTm}}/{{tmColumn}} → sqlplus 分别打源库与目标库 → 结果 diff。"
         % CODE_SENTINEL, None),
        ("迁移批次口径",
         "备注“完整”= 存量与增量均需迁移，按完整口径验证；备注“投产前迁移”= _HIS 历史表在投产前一次性迁移，"
         "按历史区间一次性比对。两者均按天维度核对记录数（dayBucket）。"
         "基础数据“按照最新基础数据文件直接导入”项不走中间表迁移 SQL。", None),
        ("各表验证点编号（与 T 代号对应）",
         "1. 记录数一致＝T1（触发表可含 T4 金额）\n"
         "2. 字段规则一致＝T2（含码值哨兵 '%s'）\n"
         "3. 无乱码或截断＝T3（T2 派生）\n"
         "4. 主键约束完整＝T5\n"
         "5. 协议业务连续性＝人工（无 T 代号；仅三张协议表）"
         % CODE_SENTINEL, None),
        ("判定口径",
         "T1、T2、T5（及触发表 T4、码值哨兵）按测试 SQL 结果判定 Pass/Fail；T3 随 T2；"
         "协议连续性为人工项，记通过/不通过/阻塞。"
         "任一门禁不通过即该 Sheet 该时间片不通过。"
         "缺陷须登记：Sheet/物理表、平台日期、主键值、字段名、期望值、实际值、批次号。", None),
        ("执行前置",
         "历史库与目录库取同一时点快照；库与客户端字符集按 GB18030；源系统（MPC）只读权限；"
         "迁移批次日志、批次号与调度记录可查；测试数据须覆盖往账/来账、成功/失败/撤销、跨日与月末；"
         "码值映射 CASE 的 else 须统一为 '%s'；测试 SQL 与映射 Excel 须同为 V0.5 规则产物。"
         % CODE_SENTINEL, None),
    ]

    migrate_items = [it for it in items if it["migrate"] == "是"]
    skip_items = [it for it in items if it["migrate"] != "是"]

    groups = []
    for prefix, plat, num in PLATFORMS:
        tis = [it for it in migrate_items if it["table"].split("_")[0] == prefix]
        if not tis:
            continue
        nf = sum(len(meta[it["table"]]["fields"]) for it in tis)
        dist = {}
        for it in tis:
            for f in meta[it["table"]]["fields"]:
                k = f["map"] or "未填"
                dist[k] = dist.get(k, 0) + 1
        hit = sum(1 for it in tis if it["table"] in test_meta)
        gnote = ("平台：%s（%s）\n迁移 Sheet：%d 张\n目标栏位：%d 个\n映射类型分布：%s\n"
                 "已匹配测试 SQL：%d / %d\n"
                 % (plat, prefix, len(tis), nf,
                    "、".join("%s %d" % (k, v) for k, v in sorted(dist.items())),
                    hit, len(tis)))
        children = []
        for it in tis:
            children.append(build_table_node(it, meta[it["table"]], test_meta.get(it["table"])))
        groups.append(("%s、%s（%s）｜%d 张迁移表" % (num, plat, prefix, len(tis)), gnote, children))

    # 未归入上述前缀的迁移表
    known = {p[0] for p in PLATFORMS}
    other = [it for it in migrate_items if it["table"].split("_")[0] not in known]
    if other:
        children = [build_table_node(it, meta[it["table"]], test_meta.get(it["table"])) for it in other]
        groups.append(("其他｜%d 张迁移表" % len(other), "未匹配平台前缀的迁移 Sheet。", children))

    if skip_items:
        sch = []
        for it in skip_items:
            m = meta[it["table"]]
            note = ("是否迁移：%s\n迁移批次/口径：%s\n源表：%s\n说明：%s\n"
                    "验证要点：不走中间表迁移对照；按最新基础数据文件直接导入后，"
                    "抽查目标表主键唯一、关键栏位与文件一致、无脏数据。"
                    % (it["migrate"], it["batch"] or "—", "、".join(m["srcs"]) or it["old"] or "—", m["desc"]))
            sch.append(("%s｜%s（不迁移）" % (it["table"], it["cn"] or m["cn"]), note, [
                ("基础数据导入核对", note, None),
            ]))
        groups.append(("不迁移 / 基础数据导入｜%d 项" % len(skip_items),
                       "目录“是否迁移≠是”，不纳入 T1–T5 中间表迁移对照。", sch))

    root_title = "跨行支付平台数据迁移测试大纲（V0.5）"
    root_note = (
        "依据《数据迁移中间表映射关系（跨行支付平台）V0.5.xlsx》编制，"
        "并与已产出测试 SQL 对齐。\n"
        "T1=记录数、T2=字段指纹、T3=乱码截断(随T2)、T4=金额SUM(并入T1)、T5=主键；"
        "定义见「大纲说明 → 测试项代号说明（T1–T5）」。\n"
        "覆盖迁移 Sheet %d 个；节点即测试执行项，备注栏为执行方法、预期结果与对应 SQL 文件。"
        % stats["migrate_yes"]
    )
    return (root_title, root_note), groups, nodes_intro


def build_table_node(it, m, tm):
    t = it["table"]
    fields = m["fields"]
    dfs = {k: 0 for k, _ in MAPPING_TYPES}
    for f in fields:
        if f["map"] in dfs:
            dfs[f["map"]] += 1
    dist_txt = "、".join("%s %d" % (k.replace("-", " "), v) for k, v in dfs.items() if v)
    need = sum(v for k, v in dfs.items() if not k.startswith("00"))

    nm = it["cn"] or m["cn"]
    pk_disp = ", ".join(p.upper() for p in pk_fields(m["pk"])) or m["pk"] or "—"
    if tm and tm.get("primaryKeys"):
        pk_disp = ", ".join(tm["primaryKeys"])

    logical = m.get("logical") or t
    physical = m.get("physical") or resolve_table_name(t)
    multi = "是（目录 %s → Sheet %s，物理表 %s）" % (logical, t, physical) if logical != t else "否"

    tnote = ("迁移项 Sheet：%s｜%s\n物理目标表：%s\n多源展开：%s\n主键：%s\n迁移批次：%s\n源表：%s\n"
             "目标栏位：%d 个（%s）\n中间表说明：%s\n%s"
             % (t, nm, physical, multi, pk_disp, BATCH_TXT.get(m["batch"], m["batch"] or "—"),
                "、".join(m["srcs"]) or "—", len(fields), dist_txt, m["desc"], sql_dir_note(t, tm)))

    df, dfdesc = date_field(fields)
    time_col = df or "【待确认时间列，见 test-job.conf tables】"
    tw = tm.get("timeWindow") if tm else None
    tw_txt = "有时间窗（替换 {{beginTm}}/{{endTm}}/{{tmColumn}}）" if tw else (
        "无业务 WHERE / timeWindow=false：整表只跑一次，不按片循环" if tw is False else
        "时间窗以测试 SQL / map SQL 为准（有 where 则挂时间谓词）"
    )

    # 1 T1
    c1 = ("对应测试 SQL：01_count_total_{src,tgt}.sql ；01_count_byday_{src,tgt}.sql\n"
          "时间维度：%s（%s）；%s\n"
          "执行方法：\n"
          "1）确定时间区间【待填：beginTm, endTm），左闭右开；覆盖区间内每一天；\n"
          "2）跳板机替换占位符后，分别在源库、目标库执行上述成对 SQL；\n"
          "3）比对片合计 COUNT；启用 dayBucket 时按日外连接比对每日 COUNT；\n"
          "4）差异日定位到主键流水（%s）并登记缺陷。\n"
          "预期结果：区间内合计与每日记录数完全一致，无漏迁、多迁、重复迁。"
          % (time_col, dfdesc or "业务日期", tw_txt, pk_disp))
    if tm and tm.get("amountCheck"):
        c1 += ("\n【T4 金额汇总】本表 amountCheck=true：T1 同语句含 SUM(AMOUNT)，"
               "源侧金额表达式与迁移 SELECT 同源；COUNT 与 SUM 均须一致。")

    # 2 T2 + 码值哨兵
    code_cols = [f["col"] for f in fields if f["map"] == "03-码值转换"]
    c2 = ("对应测试 SQL：02_hash_{src,tgt}.sql\n"
          "执行方法：源侧用与迁移 SELECT 相同的 renderField 表达式拼行指纹（STANDARD_HASH/MD5），"
          "目标侧对物理列规范化后哈希；按主键对齐比对。\n"
          "覆盖：目标栏位 %d 个，指纹纳入非 00 栏位 %d 个；00-不迁移 %d 个不入指纹，须另核为 NULL/约定默认值。\n"
          "规范化：NVL(TRIM(TO_CHAR(expr)), CHR(1))；列序与 Excel 映射行序一致。\n"
          "预期结果：片级聚合指纹一致；失败时用主键定位差异字段。"
          % (len(fields), need, dfs.get("00-不迁移", 0)))
    if code_cols:
        c2 += (
            "\n【码值哨兵 '%s'】本表 03-码值转换栏位 %d 个：%s\n"
            "约定：映射 CASE/码表 else 默认值统一为 '%s'，防止未覆盖码值遗漏。\n"
            "测试 SQL（目标库）：对上述每个栏位 "
            "SELECT COUNT(*) FROM %s t WHERE t.{栏位} = '%s' "
            "[AND 时间窗]；任栏位计数 ≠ 0 则失败，并登记主键与原码。\n"
            "建议文件：03_code_sentinel_tgt.sql（可多栏位 UNION ALL 输出 COL_NAME, CNT）。"
            % (CODE_SENTINEL, len(code_cols),
               "、".join(code_cols[:12]) + ("…" if len(code_cols) > 12 else ""),
               CODE_SENTINEL, physical, CODE_SENTINEL)
        )
    if tm and tm.get("hashColumns"):
        c2 += "\n_meta.hashColumns（%d）：%s" % (
            len(tm["hashColumns"]),
            "、".join(tm["hashColumns"][:12]) + ("…" if len(tm["hashColumns"]) > 12 else ""),
        )
    if tm and not tm.get("pkResolved", True):
        c2 += "\n【阻塞】_meta.pkResolved=false：T2/T5 不可执行，须先补主键定义。"

    # 3 T3
    char_fields = [f for f in fields if any(x in f["type"] for x in CHAR_TYPES)]
    char_long = sorted(char_fields, key=lambda f: field_len(f), reverse=True)
    pks = [p.upper() for p in pk_fields(m["pk"])]
    if tm and tm.get("primaryKeys"):
        pks = list(tm["primaryKeys"])
    key = list(pks)
    for f in fields:
        if f["col"].upper() in key:
            continue
        if any(k in f["desc"] for k in TEXT_KEYS) and any(x in f["type"] for x in CHAR_TYPES):
            key.append(f["col"].upper())
    for f in char_long[:3]:
        if f["col"].upper() not in key:
            key.append(f["col"].upper())
    shown = "、".join(key[:14]) + ("等 %d 个" % len(key) if len(key) > 14 else "")
    lf = long_field(fields)
    c3 = ("对应测试 SQL：无独立文件（T3 由 T2 派生）。\n"
          "口径：T2 Pass ⇒ 本项 Pass；T2 Fail 时优先用指纹差异定位乱码/截断字段。\n"
          "关注字段（主键 + 中文语义 + 最长字符，共 %d 个）：\n%s\n"
          "补充抽查：源值 → 中间表 → 目录库三段比对；字符集 GB18030；按定义长度校字节长度。\n"
          "预期结果：无 ?、□、U+FFFD、“烫烫烫”类乱码；无半截汉字；超长值按规则截断且留痕。%s"
          % (len(key), shown,
             ("重点验证最长字段 %s（定义长度 %d）。" % lf if lf else "")))

    # 4 T5（原⑤；生僻字已移出范围）
    c5 = ("对应测试 SQL：05_pk_dup_{src,tgt}.sql、05_pk_null_{src,tgt}.sql、"
          "05_pk_stat_{src,tgt}.sql、05_pk_set_{src,tgt}.sql\n"
          "主键：%s\n"
          "校验方法：\n"
          "1）唯一性：dup 结果为空；stat 中 COUNT(*) = COUNT(DISTINCT PK_KEY)；\n"
          "2）非空：null 结果为空（无 NULL、无空串）；\n"
          "3）集合一致：源/目标 05_pk_set 结果集 diff 为空；\n"
          "4）约束：重复写入触发唯一约束报错，不覆盖既有记录。\n"
          "预期结果：无重复主键、无空主键、无孤儿记录；重跑与补跑幂等。"
          % pk_disp)

    checks = []
    # 标题格式：序号. 中文名（代号T*｜含义摘要），代号定义见大纲说明「测试项代号说明」
    title1 = "1. 记录数一致（T1｜按天 COUNT"
    if tm and tm.get("amountCheck"):
        title1 += "；含T4金额SUM"
    title1 += "）"
    checks.append((title1, c1, None))

    mchildren = []
    for k, how in MAPPING_TYPES:
        if dfs.get(k):
            mchildren.append(("%s（%d 个栏位）" % (k, dfs[k]), "验证方法：%s" % how, None))
    title2 = "2. 字段规则一致（T2｜行指纹MD5"
    if code_cols:
        title2 += "；码值哨兵#"
    title2 += "）"
    checks.append((title2, c2, mchildren))
    checks.append(("3. 无乱码或截断（T3｜随T2派生，无独立SQL）", c3, None))
    checks.append(("4. 主键约束完整（T5｜唯一/非空/集合一致）", c5, None))

    # 5 协议：仅三张表挂载；备注标明人工验证
    if t in AGREEMENT_TABLES:
        a = AGREEMENT_TABLES[t]
        c6 = ("【人工验证｜无T代号】不生成测试 SQL，须业务人员手工执行与签字确认。\n"
              "适用表：IBPS_PROTOCOLINFO、IBPS_MOBILEINFO、BEPS_PTCBOOK（含多源编号 Sheet）。\n"
              "验证重点：\n1）主键与要素：%s\n"
              "2）状态一致：已签约不得迁成已撤销，解约记录保留原解约要素；\n"
              "3）绑定关系：协议号可反查客户、账号与签约机构，无断链；\n"
              "4）业务连续性：%s\n"
              "预期结果：存量协议全部可正常使用，状态、绑定关系与协议要素无变化。"
              % (a[0], a[1]))
        checks.append(("5. 协议业务连续性（人工｜无T代号）", c6, None))

    return ("%s｜%s" % (t, nm), tnote, checks)


def topic_xml(title, note, children, level=0):
    tid = "t" + uuid.uuid4().hex[:22]
    parts = ['<topic id="%s">' % tid]
    parts.append("<title>%s</title>" % escape(title))
    if note:
        parts.append("<notes><plain>%s</plain></notes>" % escape(note))
    if children:
        parts.append("<children><topics type=\"attached\">")
        for t, n, ch in children:
            parts.append(topic_xml(t, n, ch, level + 1))
        parts.append("</topics></children>")
    parts.append("</topic>")
    return "".join(parts)


def main(base, test_sql_dir=None):
    src = os.path.join(base, SRC)
    if not os.path.isfile(src):
        # 允许 docs/ 作为工作目录或仓库根目录
        alt = os.path.join(base, "docs", SRC)
        if os.path.isfile(alt):
            src = alt
            out_base = os.path.join(base, "docs")
        else:
            raise SystemExit("找不到映射 Excel: %s" % src)
    else:
        out_base = base

    test_sql_dir = test_sql_dir or os.environ.get("TEST_SQL_DIR") or DEFAULT_TEST_SQL_DIR
    test_meta = load_test_meta(test_sql_dir)

    items, meta = read_source(src)
    migrate_items = [it for it in items if it["migrate"] == "是"]

    dist = {}
    nfields = 0
    for it in migrate_items:
        m = meta[it["table"]]
        nfields += len(m["fields"])
        for f in m["fields"]:
            k = f["map"] or "未填"
            dist[k] = dist.get(k, 0) + 1

    agreement = sum(1 for it in migrate_items if it["table"] in AGREEMENT_TABLES)
    platforms_hit = len({it["table"].split("_")[0] for it in migrate_items
                         if it["table"].split("_")[0] in {p[0] for p in PLATFORMS}})
    test_hit = sum(1 for it in migrate_items if it["table"] in test_meta)
    test_miss = len(migrate_items) - test_hit

    stats = {
        "sheets": len(items),
        "migrate_yes": len(migrate_items),
        "migrate_no": len(items) - len(migrate_items),
        "fields": nfields,
        "dist": {k: dist[k] for k in sorted(dist)},
        "agreement": agreement,
        "platforms": platforms_hit,
        "test_hit": test_hit,
        "test_miss": test_miss,
    }

    intro, groups, intro_children = build(items, meta, stats, test_meta)
    ts = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    stamp = str(int(datetime.datetime.now().timestamp() * 1000))

    root_children = [("大纲说明", intro[1], intro_children)]
    for g in groups:
        root_children.append(g)

    body = "".join(topic_xml(t, n, c) for t, n, c in root_children)
    content = (
        '<?xml version="1.0" encoding="UTF-8" standalone="no"?>'
        '<xmap-content xmlns="urn:xmind:xmap:xmlns:content:2.0" '
        'xmlns:fo="http://www.w3.org/1999/XSL/Format" '
        'xmlns:svg="http://www.w3.org/2000/svg" '
        'xmlns:xhtml="http://www.w3.org/1999/xhtml" '
        'xmlns:xlink="http://www.w3.org/1999/xlink" '
        'version="2.0" timestamp="%s">'
        '<sheet id="s%s" timestamp="%s">'
        "<title>测试大纲</title>"
        '<topic id="root%s" structure-class="org.xmind.ui.logic.right" timestamp="%s">'
        "<title>%s</title>"
        "%s"
        "<children><topics type=\"attached\">%s</topics></children>"
        "</topic></sheet></xmap-content>"
        % (stamp, uuid.uuid4().hex[:20], stamp, uuid.uuid4().hex[:20], stamp,
           escape(intro[0]),
           ("<notes><plain>%s</plain></notes>" % escape(intro[1])) if intro[1] else "",
           body)
    )

    meta_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="no"?>'
        '<meta xmlns="urn:xmind:xmap:xmlns:meta:2.0" version="2.0">'
        "<Creator><Name>数据迁移测试大纲生成器</Name><Version>1.1</Version></Creator>"
        "<CreateTime>%s</CreateTime><ModifyTime>%s</ModifyTime>"
        "</meta>" % (ts, ts)
    )

    styles_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="no"?>'
        '<xmap-styles xmlns="urn:xmind:xmap:xmlns:style:2.0" '
        'xmlns:fo="http://www.w3.org/1999/XSL/Format" '
        'xmlns:svg="http://www.w3.org/2000/svg" '
        'xmlns:xhtml="http://www.w3.org/1999/xhtml" '
        'xmlns:xlink="http://www.w3.org/1999/xlink" version="2.0">'
        "<styles></styles></xmap-styles>"
    )

    manifest = (
        '<?xml version="1.0" encoding="UTF-8" standalone="no"?>'
        '<manifest xmlns="urn:xmind:xmap:xmlns:manifest:1.0">'
        '<file-entry full-path="content.xml" media-type="text/xml"/>'
        '<file-entry full-path="META-INF/" media-type=""/>'
        '<file-entry full-path="META-INF/manifest.xml" media-type="text/xml"/>'
        '<file-entry full-path="meta.xml" media-type="text/xml"/>'
        '<file-entry full-path="styles.xml" media-type="text/xml"/>'
        "</manifest>"
    )

    out = os.path.join(out_base, OUT)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("content.xml", content)
        z.writestr("meta.xml", meta_xml)
        z.writestr("styles.xml", styles_xml)
        z.writestr("META-INF/manifest.xml", manifest)

    n_topics = content.count("<topic id=")
    print(json.dumps({
        "out": out,
        "size_kb": round(os.path.getsize(out) / 1024, 1),
        "src": src,
        "test_sql_dir": test_sql_dir,
        "sheets": len(items),
        "migrate_yes": stats["migrate_yes"],
        "migrate_no": stats["migrate_no"],
        "topics": n_topics,
        "fields": nfields,
        "dist": stats["dist"],
        "test_hit": test_hit,
        "test_miss": test_miss,
        "groups": [(g[0], len(g[2])) for g in groups],
    }, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    base = sys.argv[1] if len(sys.argv) > 1 else "."
    test_dir = sys.argv[2] if len(sys.argv) > 2 else None
    main(base, test_dir)

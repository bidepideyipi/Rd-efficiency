#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""根据《数据迁移中间表映射关系（跨行支付平台）V0.4.xlsx》生成 XMind 8 测试大纲。

输出为 XMind 8 原生格式（zip + content.xml），XMind 8 / XMind 2020+ 均可打开。
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

SRC = "数据迁移中间表映射关系（跨行支付平台）V0.4.xlsx"
OUT = "数据迁移测试大纲（跨行支付平台）V0.4.xmind"

PLATFORMS = [
    ("IBPS", "超网", "一"),
    ("HVPS", "大额", "二"),
    ("BEPS", "小额", "三"),
    ("POP", "报文", "四"),
    ("SAPS", "清算账户", "五"),
    ("CBPS", "城银清", "六"),
]

MAPPING_TYPES = [
    ("01-直接迁移", "源表栏位值原样写入目标栏位。逐行逐字段比对（含前导零、首尾空格、定长补位），不得 trim 或改写。"),
    ("02-默认值", "目标值恒等于映射行的“默认值”列，与源值无关。核对 NULL/空串是否按规则填充，默认值不得被其它逻辑覆盖。"),
    ("03-码值转换", "按“码值代码”及规则说明转换。源侧全部码值逐一验证，含边界码与未知码，不得出现未映射原码落库。"),
    ("04-类型转换", "按强制类型转换规则（number→char、int 日期→date 等）。核对格式、精度、四舍五入/截断方向与空值处理。"),
    ("05-加工映射", "按“规则说明”中的 CASE 分支、SUBSTR、多条件赋值逐条复算。构造覆盖每个分支的边界数据，分支全覆盖。"),
    ("00-不迁移", "目标栏位按约定不落值。核对目标值为 NULL 或约定默认值，且无源数据被误写入。"),
]

AGREEMENT_TABLES = {
    "IBPS_PROTOCOLINFO": (
        "协议号 PROTOCOLNO 为唯一主键，协议类型 PROTOCOLTYPE、协议状态 PROTOCOLSTATUS（1-已签约/2-已撤销）、协议日期 PROTOCOLDATE 需按源一致。",
        "迁移后按存量协议发起一笔协议支付（授权支付/账户信息查询），验证签约校验、限额与协议有效期判定正常。",
    ),
    "IBPS_MOBILEINFO": (
        "复合主键 PHONENO+IDNO+IDTYPE+ACCOUNTNO 需唯一，签约状态 SIGNSTATUS、默认账户属性 DFTACCTTYPE、开户行 ACCBANK 与源一致，手机号—证件—账号绑定关系不断裂。",
        "迁移后用存量绑定关系发起手机号码支付，验证签约状态校验、默认账户选取与解绑功能正常。",
    ),
    "BEPS_PTCBOOK": (
        "复合主键 CONTRACTTYPE+PROTOCOLNO+SIGNBANKNO 需唯一，协议状态 PROTOCOLSTATUS、签约日期/时间/流水与源一致；同一协议号跨签约行不得串号。",
        "迁移后以存量代收/代付协议发起一笔定期借记或定期贷记业务，验证协议有效性、业务种类与限额校验正常。",
    ),
    "BEPS_PROPAYITEMSBOOK": (
        "复合主键 PAYITEMCODE+CONTRACTTYPE+PROTOCOLNO 需唯一，费项代码与协议号的对应关系完整，不得出现孤儿费项或协议缺失。",
        "迁移后按存量协议发起收费/费项计算业务，验证费项命中与计费结果正确。",
    ),
}

NAME_KEYS = ("名称", "户名", "姓名", "地址", "摘要", "附言", "用途")
TEXT_KEYS = NAME_KEYS + ("备注", "行名", "信息", "内容", "报文", "原因", "说明", "理由", "描述")
CHAR_TYPES = ("CHAR", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2", "CLOB", "TEXT")

BATCH_TXT = {
    "完整": "完整（存量 + 增量）",
    "投产前迁移": "投产前迁移（历史表一次性迁移）",
}


def clean(v):
    if v is None:
        return ""
    s = str(v).replace("\r", " ").replace("\n", " ")
    return re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", s).strip()


def read_source(path):
    wb = openpyxl.load_workbook(path, data_only=True)
    cat = wb["目录"]
    items = []
    for row in cat.iter_rows(min_row=3, max_row=cat.max_row, values_only=True):
        if row[1]:
            items.append({
                "table": clean(row[1]),
                "cn": clean(row[2]),
                "desc": clean(row[3]),
                "migrate": clean(row[4]),
                "batch": clean(row[5]),
                "old": clean(row[7]),
                "oldaux": clean(row[8]),
            })

    meta = {}
    for it in items:
        ws = wb[it["table"]]
        head = clean(ws.cell(1, 1).value)
        m = re.search(r"表名[:：](.*?)([A-Za-z0-9_]+)\s*主键[:：]\s*(.*)$", head)
        if m:
            cn = m.group(1).strip()
            pk = m.group(3).strip()
        else:
            cn, pk = it["cn"], head.split("主键")[-1].lstrip("：: ")

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

        meta[it["table"]] = {
            "cn": cn or it["cn"],
            "pk": pk,
            "fields": fields,
            "srcs": sorted({f["src"] for f in fields if f["src"]}),
            "batch": it["batch"],
            "desc": it["desc"],
            "old": it["old"],
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


def build(items, meta, stats):
    def note(text):
        return "<notes><plain>%s</plain></notes>" % escape(text)

    nodes = []  # (title, note_or_None, children|None)

    # ---------- 根节点 ----------
    intro_children = [
        ("依据文档", "《数据迁移中间表映射关系（跨行支付平台）V0.4.xlsx》：每个中间表以英文表名建 sheet，含目标中间表定义（前 11 列）与源数据映射规则（12–22 列）；目录 sheet 登记中文名、是否迁移、迁移批次、源表与辅助表。", None),
        ("覆盖范围", "6 个接入平台、%d 个迁移项；目录“是否迁移”全部为“是”，全部纳入本轮测试。每个迁移项均执行六项验证点。" % stats["tables"], None),
        ("规模概览", "迁移项 %d 张；目标栏位合计 %d 个；映射类型分布：%s。协议类表 %d 张，含协议关联字段的表 %d 张。"
         % (stats["tables"], stats["fields"],
            "、".join("%s %d 个" % (k, v) for k, v in stats["dist"].items()),
            stats["agreement"], stats["protocol_like"]), None),
        ("迁移批次口径", "备注“完整”= 存量与增量均需迁移，按完整口径验证；备注“投产前迁移”= _HIS 历史表在投产前一次性迁移，按历史区间一次性比对。两者均按天维度核对记录数。", None),
        ("六项验证点", "① 指定时间区间历史库与目录库记录数一致（以天为维度）；② 迁移字段结果与迁移规则结果一致；③ 关键字段无乱码或截断；④ 生僻字迁移一致；⑤ 迁移数据主键约束完整；⑥ 协议类表验证业务连续性（仅协议类表执行，其余表记 N/A 并对协议号关联做抽查）。", None),
        ("判定口径", "六项逐项判定“通过 / 不通过 / 阻塞”；任一项不通过即该迁移项不通过。缺陷须登记：表名、平台日期、主键值、字段名、期望值、实际值、批次号，可追溯到具体流水。", None),
        ("执行前置", "历史库与目录库取同一时点快照；库与客户端字符集按 GB18030；源系统（MPC）只读权限；迁移批次日志、批次号与调度记录可查；测试数据须覆盖往账/来账、成功/失败/撤销、跨日与月末。", None),
    ]

    groups = []
    for prefix, plat, num in PLATFORMS:
        tis = [it for it in items if it["table"].split("_")[0] == prefix]
        if not tis:
            continue
        nf = sum(meta[it["table"]]["fields"].__len__() for it in tis)
        dist = {}
        for it in tis:
            for f in meta[it["table"]]["fields"]:
                k = f["map"] or "未填"
                dist[k] = dist.get(k, 0) + 1
        gnote = ("平台：%s（%s）\n迁移项：%d 张表\n目标栏位：%d 个\n映射类型分布：%s\n"
                 % (plat, prefix, len(tis), nf,
                    "、".join("%s %d" % (k, v) for k, v in sorted(dist.items()))))
        children = []
        for it in tis:
            t = it["table"]
            m = meta[t]
            fields = m["fields"]
            dfs = {k: 0 for k, _ in MAPPING_TYPES}
            for f in fields:
                if f["map"] in dfs:
                    dfs[f["map"]] += 1
            dist_txt = "、".join("%s %d" % (k.replace("-", " "), v) for k, v in dfs.items() if v)
            need = sum(v for k, v in dfs.items() if not k.startswith("00"))

            nm = it["cn"] or m["cn"]
            pk_disp = ", ".join(p.upper() for p in pk_fields(m["pk"])) or m["pk"] or "—"
            tnote = ("迁移项：%s｜%s\n主键：%s\n迁移批次：%s\n源表：%s\n目标栏位：%d 个（%s）\n中间表说明：%s"
                     % (t, nm, pk_disp, BATCH_TXT.get(m["batch"], m["batch"] or "—"),
                        "、".join(m["srcs"]) or "—", len(fields), dist_txt, m["desc"]))

            # 1 记录数一致
            df, dfdesc = date_field(fields)
            c1 = ("时间维度：%s\n执行方法：\n1）确定时间区间【待填：YYYYMMDD - YYYYMMDD】，覆盖区间内每一天（含首尾）；\n"
                  "2）历史库（源）：SELECT %s, COUNT(*) FROM 源表 GROUP BY %s；\n"
                  "3）目录库（目标）：SELECT %s, COUNT(*) FROM %s GROUP BY %s；\n"
                  "4）逐日比对两侧记录数，并核对区间合计、最早/最晚日期一致。\n"
                  "预期结果：区间内每一天记录数完全一致，无漏迁、多迁、重复迁；差异日须定位到具体流水（%s）并登记缺陷。"
                  % (df + "（" + dfdesc + "）" if df else "【本表未识别到日期栏位，按业务日期字段确认】",
                     df, df, df, t, df, pk_disp))
            c1_note = c1 if df else c1

            # 2 字段与规则一致
            c2 = ("执行方法：按《中间表映射关系》的“映射类型 + 默认值 + 码值代码 + 规则说明”逐字段复算，目标值必须等于规则计算结果。\n"
                  "覆盖：目标栏位 %d 个，其中应迁移核对 %d 个；00-不迁移 %d 个栏位须核对为 NULL 或约定默认值。\n"
                  "比对方式：字符型按字节比对（含前导零、首尾空格、定长补位），数值型按精度比对，日期按 YYYYMMDD 格式比对；源表多表关联场景按“库表关联条件”还原关联结果后再比对。\n"
                  "预期结果：逐字段一致，无规则未实现、无默认值缺失、无码值漏转、无关联条件遗漏。"
                  % (len(fields), need, dfs.get("00-不迁移", 0)))

            # 3 乱码/截断
            char_fields = [f for f in fields if any(x in f["type"] for x in CHAR_TYPES)]
            char_long = sorted(char_fields, key=lambda f: field_len(f), reverse=True)
            pks = [p.upper() for p in pk_fields(m["pk"])]
            key = list(pks)
            for f in fields:
                if f["col"].upper() in key:
                    continue
                if any(k in f["desc"] for k in TEXT_KEYS) and any(x in f["type"] for x in CHAR_TYPES):
                    key.append(f["col"].upper())
            for f in char_long[:3]:  # 截断风险最高的长文本栏位必须入清单
                if f["col"].upper() not in key:
                    key.append(f["col"].upper())
            shown = "、".join(key[:14]) + ("等 %d 个" % len(key) if len(key) > 14 else "")
            lf = long_field(fields)
            c3 = ("关键字段（主键 + 中文语义栏位 + 最长字符栏位，共 %d 个）：\n%s\n"
                  "校验方法：源值 → 中间表 → 目录库三段比对；字符集按 GB18030 读写，按定义长度校验字节长度。\n"
                  "预期结果：无 ?、□、U+FFFD 替换符、“烫烫烫”类乱码；无半截汉字或半截字段；超长值按规则截断且留痕，不得静默丢弃或报错中断批次。%s"
                  % (len(key), shown,
                     ("重点验证最长字段 %s（定义长度 %d）。" % lf if lf else "")))

            # 4 生僻字
            rare = [f["col"] for f in char_fields
                    if any(k in f["desc"] for k in NAME_KEYS)
                    and not any(x in f["desc"] for x in ("代码", "编号", "标识", "类型", "状态", "标志", "属性", "码值"))
                    and not f["desc"].endswith("码")]
            fallback = ""
            if not rare:
                rare = [f["col"] for f in char_long[:3]]
                fallback = ("样本说明：本表无姓名/名称/地址类栏位，改取上述长文本栏位构造样本"
                            "（迁移链路上同样存在字符集转换损坏风险）。") if rare else \
                           "本表无合适的字符型栏位，生僻字验证随批抽样，覆盖客户名称、摘要类数据。"
            c4 = ("覆盖字段（%d 个）：%s\n"
                  "测试样本：GB18030 扩展区与生僻姓氏用字，如“𠮷”（U+20BB7）、“㛃”（U+36C3）、“龘”“燚”“甯”“禤”“雒”；另含全角符号、繁体字与组合字符。\n"
                  "执行方法：构造含上述字符的源数据，源库 → 中间表 → 目录库逐字比对码位（HEX/UNICODE 值），并验证前端展示与导出文件。\n"
                  "预期结果：码位完全一致，无变问号、无丢字、无归一化差异；报表、接口回放与凭证打印显示正常。%s"
                  % (len(rare), "、".join(rare[:10]) + ("等" if len(rare) > 10 else ""), fallback))

            # 5 主键
            c5 = ("主键：%s\n校验方法：\n1）唯一性：COUNT(*) = COUNT(DISTINCT %s)；\n"
                  "2）非空：主键各栏位无 NULL、无空串；\n"
                  "3）与源表一一对应：源主键集合 = 目标主键集合，无合并、拆分、增补；\n"
                  "4）约束校验：重复写入触发唯一约束报错，不覆盖既有记录。\n"
                  "预期结果：无重复主键、无空主键、无孤儿记录；重跑与补跑幂等，不产生重复数据。"
                  % (pk_disp, pk_disp))

            # 6 协议连续性
            if t in AGREEMENT_TABLES:
                a = AGREEMENT_TABLES[t]
                c6 = ("适用性：是（协议类表）\n验证重点：\n1）主键与要素：%s\n"
                      "2）状态一致：已签约不得迁成已撤销，解约记录保留原解约要素；\n"
                      "3）绑定关系：协议号可反查客户、账号与签约机构，无断链；\n"
                      "4）业务连续性：%s\n"
                      "预期结果：存量协议全部可正常使用，状态、绑定关系与协议要素无变化。"
                      % (a[0], a[1]))
            else:
                proto = [f["col"] for f in fields
                         if "PROTOCOL" in f["col"].upper() or "协议" in f["desc"]]
                extra = ("本表含协议关联字段：%s，补充“协议号关联完整性”抽查：业务流水中的协议号均能在协议表命中，无孤儿协议号。"
                         % "、".join(proto[:6]) + ("等" if len(proto) > 6 else "")) if proto else \
                        "本表无协议关联字段，仅记录 N/A。"
                c6 = ("适用性：不适用（本表非协议类表，按 N/A 记录）\n"
                      "说明：第 6 项仅对协议类表（IBPS_PROTOCOLINFO、IBPS_MOBILEINFO、BEPS_PTCBOOK、BEPS_PROPAYITEMSBOOK）执行。\n%s" % extra)

            checks = []
            checks.append(("1. 指定时间区间历史库与目录库记录数一致（以天为维度）", c1_note, None))
            mchildren = []
            for k, how in MAPPING_TYPES:
                if dfs.get(k):
                    mchildren.append(("%s（%d 个栏位）" % (k, dfs[k]), "验证方法：%s" % how, None))
            checks.append(("2. 迁移字段结果与迁移规则结果一致", c2, mchildren))
            checks.append(("3. 关键字段无乱码或截断", c3, None))
            checks.append(("4. 生僻字迁移一致", c4, None))
            checks.append(("5. 迁移数据主键约束完整", c5, None))
            checks.append(("6. 协议类表验证业务连续性", c6, None))

            children.append(("%s｜%s" % (t, nm), tnote, checks))
        groups.append(("%s、%s（%s）｜%d 张迁移表" % (num, plat, prefix, len(tis)), gnote, children))

    return (("跨行支付平台数据迁移测试大纲（V0.4）",
             "依据《数据迁移中间表映射关系（跨行支付平台）V0.4.xlsx》编制。\n覆盖 %d 个迁移项，逐表执行六项验证点；节点即测试执行项，备注栏为执行方法与预期结果。"
             % stats["tables"]), groups, intro_children)


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


def main(base):
    src = os.path.join(base, SRC)
    items, meta = read_source(src)

    dist = {}
    nfields = 0
    for t, m in meta.items():
        nfields += len(m["fields"])
        for f in m["fields"]:
            k = f["map"] or "未填"
            dist[k] = dist.get(k, 0) + 1
    proto_like = sum(1 for m in meta.values()
                     if any("PROTOCOL" in f["col"].upper() or "协议" in f["desc"] for f in m["fields"]))
    stats = {
        "tables": len(items),
        "fields": nfields,
        "dist": {k: dist[k] for k in sorted(dist)},
        "agreement": len(AGREEMENT_TABLES),
        "protocol_like": proto_like,
    }

    intro, groups, intro_children = build(items, meta, stats)
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
           escape(intro[0]), escape(intro[1]) and "<notes><plain>%s</plain></notes>" % escape(intro[1]),
           body)
    )

    meta_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="no"?>'
        '<meta xmlns="urn:xmind:xmap:xmlns:meta:2.0" version="2.0">'
        "<Creator><Name>数据迁移测试大纲生成器</Name><Version>1.0</Version></Creator>"
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

    out = os.path.join(base, OUT)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("content.xml", content)
        z.writestr("meta.xml", meta_xml)
        z.writestr("styles.xml", styles_xml)
        z.writestr("META-INF/manifest.xml", manifest)

    # 统计校验
    n_topics = content.count("<topic id=")
    print(json.dumps({
        "out": out,
        "size_kb": round(os.path.getsize(out) / 1024, 1),
        "tables": len(items),
        "topics": n_topics,
        "fields": nfields,
        "dist": stats["dist"],
        "groups": [(g[0], len(g[2])) for g in groups],
    }, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")

# 「01-直接迁移」规则说明非空且不符合 Oracle SQL 清单

- 源文件：`数据迁移中间表映射关系（跨行支付平台）V0.4(1).xlsx`
- 扫描时间：2026-11-10 16:39:22
- 前置：映射类型 `01-直接迁移`（或含「直接映射/直接迁移」）且规则说明非空
- 再筛：规则说明**不能**作为可嵌入 SELECT 列表的 Oracle 表达式
  - 对齐生成器 `SqlRuleHeuristics`，并检查：中文/叙述用语、括号、裸 JOIN 片段、用 `+` 做字符拼接等
- 「01 + 规则非空」：96 行
- **不符合 Oracle SQL**：**10** 行；Sheet：**8**

## 汇总

| Sheet | 命中行数 |
|-------|----------|
| IBPS_PAYMENTINIT | 1 |
| HVPS_PAYMENTINIT | 2 |
| HVPS_PAYMENTINIT_HIS | 2 |
| HVPS_HOSTBOOKINIT_HIS | 1 |
| HVPS_HOSTBOOKSUCC_HIS | 1 |
| HVPS_HOSTBOOKFAIL_HIS | 1 |
| BEPS_HOSTBOOKSUCC_HIS | 1 |
| CBPS_PAYMENTINIT_HIS | 1 |

## 明细

| Sheet | Excel行号 | 序号 | 栏位名 | 判定原因 | 规则说明（截断） |
|-------|-----------|------|--------|----------|------------------|
| IBPS_PAYMENTINIT | 104 | 100 | BUSICHANNELCODE | 含中文（非字面量）; 含叙述用语「部分要」; 不像SQL表达式（无CASE/函数/标识符等特征） | 部分要用3位英文转换6位数字 |
| HVPS_PAYMENTINIT | 81 | 78 | POSTSCRIPT | 疑似用+做字符拼接（Oracle应为\|\|）; 不像SQL表达式（无CASE/函数/标识符等特征） | INFO+INFO2 |
| HVPS_PAYMENTINIT | 82 | 79 | REMARK | 疑似用+做字符拼接（Oracle应为\|\|）; 不像SQL表达式（无CASE/函数/标识符等特征） | NOTE+NOTE2 |
| HVPS_PAYMENTINIT_HIS | 81 | 78 | POSTSCRIPT | 疑似用+做字符拼接（Oracle应为\|\|）; 不像SQL表达式（无CASE/函数/标识符等特征） | INFO+INFO2 |
| HVPS_PAYMENTINIT_HIS | 82 | 79 | REMARK | 疑似用+做字符拼接（Oracle应为\|\|）; 不像SQL表达式（无CASE/函数/标识符等特征） | NOTE+NOTE2 |
| HVPS_HOSTBOOKINIT_HIS | 9 | 6 | FEEAMOUNT | 仅为JOIN片段，不能作为SELECT表达式 | left join a08thvtrx_his b on b.businesstrace=a.businesstrace |
| HVPS_HOSTBOOKSUCC_HIS | 21 | 18 | FEEAMOUNT | 仅为JOIN片段，不能作为SELECT表达式 | left join a08thvtrx_his b on b.businesstrace=a.businesstrace |
| HVPS_HOSTBOOKFAIL_HIS | 21 | 18 | FEEAMOUNT | 仅为JOIN片段，不能作为SELECT表达式 | left join a08thvtrx_his b on b.businesstrace=a.businesstrace |
| BEPS_HOSTBOOKSUCC_HIS | 37 | 34 | FEEAMOUNT | 仅为JOIN片段，不能作为SELECT表达式 | left join a08tbetrx_his b on b.businesstrace=a.businesstrace |
| CBPS_PAYMENTINIT_HIS | 129 | 126 | CANCELACCSERNO | 仅为JOIN片段，不能作为SELECT表达式 | left join A08TWRITEOFFINFO c on c.BUSINESSTRACE=a.BUSINES... |

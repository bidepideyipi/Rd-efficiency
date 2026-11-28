package com.mig.mapsql.core.testsql;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.core.model.FieldMapping;
import com.mig.mapsql.core.model.MappingType;
import com.mig.mapsql.core.model.TableMapping;
import com.mig.mapsql.core.sql.SelectSqlRenderer;
import com.mig.mapsql.core.util.Strings;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 按设计文档生成 T1/T2（及条件 T4）成对 SQL；T5 由 T2 明细派生。 */
@Component
public class TestSqlRenderer {

    public static final String TGT_ALIAS = "t";

    private final GeneratorProperties properties;
    private final SelectSqlRenderer selectSqlRenderer;

    public TestSqlRenderer(GeneratorProperties properties, SelectSqlRenderer selectSqlRenderer) {
        this.properties = properties;
        this.selectSqlRenderer = selectSqlRenderer;
    }

    public TestSqlBundle render(TableMapping table) {
        String primaryAlias = properties.getSourceAlias();
        String target = AmountCheckPolicy.physicalTable(table);
        if (Strings.isBlank(table.getSourceTable())) {
            throw new IllegalStateException("源表表名为空: " + table.getSheetName());
        }

        List<String> pks = TestPkResolver.resolve(table);
        boolean amountCheck = AmountCheckPolicy.enabled(table);
        boolean timeWindow = selectSqlRenderer.hasTimeWindow(table);

        List<FieldMapping> hashFields = new ArrayList<FieldMapping>();
        List<String> hashCols = new ArrayList<String>();
        Map<String, FieldMapping> byTarget = new LinkedHashMap<String, FieldMapping>();
        for (FieldMapping f : table.getFields()) {
            if (f.getMappingType() == MappingType.SKIP) {
                continue;
            }
            if (Strings.isBlank(f.getTargetColumn())) {
                continue;
            }
            hashFields.add(f);
            hashCols.add(f.getTargetColumn().trim().toUpperCase(Locale.ROOT));
            byTarget.put(f.getTargetColumn().trim().toUpperCase(Locale.ROOT), f);
        }
        if (hashFields.isEmpty()) {
            throw new IllegalStateException("无有效迁移列: " + table.getSheetName());
        }

        TestSqlBundle bundle = new TestSqlBundle();
        bundle.setSheet(table.getSheetName());
        bundle.setTargetTable(target);
        bundle.setSourceTable(table.getSourceTable());
        bundle.setPrimaryKeys(pks);
        bundle.setHashColumns(hashCols);
        bundle.setAmountCheck(amountCheck);
        bundle.setTimeWindow(timeWindow);
        bundle.setPkResolved(!pks.isEmpty());
        if (pks.isEmpty()) {
            bundle.setPkError("无法解析主键（nullable=PK 与标题「主键：」均为空）");
        }

        FieldMapping amountField = AmountCheckPolicy.findAmountField(table);
        String amountSrcExpr = null;
        if (amountCheck && amountField != null) {
            amountSrcExpr = selectSqlRenderer.renderFieldExpression(amountField, table, primaryAlias);
        }

        bundle.getFiles().put("01_count_total_src.sql",
                renderCount(table, primaryAlias, true, false, amountCheck, amountSrcExpr));
        bundle.getFiles().put("01_count_total_tgt.sql",
                renderCount(table, TGT_ALIAS, false, false, amountCheck, null));
        bundle.getFiles().put("01_count_byday_src.sql",
                renderCount(table, primaryAlias, true, true, amountCheck, amountSrcExpr));
        bundle.getFiles().put("01_count_byday_tgt.sql",
                renderCount(table, TGT_ALIAS, false, true, amountCheck, null));

        if (!pks.isEmpty()) {
            List<String> srcPkExprs = new ArrayList<String>();
            List<String> tgtPkExprs = new ArrayList<String>();
            for (String pk : pks) {
                FieldMapping pf = byTarget.get(pk);
                if (pf != null) {
                    srcPkExprs.add(selectSqlRenderer.renderFieldExpression(pf, table, primaryAlias));
                } else {
                    // 标题主键但映射中无对应迁移列：源侧用主表列名兜底
                    srcPkExprs.add(primaryAlias + "." + pk);
                }
                tgtPkExprs.add(TGT_ALIAS + "." + pk);
            }

            List<String> srcHashExprs = new ArrayList<String>();
            List<String> tgtHashExprs = new ArrayList<String>();
            for (FieldMapping f : hashFields) {
                srcHashExprs.add(selectSqlRenderer.renderFieldExpression(f, table, primaryAlias));
                tgtHashExprs.add(TGT_ALIAS + "." + f.getTargetColumn().trim());
            }

            bundle.getFiles().put("02_hash_src.sql",
                    renderHash(table, primaryAlias, true, srcPkExprs, srcHashExprs));
            bundle.getFiles().put("02_hash_tgt.sql",
                    renderHash(table, TGT_ALIAS, false, tgtPkExprs, tgtHashExprs));
        }

        return bundle;
    }

    private String renderCount(TableMapping table, String alias, boolean source,
                               boolean byDay, boolean amountCheck, String amountSrcExpr) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, table, source ? "T1 count src" : "T1 count tgt", byDay ? "byday" : "total");
        sb.append("SELECT\n");
        if (byDay) {
            sb.append("  ").append(dayExpr(alias, source, table)).append(" || CHR(1) || TO_CHAR(COUNT(*))");
            if (amountCheck) {
                sb.append(" || CHR(1) || TO_CHAR(").append(amountSumExpr(source, amountSrcExpr)).append(")");
            }
            sb.append(" AS LINE\n");
        } else {
            sb.append("  TO_CHAR(COUNT(*))");
            if (amountCheck) {
                sb.append(" || CHR(1) || TO_CHAR(").append(amountSumExpr(source, amountSrcExpr)).append(")");
            }
            sb.append(" AS LINE\n");
        }
        appendFrom(sb, table, alias, source);
        if (byDay) {
            sb.append("GROUP BY ").append(dayExpr(alias, source, table)).append("\n");
            sb.append("ORDER BY 1\n");
        }
        sb.append(";\n");
        return sb.toString();
    }

    private static String amountSumExpr(boolean source, String amountSrcExpr) {
        if (source) {
            return "NVL(SUM((" + amountSrcExpr + ")), 0)";
        }
        return "NVL(SUM(" + TGT_ALIAS + ".AMOUNT), 0)";
    }

    private String dayExpr(String alias, boolean source, TableMapping table) {
        // 源侧用 Excel {{时间分区}}；目标侧仍可被 test-job.conf 以 {{tgtTmColumn}} 覆盖不同列名。
        String col;
        if (source) {
            col = Strings.trimToEmpty(table.getTimeColumn());
            if (Strings.isBlank(col)) {
                col = "{{srcTmColumn}}";
            }
        } else {
            col = "{{tgtTmColumn}}";
        }
        if ("to_date".equalsIgnoreCase(properties.getTimePredicate())) {
            return "TO_CHAR(" + alias + "." + col + ", 'YYYYMMDD')";
        }
        return "SUBSTR(TO_CHAR(" + alias + "." + col + "), 1, 8)";
    }

    private String renderHash(TableMapping table, String alias, boolean source,
                              List<String> pkExprs, List<String> hashExprs) {
        StringBuilder sb = new StringBuilder();
        appendHeader(sb, table, source ? "T2 hash src" : "T2 hash tgt", "file");
        sb.append("-- rowFormat: NULL_FLAG<0x01>ROW_MD5<0x01>PK_KEY\n\n");
        String pkKey = concatNorm(pkExprs);
        String rowMd5 = "STANDARD_HASH(" + concatNorm(hashExprs) + ", 'MD5')";
        String nullFlag = "CASE WHEN " + nullPkPredicate(pkExprs) + " THEN '1' ELSE '0' END";
        sb.append("SELECT\n");
        sb.append("  (").append(nullFlag).append(") || CHR(1) || (")
                .append(rowMd5).append(") || CHR(1) || (").append(pkKey).append(") AS LINE\n");
        appendFrom(sb, table, alias, source);
        sb.append("ORDER BY ").append(pkKey).append("\n");
        sb.append(";\n");
        return sb.toString();
    }

    private static String nullPkPredicate(List<String> pkExprs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pkExprs.size(); i++) {
            if (i > 0) {
                sb.append(" OR ");
            }
            String e = pkExprs.get(i);
            sb.append("(").append(e).append(") IS NULL OR TRIM(TO_CHAR((").append(e).append("))) IS NULL");
        }
        return sb.toString();
    }

    /** norm(expr) = NVL(TRIM(TO_CHAR(expr)), CHR(1))，多列用 CHR(0) 拼接。 */
    static String concatNorm(List<String> exprs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < exprs.size(); i++) {
            if (i > 0) {
                sb.append(" || CHR(0) || ");
            }
            sb.append("NVL(TRIM(TO_CHAR((").append(exprs.get(i)).append("))), CHR(1))");
        }
        return sb.toString();
    }

    private void appendHeader(StringBuilder sb, TableMapping table, String kind, String note) {
        sb.append("-- Generated by dev-efficiency (test)\n");
        sb.append("-- sheet: ").append(table.getSheetName()).append("\n");
        sb.append("-- targetTable: ").append(AmountCheckPolicy.physicalTable(table)).append("\n");
        sb.append("-- kind: ").append(kind);
        if (note != null) {
            sb.append(" / ").append(note);
        }
        sb.append("\n");
        sb.append("-- DO NOT EDIT manually unless necessary\n\n");
    }

    private void appendFrom(StringBuilder sb, TableMapping table, String alias, boolean source) {
        sb.append(buildFromBody(table, alias, source));
    }

    private String buildFromBody(TableMapping table, String alias, boolean source) {
        if (source) {
            // 源侧时间列已由 yml 写入；与 map SQL 一致
            return selectSqlRenderer.renderFromWhere(table, alias);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("FROM ").append(AmountCheckPolicy.physicalTable(table)).append(" ").append(alias).append("\n");
        if (selectSqlRenderer.hasTimeWindow(table)) {
            // 目标侧保留占位符，便于 test-job.conf 配置与源不同的时间列
            sb.append("WHERE ").append(selectSqlRenderer.renderTimePredicate(alias, "{{tgtTmColumn}}")).append("\n");
        }
        return sb.toString();
    }
}

package com.mig.mapsql.standard;

import com.mig.mapsql.catalog.DbColumnMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 生成符合数标的 ALTER SQL。
 * <p>
 * 对 CHAR / CLOB / NUMBER 等不能直接 MODIFY 转换的类型，按四步重建（4 条逻辑语句）：
 * <ol>
 *   <li>新增临时字段</li>
 *   <li>复制 COMMENT</li>
 *   <li>拷贝原始值</li>
 *   <li>删除旧字段 + 改回原名</li>
 * </ol>
 */
public final class AlterSqlBuilder {

    private static final String TMP_SUFFIX = "_MIGTMP";

    private AlterSqlBuilder() {
    }

    /**
     * 生成可执行 SQL 脚本行（含注释头）。
     * rebuild=true 时输出四步：ADD、COMMENT、UPDATE、DROP+RENAME（第四步含两条 DDL）。
     */
    public static List<String> buildScriptLines(String tableName, DbColumnMeta col,
                                                String currentTypeDisplay, String targetType,
                                                boolean rebuild) {
        String table = tableName.trim().toUpperCase(Locale.ROOT);
        String colName = col.getColumnName().trim().toUpperCase(Locale.ROOT);
        String tgt = targetType.trim().toUpperCase(Locale.ROOT);
        List<String> sqls = new ArrayList<String>();

        if (!rebuild) {
            sqls.add("-- MODIFY " + table + "." + colName + ": " + currentTypeDisplay + " -> " + tgt);
            sqls.add("ALTER TABLE " + table + " MODIFY (" + colName + " " + tgt + ");");
            return sqls;
        }

        RebuildStep[] steps = buildFourSteps(table, colName, col.getComment(), currentTypeDisplay, tgt);
        sqls.add("-- 四步重建 " + table + "." + colName + ": " + currentTypeDisplay + " -> " + tgt);
        for (RebuildStep step : steps) {
            sqls.add("-- " + step.index + ". " + step.title);
            for (String line : step.sqlStatements) {
                sqls.add(line);
            }
        }
        return sqls;
    }

    /**
     * 严格四步：1 ADD  2 COMMENT  3 UPDATE  4 DROP+RENAME
     */
    public static RebuildStep[] buildFourSteps(String table, String colName, String comment,
                                               String currentTypeDisplay, String targetType) {
        String tmp = uniqueTmpName(colName);
        String escapedComment = comment == null ? "" : comment.replace("'", "''");
        String cast = castExpr(colName, currentTypeDisplay, targetType);

        return new RebuildStep[]{
                new RebuildStep(1, "新增字段",
                        new String[]{"ALTER TABLE " + table + " ADD (" + tmp + " " + targetType + ");"}),
                new RebuildStep(2, "复制 COMMENT",
                        new String[]{"COMMENT ON COLUMN " + table + "." + tmp + " IS '" + escapedComment + "';"}),
                new RebuildStep(3, "拷贝原始值",
                        new String[]{"UPDATE " + table + " SET " + tmp + " = " + cast + ";"}),
                new RebuildStep(4, "删除旧字段并改回原名",
                        new String[]{
                                "ALTER TABLE " + table + " DROP COLUMN " + colName + ";",
                                "ALTER TABLE " + table + " RENAME COLUMN " + tmp + " TO " + colName + ";"
                        })
        };
    }

    private static String uniqueTmpName(String colName) {
        String base = colName + TMP_SUFFIX;
        if (base.length() <= 30) {
            return base;
        }
        int keep = 30 - TMP_SUFFIX.length();
        return colName.substring(0, Math.max(1, keep)) + TMP_SUFFIX;
    }

    private static String castExpr(String colName, String srcTypeDisplay, String targetType) {
        ColumnTypeParser.ParsedType src = ColumnTypeParser.parse(srcTypeDisplay);
        ColumnTypeParser.ParsedType tgt = ColumnTypeParser.parse(targetType);
        if ("CLOB".equals(src.family) && "VARCHAR2".equals(tgt.family)) {
            int len = tgt.length != null ? tgt.length : 4000;
            return "DBMS_LOB.SUBSTR(" + colName + ", " + len + ", 1)";
        }
        if ("CHAR".equals(src.family) && "VARCHAR2".equals(tgt.family)) {
            return "RTRIM(" + colName + ")";
        }
        if ("NUMBER".equals(src.family) || "NUMBER".equals(tgt.family)) {
            return "CAST(" + colName + " AS " + targetType + ")";
        }
        return "CAST(" + colName + " AS " + targetType + ")";
    }

    public static String typeDisplay(DbColumnMeta col) {
        String dt = col.getDataType() == null ? "" : col.getDataType().trim().toUpperCase(Locale.ROOT);
        String len = col.getExcelLength() == null ? "" : col.getExcelLength().trim();
        if (len.isEmpty()) {
            return dt;
        }
        return dt + "(" + len + ")";
    }

    public static final class RebuildStep {
        public final int index;
        public final String title;
        public final String[] sqlStatements;

        public RebuildStep(int index, String title, String[] sqlStatements) {
            this.index = index;
            this.title = title;
            this.sqlStatements = sqlStatements;
        }
    }
}

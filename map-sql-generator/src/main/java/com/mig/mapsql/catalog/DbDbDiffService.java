package com.mig.mapsql.catalog;

import com.mig.mapsql.standard.AlterSqlBuilder;
import com.mig.mapsql.standard.ColumnTypeParser;
import com.mig.mapsql.standard.StandardScanService;
import com.mig.mapsql.standard.StandardScanService.Match;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 库与库结构对比：以左库为基准生成右库对齐 SQL（风格参考 A/B 差异报告）。
 * <ul>
 *   <li>左有右无表 → CREATE</li>
 *   <li>左无右有表 → 仅 TIP，不生成 DROP TABLE</li>
 *   <li>左有右无列 → ALTER ADD</li>
 *   <li>左无右有列 → TIP + 生成 DROP COLUMN</li>
 *   <li>类型不同 → 先数标，无数标则以左为准，MODIFY/四步重建右库</li>
 *   <li>COMMENT 不同 → 以左为准 COMMENT ON COLUMN</li>
 * </ul>
 */
@Service
public class DbDbDiffService {

    private final ObSchemaMetadataClient metadataClient;
    private final StandardScanService standardScanService;

    public DbDbDiffService(ObSchemaMetadataClient metadataClient,
                           StandardScanService standardScanService) {
        this.metadataClient = metadataClient;
        this.standardScanService = standardScanService;
    }

    public DiffResult compare(String leftDs, String rightDs, List<String> tableFilter) {
        if (leftDs == null || leftDs.trim().isEmpty()) {
            throw new IllegalArgumentException("leftDatasourceId 必填");
        }
        if (rightDs == null || rightDs.trim().isEmpty()) {
            throw new IllegalArgumentException("rightDatasourceId 必填");
        }
        leftDs = leftDs.trim();
        rightDs = rightDs.trim();
        if (leftDs.equals(rightDs)) {
            throw new IllegalArgumentException("左右数据源不能相同");
        }

        String leftOwner = metadataClient.resolveOwner(leftDs);
        String rightOwner = metadataClient.resolveOwner(rightDs);
        Set<String> leftSet = new LinkedHashSet<String>(
                filterTables(metadataClient.listTables(leftDs), tableFilter));
        Set<String> rightSet = new LinkedHashSet<String>(
                filterTables(metadataClient.listTables(rightDs), tableFilter));

        Set<String> all = new LinkedHashSet<String>();
        if (tableFilter == null || tableFilter.isEmpty()) {
            all.addAll(leftSet);
            all.addAll(rightSet);
        } else {
            all.addAll(normalizeFilter(tableFilter));
        }

        List<String> script = new ArrayList<String>();
        script.add("-- db-db schema diff");
        script.add("-- A(left)=" + leftDs + " owner=" + leftOwner);
        script.add("-- B(right)=" + rightDs + " owner=" + rightOwner);
        script.add("-- tables=" + all.size());
        script.add("");

        DiffResult result = new DiffResult();
        result.leftDatasourceId = leftDs;
        result.rightDatasourceId = rightDs;
        result.leftOwner = leftOwner;
        result.rightOwner = rightOwner;

        for (String table : all) {
            boolean inA = leftSet.contains(table);
            boolean inB = rightSet.contains(table);
            if (tableFilter != null && !tableFilter.isEmpty()) {
                // 过滤名单可能含仅一侧存在的表
                if (!inA) {
                    inA = metadataClient.tableExists(leftDs, table);
                }
                if (!inB) {
                    inB = metadataClient.tableExists(rightDs, table);
                }
            }

            if (!inA && !inB) {
                continue;
            }

            result.tablesCompared++;
            script.add("========== " + table + " ==========");

            if (inA && !inB) {
                result.createTables++;
                script.add("-- existence: A only → CREATE");
                Map<String, DbColumnMeta> leftCols = metadataClient.describeColumns(leftDs, table);
                script.addAll(buildCreateTable(table, leftCols));
                script.add("");
                continue;
            }

            if (!inA && inB) {
                result.tipTablesOnlyInB++;
                script.add("-- existence: B only → TIP（左库无此表，不生成 DROP TABLE）");
                script.add("-- TIP: table " + table + " exists only in B");
                script.add("");
                continue;
            }

            script.add("-- existence: BOTH");
            Map<String, DbColumnMeta> leftCols = metadataClient.describeColumns(leftDs, table);
            Map<String, DbColumnMeta> rightCols = metadataClient.describeColumns(rightDs, table);
            TableDiff td = diffColumns(table, leftCols, rightCols);
            result.addColumnDiffs += td.addCount;
            result.dropColumnTips += td.dropCount;
            result.modifyColumns += td.modifyCount;
            result.commentUpdates += td.commentCount;
            script.add("-- column diffs(" + td.diffCount + "):");
            script.addAll(td.lines);
            script.add("");
        }

        result.sqlScript = join(script);
        return result;
    }

    TableDiff diffColumns(String table,
                          Map<String, DbColumnMeta> leftCols,
                          Map<String, DbColumnMeta> rightCols) {
        TableDiff td = new TableDiff();
        Set<String> names = new LinkedHashSet<String>();
        names.addAll(leftCols.keySet());
        names.addAll(rightCols.keySet());

        for (String colName : names) {
            DbColumnMeta a = leftCols.get(colName);
            DbColumnMeta b = rightCols.get(colName);

            if (a != null && b == null) {
                td.addCount++;
                td.diffCount++;
                td.lines.add("--   " + colName + " [ADDED in A]");
                td.lines.add("--     A:" + describeCol(a));
                td.lines.add("--     B:(missing)");
                td.lines.addAll(buildAddColumn(table, a));
                continue;
            }
            if (a == null && b != null) {
                td.dropCount++;
                td.diffCount++;
                td.lines.add("--   " + colName + " [ONLY in B] TIP → DROP");
                td.lines.add("--     A:(missing)");
                td.lines.add("--     B:" + describeCol(b));
                td.lines.add("-- TIP: 左库无此字段，生成删除 SQL（请确认后执行）");
                td.lines.add("ALTER TABLE " + table + " DROP COLUMN " + colName + ";");
                continue;
            }

            // both exist
            String aType = AlterSqlBuilder.typeDisplay(a);
            String bType = AlterSqlBuilder.typeDisplay(b);
            boolean typeDiff = !typesEqual(aType, bType);
            boolean nullDiff = !nullableNorm(a.getNullable()).equals(nullableNorm(b.getNullable()));
            boolean commentDiff = !commentNorm(a.getComment()).equals(commentNorm(b.getComment()));

            if (!typeDiff && !nullDiff && !commentDiff) {
                continue;
            }

            td.diffCount++;
            if (typeDiff || nullDiff) {
                Desired desired = resolveDesiredType(a, b, aType, bType, typeDiff);
                td.lines.add("--   " + colName + " [MODIFIED]");
                td.lines.add("--     A:" + describeCol(a));
                td.lines.add("--     B:" + describeCol(b));
                if (desired.basis != null) {
                    td.lines.add("--     target=" + desired.type + " (basis=" + desired.basis + ")");
                }

                if (desired.type != null && !typesEqual(bType, desired.type)) {
                    td.modifyCount++;
                    boolean rebuild = desired.rebuild
                            || StandardScanService.needsRebuild(bType, desired.type);
                    DbColumnMeta forSql = copyWithComment(a);
                    td.lines.addAll(AlterSqlBuilder.buildScriptLines(
                            table, forSql, bType, desired.type, rebuild));
                } else if (nullDiff) {
                    td.modifyCount++;
                    td.lines.add("-- nullable A=" + nullableNorm(a.getNullable())
                            + " B=" + nullableNorm(b.getNullable()) + " → 以左为准");
                    if ("N".equals(nullableNorm(a.getNullable()))) {
                        td.lines.add("ALTER TABLE " + table + " MODIFY (" + colName + " " + bType + " NOT NULL);");
                    } else {
                        td.lines.add("ALTER TABLE " + table + " MODIFY (" + colName + " " + bType + " NULL);");
                    }
                }

                if (commentDiff) {
                    td.commentCount++;
                    td.lines.addAll(buildCommentSql(table, colName, a.getComment(), "COMMENT 以左库为准"));
                }
            } else if (commentDiff) {
                td.lines.add("--   " + colName + " [COMMENT]");
                td.lines.add("--     A:" + describeCol(a));
                td.lines.add("--     B:" + describeCol(b));
                td.commentCount++;
                td.lines.addAll(buildCommentSql(table, colName, a.getComment(), "COMMENT 以左库为准"));
            }
        }
        return td;
    }

    private Desired resolveDesiredType(DbColumnMeta left, DbColumnMeta right,
                                       String leftType, String rightType, boolean typeDiff) {
        Desired d = new Desired();
        if (!typeDiff) {
            d.type = leftType;
            d.basis = "LEFT";
            return d;
        }
        // 先数标：优先匹配左列，再匹配右列
        Match m = standardScanService.resolveStandard(left);
        if (m == null) {
            m = standardScanService.resolveStandard(right);
        }
        if (m != null) {
            d.type = m.targetType;
            d.basis = "STANDARD:" + m.rule.getRuleType() + "/" + m.rule.getMatchExpr();
            d.rebuild = m.rule.isRebuild();
            return d;
        }
        d.type = leftType;
        d.basis = "LEFT";
        return d;
    }

    static List<String> buildCreateTable(String table, Map<String, DbColumnMeta> cols) {
        List<String> lines = new ArrayList<String>();
        if (cols.isEmpty()) {
            lines.add("-- TIP: 左库表 " + table + " 无列信息，跳过 CREATE");
            return lines;
        }
        lines.add("CREATE TABLE " + table + " (");
        List<DbColumnMeta> ordered = new ArrayList<DbColumnMeta>(cols.values());
        for (int i = 0; i < ordered.size(); i++) {
            DbColumnMeta c = ordered.get(i);
            String type = AlterSqlBuilder.typeDisplay(c);
            String nullSql = "N".equals(nullableNorm(c.getNullable())) ? " NOT NULL" : "";
            String comma = i < ordered.size() - 1 ? "," : "";
            lines.add("  " + c.getColumnName() + " " + type + nullSql + comma);
        }
        lines.add(");");
        for (DbColumnMeta c : ordered) {
            if (c.getComment() != null && !c.getComment().trim().isEmpty()) {
                lines.addAll(buildCommentSql(table, c.getColumnName(), c.getComment(), null));
            }
        }
        return lines;
    }

    static List<String> buildAddColumn(String table, DbColumnMeta col) {
        List<String> lines = new ArrayList<String>();
        String type = AlterSqlBuilder.typeDisplay(col);
        String nullSql = "N".equals(nullableNorm(col.getNullable())) ? " NOT NULL" : "";
        lines.add("ALTER TABLE " + table + " ADD (" + col.getColumnName() + " " + type + nullSql + ");");
        if (col.getComment() != null && !col.getComment().trim().isEmpty()) {
            lines.addAll(buildCommentSql(table, col.getColumnName(), col.getComment(), null));
        }
        return lines;
    }

    static List<String> buildCommentSql(String table, String col, String comment, String tip) {
        List<String> lines = new ArrayList<String>();
        if (tip != null) {
            lines.add("-- " + tip);
        }
        String escaped = comment == null ? "" : comment.replace("'", "''");
        lines.add("COMMENT ON COLUMN " + table + "." + col + " IS '" + escaped + "';");
        return lines;
    }

    static String describeCol(DbColumnMeta c) {
        return "type=" + AlterSqlBuilder.typeDisplay(c)
                + ", nullable=" + nullableNorm(c.getNullable())
                + ", default=null"
                + ", comment=" + (c.getComment() == null ? "" : c.getComment());
    }

    private static DbColumnMeta copyWithComment(DbColumnMeta src) {
        DbColumnMeta m = new DbColumnMeta();
        m.setTableName(src.getTableName());
        m.setColumnName(src.getColumnName());
        m.setDataType(src.getDataType());
        m.setExcelType(src.getExcelType());
        m.setExcelLength(src.getExcelLength());
        m.setNullable(src.getNullable());
        m.setComment(src.getComment());
        m.setColumnId(src.getColumnId());
        return m;
    }

    static boolean typesEqual(String a, String b) {
        ColumnTypeParser.ParsedType pa = ColumnTypeParser.parse(a);
        ColumnTypeParser.ParsedType pb = ColumnTypeParser.parse(b);
        return pa.display().equalsIgnoreCase(pb.display());
    }

    static String nullableNorm(String n) {
        if (n == null || n.trim().isEmpty()) {
            return "Y";
        }
        String u = n.trim().toUpperCase(Locale.ROOT);
        if ("N".equals(u) || "NO".equals(u)) {
            return "N";
        }
        return "Y";
    }

    static String commentNorm(String c) {
        return c == null ? "" : c.trim();
    }

    private static List<String> filterTables(List<String> tables, List<String> filter) {
        if (filter == null || filter.isEmpty()) {
            return new ArrayList<String>(tables);
        }
        Set<String> want = new LinkedHashSet<String>(normalizeFilter(filter));
        List<String> out = new ArrayList<String>();
        for (String t : tables) {
            if (want.contains(t.toUpperCase(Locale.ROOT))) {
                out.add(t.toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static List<String> normalizeFilter(List<String> filter) {
        List<String> upper = new ArrayList<String>();
        for (String t : filter) {
            if (t != null && !t.trim().isEmpty()) {
                upper.add(t.trim().toUpperCase(Locale.ROOT));
            }
        }
        return upper;
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    static final class Desired {
        String type;
        String basis;
        boolean rebuild;
    }

    static final class TableDiff {
        int diffCount;
        int addCount;
        int dropCount;
        int modifyCount;
        int commentCount;
        final List<String> lines = new ArrayList<String>();
    }

    public static class DiffResult {
        public String leftDatasourceId;
        public String rightDatasourceId;
        public String leftOwner;
        public String rightOwner;
        public int tablesCompared;
        public int createTables;
        public int tipTablesOnlyInB;
        public int addColumnDiffs;
        public int dropColumnTips;
        public int modifyColumns;
        public int commentUpdates;
        public String sqlScript;

        public Map<String, Object> toSummaryMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("leftDatasourceId", leftDatasourceId);
            m.put("rightDatasourceId", rightDatasourceId);
            m.put("leftOwner", leftOwner);
            m.put("rightOwner", rightOwner);
            m.put("tablesCompared", tablesCompared);
            m.put("createTables", createTables);
            m.put("tipTablesOnlyInB", tipTablesOnlyInB);
            m.put("addColumns", addColumnDiffs);
            m.put("dropColumnTips", dropColumnTips);
            m.put("modifyColumns", modifyColumns);
            m.put("commentUpdates", commentUpdates);
            return m;
        }
    }
}

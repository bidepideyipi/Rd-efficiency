package com.mig.mapsql.core.util;

import com.mig.mapsql.core.model.LeftJoinSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JoinConditionParser {

    public enum ConditionKind {
        WHERE_SELECT,
        WHERE_PLAIN,
        LEFT_JOIN,
        OTHER_JOIN,
        UNKNOWN
    }

    private static final Pattern SELECT_WHERE = Pattern.compile(
            "(?is)^\\s*select\\s+\\*\\s+from\\s+([A-Za-z0-9_#$\"\\.]+)\\s+where\\s+(.+)$");

    private static final Pattern LEFT_JOIN = Pattern.compile(
            "(?is)^\\s*left\\s+(?:outer\\s+)?join\\s+([A-Za-z0-9_#$\"\\.]+)\\s+(?:([A-Za-z][A-Za-z0-9_#$]*)\\s+)?on\\s+(.+)$");

    private static final Pattern OTHER_JOIN = Pattern.compile(
            "(?is)^\\s*(?:inner\\s+|right\\s+(?:outer\\s+)?|full\\s+(?:outer\\s+)?)?join\\s+.+");

    private JoinConditionParser() {
    }

    public static String normalizeKey(String raw) {
        return Strings.trimToEmpty(raw).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public static ClassifiedCondition classify(String raw) {
        if (Strings.isBlank(raw)) {
            return null;
        }
        String text = raw.trim();
        Matcher left = LEFT_JOIN.matcher(text);
        if (left.matches()) {
            String table = stripQuotes(left.group(1));
            String alias = left.group(2);
            String on = left.group(3).trim();
            while (on.endsWith(";")) {
                on = on.substring(0, on.length() - 1).trim();
            }
            return new ClassifiedCondition(ConditionKind.LEFT_JOIN, text, table, alias, on);
        }
        if (OTHER_JOIN.matcher(text).matches() && !normalizeKey(text).startsWith("left ")) {
            return new ClassifiedCondition(ConditionKind.OTHER_JOIN, text, null, null, null);
        }
        Matcher sw = SELECT_WHERE.matcher(text);
        if (sw.matches()) {
            String table = stripQuotes(sw.group(1));
            String pred = sw.group(2).trim();
            while (pred.endsWith(";")) {
                pred = pred.substring(0, pred.length() - 1).trim();
            }
            return new ClassifiedCondition(ConditionKind.WHERE_SELECT, text, table, null, pred);
        }
        String pred = text;
        while (pred.endsWith(";")) {
            pred = pred.substring(0, pred.length() - 1).trim();
        }
        return new ClassifiedCondition(ConditionKind.WHERE_PLAIN, text, null, null, pred);
    }

    /**
     * 将去重后的关联条件原文解析为 WHERE 谓词与 LEFT JOIN 列表。
     */
    public static RelationParseResult parseAll(List<String> rawConditions, String primaryAlias) {
        RelationParseResult result = new RelationParseResult();
        if (rawConditions == null || rawConditions.isEmpty()) {
            return result;
        }

        Map<String, String> whereByKey = new LinkedHashMap<String, String>();
        Map<String, LeftJoinSpec> leftByTable = new LinkedHashMap<String, LeftJoinSpec>();
        int autoAliasOrd = 0;

        for (String raw : rawConditions) {
            ClassifiedCondition c = classify(raw);
            if (c == null) {
                continue;
            }
            switch (c.getKind()) {
                case WHERE_SELECT:
                case WHERE_PLAIN:
                    String wkey = normalizeKey(c.getPredicate());
                    if (!whereByKey.containsKey(wkey)) {
                        whereByKey.put(wkey, c.getPredicate());
                    }
                    if (c.getTableName() != null) {
                        result.getWhereSourceTables().add(c.getTableName());
                    }
                    break;
                case LEFT_JOIN:
                    String tableKey = c.getTableName().toUpperCase(Locale.ROOT);
                    String alias = c.getAlias();
                    if (Strings.isBlank(alias)) {
                        alias = nextJoinAlias(primaryAlias, autoAliasOrd++);
                        result.getNotes().add("LEFT JOIN " + c.getTableName() + " 自动分配别名 " + alias);
                    }
                    String on = rewritePrimaryAlias(c.getPredicate(), primaryAlias);
                    LeftJoinSpec existing = leftByTable.get(tableKey);
                    if (existing == null) {
                        leftByTable.put(tableKey, new LeftJoinSpec(c.getTableName(), alias, on, c.getRaw()));
                        if (c.getRaw() != null && normalizeKey(c.getRaw()).contains(" a.")) {
                            result.getNotes().add("LEFT JOIN ON 中的 a. 已改写为 " + primaryAlias + ".");
                        }
                    } else if (!normalizeKey(existing.getOnPredicate()).equals(normalizeKey(on))) {
                        result.setLeftJoinConflict(true);
                    }
                    break;
                case OTHER_JOIN:
                    result.getUnsupportedJoins().add(c.getRaw());
                    break;
                default:
                    result.getUnsupportedJoins().add(c.getRaw());
                    break;
            }
        }

        if (whereByKey.size() > 1) {
            result.setWhereConflict(true);
            result.setWherePredicate(null);
        } else if (whereByKey.size() == 1) {
            result.setWherePredicate(whereByKey.values().iterator().next());
        }

        result.getLeftJoins().addAll(leftByTable.values());
        return result;
    }

    public static String rewritePrimaryAlias(String onPredicate, String primaryAlias) {
        if (Strings.isBlank(onPredicate)) {
            return onPredicate;
        }
        // Excel 常用 a. 表示主表
        return onPredicate.replaceAll("(?i)\\ba\\.", primaryAlias + ".");
    }

    private static String nextJoinAlias(String primaryAlias, int index) {
        // b, c, d... 跳过与主表别名冲突
        int n = 0;
        for (char ch = 'b'; ch <= 'z'; ch++) {
            String candidate = String.valueOf(ch);
            if (candidate.equalsIgnoreCase(primaryAlias)) {
                continue;
            }
            if (n == index) {
                return candidate;
            }
            n++;
        }
        return "j" + index;
    }

    private static String stripQuotes(String name) {
        String n = name.trim();
        if ((n.startsWith("\"") && n.endsWith("\"")) || (n.startsWith("'") && n.endsWith("'"))) {
            return n.substring(1, n.length() - 1);
        }
        return n;
    }

    public static final class ClassifiedCondition {
        private final ConditionKind kind;
        private final String raw;
        private final String tableName;
        private final String alias;
        private final String predicate;

        public ClassifiedCondition(ConditionKind kind, String raw, String tableName, String alias, String predicate) {
            this.kind = kind;
            this.raw = raw;
            this.tableName = tableName;
            this.alias = alias;
            this.predicate = predicate;
        }

        public ConditionKind getKind() {
            return kind;
        }

        public String getRaw() {
            return raw;
        }

        public String getTableName() {
            return tableName;
        }

        public String getAlias() {
            return alias;
        }

        public String getPredicate() {
            return predicate;
        }
    }

    public static final class RelationParseResult {
        private String wherePredicate;
        private boolean whereConflict;
        private boolean leftJoinConflict;
        private final List<LeftJoinSpec> leftJoins = new ArrayList<LeftJoinSpec>();
        private final List<String> unsupportedJoins = new ArrayList<String>();
        private final List<String> whereSourceTables = new ArrayList<String>();
        private final List<String> notes = new ArrayList<String>();

        public String getWherePredicate() {
            return wherePredicate;
        }

        public void setWherePredicate(String wherePredicate) {
            this.wherePredicate = wherePredicate;
        }

        public boolean isWhereConflict() {
            return whereConflict;
        }

        public void setWhereConflict(boolean whereConflict) {
            this.whereConflict = whereConflict;
        }

        public boolean isLeftJoinConflict() {
            return leftJoinConflict;
        }

        public void setLeftJoinConflict(boolean leftJoinConflict) {
            this.leftJoinConflict = leftJoinConflict;
        }

        public List<LeftJoinSpec> getLeftJoins() {
            return leftJoins;
        }

        public List<String> getUnsupportedJoins() {
            return unsupportedJoins;
        }

        public List<String> getWhereSourceTables() {
            return whereSourceTables;
        }

        public List<String> getNotes() {
            return notes;
        }
    }
}

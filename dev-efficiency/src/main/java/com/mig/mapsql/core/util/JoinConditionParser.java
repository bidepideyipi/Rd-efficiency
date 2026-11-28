package com.mig.mapsql.core.util;

import com.mig.mapsql.core.model.LeftJoinSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JoinConditionParser {

    public enum ConditionKind {
        WHERE_SELECT,
        WHERE_PLAIN,
        LEFT_JOIN,
        INNER_JOIN,
        OTHER_JOIN,
        UNKNOWN
    }

    private static final Pattern SELECT_WHERE = Pattern.compile(
            "(?is)^\\s*select\\s+\\*\\s+from\\s+([A-Za-z0-9_#$\"\\.]+)\\s+(?:[A-Za-z][A-Za-z0-9_#$]*\\s+)?where\\s+(.+)$");

    /** 文中任意位置的 JOIN 子句（LEFT / INNER / 裸 JOIN / RIGHT / FULL） */
    private static final Pattern JOIN_CLAUSE = Pattern.compile(
            "(?is)\\b((?:left\\s+(?:outer\\s+)?)|(?:inner\\s+)|(?:right\\s+(?:outer\\s+)?)|(?:full\\s+(?:outer\\s+)?))?join\\s+"
                    + "([A-Za-z0-9_#$\"\\.]+)\\s+(?:([A-Za-z][A-Za-z0-9_#$]*)\\s+)?on\\s+"
                    + "(.+?)(?=\\s*;\\s*$|\\s*$|\\b(?:left\\s+(?:outer\\s+)?|inner\\s+|right\\s+(?:outer\\s+)?|full\\s+(?:outer\\s+)?)join\\b)");

    /** select * from T [alias] where PRED，且后面可能还有 join —— 只取 where 段 */
    private static final Pattern SELECT_WHERE_BEFORE_JOIN = Pattern.compile(
            "(?is)^\\s*select\\s+\\*\\s+from\\s+([A-Za-z0-9_#$\"\\.]+)\\s+(?:([A-Za-z][A-Za-z0-9_#$]*)\\s+)?"
                    + "where\\s+(.+?)(?=\\b(?:left\\s+(?:outer\\s+)?|inner\\s+|right\\s+(?:outer\\s+)?|full\\s+(?:outer\\s+)?)?join\\b)");

    /** select * from T [alias] …（用于识别主表，无论后面是 where 还是 join） */
    private static final Pattern SELECT_FROM_TABLE = Pattern.compile(
            "(?is)^\\s*select\\s+\\*\\s+from\\s+([A-Za-z0-9_#$\"\\.]+)\\b");

    private JoinConditionParser() {
    }

    public static String normalizeKey(String raw) {
        return Strings.trimToEmpty(raw).replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * 从一条「库表关联条件」原文拆出 0..N 条分类条件（支持 select+inner join 写在同一格）。
     */
    public static List<ClassifiedCondition> extractConditions(String raw) {
        List<ClassifiedCondition> out = new ArrayList<ClassifiedCondition>();
        if (Strings.isBlank(raw)) {
            return out;
        }
        String text = raw.trim();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }

        Matcher joinMatcher = JOIN_CLAUSE.matcher(text);
        boolean foundJoin = false;
        while (joinMatcher.find()) {
            foundJoin = true;
            String typeToken = joinMatcher.group(1);
            String table = stripQuotes(joinMatcher.group(2));
            String alias = joinMatcher.group(3);
            String on = trimTrailingSemi(joinMatcher.group(4));
            String kindType = classifyJoinType(typeToken);
            if ("RIGHT".equals(kindType) || "FULL".equals(kindType)) {
                out.add(new ClassifiedCondition(ConditionKind.OTHER_JOIN, joinMatcher.group(), table, alias, on));
            } else if ("INNER".equals(kindType)) {
                out.add(new ClassifiedCondition(ConditionKind.INNER_JOIN, joinMatcher.group(), table, alias, on));
            } else {
                out.add(new ClassifiedCondition(ConditionKind.LEFT_JOIN, joinMatcher.group(), table, alias, on));
            }
        }

        if (foundJoin) {
            Matcher swj = SELECT_WHERE_BEFORE_JOIN.matcher(text);
            if (swj.find()) {
                String table = stripQuotes(swj.group(1));
                String pred = trimTrailingSemi(swj.group(3));
                out.add(0, new ClassifiedCondition(ConditionKind.WHERE_SELECT, swj.group(), table, null, pred));
            } else {
                Matcher fromOnly = SELECT_FROM_TABLE.matcher(text);
                if (fromOnly.find()) {
                    // 无 WHERE，仅记录主表来源，谓词为空（parseAll 忽略空谓词）
                    String table = stripQuotes(fromOnly.group(1));
                    out.add(0, new ClassifiedCondition(ConditionKind.WHERE_SELECT, fromOnly.group(), table, null, null));
                }
            }
            return out;
        }

        // 无 JOIN：原有 select…where / 纯谓词
        Matcher sw = SELECT_WHERE.matcher(text);
        if (sw.matches()) {
            String table = stripQuotes(sw.group(1));
            String pred = trimTrailingSemi(sw.group(2));
            out.add(new ClassifiedCondition(ConditionKind.WHERE_SELECT, text, table, null, pred));
            return out;
        }

        // select * from T [alias] 且无 WHERE：只记主表，谓词为空（避免把整段 select 塞进 AND）
        Matcher fromOnly = SELECT_FROM_TABLE.matcher(text);
        if (fromOnly.find()) {
            String table = stripQuotes(fromOnly.group(1));
            out.add(new ClassifiedCondition(ConditionKind.WHERE_SELECT, fromOnly.group(), table, null, null));
            return out;
        }

        out.add(new ClassifiedCondition(ConditionKind.WHERE_PLAIN, text, null, null, trimTrailingSemi(text)));
        return out;
    }

    /** 兼容旧调用：返回首条分类（测试 / 调试用） */
    public static ClassifiedCondition classify(String raw) {
        List<ClassifiedCondition> list = extractConditions(raw);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 将去重后的关联条件原文解析为 WHERE 谓词与 JOIN 列表（LEFT / INNER）。
     */
    public static RelationParseResult parseAll(List<String> rawConditions, String primaryAlias) {
        RelationParseResult result = new RelationParseResult();
        if (rawConditions == null || rawConditions.isEmpty()) {
            return result;
        }

        Map<String, String> whereByKey = new LinkedHashMap<String, String>();
        // 按别名去重：同一副表可多条 JOIN（不同别名）；同别名且 ON/类型一致则合并
        Map<String, LeftJoinSpec> joinByAlias = new LinkedHashMap<String, LeftJoinSpec>();
        Set<String> usedAliases = new HashSet<String>();
        if (!Strings.isBlank(primaryAlias)) {
            usedAliases.add(primaryAlias.toLowerCase(Locale.ROOT));
        }
        int autoAliasOrd = 0;

        for (String raw : rawConditions) {
            for (ClassifiedCondition c : extractConditions(raw)) {
                switch (c.getKind()) {
                    case WHERE_SELECT:
                    case WHERE_PLAIN:
                        if (!Strings.isBlank(c.getPredicate())) {
                            String wkey = normalizeKey(c.getPredicate());
                            if (!whereByKey.containsKey(wkey)) {
                                whereByKey.put(wkey, c.getPredicate());
                            }
                        }
                        if (c.getTableName() != null) {
                            result.getWhereSourceTables().add(c.getTableName());
                        }
                        break;
                    case LEFT_JOIN:
                    case INNER_JOIN:
                        autoAliasOrd = addJoin(result, joinByAlias, usedAliases, c, primaryAlias, autoAliasOrd);
                        break;
                    case OTHER_JOIN:
                        result.getUnsupportedJoins().add(c.getRaw());
                        break;
                    default:
                        result.getUnsupportedJoins().add(c.getRaw());
                        break;
                }
            }
        }

        if (whereByKey.size() > 1) {
            result.setWhereConflict(true);
            result.setWherePredicate(null);
        } else if (whereByKey.size() == 1) {
            result.setWherePredicate(whereByKey.values().iterator().next());
        }

        result.getLeftJoins().addAll(joinByAlias.values());
        return result;
    }

    /**
     * 从字段行「库表关联条件」中解析与源表匹配的 JOIN 别名（Excel 原文别名；可能已被自动改名）。
     */
    public static String extractJoinAliasForTable(String rawJoinCondition, String sourceTable) {
        ClassifiedCondition c = findJoinForTable(rawJoinCondition, sourceTable);
        if (c == null || Strings.isBlank(c.getAlias())) {
            return null;
        }
        return c.getAlias().trim();
    }

    /**
     * 解析字段应使用的 JOIN 别名：仅当字段「源表」明确等于某条 JOIN 表名时才返回副表别名
     * （兼容同表多关联时 Excel 共用别名、生成器自动改名）。
     * <p>
     * 源表为空、或源表是主表、或本行虽粘贴了整段 join SQL 但字段实属主表时，返回 null，
     * 由调用方回退主表别名——禁止回退到「条件里的第一条 JOIN」，否则 MAINSEQ 会被误标成 b.MAINSEQ。
     */
    public static String resolveJoinAlias(String rawJoinCondition,
                                          String sourceTable,
                                          List<LeftJoinSpec> joins,
                                          String primaryAlias) {
        ClassifiedCondition c = findJoinForTable(rawJoinCondition, sourceTable);
        if (c == null) {
            return null;
        }
        String on = rewritePrimaryAlias(c.getPredicate(), primaryAlias);
        String fieldCanon = canonicalizeOn(on, c.getAlias());
        if (joins != null && c.getTableName() != null) {
            for (LeftJoinSpec lj : joins) {
                if (lj.getTableName() == null) {
                    continue;
                }
                if (!lj.getTableName().equalsIgnoreCase(c.getTableName().trim())) {
                    continue;
                }
                if (fieldCanon.equals(canonicalizeOn(lj.getOnPredicate(), lj.getAlias()))) {
                    return lj.getAlias();
                }
            }
        }
        return Strings.isBlank(c.getAlias()) ? null : c.getAlias().trim();
    }

    private static ClassifiedCondition findJoinForTable(String rawJoinCondition, String sourceTable) {
        if (Strings.isBlank(rawJoinCondition) || Strings.isBlank(sourceTable)) {
            return null;
        }
        String want = sourceTable.trim().toUpperCase(Locale.ROOT);
        for (ClassifiedCondition c : extractConditions(rawJoinCondition)) {
            if (c.getKind() != ConditionKind.LEFT_JOIN && c.getKind() != ConditionKind.INNER_JOIN) {
                continue;
            }
            if (c.getTableName() == null) {
                continue;
            }
            if (want.equals(c.getTableName().trim().toUpperCase(Locale.ROOT))) {
                return c;
            }
        }
        return null;
    }

    /** 将 ON 中副表别名前缀归一，便于「自动改名前后」按 ON 语义匹配。 */
    static String canonicalizeOn(String onPredicate, String joinAlias) {
        if (Strings.isBlank(onPredicate)) {
            return "";
        }
        String x = onPredicate;
        if (!Strings.isBlank(joinAlias)) {
            x = x.replaceAll("(?i)\\b" + Pattern.quote(joinAlias.trim()) + "\\.", "#j.");
        }
        return normalizeKey(x);
    }

    private static String rewriteJoinSelfAlias(String onPredicate, String fromAlias, String toAlias) {
        if (Strings.isBlank(onPredicate) || Strings.isBlank(fromAlias) || Strings.isBlank(toAlias)) {
            return onPredicate;
        }
        if (fromAlias.equalsIgnoreCase(toAlias)) {
            return onPredicate;
        }
        return onPredicate.replaceAll("(?i)\\b" + Pattern.quote(fromAlias.trim()) + "\\.", toAlias + ".");
    }

    private static int addJoin(RelationParseResult result,
                               Map<String, LeftJoinSpec> joinByAlias,
                               Set<String> usedAliases,
                               ClassifiedCondition c,
                               String primaryAlias,
                               int autoAliasOrd) {
        String joinType = c.getKind() == ConditionKind.INNER_JOIN
                ? LeftJoinSpec.TYPE_INNER
                : LeftJoinSpec.TYPE_LEFT;
        String on = rewritePrimaryAlias(c.getPredicate(), primaryAlias);

        String alias = c.getAlias();
        if (Strings.isBlank(alias)) {
            alias = nextJoinAlias(primaryAlias, usedAliases);
            autoAliasOrd++;
            result.getNotes().add(joinType + " JOIN " + c.getTableName() + " 自动分配别名 " + alias);
        }

        String aliasKey = alias.toLowerCase(Locale.ROOT);
        LeftJoinSpec existing = joinByAlias.get(aliasKey);
        if (existing != null) {
            boolean sameTable = existing.getTableName().equalsIgnoreCase(c.getTableName());
            boolean sameOn = canonicalizeOn(existing.getOnPredicate(), existing.getAlias())
                    .equals(canonicalizeOn(on, alias));
            boolean sameType = existing.getJoinType().equalsIgnoreCase(joinType);
            if (sameTable && sameOn && sameType) {
                return autoAliasOrd; // 同别名相同 JOIN：合并
            }
            // 同别名冲突（不同表，或同表不同 ON/类型）：自动改别名
            String oldAlias = alias;
            alias = nextJoinAlias(primaryAlias, usedAliases);
            aliasKey = alias.toLowerCase(Locale.ROOT);
            autoAliasOrd++;
            on = rewriteJoinSelfAlias(on, oldAlias, alias);
            if (sameTable) {
                result.getNotes().add(joinType + " JOIN " + c.getTableName()
                        + " 别名 " + oldAlias + " 已用于不同 ON，自动改为 " + alias);
            } else {
                result.getNotes().add(joinType + " JOIN " + c.getTableName()
                        + " 别名 " + oldAlias + " 已被其他表占用，自动改为 " + alias);
            }
        } else if (usedAliases.contains(aliasKey)) {
            String oldAlias = alias;
            alias = nextJoinAlias(primaryAlias, usedAliases);
            aliasKey = alias.toLowerCase(Locale.ROOT);
            autoAliasOrd++;
            on = rewriteJoinSelfAlias(on, oldAlias, alias);
            result.getNotes().add(joinType + " JOIN " + c.getTableName()
                    + " 别名 " + oldAlias + " 冲突，自动改为 " + alias);
        }

        usedAliases.add(aliasKey);
        joinByAlias.put(aliasKey, new LeftJoinSpec(c.getTableName(), alias, on, c.getRaw(), joinType));
        if (c.getRaw() != null && normalizeKey(c.getRaw()).contains(" a.")) {
            result.getNotes().add(joinType + " JOIN ON 中的 a. 已改写为 " + primaryAlias + ".");
        }
        return autoAliasOrd;
    }

    private static String classifyJoinType(String typeToken) {
        if (typeToken == null || typeToken.trim().isEmpty()) {
            return "INNER"; // 裸 JOIN 视为 INNER
        }
        String t = typeToken.toLowerCase(Locale.ROOT);
        if (t.startsWith("left")) {
            return "LEFT";
        }
        if (t.startsWith("inner")) {
            return "INNER";
        }
        if (t.startsWith("right")) {
            return "RIGHT";
        }
        if (t.startsWith("full")) {
            return "FULL";
        }
        return "INNER";
    }

    public static String rewritePrimaryAlias(String onPredicate, String primaryAlias) {
        if (Strings.isBlank(onPredicate)) {
            return onPredicate;
        }
        return onPredicate.replaceAll("(?i)\\ba\\.", primaryAlias + ".");
    }

    private static String nextJoinAlias(String primaryAlias, Set<String> used) {
        for (char ch = 'b'; ch <= 'z'; ch++) {
            String candidate = String.valueOf(ch);
            if (candidate.equalsIgnoreCase(primaryAlias)) {
                continue;
            }
            if (used != null && used.contains(candidate)) {
                continue;
            }
            return candidate;
        }
        int i = 0;
        while (true) {
            String candidate = "j" + i;
            if (used == null || !used.contains(candidate)) {
                return candidate;
            }
            i++;
        }
    }

    private static String trimTrailingSemi(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        while (t.endsWith(";")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
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

package com.mig.mapsql.standard;

import com.mig.mapsql.config.GeneratorProperties;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Repository
public class StandardRuleRepository {

    private final String dbPath;

    public StandardRuleRepository(GeneratorProperties properties) {
        this.dbPath = properties.getStandardDbPath();
    }

    @PostConstruct
    public void init() {
        File file = new File(dbPath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE IF NOT EXISTS data_standard_rule ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "rule_type TEXT NOT NULL,"
                            + "match_expr TEXT NOT NULL,"
                            + "target_type TEXT NOT NULL,"
                            + "rebuild INTEGER NOT NULL DEFAULT 1,"
                            + "enabled INTEGER NOT NULL DEFAULT 1,"
                            + "priority INTEGER NOT NULL DEFAULT 100,"
                            + "remark TEXT"
                            + ")");
            Integer cnt;
            try (ResultSet rs = st.executeQuery("SELECT COUNT(1) FROM data_standard_rule")) {
                rs.next();
                cnt = rs.getInt(1);
            }
            if (cnt == 0) {
                seedDefaults(conn);
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化数标 SQLite 失败: " + dbPath, e);
        }
    }

    private void seedDefaults(Connection conn) throws Exception {
        insert(conn, "TYPE", "CHAR", "VARCHAR2({n})", true, true, 100, "CHAR(n) → VARCHAR2(n)，四步重建");
        insert(conn, "TYPE", "CLOB", "VARCHAR2(400)", true, true, 100, "CLOB → VARCHAR2(400)，四步重建");
        insert(conn, "COLUMN", "AMOUNT", "NUMBER(22,2)", true, true, 10, "AMOUNT → NUMBER(22,2)，四步重建");
    }

    private static void insert(Connection conn, String ruleType, String matchExpr, String targetType,
                               boolean rebuild, boolean enabled, int priority, String remark) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO data_standard_rule(rule_type,match_expr,target_type,rebuild,enabled,priority,remark) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, ruleType);
            ps.setString(2, matchExpr);
            ps.setString(3, targetType);
            ps.setInt(4, rebuild ? 1 : 0);
            ps.setInt(5, enabled ? 1 : 0);
            ps.setInt(6, priority);
            ps.setString(7, remark);
            ps.executeUpdate();
        }
    }

    private Connection open() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    public List<StandardRule> listAll() {
        List<StandardRule> list = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id,rule_type,match_expr,target_type,rebuild,enabled,priority,remark "
                             + "FROM data_standard_rule ORDER BY priority ASC, id ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取数标规则失败", e);
        }
        return list;
    }

    public List<StandardRule> listEnabled() {
        List<StandardRule> list = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id,rule_type,match_expr,target_type,rebuild,enabled,priority,remark "
                             + "FROM data_standard_rule WHERE enabled=1 ORDER BY priority ASC, id ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取数标规则失败", e);
        }
        return list;
    }

    public StandardRule save(StandardRule rule) {
        validate(rule);
        try (Connection conn = open()) {
            if (rule.getId() == null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO data_standard_rule(rule_type,match_expr,target_type,rebuild,enabled,priority,remark) "
                                + "VALUES(?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, rule);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            rule.setId(keys.getLong(1));
                        }
                    }
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE data_standard_rule SET rule_type=?,match_expr=?,target_type=?,rebuild=?,enabled=?,"
                                + "priority=?,remark=? WHERE id=?")) {
                    bind(ps, rule);
                    ps.setLong(8, rule.getId());
                    ps.executeUpdate();
                }
            }
            return rule;
        } catch (Exception e) {
            throw new IllegalStateException("保存数标规则失败", e);
        }
    }

    public void delete(long id) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM data_standard_rule WHERE id=?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("删除数标规则失败", e);
        }
    }

    private static void bind(PreparedStatement ps, StandardRule rule) throws Exception {
        ps.setString(1, rule.getRuleType().trim().toUpperCase(Locale.ROOT));
        ps.setString(2, rule.getMatchExpr().trim());
        ps.setString(3, rule.getTargetType().trim());
        ps.setInt(4, rule.isRebuild() ? 1 : 0);
        ps.setInt(5, rule.isEnabled() ? 1 : 0);
        ps.setInt(6, rule.getPriority());
        if (rule.getRemark() == null) {
            ps.setNull(7, Types.VARCHAR);
        } else {
            ps.setString(7, rule.getRemark());
        }
    }

    private static void validate(StandardRule rule) {
        if (rule.getRuleType() == null || rule.getRuleType().trim().isEmpty()) {
            throw new IllegalArgumentException("ruleType 必填：TYPE 或 COLUMN");
        }
        String t = rule.getRuleType().trim().toUpperCase(Locale.ROOT);
        if (!"TYPE".equals(t) && !"COLUMN".equals(t)) {
            throw new IllegalArgumentException("ruleType 仅支持 TYPE / COLUMN");
        }
        if (rule.getMatchExpr() == null || rule.getMatchExpr().trim().isEmpty()) {
            throw new IllegalArgumentException("matchExpr 必填");
        }
        if (rule.getTargetType() == null || rule.getTargetType().trim().isEmpty()) {
            throw new IllegalArgumentException("targetType 必填");
        }
    }

    private static StandardRule map(ResultSet rs) throws Exception {
        StandardRule r = new StandardRule();
        r.setId(rs.getLong("id"));
        r.setRuleType(rs.getString("rule_type"));
        r.setMatchExpr(rs.getString("match_expr"));
        r.setTargetType(rs.getString("target_type"));
        r.setRebuild(rs.getInt("rebuild") == 1);
        r.setEnabled(rs.getInt("enabled") == 1);
        r.setPriority(rs.getInt("priority"));
        r.setRemark(rs.getString("remark"));
        return r;
    }
}

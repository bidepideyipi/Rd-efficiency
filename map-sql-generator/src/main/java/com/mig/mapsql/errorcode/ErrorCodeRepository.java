package com.mig.mapsql.errorcode;

import com.mig.mapsql.config.GeneratorProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ErrorCodeRepository {

    public static final String CLASSPATH_XLSX = "errorcode/error-code-standard.xlsx";

    private final String dbPath;

    public ErrorCodeRepository(GeneratorProperties properties) {
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
                    "CREATE TABLE IF NOT EXISTS error_code_standard ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "module TEXT,"
                            + "category TEXT,"
                            + "vendor_code TEXT,"
                            + "vendor_const TEXT,"
                            + "seq_no INTEGER,"
                            + "full_code TEXT NOT NULL,"
                            + "message TEXT"
                            + ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ecs_category ON error_code_standard(category)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_ecs_full_code ON error_code_standard(full_code)");
            int cnt;
            try (ResultSet rs = st.executeQuery("SELECT COUNT(1) FROM error_code_standard")) {
                rs.next();
                cnt = rs.getInt(1);
            }
            if (cnt == 0) {
                importFromClasspath(conn);
            }
        } catch (Exception e) {
            throw new IllegalStateException("初始化错误码 SQLite 失败: " + dbPath, e);
        }
    }

    /** 测试用：从 classpath Excel 重新装载（清空后导入）。 */
    int reloadFromClasspath() {
        try (Connection conn = open()) {
            try (Statement st = conn.createStatement()) {
                st.execute("DELETE FROM error_code_standard");
            }
            return importFromClasspath(conn);
        } catch (Exception e) {
            throw new IllegalStateException("重新导入错误码失败", e);
        }
    }

    private int importFromClasspath(Connection conn) throws Exception {
        ClassPathResource res = new ClassPathResource(CLASSPATH_XLSX);
        if (!res.exists()) {
            throw new IllegalStateException("classpath 缺少 " + CLASSPATH_XLSX);
        }
        try (InputStream in = res.getInputStream()) {
            List<ErrorCodeEntry> rows = ErrorCodeExcelReader.read(in);
            return insertAll(conn, rows);
        }
    }

    private static int insertAll(Connection conn, List<ErrorCodeEntry> rows) throws Exception {
        boolean old = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO error_code_standard(module,category,vendor_code,vendor_const,seq_no,full_code,message) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            for (ErrorCodeEntry e : rows) {
                ps.setString(1, nullToEmpty(e.getModule()));
                ps.setString(2, nullToEmpty(e.getCategory()));
                ps.setString(3, nullToEmpty(e.getVendorCode()));
                ps.setString(4, nullToEmpty(e.getVendorConst()));
                if (e.getSeqNo() == null) {
                    ps.setNull(5, Types.INTEGER);
                } else {
                    ps.setInt(5, e.getSeqNo());
                }
                ps.setString(6, nullToEmpty(e.getFullCode()));
                ps.setString(7, nullToEmpty(e.getMessage()));
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            conn.commit();
            int n = 0;
            for (int c : counts) {
                if (c > 0) {
                    n += c;
                } else if (c == Statement.SUCCESS_NO_INFO) {
                    n++;
                }
            }
            return n > 0 ? n : rows.size();
        } catch (Exception ex) {
            conn.rollback();
            throw ex;
        } finally {
            conn.setAutoCommit(old);
        }
    }

    public List<String> listCategories() {
        List<String> list = new ArrayList<String>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT DISTINCT category FROM error_code_standard "
                             + "WHERE category IS NOT NULL AND category<>'' ORDER BY category");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(rs.getString(1));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取错误码分类失败", e);
        }
        return list;
    }

    /**
     * @param category 精确匹配分类，空则不过滤
     * @param code     模糊匹配完整错误码 / 厂商原码 / 常量名
     * @param message  模糊匹配银行错误说明
     */
    public List<ErrorCodeEntry> search(String category, String code, String message) {
        StringBuilder sql = new StringBuilder(
                "SELECT id,module,category,vendor_code,vendor_const,seq_no,full_code,message "
                        + "FROM error_code_standard WHERE 1=1");
        List<String> params = new ArrayList<String>();
        if (category != null && !category.trim().isEmpty()) {
            sql.append(" AND category = ?");
            params.add(category.trim());
        }
        if (code != null && !code.trim().isEmpty()) {
            sql.append(" AND (full_code LIKE ? OR vendor_code LIKE ? OR vendor_const LIKE ?)");
            String like = "%" + code.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (message != null && !message.trim().isEmpty()) {
            sql.append(" AND message LIKE ?");
            params.add("%" + message.trim() + "%");
        }
        sql.append(" ORDER BY seq_no ASC, id ASC");

        List<ErrorCodeEntry> list = new ArrayList<ErrorCodeEntry>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("查询错误码失败", e);
        }
        return list;
    }

    public int count() {
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(1) FROM error_code_standard")) {
            rs.next();
            return rs.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException("统计错误码失败", e);
        }
    }

    public Map<String, Object> summary() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("total", count());
        body.put("categories", listCategories());
        Map<String, Integer> byCategory = new LinkedHashMap<String, Integer>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT category, COUNT(1) c FROM error_code_standard "
                             + "GROUP BY category ORDER BY category");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                byCategory.put(rs.getString(1), rs.getInt(2));
            }
        } catch (Exception e) {
            throw new IllegalStateException("汇总错误码失败", e);
        }
        body.put("byCategory", byCategory);
        return body;
    }

    private Connection open() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    private static ErrorCodeEntry map(ResultSet rs) throws Exception {
        ErrorCodeEntry e = new ErrorCodeEntry();
        e.setId(rs.getLong("id"));
        e.setModule(rs.getString("module"));
        e.setCategory(rs.getString("category"));
        e.setVendorCode(rs.getString("vendor_code"));
        e.setVendorConst(rs.getString("vendor_const"));
        int seq = rs.getInt("seq_no");
        e.setSeqNo(rs.wasNull() ? null : seq);
        e.setFullCode(rs.getString("full_code"));
        e.setMessage(rs.getString("message"));
        return e;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

package com.mig.mapsql.catalog;

import com.mig.mapsql.config.MigDataSourceProperties.DataSourceDef;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OceanBase Oracle 模式：读取基表列定义（ALL_TAB_COLUMNS）。
 */
@Component
public class ObSchemaMetadataClient {

    private final MigDataSourceRegistry registry;

    public ObSchemaMetadataClient(MigDataSourceRegistry registry) {
        this.registry = registry;
    }

    public Map<String, DbColumnMeta> describeColumns(String datasourceId, String tableName) {
        DataSourceDef def = registry.requireDef(datasourceId);
        DataSource ds = registry.requireDataSource(datasourceId);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String owner = resolveOwner(jdbc, def.getSchema());
        String table = tableName.trim().toUpperCase(Locale.ROOT);

        String sql = "SELECT COLUMN_NAME, DATA_TYPE, NULLABLE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE, "
                + "CHAR_LENGTH, COLUMN_ID "
                + "FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID";

        Map<String, DbColumnMeta> map = new LinkedHashMap<String, DbColumnMeta>();
        jdbc.query(sql, new Object[]{owner, table}, (ResultSet rs) -> {
            DbColumnMeta m = new DbColumnMeta();
            m.setTableName(table);
            m.setColumnName(rs.getString("COLUMN_NAME"));
            String dataType = rs.getString("DATA_TYPE");
            Integer dataLength = getInt(rs, "DATA_LENGTH");
            Integer dataPrecision = getInt(rs, "DATA_PRECISION");
            Integer dataScale = getInt(rs, "DATA_SCALE");
            Integer charLength = getInt(rs, "CHAR_LENGTH");
            OracleTypeFormatter.applyExcelTypeAndLength(m, dataType, dataLength, dataPrecision, dataScale, charLength);
            m.setNullable(rs.getString("NULLABLE"));
            m.setColumnId(rs.getInt("COLUMN_ID"));
            map.put(m.getColumnName().toUpperCase(Locale.ROOT), m);
        });

        if (map.isEmpty()) {
            return map;
        }

        try {
            String csql = "SELECT COLUMN_NAME, COMMENTS FROM ALL_COL_COMMENTS "
                    + "WHERE OWNER = ? AND TABLE_NAME = ?";
            jdbc.query(csql, new Object[]{owner, table}, (ResultSet rs) -> {
                String col = rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT);
                DbColumnMeta m = map.get(col);
                if (m != null) {
                    m.setComment(rs.getString("COMMENTS"));
                }
            });
        } catch (Exception ignored) {
            // comments optional
        }
        return map;
    }

    public boolean tableExists(String datasourceId, String tableName) {
        DataSourceDef def = registry.requireDef(datasourceId);
        DataSource ds = registry.requireDataSource(datasourceId);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String owner = resolveOwner(jdbc, def.getSchema());
        String table = tableName.trim().toUpperCase(Locale.ROOT);
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(1) FROM ALL_TABLES WHERE OWNER = ? AND TABLE_NAME = ?",
                new Object[]{owner, table}, Integer.class);
        return cnt != null && cnt > 0;
    }

    public List<String> listTables(String datasourceId) {
        DataSourceDef def = registry.requireDef(datasourceId);
        DataSource ds = registry.requireDataSource(datasourceId);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String owner = resolveOwner(jdbc, def.getSchema());
        return jdbc.query(
                "SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER = ? ORDER BY TABLE_NAME",
                new Object[]{owner},
                (rs, i) -> rs.getString(1));
    }

    public String resolveOwner(String datasourceId) {
        DataSourceDef def = registry.requireDef(datasourceId);
        DataSource ds = registry.requireDataSource(datasourceId);
        return resolveOwner(new JdbcTemplate(ds), def.getSchema());
    }

    private static String resolveOwner(JdbcTemplate jdbc, String configured) {
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim().toUpperCase(Locale.ROOT);
        }
        return jdbc.queryForObject("SELECT USER FROM DUAL", String.class).toUpperCase(Locale.ROOT);
    }

    private static Integer getInt(ResultSet rs, String col) throws java.sql.SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}

package com.mig.mapsql.catalog;

import com.mig.mapsql.config.MigDataSourceProperties;
import com.mig.mapsql.config.MigDataSourceProperties.DataSourceDef;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 id 注册 OceanBase Hikari 数据源（启动时创建 enabled 项）。
 */
@Component
public class MigDataSourceRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MigDataSourceRegistry.class);

    private final Map<String, DataSourceDef> defs = new LinkedHashMap<String, DataSourceDef>();
    private final Map<String, HikariDataSource> pools = new LinkedHashMap<String, HikariDataSource>();

    public MigDataSourceRegistry(MigDataSourceProperties properties) {
        if (properties.getDatasources() == null) {
            return;
        }
        for (DataSourceDef def : properties.getDatasources()) {
            if (def == null || !def.isEnabled()) {
                continue;
            }
            if (def.getId() == null || def.getId().trim().isEmpty()) {
                throw new IllegalStateException("mig.datasources[].id is required");
            }
            String id = def.getId().trim();
            if (defs.containsKey(id)) {
                throw new IllegalStateException("duplicate datasource id: " + id);
            }
            defs.put(id, def);
            pools.put(id, createPool(def));
            log.info("Registered datasource id={} name={} schema={}",
                    id, def.displayName(), def.getSchema());
        }
    }

    private static HikariDataSource createPool(DataSourceDef def) {
        if (def.getJdbcUrl() == null || def.getJdbcUrl().trim().isEmpty()) {
            throw new IllegalStateException("datasource " + def.getId() + " missing jdbcUrl");
        }
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("mig-" + def.getId());
        ds.setDriverClassName(def.getDriverClassName() == null || def.getDriverClassName().trim().isEmpty()
                ? "com.oceanbase.jdbc.Driver" : def.getDriverClassName().trim());
        ds.setJdbcUrl(def.getJdbcUrl().trim());
        ds.setUsername(def.getUsername());
        ds.setPassword(def.getPassword());
        ds.setMaximumPoolSize(3);
        ds.setMinimumIdle(0);
        ds.setConnectionTimeout(30_000L);
        ds.setIdleTimeout(60_000L);
        ds.setMaxLifetime(300_000L);
        ds.setConnectionTestQuery("SELECT 1 FROM DUAL");
        return ds;
    }

    public List<DataSourceDef> listEnabled() {
        return Collections.unmodifiableList(new ArrayList<DataSourceDef>(defs.values()));
    }

    public DataSourceDef requireDef(String id) {
        DataSourceDef def = defs.get(normalizeId(id));
        if (def == null) {
            throw new IllegalArgumentException("unknown or disabled datasource id: " + id);
        }
        return def;
    }

    public DataSource requireDataSource(String id) {
        HikariDataSource ds = pools.get(normalizeId(id));
        if (ds == null) {
            throw new IllegalArgumentException("unknown or disabled datasource id: " + id);
        }
        return ds;
    }

    public void testConnection(String id) {
        DataSource ds = requireDataSource(id);
        try {
            java.sql.Connection c = ds.getConnection();
            try {
                c.createStatement().execute("SELECT 1 FROM DUAL");
            } finally {
                c.close();
            }
        } catch (Exception e) {
            throw new IllegalStateException("datasource test failed: " + id + " — " + e.getMessage(), e);
        }
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim();
    }

    @Override
    public void destroy() {
        for (Map.Entry<String, HikariDataSource> e : pools.entrySet()) {
            try {
                e.getValue().close();
            } catch (Exception ex) {
                log.warn("close datasource {} failed: {}", e.getKey(), ex.getMessage());
            }
        }
        pools.clear();
    }
}

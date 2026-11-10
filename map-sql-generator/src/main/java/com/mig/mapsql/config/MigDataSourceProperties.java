package com.mig.mapsql.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * OceanBase（Oracle 模式）多数据源配置。仅用于元数据读取 / Excel 对比。
 */
@ConfigurationProperties(prefix = "mig")
public class MigDataSourceProperties {

    private List<DataSourceDef> datasources = new ArrayList<DataSourceDef>();

    public List<DataSourceDef> getDatasources() {
        return datasources;
    }

    public void setDatasources(List<DataSourceDef> datasources) {
        this.datasources = datasources == null ? new ArrayList<DataSourceDef>() : datasources;
    }

    public static class DataSourceDef {
        private String id;
        private String name;
        private String jdbcUrl;
        private String username;
        private String password;
        /** Oracle owner / schema，空则用当前用户 */
        private String schema;
        private String driverClassName = "com.oceanbase.jdbc.Driver";
        private boolean enabled = true;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String displayName() {
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
            return id == null ? "(unset)" : id;
        }
    }
}

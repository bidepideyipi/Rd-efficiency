package com.mig.mapsql.cnccq;

import com.mig.mapsql.config.GeneratorProperties;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Repository
public class CnccqConfigRepository {

    private final String dbPath;

    public CnccqConfigRepository(GeneratorProperties properties) {
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
                    "CREATE TABLE IF NOT EXISTS cnccq_endpoint_config ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + "name TEXT NOT NULL,"
                            + "host TEXT NOT NULL,"
                            + "port INTEGER NOT NULL,"
                            + "queue_manager TEXT NOT NULL,"
                            + "inbound_queue TEXT,"
                            + "outbound_queue TEXT,"
                            + "username TEXT,"
                            + "password TEXT,"
                            + "tcp_wait_time_ms INTEGER NOT NULL DEFAULT 60000,"
                            + "pool_size INTEGER NOT NULL DEFAULT 2,"
                            + "remark TEXT,"
                            + "created_at TEXT,"
                            + "updated_at TEXT"
                            + ")");
            st.execute("CREATE INDEX IF NOT EXISTS idx_cnccq_cfg_name ON cnccq_endpoint_config(name)");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 CNCCQ 配置 SQLite 失败: " + dbPath, e);
        }
    }

    public List<CnccqEndpointConfig> listAll() {
        List<CnccqEndpointConfig> list = new ArrayList<CnccqEndpointConfig>();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id,name,host,port,queue_manager,inbound_queue,outbound_queue,"
                             + "username,password,tcp_wait_time_ms,pool_size,remark,created_at,updated_at "
                             + "FROM cnccq_endpoint_config ORDER BY id ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 CNCCQ 配置失败", e);
        }
        return list;
    }

    public CnccqEndpointConfig findById(long id) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id,name,host,port,queue_manager,inbound_queue,outbound_queue,"
                             + "username,password,tcp_wait_time_ms,pool_size,remark,created_at,updated_at "
                             + "FROM cnccq_endpoint_config WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return map(rs);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 CNCCQ 配置失败: id=" + id, e);
        }
        return null;
    }

    public CnccqEndpointConfig save(CnccqEndpointConfig cfg) {
        validate(cfg);
        String now = nowIso();
        try (Connection conn = open()) {
            if (cfg.getId() == null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO cnccq_endpoint_config("
                                + "name,host,port,queue_manager,inbound_queue,outbound_queue,"
                                + "username,password,tcp_wait_time_ms,pool_size,remark,created_at,updated_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    bind(ps, cfg, now, true);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            cfg.setId(keys.getLong(1));
                        }
                    }
                    cfg.setCreatedAt(now);
                    cfg.setUpdatedAt(now);
                }
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE cnccq_endpoint_config SET "
                                + "name=?,host=?,port=?,queue_manager=?,"
                                + "inbound_queue=?,outbound_queue=?,username=?,password=?,"
                                + "tcp_wait_time_ms=?,pool_size=?,remark=?,updated_at=? WHERE id=?")) {
                    bind(ps, cfg, now, false);
                    ps.setLong(13, cfg.getId());
                    int n = ps.executeUpdate();
                    if (n == 0) {
                        throw new IllegalArgumentException("CNCCQ 配置不存在: id=" + cfg.getId());
                    }
                    cfg.setUpdatedAt(now);
                }
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("保存 CNCCQ 配置失败", e);
        }
        return cfg;
    }

    public void delete(long id) {
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM cnccq_endpoint_config WHERE id=?")) {
            ps.setLong(1, id);
            int n = ps.executeUpdate();
            if (n == 0) {
                throw new IllegalArgumentException("CNCCQ 配置不存在: id=" + id);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("删除 CNCCQ 配置失败", e);
        }
    }

    private static void validate(CnccqEndpointConfig cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("配置不能为空");
        }
        require(cfg.getName(), "名称");
        require(cfg.getHost(), "IP/主机");
        if (cfg.getPort() <= 0 || cfg.getPort() > 65535) {
            throw new IllegalArgumentException("端口无效: " + cfg.getPort());
        }
        require(cfg.getQueueManager(), "队列管理器");
        boolean hasIn = !isBlank(cfg.getInboundQueue());
        boolean hasOut = !isBlank(cfg.getOutboundQueue());
        if (!hasIn && !hasOut) {
            throw new IllegalArgumentException("来账队列与往账队列至少填一项");
        }
        if (cfg.getTcpWaitTimeMs() <= 0) {
            cfg.setTcpWaitTimeMs(60_000L);
        }
        if (cfg.getPoolSize() <= 0) {
            cfg.setPoolSize(2);
        }
    }

    private static void require(String v, String label) {
        if (isBlank(v)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static void bind(PreparedStatement ps, CnccqEndpointConfig cfg, String now, boolean insert)
            throws Exception {
        ps.setString(1, trim(cfg.getName()));
        ps.setString(2, trim(cfg.getHost()));
        ps.setInt(3, cfg.getPort());
        ps.setString(4, trim(cfg.getQueueManager()));
        ps.setString(5, blankToNull(cfg.getInboundQueue()));
        ps.setString(6, blankToNull(cfg.getOutboundQueue()));
        ps.setString(7, blankToNull(cfg.getUsername()));
        ps.setString(8, blankToNull(cfg.getPassword()));
        ps.setLong(9, cfg.getTcpWaitTimeMs());
        ps.setInt(10, cfg.getPoolSize());
        ps.setString(11, blankToNull(cfg.getRemark()));
        if (insert) {
            ps.setString(12, now);
            ps.setString(13, now);
        } else {
            ps.setString(12, now);
        }
    }

    private static CnccqEndpointConfig map(ResultSet rs) throws Exception {
        CnccqEndpointConfig c = new CnccqEndpointConfig();
        c.setId(rs.getLong("id"));
        c.setName(rs.getString("name"));
        c.setHost(rs.getString("host"));
        c.setPort(rs.getInt("port"));
        c.setQueueManager(rs.getString("queue_manager"));
        c.setInboundQueue(rs.getString("inbound_queue"));
        c.setOutboundQueue(rs.getString("outbound_queue"));
        c.setUsername(rs.getString("username"));
        c.setPassword(rs.getString("password"));
        c.setTcpWaitTimeMs(rs.getLong("tcp_wait_time_ms"));
        c.setPoolSize(rs.getInt("pool_size"));
        c.setRemark(rs.getString("remark"));
        c.setCreatedAt(rs.getString("created_at"));
        c.setUpdatedAt(rs.getString("updated_at"));
        return c;
    }

    private Connection open() throws Exception {
        Class.forName("org.sqlite.JDBC");
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nowIso() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}

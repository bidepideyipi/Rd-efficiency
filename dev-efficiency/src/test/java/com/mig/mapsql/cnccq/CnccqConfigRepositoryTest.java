package com.mig.mapsql.cnccq;

import com.mig.mapsql.config.GeneratorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CnccqConfigRepositoryTest {

    @TempDir
    Path tempDir;

    private CnccqConfigRepository repository;

    @BeforeEach
    void setUp() {
        GeneratorProperties props = new GeneratorProperties();
        props.setStandardDbPath(tempDir.resolve("cnccq-test.db").toString());
        repository = new CnccqConfigRepository(props);
        repository.init();
    }

    @Test
    void crudRoundTrip() {
        CnccqEndpointConfig cfg = sample("dev-cnccq");
        CnccqEndpointConfig saved = repository.save(cfg);
        assertNotNull(saved.getId());

        List<CnccqEndpointConfig> all = repository.listAll();
        assertEquals(1, all.size());
        assertEquals("10.0.0.2", all.get(0).getHost());
        assertEquals(1414, all.get(0).getPort());
        assertEquals("QM.CNCCQ", all.get(0).getQueueManager());
        assertEquals("Q.IN", all.get(0).getInboundQueue());
        assertEquals("Q.OUT", all.get(0).getOutboundQueue());
        assertEquals(60_000L, all.get(0).getTcpWaitTimeMs());
        assertEquals(2, all.get(0).getPoolSize());

        saved.setName("dev-cnccq-2");
        saved.setPort(1415);
        saved.setPoolSize(4);
        repository.save(saved);

        CnccqEndpointConfig loaded = repository.findById(saved.getId());
        assertEquals("dev-cnccq-2", loaded.getName());
        assertEquals(1415, loaded.getPort());
        assertEquals(4, loaded.getPoolSize());

        repository.delete(saved.getId());
        assertTrue(repository.listAll().isEmpty());
    }

    @Test
    void rejectWhenQueuesMissing() {
        CnccqEndpointConfig cfg = sample("bad");
        cfg.setInboundQueue("");
        cfg.setOutboundQueue(null);
        assertThrows(IllegalArgumentException.class, () -> repository.save(cfg));
    }

    @Test
    void rejectWhenQueueManagerMissing() {
        CnccqEndpointConfig cfg = sample("bad-qm");
        cfg.setQueueManager("  ");
        assertThrows(IllegalArgumentException.class, () -> repository.save(cfg));
    }

    private static CnccqEndpointConfig sample(String name) {
        CnccqEndpointConfig cfg = new CnccqEndpointConfig();
        cfg.setName(name);
        cfg.setHost("10.0.0.2");
        cfg.setPort(1414);
        cfg.setQueueManager("QM.CNCCQ");
        cfg.setInboundQueue("Q.IN");
        cfg.setOutboundQueue("Q.OUT");
        cfg.setUsername("cquser");
        cfg.setPassword("secret");
        cfg.setTcpWaitTimeMs(60_000L);
        cfg.setPoolSize(2);
        return cfg;
    }
}

package com.mig.mapsql.mq;

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

class MqConfigRepositoryTest {

    @TempDir
    Path tempDir;

    private MqConfigRepository repository;

    @BeforeEach
    void setUp() {
        GeneratorProperties props = new GeneratorProperties();
        props.setStandardDbPath(tempDir.resolve("mq-test.db").toString());
        repository = new MqConfigRepository(props);
        repository.init();
    }

    @Test
    void crudRoundTrip() {
        MqEndpointConfig cfg = sample("dev-mq");
        MqEndpointConfig saved = repository.save(cfg);
        assertNotNull(saved.getId());

        List<MqEndpointConfig> all = repository.listAll();
        assertEquals(1, all.size());
        assertEquals("10.0.0.1", all.get(0).getHost());
        assertEquals(1414, all.get(0).getPort());
        assertEquals("QM1", all.get(0).getQueueManager());
        assertEquals("CHANNEL.SVRCONN", all.get(0).getChannel());
        assertEquals("Q.IN", all.get(0).getInboundQueue());
        assertEquals("Q.OUT", all.get(0).getOutboundQueue());

        saved.setName("dev-mq-2");
        saved.setPort(1415);
        repository.save(saved);

        MqEndpointConfig loaded = repository.findById(saved.getId());
        assertEquals("dev-mq-2", loaded.getName());
        assertEquals(1415, loaded.getPort());

        repository.delete(saved.getId());
        assertTrue(repository.listAll().isEmpty());
    }

    @Test
    void rejectWhenQueuesMissing() {
        MqEndpointConfig cfg = sample("bad");
        cfg.setInboundQueue("");
        cfg.setOutboundQueue(null);
        assertThrows(IllegalArgumentException.class, () -> repository.save(cfg));
    }

    private static MqEndpointConfig sample(String name) {
        MqEndpointConfig cfg = new MqEndpointConfig();
        cfg.setName(name);
        cfg.setHost("10.0.0.1");
        cfg.setPort(1414);
        cfg.setQueueManager("QM1");
        cfg.setChannel("CHANNEL.SVRCONN");
        cfg.setInboundQueue("Q.IN");
        cfg.setOutboundQueue("Q.OUT");
        cfg.setUsername("mquser");
        cfg.setPassword("secret");
        return cfg;
    }
}

package com.mig.mapsql.errorcode;

import com.mig.mapsql.config.GeneratorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorCodeRepositoryTest {

    @TempDir
    Path tempDir;

    private ErrorCodeRepository repository;

    @BeforeEach
    void setUp() {
        GeneratorProperties props = new GeneratorProperties();
        props.setStandardDbPath(tempDir.resolve("error-code-test.db").toString());
        repository = new ErrorCodeRepository(props);
        repository.init();
    }

    @Test
    void seedsAndFilters() {
        assertTrue(repository.count() > 0);
        List<String> cats = repository.listCategories();
        assertTrue(cats.contains("业务类1"));

        List<ErrorCodeEntry> biz = repository.search("业务类1", null, null);
        assertFalse(biz.isEmpty());
        for (ErrorCodeEntry e : biz) {
            assertEquals("业务类1", e.getCategory());
        }

        List<ErrorCodeEntry> byCode = repository.search(null, "IPPxIPPx1", null);
        assertFalse(byCode.isEmpty());

        List<ErrorCodeEntry> byMsg = repository.search(null, null, "流水");
        assertFalse(byMsg.isEmpty());
        for (ErrorCodeEntry e : byMsg) {
            assertTrue(e.getMessage().contains("流水"));
        }
    }

    @Test
    void reloadReplacesRows() {
        int before = repository.count();
        int imported = repository.reloadFromClasspath();
        assertEquals(before, imported);
        assertEquals(before, repository.count());
    }
}

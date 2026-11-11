package com.mig.mapsql.errorcode;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorCodeExcelReaderTest {

    @Test
    void readsClasspathWorkbook() throws Exception {
        ClassPathResource res = new ClassPathResource(ErrorCodeRepository.CLASSPATH_XLSX);
        assertTrue(res.exists());
        try (InputStream in = res.getInputStream()) {
            List<ErrorCodeEntry> rows = ErrorCodeExcelReader.read(in);
            assertFalse(rows.isEmpty());
            boolean hasBiz = false;
            boolean hasSys = false;
            boolean hasUnknown = false;
            for (ErrorCodeEntry e : rows) {
                assertFalse(e.getFullCode() == null || e.getFullCode().isEmpty());
                if ("业务类1".equals(e.getCategory())) {
                    hasBiz = true;
                }
                if ("系统类2".equals(e.getCategory())) {
                    hasSys = true;
                }
                if ("结果未知类3".equals(e.getCategory())) {
                    hasUnknown = true;
                }
            }
            assertTrue(hasBiz && hasSys && hasUnknown);
        }
    }
}

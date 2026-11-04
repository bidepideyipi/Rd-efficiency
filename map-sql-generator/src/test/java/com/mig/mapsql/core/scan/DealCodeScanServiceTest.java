package com.mig.mapsql.core.scan;

import com.mig.mapsql.core.scan.model.DealCodeHit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DealCodeScanServiceTest {

    private final DealCodeScanService service = new DealCodeScanService();

    @Test
    void scansExampleDirectory() {
        Path example = resolveExampleDir();
        DealCodeScanService.ScanReport report = service.scan(example.toString());

        assertEquals(2, report.getFileCount());
        assertEquals(4, report.getHitCount());
        assertEquals(0, report.getFailedFiles());

        List<DealCodeHit> rows = report.getRows();
        assertTrue(contains(rows, "BEPS", "BEPSBusiCheckService", "A000000", "对账受理成功"));
        assertTrue(contains(rows, "BEPS", "BEPSBusiCheckService", "A00000", "对账完成"));
        assertTrue(contains(rows, "BEPS", "BEPSBusiCheckService", "FIBC009", "仍存在待补发数据"));
        assertTrue(contains(rows, "BEPS", "BEPSBusiCheckService", "A000000", "待补发数据已处理完成"));
    }

    @Test
    void csvEscapesAndHasHeader() {
        String csv = service.toCsv(java.util.Arrays.asList(
                new DealCodeHit("S", "M", "C", "msg,with\"quote", "S/M/x.fc")
        ));
        assertTrue(csv.startsWith("\uFEFF服务名,模块名,dealcode,dealmsg\n"));
        assertTrue(csv.contains("\"msg,with\"\"quote\""));
        assertTrue(csv.contains("\n总览\ndealcode,数量\n"));
        assertTrue(csv.contains("C,1\n"));
    }

    @Test
    void csvAppendsOverviewSortedAscending() {
        Path example = resolveExampleDir();
        DealCodeScanService.ScanReport report = service.scan(example.toString());
        String csv = service.toCsv(report.getRows());

        int overviewAt = csv.indexOf("\n总览\ndealcode,数量\n");
        assertTrue(overviewAt > 0);
        String overviewPart = csv.substring(overviewAt);
        assertTrue(overviewPart.contains("A00000,1\n"));
        assertTrue(overviewPart.contains("A000000,2\n"));
        assertTrue(overviewPart.contains("FIBC009,1\n"));
        assertTrue(overviewPart.indexOf("A00000,1") < overviewPart.indexOf("A000000,2"));
        assertTrue(overviewPart.indexOf("A000000,2") < overviewPart.indexOf("FIBC009,1"));
    }

    private static boolean contains(List<DealCodeHit> rows, String service, String module,
                                   String code, String msg) {
        for (DealCodeHit h : rows) {
            if (service.equals(h.getServiceName())
                    && module.equals(h.getModuleName())
                    && code.equals(h.getDealcode())
                    && msg.equals(h.getDealmsg())) {
                return true;
            }
        }
        return false;
    }

    private static Path resolveExampleDir() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path[] candidates = new Path[]{
                cwd.resolve("example"),
                cwd.resolve("../example"),
                cwd.getParent() != null ? cwd.getParent().resolve("example") : null
        };
        for (Path p : candidates) {
            if (p != null && java.nio.file.Files.isDirectory(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("找不到 example 目录，cwd=" + cwd);
    }
}

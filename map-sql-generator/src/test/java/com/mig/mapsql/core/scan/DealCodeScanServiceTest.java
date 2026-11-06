package com.mig.mapsql.core.scan;

import com.mig.mapsql.core.scan.model.DealCodeHit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DealCodeScanServiceTest {

    private final DealCodeScanService service = new DealCodeScanService();

    @Test
    void windowsDrivePathIsRejectedWhenServiceIsNotWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.startsWith("windows")) {
            return;
        }
        DealCodeScanService.DirStatus status = service.checkDir("D:\\GITLAB\\IPP");
        assertFalse(status.isOk());
        assertTrue(status.getError().contains("Windows"));
        assertTrue(status.getError().contains("D:\\GITLAB\\IPP"));
    }

    @Test
    void scansExampleDirectory() {
        Path example = resolveExampleDir();
        DealCodeScanService.ScanReport report = service.scan(example.toString());

        assertEquals(3, report.getFileCount());
        assertEquals(5, report.getHitCount());
        assertEquals(0, report.getFailedFiles());

        List<DealCodeHit> rows = report.getRows();
        assertTrue(contains(rows, "BEPS", "BEPSBusiCheckService", "A000000", "对账受理成功"));
        assertTrue(contains(rows, "BEPS", "BEPSBusiCheckService", "A00000", "对账完成"));
        assertTrue(contains(rows, "BEPS", "BEPSBusiCheckService", "FIBC009", "仍存在待补发数据"));
        assertTrue(contains(rows, "BEPS", "BEPSBusiCheckService", "A000000", "待补发数据已处理完成"));
        assertTrue(containsLocation(rows, "A000000", "对账受理成功", "CheckMainProcess.fc", 12));
        assertTrue(containsLocation(rows, "A00000", "对账完成", "CheckMainProcess.fc", 19));
        assertTrue(containsLocation(rows, "FIBC009", "仍存在待补发数据", "BusiReissueResultQuery.fc", 14));
        assertTrue(containsLocation(rows, "A000000", "待补发数据已处理完成", "BusiReissueResultQuery.fc", 21));
    }

    @Test
    void csvEscapesAndHasHeader() {
        String csv = service.toCsv(java.util.Arrays.asList(
                new DealCodeHit("S", "M", "C", "msg,with\"quote", "S/M/x.fc")
        ));
        assertTrue(csv.startsWith("\uFEFF服务名,模块名,dealcode,dealmsg,文件名,行号\n"));
        assertTrue(csv.contains("x.fc,0\n"));
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
        assertTrue(overviewPart.contains("A000000,3\n"));
        assertTrue(overviewPart.contains("FIBC009,1\n"));
        assertTrue(overviewPart.indexOf("A00000,1") < overviewPart.indexOf("A000000,3"));
        assertTrue(overviewPart.indexOf("A000000,3") < overviewPart.indexOf("FIBC009,1"));
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

    private static boolean containsLocation(List<DealCodeHit> rows, String code, String msg,
                                            String fileName, int line) {
        for (DealCodeHit h : rows) {
            if (code.equals(h.getDealcode())
                    && msg.equals(h.getDealmsg())
                    && fileName.equals(h.getFileName())
                    && line == h.getLineNumber()) {
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

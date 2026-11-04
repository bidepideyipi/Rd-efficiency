package com.mig.mapsql.web.scan;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.core.scan.DealCodeScanService;
import com.mig.mapsql.web.dto.ScanDirRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/scan/dealcode")
public class ScanController {

    private final DealCodeScanService scanService;
    private final GeneratorProperties properties;

    public ScanController(DealCodeScanService scanService, GeneratorProperties properties) {
        this.scanService = scanService;
        this.properties = properties;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        String defaultDir = properties.getScan().getDefaultProjectDir();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("defaultProjectDir", defaultDir == null ? "" : defaultDir);
        if (defaultDir != null && !defaultDir.trim().isEmpty()) {
            body.put("status", scanService.checkDir(defaultDir).toMap());
        }
        return body;
    }

    @PostMapping("/check-dir")
    public Map<String, Object> checkDir(@RequestBody ScanDirRequest req) {
        String path = req.getPath() != null ? req.getPath() : req.getProjectDir();
        return scanService.checkDir(path).toMap();
    }

    @PostMapping
    public Map<String, Object> scan(@RequestBody ScanDirRequest req) {
        String dir = resolveProjectDir(req);
        DealCodeScanService.ScanReport report = scanService.scan(dir);
        return scanService.toResponseBody(report);
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody ScanDirRequest req) {
        String dir = resolveProjectDir(req);
        DealCodeScanService.ScanReport report = scanService.scan(dir);
        byte[] bytes = scanService.toCsv(report.getRows()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"dealcode-scan.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes);
    }

    private static String resolveProjectDir(ScanDirRequest req) {
        if (req.getProjectDir() != null && !req.getProjectDir().trim().isEmpty()) {
            return req.getProjectDir().trim();
        }
        if (req.getPath() != null && !req.getPath().trim().isEmpty()) {
            return req.getPath().trim();
        }
        throw new IllegalArgumentException("请提供 projectDir");
    }
}

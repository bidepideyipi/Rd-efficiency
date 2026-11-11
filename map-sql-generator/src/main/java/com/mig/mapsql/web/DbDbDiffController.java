package com.mig.mapsql.web;

import com.mig.mapsql.catalog.DbDbDiffService;
import com.mig.mapsql.catalog.DbDbDiffService.DiffResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/db-db-diff")
public class DbDbDiffController {

    private final DbDbDiffService diffService;

    public DbDbDiffController(DbDbDiffService diffService) {
        this.diffService = diffService;
    }

    @PostMapping
    public Map<String, Object> compare(@RequestBody DiffRequest req) {
        DiffResult result = diffService.compare(
                req == null ? null : req.leftDatasourceId,
                req == null ? null : req.rightDatasourceId,
                req == null ? null : req.tables);
        Map<String, Object> m = result.toSummaryMap();
        m.put("sqlScript", result.sqlScript);
        return m;
    }

    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody DiffRequest req) throws Exception {
        DiffResult result = diffService.compare(
                req == null ? null : req.leftDatasourceId,
                req == null ? null : req.rightDatasourceId,
                req == null ? null : req.tables);
        byte[] body = result.sqlScript.getBytes(StandardCharsets.UTF_8);
        String filename = "db-db-diff-" + result.leftDatasourceId + "-vs-" + result.rightDatasourceId + ".sql";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
        Map<String, Object> summary = result.toSummaryMap();
        StringBuilder hdr = new StringBuilder();
        for (Map.Entry<String, Object> e : summary.entrySet()) {
            if (hdr.length() > 0) {
                hdr.append(';');
            }
            hdr.append(e.getKey()).append('=').append(e.getValue());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Diff-Summary", hdr.toString())
                .contentType(MediaType.parseMediaType("application/sql"))
                .body(body);
    }

    public static class DiffRequest {
        public String leftDatasourceId;
        public String rightDatasourceId;
        public List<String> tables;
    }
}

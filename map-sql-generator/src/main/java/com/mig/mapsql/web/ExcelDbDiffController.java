package com.mig.mapsql.web;

import com.mig.mapsql.catalog.ExcelDbDiffResult;
import com.mig.mapsql.catalog.ExcelDbDiffService;
import com.mig.mapsql.catalog.MigDataSourceRegistry;
import com.mig.mapsql.config.MigDataSourceProperties.DataSourceDef;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ExcelDbDiffController {

    private final MigDataSourceRegistry registry;
    private final ExcelDbDiffService diffService;

    public ExcelDbDiffController(MigDataSourceRegistry registry, ExcelDbDiffService diffService) {
        this.registry = registry;
        this.diffService = diffService;
    }

    @GetMapping("/datasources")
    public List<Map<String, Object>> listDatasources() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (DataSourceDef def : registry.listEnabled()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", def.getId());
            m.put("name", def.displayName());
            m.put("schema", def.getSchema());
            m.put("enabled", def.isEnabled());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/datasources/test")
    public Map<String, Object> test(@RequestParam("id") String id) {
        registry.testConnection(id);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("ok", true);
        return m;
    }

    @PostMapping("/excel-db-diff")
    public ResponseEntity<byte[]> diff(@RequestParam("datasourceId") String datasourceId,
                                       @RequestParam("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        ExcelDbDiffResult result = diffService.compareAndPatch(datasourceId, file.getInputStream());
        String filename = "excel-db-diff.xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");

        String summary = "compared=" + result.getSheetsCompared()
                + ";typeUpdated=" + result.getTypeUpdated()
                + ";excelOnly=" + result.getExcelOnlyMarked()
                + ";dbAppended=" + result.getDbOnlyAppended()
                + ";skippedNoTable=" + result.getSheetsSkippedNoTable();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Diff-Summary", summary)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(result.getWorkbookBytes());
    }
}

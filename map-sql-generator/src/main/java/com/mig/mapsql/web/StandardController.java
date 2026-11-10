package com.mig.mapsql.web;

import com.mig.mapsql.standard.StandardRule;
import com.mig.mapsql.standard.StandardRuleRepository;
import com.mig.mapsql.standard.StandardScanService;
import com.mig.mapsql.standard.StandardScanService.ColumnHit;
import com.mig.mapsql.standard.StandardScanService.ScanResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/standard")
public class StandardController {

    private final StandardRuleRepository ruleRepository;
    private final StandardScanService scanService;

    public StandardController(StandardRuleRepository ruleRepository, StandardScanService scanService) {
        this.ruleRepository = ruleRepository;
        this.scanService = scanService;
    }

    @GetMapping("/rules")
    public List<StandardRule> listRules() {
        return ruleRepository.listAll();
    }

    @PostMapping("/rules")
    public StandardRule create(@RequestBody StandardRule rule) {
        rule.setId(null);
        return ruleRepository.save(rule);
    }

    @PutMapping("/rules/{id}")
    public StandardRule update(@PathVariable("id") long id, @RequestBody StandardRule rule) {
        rule.setId(id);
        return ruleRepository.save(rule);
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id) {
        ruleRepository.delete(id);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("id", id);
        return m;
    }

    @PostMapping("/scan")
    public Map<String, Object> scan(@RequestBody ScanRequest req) {
        if (req == null || req.datasourceId == null || req.datasourceId.trim().isEmpty()) {
            throw new IllegalArgumentException("datasourceId 必填");
        }
        ScanResult result = scanService.scan(req.datasourceId, req.tables);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("datasourceId", result.datasourceId);
        m.put("owner", result.owner);
        m.put("tablesScanned", result.tablesScanned);
        m.put("hitCount", result.hitCount);
        List<Map<String, Object>> hits = new ArrayList<Map<String, Object>>();
        for (ColumnHit h : result.hits) {
            hits.add(h.toMap());
        }
        m.put("hits", hits);
        m.put("sqlScript", result.sqlScript);
        return m;
    }

    @PostMapping("/scan/export")
    public ResponseEntity<byte[]> export(@RequestBody ScanRequest req) throws Exception {
        if (req == null || req.datasourceId == null || req.datasourceId.trim().isEmpty()) {
            throw new IllegalArgumentException("datasourceId 必填");
        }
        ScanResult result = scanService.scan(req.datasourceId, req.tables);
        byte[] body = result.sqlScript.getBytes(StandardCharsets.UTF_8);
        String filename = "standard-alter-" + req.datasourceId + ".sql";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8.name()).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Hit-Count", String.valueOf(result.hitCount))
                .contentType(MediaType.parseMediaType("application/sql"))
                .body(body);
    }

    @GetMapping("/config")
    public Map<String, Object> config(@RequestParam(value = "path", required = false) String ignored) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ruleCount", ruleRepository.listAll().size());
        return m;
    }

    public static class ScanRequest {
        public String datasourceId;
        public List<String> tables;
    }
}

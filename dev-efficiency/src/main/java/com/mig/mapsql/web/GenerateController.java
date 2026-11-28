package com.mig.mapsql.web;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.core.service.GenerateService;
import com.mig.mapsql.core.service.TestGenerateService;
import com.mig.mapsql.web.dto.ExportDirRequest;
import com.mig.mapsql.web.dto.GenerateRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class GenerateController {

    private final GenerateService generateService;
    private final TestGenerateService testGenerateService;
    private final GeneratorProperties properties;

    public GenerateController(GenerateService generateService,
                              TestGenerateService testGenerateService,
                              GeneratorProperties properties) {
        this.generateService = generateService;
        this.testGenerateService = testGenerateService;
        this.properties = properties;
    }

    @GetMapping("/config/export-dir")
    public Map<String, Object> getExportDir() {
        GenerateService.ExportDirStatus status = generateService.checkExportDir(properties.getDefaultExportDir());
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("defaultExportDir", properties.getDefaultExportDir());
        body.put("status", status);
        return body;
    }

    @PostMapping("/config/export-dir")
    public GenerateService.ExportDirStatus checkExportDir(@RequestBody ExportDirRequest req) {
        return generateService.checkExportDir(req.getPath());
    }

    @PostMapping(value = "/excel/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "previousUploadId", required = false) String previousUploadId)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请上传 Excel 文件");
        }
        String rawName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String name = Paths.get(rawName).getFileName().toString();
        if (name.isEmpty() || !name.toLowerCase().endsWith(".xlsx")) {
            throw new IllegalArgumentException("仅支持 .xlsx");
        }
        Path dir = Paths.get(properties.getUploadTempDir()).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        // 同名覆盖：按原始文件名落盘，避免每次 createTempFile 残留旧副本
        Path target = dir.resolve(name).normalize();
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("非法文件名");
        }
        boolean replaced = Files.exists(target);
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        String uploadId = generateService.storeUpload(target, previousUploadId);
        List<String> sheets = generateService.listSheets(uploadId);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("uploadId", uploadId);
        body.put("fileName", name);
        body.put("sheets", sheets);
        body.put("sheetCount", sheets.size());
        body.put("overwritten", replaced);
        return body;
    }

    @GetMapping("/excel/{uploadId}/sheets")
    public Map<String, Object> sheets(@PathVariable String uploadId) throws IOException {
        List<String> sheets = generateService.listSheets(uploadId);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("uploadId", uploadId);
        body.put("sheets", sheets);
        return body;
    }

    @PostMapping("/generate")
    public GenerateService.GenerateReport generate(@RequestBody GenerateRequest req) throws IOException {
        if (req.getUploadId() == null || req.getUploadId().trim().isEmpty()) {
            throw new IllegalArgumentException("uploadId 不能为空");
        }
        return generateService.generate(
                req.getUploadId(),
                req.getTables(),
                req.getExportDir(),
                req.isOverwrite(),
                req.isStopOnError());
    }

    @PostMapping("/generate-test")
    public GenerateService.GenerateReport generateTest(@RequestBody GenerateRequest req) throws IOException {
        if (req.getUploadId() == null || req.getUploadId().trim().isEmpty()) {
            throw new IllegalArgumentException("uploadId 不能为空");
        }
        return testGenerateService.generate(
                req.getUploadId(),
                req.getTables(),
                req.getExportDir(),
                req.isOverwrite(),
                req.isStopOnError());
    }

    @GetMapping("/files")
    public List<Map<String, Object>> listFiles(@RequestParam(required = false) String exportDir) throws IOException {
        Path dir = generateService.resolveExportDir(exportDir);
        if (!Files.exists(dir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> {
                        Map<String, Object> m = new LinkedHashMap<String, Object>();
                        m.put("name", p.getFileName().toString());
                        m.put("path", p.toAbsolutePath().toString());
                        try {
                            m.put("size", Files.size(p));
                        } catch (IOException e) {
                            m.put("size", -1);
                        }
                        return m;
                    })
                    .collect(Collectors.toList());
        }
    }

    @GetMapping("/files/{tableName}")
    public ResponseEntity<Resource> download(@PathVariable String tableName,
                                             @RequestParam(required = false) String exportDir) throws IOException {
        Path dir = generateService.resolveExportDir(exportDir);
        Path file = dir.resolve(tableName + ".sql").normalize();
        if (!file.startsWith(dir) || !Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file.toFile());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + tableName + ".sql\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}

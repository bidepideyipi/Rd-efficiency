package com.mig.mapsql.core.scan;

import com.mig.mapsql.core.scan.model.DealCodeHit;
import com.mig.mapsql.core.scan.model.ParamPair;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class DealCodeScanService {

    public DirStatus checkDir(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return DirStatus.fail("", "路径为空");
        }
        Path path = Paths.get(rawPath.trim()).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            return DirStatus.fail(path.toString(), "目录不存在");
        }
        if (!Files.isDirectory(path)) {
            return DirStatus.fail(path.toString(), "不是目录");
        }
        if (!Files.isReadable(path)) {
            return DirStatus.fail(path.toString(), "目录不可读");
        }
        return DirStatus.ok(path.toString());
    }

    public ScanReport scan(String projectDir) {
        DirStatus status = checkDir(projectDir);
        if (!status.isOk()) {
            throw new IllegalArgumentException(status.getError() + ": " + status.getPath());
        }
        Path root = Paths.get(status.getPath());
        List<DealCodeHit> rows = new ArrayList<DealCodeHit>();
        List<String> warnings = new ArrayList<String>();
        int fileCount = 0;
        int failedFiles = 0;

        List<Path> files;
        try {
            files = FcFileWalker.listFcFiles(root);
        } catch (IOException e) {
            throw new IllegalArgumentException("遍历目录失败: " + e.getMessage());
        }

        for (Path file : files) {
            fileCount++;
            String rel = root.relativize(file).toString().replace('\\', '/');
            String[] parts = pathParts(rel);
            String service = parts.length > 0 ? parts[0] : "";
            String module = parts.length > 1 ? parts[1] : "";
            // file directly under root: parts[0] is filename — treat as no service/module
            if (parts.length == 1 && parts[0].toLowerCase().endsWith(".fc")) {
                service = "";
                module = "";
            } else if (parts.length == 2 && parts[1].toLowerCase().endsWith(".fc")) {
                // service/file.fc — only service, no module
                module = "";
            }

            try {
                String content = readText(file);
                List<ParamPair> pairs = ParamListExtractor.extract(content);
                for (ParamPair pair : pairs) {
                    if (pair.isIncomplete()) {
                        warnings.add("不完整 paramList: " + rel
                                + " dealcode=" + pair.getDealcode()
                                + " dealmsg=" + pair.getDealmsg());
                    }
                    rows.add(new DealCodeHit(service, module, pair.getDealcode(), pair.getDealmsg(), rel));
                }
            } catch (Exception e) {
                failedFiles++;
                warnings.add("读取失败: " + rel + " — " + e.getMessage());
            }
        }

        return new ScanReport(status.getPath(), fileCount, rows.size(), failedFiles, rows, warnings);
    }

    public String toCsv(List<DealCodeHit> rows) {
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM for Excel
        sb.append('\uFEFF');
        sb.append("服务名,模块名,dealcode,dealmsg\n");
        if (rows != null) {
            for (DealCodeHit hit : rows) {
                sb.append(csvField(hit.getServiceName())).append(',')
                        .append(csvField(hit.getModuleName())).append(',')
                        .append(csvField(hit.getDealcode())).append(',')
                        .append(csvField(hit.getDealmsg())).append('\n');
            }
        }
        // Overview section: dealcode counts, ascending
        sb.append('\n');
        sb.append("总览\n");
        sb.append("dealcode,数量\n");
        for (Map<String, Object> row : buildOverview(rows)) {
            sb.append(csvField(String.valueOf(row.get("dealcode")))).append(',')
                    .append(row.get("count")).append('\n');
        }
        return sb.toString();
    }

    public Map<String, Object> toResponseBody(ScanReport report) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("projectDir", report.getProjectDir());
        body.put("fileCount", report.getFileCount());
        body.put("hitCount", report.getHitCount());
        body.put("failedFiles", report.getFailedFiles());
        List<Map<String, String>> rowMaps = new ArrayList<Map<String, String>>();
        for (DealCodeHit hit : report.getRows()) {
            Map<String, String> m = new LinkedHashMap<String, String>();
            m.put("服务名", hit.getServiceName());
            m.put("模块名", hit.getModuleName());
            m.put("dealcode", hit.getDealcode());
            m.put("dealmsg", hit.getDealmsg());
            rowMaps.add(m);
        }
        body.put("rows", rowMaps);
        body.put("overview", buildOverview(report.getRows()));
        body.put("warnings", report.getWarnings());
        body.put("csvPreview", toCsv(report.getRows()));
        return body;
    }

    /**
     * Group by dealcode with occurrence count, sorted by dealcode ascending.
     */
    public List<Map<String, Object>> buildOverview(List<DealCodeHit> rows) {
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        if (rows != null) {
            for (DealCodeHit hit : rows) {
                String code = hit.getDealcode() == null ? "" : hit.getDealcode();
                Integer n = counts.get(code);
                counts.put(code, n == null ? 1 : n + 1);
            }
        }
        List<Map<String, Object>> overview = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("dealcode", e.getKey());
            row.put("count", e.getValue());
            overview.add(row);
        }
        // TreeMap already sorts by key ascending; keep explicit for clarity
        Collections.sort(overview, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                String ca = String.valueOf(a.get("dealcode"));
                String cb = String.valueOf(b.get("dealcode"));
                return ca.compareTo(cb);
            }
        });
        return overview;
    }

    private static String[] pathParts(String rel) {
        if (rel == null || rel.isEmpty()) {
            return new String[0];
        }
        String normalized = rel;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty()) {
            return new String[0];
        }
        return normalized.split("/");
    }

    private static String readText(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        // try UTF-8 first; fall back to platform default if needed is unnecessary for .fc samples
        Charset cs = StandardCharsets.UTF_8;
        return new String(bytes, cs);
    }

    static String csvField(String value) {
        if (value == null) {
            value = "";
        }
        boolean needQuote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public static final class DirStatus {
        private final boolean ok;
        private final String path;
        private final String error;

        private DirStatus(boolean ok, String path, String error) {
            this.ok = ok;
            this.path = path;
            this.error = error;
        }

        public static DirStatus ok(String path) {
            return new DirStatus(true, path, null);
        }

        public static DirStatus fail(String path, String error) {
            return new DirStatus(false, path, error);
        }

        public boolean isOk() {
            return ok;
        }

        public String getPath() {
            return path;
        }

        public String getError() {
            return error;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ok", ok);
            m.put("path", path);
            m.put("error", error);
            return m;
        }
    }

    public static final class ScanReport {
        private final String projectDir;
        private final int fileCount;
        private final int hitCount;
        private final int failedFiles;
        private final List<DealCodeHit> rows;
        private final List<String> warnings;

        public ScanReport(String projectDir, int fileCount, int hitCount, int failedFiles,
                          List<DealCodeHit> rows, List<String> warnings) {
            this.projectDir = projectDir;
            this.fileCount = fileCount;
            this.hitCount = hitCount;
            this.failedFiles = failedFiles;
            this.rows = rows;
            this.warnings = warnings;
        }

        public String getProjectDir() {
            return projectDir;
        }

        public int getFileCount() {
            return fileCount;
        }

        public int getHitCount() {
            return hitCount;
        }

        public int getFailedFiles() {
            return failedFiles;
        }

        public List<DealCodeHit> getRows() {
            return rows;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}

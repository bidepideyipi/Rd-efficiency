package com.mig.mapsql.core.service;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.core.excel.ExcelMappingReader;
import com.mig.mapsql.core.model.TableMapping;
import com.mig.mapsql.core.model.ValidationMessage;
import com.mig.mapsql.core.sql.SelectSqlRenderer;
import com.mig.mapsql.core.util.OracleIdentifier;
import com.mig.mapsql.core.util.Strings;
import com.mig.mapsql.core.validate.MappingValidator;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class GenerateService {

    private final GeneratorProperties properties;
    private final ExcelMappingReader excelReader;
    private final MappingValidator validator;
    private final SelectSqlRenderer renderer;
    private final ConcurrentHashMap<String, Path> uploads = new ConcurrentHashMap<String, Path>();

    public GenerateService(GeneratorProperties properties,
                           ExcelMappingReader excelReader,
                           MappingValidator validator,
                           SelectSqlRenderer renderer) {
        this.properties = properties;
        this.excelReader = excelReader;
        this.validator = validator;
        this.renderer = renderer;
    }

    public String storeUpload(Path tempFile) {
        return storeUpload(tempFile, null);
    }

    /**
     * 登记上传文件。同路径（同名覆盖）时复用/替换旧 uploadId；
     * 若传入 previousUploadId 且指向不同路径，则删除旧临时文件。
     */
    public String storeUpload(Path tempFile, String previousUploadId) {
        Path normalized = tempFile.toAbsolutePath().normalize();
        cleanupPreviousUpload(previousUploadId, normalized);

        // 同路径已有登记时，替换为新 id，避免内存里残留多条指向同一文件
        uploads.entrySet().removeIf(e -> e.getValue().toAbsolutePath().normalize().equals(normalized));

        String id = UUID.randomUUID().toString().replace("-", "");
        uploads.put(id, normalized);
        return id;
    }

    private void cleanupPreviousUpload(String previousUploadId, Path keep) {
        if (previousUploadId == null || previousUploadId.trim().isEmpty()) {
            return;
        }
        Path old = uploads.remove(previousUploadId.trim());
        if (old == null) {
            return;
        }
        Path oldNorm = old.toAbsolutePath().normalize();
        if (oldNorm.equals(keep)) {
            return;
        }
        try {
            Files.deleteIfExists(oldNorm);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    public Path getUpload(String uploadId) {
        Path p = uploads.get(uploadId);
        if (p == null || !Files.exists(p)) {
            throw new IllegalArgumentException("uploadId 无效或文件已失效: " + uploadId);
        }
        return p;
    }

    public List<String> listSheets(String uploadId) throws IOException {
        return excelReader.listBusinessSheets(getUpload(uploadId));
    }

    public Path resolveExportDir(String requested) throws IOException {
        String raw = Strings.isBlank(requested) ? properties.getDefaultExportDir() : requested.trim();
        Path exportDir = Paths.get(raw).toAbsolutePath().normalize();
        Files.createDirectories(exportDir);
        if (!Files.isWritable(exportDir)) {
            throw new IllegalArgumentException("目录不可写: " + exportDir);
        }
        return exportDir;
    }

    public ExportDirStatus checkExportDir(String requested) {
        try {
            Path dir = resolveExportDir(requested);
            return new ExportDirStatus(dir.toString(), true, true, null);
        } catch (Exception e) {
            return new ExportDirStatus(requested, false, false, e.getMessage());
        }
    }

    public GenerateReport generate(String uploadId, List<String> tables, String exportDir,
                                   boolean overwrite, boolean stopOnError) throws IOException {
        Path excel = getUpload(uploadId);
        Path outDir = resolveExportDir(exportDir);
        List<TableMapping> mappings = excelReader.readTables(excel, tables);

        GenerateReport report = new GenerateReport();
        report.setExportDir(outDir.toString());
        report.setStartedAt(Instant.now().toString());

        if (properties.isWriteMeta()) {
            Files.createDirectories(outDir.resolve("_meta"));
        }

        for (TableMapping table : mappings) {
            TableResult tr = new TableResult();
            tr.setTable(table.getSheetName());
            List<ValidationMessage> messages = validator.validate(table);
            tr.setMessages(messages.stream().map(ValidationMessage::toString).collect(Collectors.toList()));

            if (validator.hasError(messages)) {
                tr.setSuccess(false);
                tr.setError(summarizeErrors(messages));
                report.getTables().add(tr);
                report.setFailed(report.getFailed() + 1);
                if (stopOnError) {
                    break;
                }
                continue;
            }

            if (!OracleIdentifier.isSafeFileName(table.getSheetName())) {
                tr.setSuccess(false);
                tr.setError("非法表名/文件名");
                report.getTables().add(tr);
                report.setFailed(report.getFailed() + 1);
                if (stopOnError) {
                    break;
                }
                continue;
            }

            Path sqlFile = outDir.resolve(table.getSheetName() + ".sql");
            if (Files.exists(sqlFile) && !overwrite) {
                tr.setSuccess(false);
                tr.setError("文件已存在且未勾选覆盖: " + sqlFile);
                report.getTables().add(tr);
                report.setFailed(report.getFailed() + 1);
                if (stopOnError) {
                    break;
                }
                continue;
            }

            try {
                String sql = renderer.render(table);
                writeUtf8(sqlFile, sql);
                tr.setSuccess(true);
                tr.setOutputFile(sqlFile.toString());
                report.setSucceeded(report.getSucceeded() + 1);

                if (properties.isWriteMeta()) {
                    Path meta = outDir.resolve("_meta").resolve(table.getSheetName() + ".json");
                    writeUtf8(meta, toSimpleMeta(table, messages, sqlFile));
                }
            } catch (Exception e) {
                tr.setSuccess(false);
                tr.setError(e.getMessage());
                report.setFailed(report.getFailed() + 1);
                if (stopOnError) {
                    report.getTables().add(tr);
                    break;
                }
            }
            report.getTables().add(tr);
        }

        report.setFinishedAt(Instant.now().toString());
        if (properties.isWriteMeta()) {
            Path reportFile = outDir.resolve("_meta").resolve("generate-report.json");
            writeUtf8(reportFile, reportSummaryJson(report));
        }
        return report;
    }

    private static void writeUtf8(Path file, String content) throws IOException {
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /** 将 ERROR 汇总到 error 字段，便于 Web 直接看到 Excel 行列 */
    public static String summarizeErrors(List<ValidationMessage> messages) {
        StringBuilder sb = new StringBuilder("校验失败");
        int n = 0;
        for (ValidationMessage m : messages) {
            if (m.getLevel() != ValidationMessage.Level.ERROR) {
                continue;
            }
            n++;
            if (n > 5) {
                sb.append(" …共").append(countErrors(messages)).append("条ERROR");
                break;
            }
            sb.append("\n  - ").append(m.toString());
        }
        return sb.toString();
    }

    private static int countErrors(List<ValidationMessage> messages) {
        int c = 0;
        for (ValidationMessage m : messages) {
            if (m.getLevel() == ValidationMessage.Level.ERROR) {
                c++;
            }
        }
        return c;
    }

    private static String toSimpleMeta(TableMapping table, List<ValidationMessage> messages, Path sqlFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"sheet\": \"").append(esc(table.getSheetName())).append("\",\n");
        sb.append("  \"targetTable\": \"").append(esc(table.getTargetTable())).append("\",\n");
        sb.append("  \"sourceTable\": \"").append(esc(table.getSourceTable())).append("\",\n");
        sb.append("  \"sqlFile\": \"").append(esc(sqlFile.toString())).append("\",\n");
        sb.append("  \"fieldCount\": ").append(table.getFields().size()).append(",\n");
        sb.append("  \"joinPredicate\": \"").append(esc(table.getJoinPredicate())).append("\",\n");
        sb.append("  \"messages\": [\n");
        for (int i = 0; i < messages.size(); i++) {
            sb.append("    \"").append(esc(messages.get(i).toString())).append("\"");
            if (i < messages.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String reportSummaryJson(GenerateReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"exportDir\": \"").append(esc(report.getExportDir())).append("\",\n");
        sb.append("  \"succeeded\": ").append(report.getSucceeded()).append(",\n");
        sb.append("  \"failed\": ").append(report.getFailed()).append(",\n");
        sb.append("  \"startedAt\": \"").append(esc(report.getStartedAt())).append("\",\n");
        sb.append("  \"finishedAt\": \"").append(esc(report.getFinishedAt())).append("\"\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    public static class ExportDirStatus {
        private final String path;
        private final boolean exists;
        private final boolean writable;
        private final String error;

        public ExportDirStatus(String path, boolean exists, boolean writable, String error) {
            this.path = path;
            this.exists = exists;
            this.writable = writable;
            this.error = error;
        }

        public String getPath() {
            return path;
        }

        public boolean isExists() {
            return exists;
        }

        public boolean isWritable() {
            return writable;
        }

        public String getError() {
            return error;
        }
    }

    public static class GenerateReport {
        private String exportDir;
        private String startedAt;
        private String finishedAt;
        private int succeeded;
        private int failed;
        private List<TableResult> tables = new ArrayList<TableResult>();

        public String getExportDir() {
            return exportDir;
        }

        public void setExportDir(String exportDir) {
            this.exportDir = exportDir;
        }

        public String getStartedAt() {
            return startedAt;
        }

        public void setStartedAt(String startedAt) {
            this.startedAt = startedAt;
        }

        public String getFinishedAt() {
            return finishedAt;
        }

        public void setFinishedAt(String finishedAt) {
            this.finishedAt = finishedAt;
        }

        public int getSucceeded() {
            return succeeded;
        }

        public void setSucceeded(int succeeded) {
            this.succeeded = succeeded;
        }

        public int getFailed() {
            return failed;
        }

        public void setFailed(int failed) {
            this.failed = failed;
        }

        public List<TableResult> getTables() {
            return tables;
        }

        public void setTables(List<TableResult> tables) {
            this.tables = tables;
        }
    }

    public static class TableResult {
        private String table;
        private boolean success;
        private String outputFile;
        private String error;
        private List<String> messages = new ArrayList<String>();

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getOutputFile() {
            return outputFile;
        }

        public void setOutputFile(String outputFile) {
            this.outputFile = outputFile;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public List<String> getMessages() {
            return messages;
        }

        public void setMessages(List<String> messages) {
            this.messages = messages;
        }
    }
}

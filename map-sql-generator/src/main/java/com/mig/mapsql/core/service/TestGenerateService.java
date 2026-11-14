package com.mig.mapsql.core.service;

import com.mig.mapsql.config.GeneratorProperties;
import com.mig.mapsql.core.excel.ExcelMappingReader;
import com.mig.mapsql.core.model.TableMapping;
import com.mig.mapsql.core.model.ValidationMessage;
import com.mig.mapsql.core.testsql.TestSqlBundle;
import com.mig.mapsql.core.testsql.TestSqlRenderer;
import com.mig.mapsql.core.util.OracleIdentifier;
import com.mig.mapsql.core.validate.MappingValidator;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TestGenerateService {

    private final GeneratorProperties properties;
    private final GenerateService generateService;
    private final ExcelMappingReader excelReader;
    private final MappingValidator validator;
    private final TestSqlRenderer testSqlRenderer;

    public TestGenerateService(GeneratorProperties properties,
                               GenerateService generateService,
                               ExcelMappingReader excelReader,
                               MappingValidator validator,
                               TestSqlRenderer testSqlRenderer) {
        this.properties = properties;
        this.generateService = generateService;
        this.excelReader = excelReader;
        this.validator = validator;
        this.testSqlRenderer = testSqlRenderer;
    }

    public GenerateService.GenerateReport generate(String uploadId, List<String> tables, String exportDir,
                                                   boolean overwrite, boolean stopOnError) throws IOException {
        Path excel = generateService.getUpload(uploadId);
        Path baseDir = generateService.resolveExportDir(exportDir);
        Path outDir = baseDir.resolve("test");
        Files.createDirectories(outDir);

        List<TableMapping> mappings = excelReader.readTables(excel, tables);

        GenerateService.GenerateReport report = new GenerateService.GenerateReport();
        report.setExportDir(outDir.toString());
        report.setStartedAt(Instant.now().toString());

        for (TableMapping table : mappings) {
            GenerateService.TableResult tr = new GenerateService.TableResult();
            tr.setTable(table.getSheetName());
            List<ValidationMessage> messages = validator.validate(table);
            tr.setMessages(messages.stream().map(ValidationMessage::toString).collect(Collectors.toList()));

            if (validator.hasError(messages)) {
                tr.setSuccess(false);
                tr.setError(GenerateService.summarizeErrors(messages));
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

            Path sheetDir = outDir.resolve(table.getSheetName());
            if (Files.exists(sheetDir) && !overwrite) {
                // 任一关键文件已存在且不覆盖则跳过
                if (Files.exists(sheetDir.resolve("01_count_total_src.sql"))) {
                    tr.setSuccess(false);
                    tr.setError("测试目录已存在且未勾选覆盖: " + sheetDir);
                    report.getTables().add(tr);
                    report.setFailed(report.getFailed() + 1);
                    if (stopOnError) {
                        break;
                    }
                    continue;
                }
            }

            try {
                TestSqlBundle bundle = testSqlRenderer.render(table);
                Files.createDirectories(sheetDir);
                List<String> written = new ArrayList<String>();
                for (Map.Entry<String, String> e : bundle.getFiles().entrySet()) {
                    Path f = sheetDir.resolve(e.getKey());
                    Files.write(f, e.getValue().getBytes(StandardCharsets.UTF_8));
                    written.add(f.toString());
                }
                Path meta = sheetDir.resolve("_meta.json");
                Files.write(meta, toMetaJson(bundle).getBytes(StandardCharsets.UTF_8));
                written.add(meta.toString());

                tr.setSuccess(true);
                tr.setOutputFile(sheetDir.toString());
                if (!bundle.isPkResolved()) {
                    List<String> msgs = new ArrayList<String>(tr.getMessages());
                    msgs.add("WARN: " + bundle.getPkError() + "；已跳过 T2/T5");
                    tr.setMessages(msgs);
                }
                if (bundle.isAmountCheck()) {
                    List<String> msgs = new ArrayList<String>(tr.getMessages());
                    msgs.add("INFO: amountCheck=true（T1 含 AMT_SUM）");
                    tr.setMessages(msgs);
                }
                report.setSucceeded(report.getSucceeded() + 1);
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
            Path reportFile = outDir.resolve("generate-test-report.json");
            Files.write(reportFile, reportSummaryJson(report).getBytes(StandardCharsets.UTF_8));
        }
        return report;
    }

    private static String toMetaJson(TestSqlBundle b) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"sheet\": \"").append(esc(b.getSheet())).append("\",\n");
        sb.append("  \"targetTable\": \"").append(esc(b.getTargetTable())).append("\",\n");
        sb.append("  \"sourceTable\": \"").append(esc(b.getSourceTable())).append("\",\n");
        sb.append("  \"primaryKeys\": ").append(jsonStringArray(b.getPrimaryKeys())).append(",\n");
        sb.append("  \"hashColumns\": ").append(jsonStringArray(b.getHashColumns())).append(",\n");
        sb.append("  \"amountCheck\": ").append(b.isAmountCheck()).append(",\n");
        sb.append("  \"timeWindow\": ").append(b.isTimeWindow()).append(",\n");
        sb.append("  \"pkResolved\": ").append(b.isPkResolved()).append(",\n");
        sb.append("  \"hashMode\": \"file\",\n");
        sb.append("  \"dayBucket\": true,\n");
        sb.append("  \"files\": ").append(jsonStringArray(new ArrayList<String>(b.getFiles().keySet()))).append("\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String jsonStringArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append("\"").append(esc(items.get(i))).append("\"");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String reportSummaryJson(GenerateService.GenerateReport report) {
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
}

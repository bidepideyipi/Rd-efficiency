package com.mig.mapsql.core.excel;

import com.mig.mapsql.core.model.ExcelColumns;
import com.mig.mapsql.core.model.FieldMapping;
import com.mig.mapsql.core.model.MappingType;
import com.mig.mapsql.core.model.TableMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimePartitionRemarkTest {

    @Test
    void resolvesSourceColumnFromQuotedMark() {
        TableMapping table = new TableMapping();
        FieldMapping f = new FieldMapping();
        f.setMappingType(MappingType.DIRECT);
        f.setTargetColumn("WORKDATE");
        f.setSourceColumn("TRANTIME");
        f.setRemark("'{{时间分区}}'");
        table.getFields().add(f);

        assertEquals("TRANTIME", ExcelMappingReader.resolveTimeColumn(table));
        assertTrue(ExcelMappingReader.marksTimePartition(f.getRemark()));
        assertTrue(f.getRemark().contains(ExcelColumns.TIME_PARTITION_MARK));
    }

    @Test
    void prefersSourceOverTargetAndIgnoresUnmarked() {
        TableMapping table = new TableMapping();
        FieldMapping other = new FieldMapping();
        other.setSourceColumn("AMOUNT");
        other.setRemark("普通备注");
        table.getFields().add(other);

        FieldMapping marked = new FieldMapping();
        marked.setTargetColumn("CRTDATE");
        marked.setSourceColumn("a.TRANSDT");
        marked.setRemark("迁移 {{时间分区}} 字段");
        table.getFields().add(marked);

        assertEquals("TRANSDT", ExcelMappingReader.resolveTimeColumn(table));
    }

    @Test
    void emptyWhenNoMark() {
        TableMapping table = new TableMapping();
        FieldMapping f = new FieldMapping();
        f.setSourceColumn("TRANTIME");
        f.setRemark("");
        table.getFields().add(f);
        assertEquals("", ExcelMappingReader.resolveTimeColumn(table));
    }
}

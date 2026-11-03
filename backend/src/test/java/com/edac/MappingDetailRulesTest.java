package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MappingDetailRulesTest {
    private static Models.MappingField field(int position,String name,String type,String description) {
        return new Models.MappingField(new Models.ColumnDetail(position,name,type,20,20,10,0,false,true),"",description,null);
    }
    @Test void usesFieldNamesForMissingDescriptionsAndShortFrequencyHeader() throws Exception {
        var registry=mock(RegistryService.class);var db=mock(JdbcTemplate.class);
        when(registry.connection(1)).thenReturn(new Models.ConnectionProfile(1,"Test","H2","localhost",9092,"MEMORY","test","jdbc:h2:mem:test","sa","PUBLIC",true));
        when(registry.table(1,1)).thenReturn(new Models.TableEntry(1,1,"PUBLIC","FIELDS","MANUAL",""));
        when(db.queryForList(anyString(),eq(String.class),eq(1L))).thenReturn(List.of());
        when(registry.mappingFields(1,1)).thenReturn(List.of(
            field(1,"CUSTOMER_ID","VARCHAR",null),field(2,"ACCOUNT_NAME","VARCHAR",""),
            field(3,"BUSINESS_DATE","VARCHAR","   "),field(4,"SOURCE_SYSTEM","VARCHAR"," / & () "),
            field(5,"STATUS_CODE","VARCHAR","NULL"),field(6,"ORIGINAL_FIELD","VARCHAR","Existing description")));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(new MappingWorkbookService(registry,db).generate(1,List.of(1L))))) {
            assertThat(book.getSheetAt(0).getRow(2).getCell(3).getStringCellValue()).isEqualTo("Update Freq");
            var sheet=book.getSheet("FIELDS");
            String[] expected={"CUSTOMER ID","ACCOUNT NAME","BUSINESS DATE","SOURCE SYSTEM","STATUS CODE","Existing description"};
            for(int i=0;i<expected.length;i++)assertThat(sheet.getRow(10+i).getCell(7).getStringCellValue()).isEqualTo(expected[i]);
            assertThat(sheet.getRow(10).getCell(1).getStringCellValue()).isEqualTo("CUSTOMER_ID");
        }
    }
    @Test void generatesRecordTypePiiCleanDescriptionsAndHashFromFirstIncludedNumericField() throws Exception {
        var registry=mock(RegistryService.class);var db=mock(JdbcTemplate.class);
        when(registry.connection(1)).thenReturn(new Models.ConnectionProfile(1,"Test","H2","localhost",9092,"MEMORY","test","jdbc:h2:mem:test","sa","PUBLIC",true));
        when(registry.table(eq(1L),anyLong())).thenAnswer(i->new Models.TableEntry(i.getArgument(1),1,"PUBLIC","TABLE_"+i.getArgument(1),"MANUAL",""));
        var fields=List.of(field(1,"NAME","VARCHAR","Customer - name"),field(2,"VALUE_A","NUMBER","Amount / total"),field(3,"VALUE_B","NUMBER","Amount & total"),field(4,"NOTE","VARCHAR","Value (%): (net)."));
        when(registry.mappingFields(eq(1L),anyLong())).thenReturn(fields);
        when(db.queryForList(anyString(),eq(String.class),anyLong())).thenReturn(List.of());
        when(db.queryForList(anyString(),eq(String.class),eq(2L))).thenReturn(List.of("VALUE_A"));
        when(db.queryForList(anyString(),eq(String.class),eq(3L))).thenReturn(List.of("NAME","VALUE_A","VALUE_B","NOTE"));
        when(db.queryForList(anyString(),eq(String.class),eq(4L))).thenReturn(List.of("VALUE_A","VALUE_B"));
        byte[] bytes=new MappingWorkbookService(registry,db).generate(1,List.of(1L,2L,3L,4L));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet=book.getSheet("TABLE_1");var record=sheet.getRow(9);var f=new DataFormatter();
            String[] expected={"1","Record Type","A","1","","","M","Record Type","","D","","N","","",""};
            for(int c=0;c<expected.length;c++)assertThat(f.formatCellValue(record.getCell(c))).as("Record Type column %s",c).isEqualTo(expected[c]);
            assertThat(sheet.getRow(10).getCell(0).getNumericCellValue()).isEqualTo(2);
            assertThat(sheet.getRow(10).getCell(7).getStringCellValue()).isEqualTo("Customer name");
            assertThat(sheet.getRow(11).getCell(7).getStringCellValue()).isEqualTo("Amount total VALUE_A");
            assertThat(sheet.getRow(12).getCell(7).getStringCellValue()).isEqualTo("Amount total VALUE_B");
            assertThat(sheet.getRow(13).getCell(7).getStringCellValue()).isEqualTo("Value net");
            assertThat(sheet.getRow(18).getCell(7).getStringCellValue()).isEqualTo("Sum of VALUE_A");
            var excluded=book.getSheet("TABLE_2");assertThat(excluded.getRow(17).getCell(7).getStringCellValue()).isEqualTo("Sum of VALUE_B");
            assertThat(excluded.getRow(11).getCell(7).getStringCellValue()).isEqualTo("Amount total");
            var empty=book.getSheet("TABLE_3");assertThat(empty.getRow(9).getCell(1).getStringCellValue()).isEqualTo("Record Type");assertThat(empty.getRow(10).getCell(0).getStringCellValue()).isEqualTo("Trailer");
            assertThat(empty.getRow(14).getCell(7).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(book.getSheet("TABLE_4").getRow(16).getCell(7).getCellType()).isEqualTo(CellType.BLANK);
            for(int i=1;i<book.getNumberOfSheets();i++) {
                var table=book.getSheetAt(i);
                for(int r=9;!"Trailer".equals(f.formatCellValue(table.getRow(r).getCell(0)));r++)assertThat(table.getRow(r).getCell(11).getStringCellValue()).isEqualTo("N");
            }
        }
    }
}

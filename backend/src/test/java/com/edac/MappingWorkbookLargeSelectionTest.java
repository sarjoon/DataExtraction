package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.stream.LongStream;
import jakarta.validation.Validation;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MappingWorkbookLargeSelectionTest {
    @Test void exportsMoreThanTwoHundredTablesWithNavigation()throws Exception{
        var registry=mock(RegistryService.class);var db=mock(JdbcTemplate.class);
        when(registry.connection(1)).thenReturn(new Models.ConnectionProfile(1,"Large","H2","localhost",9092,"MEMORY","large","jdbc:h2:mem:large","sa","PUBLIC",true,true));
        var ids=LongStream.rangeClosed(1,201).boxed().toList();
        try(var factory=Validation.buildDefaultValidatorFactory()){
            assertThat(factory.getValidator().validate(new Models.WorkbookRequest(ids))).isEmpty();
        }
        when(registry.table(eq(1L),anyLong())).thenAnswer(i->new Models.TableEntry(i.getArgument(1),1,"PUBLIC","TABLE_"+i.getArgument(1),"MANUAL",""));
        when(registry.mappingFields(eq(1L),anyLong())).thenReturn(List.of(new Models.MappingField(new Models.ColumnDetail(1,"ID","INTEGER",4,4,10,0,false,true),"PK","Identifier",null)));
        when(db.queryForList(anyString(),eq(String.class),anyLong())).thenReturn(List.of());
        var bytes=new MappingWorkbookService(registry,db).generate(1,ids);
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
            assertThat(book.getNumberOfSheets()).isEqualTo(202);
            assertThat(book.getSheet("TABLE_201").getRow(0).getCell(0).getHyperlink().getAddress()).contains("FIle Spec");
            assertThat(book.getSheetAt(0).getRow(203).getCell(1).getStringCellValue()).isEqualTo("TABLE_201");
        }
    }
}

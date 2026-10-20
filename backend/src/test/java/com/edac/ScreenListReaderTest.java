package com.edac;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ScreenListReaderTest {
    private final TableListReader reader=new TableListReader();
    @Test void readsReadableFieldHeadersAndRejectsIncompleteRows() throws Exception {
        var csv=new MockMultipartFile("file","readable.csv","text/csv","FIELD_NAME,SCREEN_NAME\nportfolio_id,Portfolio Screen".getBytes(StandardCharsets.UTF_8));
        assertThat(reader.readReadableFields(csv)).containsExactly(new ReadableFieldService.FieldName("Portfolio Screen","portfolio_id"));
        var bad=new MockMultipartFile("file","bad.csv","text/csv","Screen Name,Field Name\nPortfolio Screen,".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->reader.readReadableFields(bad)).hasMessageContaining("Missing screen or field");
        try(var wb=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var sheet=wb.createSheet("Fields");var row=sheet.createRow(0);row.createCell(0).setCellValue("Screen Name");row.createCell(1).setCellValue("Field Name");
            row=sheet.createRow(1);row.createCell(0).setCellValue("Portfolio Screen");row.createCell(1).setCellValue("VALUE_D");wb.write(output);
            assertThat(reader.readReadableFields(new MockMultipartFile("file","fields.xlsx","application/octet-stream",output.toByteArray())))
                .containsExactly(new ReadableFieldService.FieldName("Portfolio Screen","VALUE_D"));
        }
    }
    @Test void readsMultipleInstrumentsFromCsvAndRejectsIncompleteRows() throws Exception {
        var csv=new MockMultipartFile("file","screens.csv","text/csv","\uFEFFScreen Name,Instrument\nPortfolio Screen,Bond\nPortfolio Screen,Equity".getBytes(StandardCharsets.UTF_8));
        assertThat(reader.readInstrumentScreens(csv,"Ignored fallback")).containsExactly(
            new TableListReader.ScreenRegistration("Bond","Portfolio Screen"),new TableListReader.ScreenRegistration("Equity","Portfolio Screen"));
        var invalid=new MockMultipartFile("file","bad.csv","text/csv","Instrument,Screen Name\nBond,Portfolio Screen\n,Account Screen".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->reader.readInstrumentScreens(invalid,"Bond")).hasMessageContaining("Missing instrument at row 3");
        var legacy=new MockMultipartFile("file","screens.txt","text/plain","Portfolio Screen".getBytes(StandardCharsets.UTF_8));
        assertThat(reader.readInstrumentScreens(legacy,"Bond")).containsExactly(new TableListReader.ScreenRegistration("Bond","Portfolio Screen"));
        assertThatThrownBy(()->reader.readInstrumentScreens(legacy,null)).hasMessageContaining("Instrument and Screen Name");
    }
    @Test void readsInstrumentsFromExcel() throws Exception {
        try(var wb=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var sheet=wb.createSheet("Screens");var header=sheet.createRow(0);
            header.createCell(0).setCellValue("Instrument");header.createCell(1).setCellValue("Screen Name");
            var row=sheet.createRow(1);row.createCell(0).setCellValue("Bond");row.createCell(1).setCellValue("Portfolio Screen");wb.write(output);
            assertThat(reader.readInstrumentScreens(new MockMultipartFile("file","screens.xlsx","application/octet-stream",output.toByteArray()),null))
                .containsExactly(new TableListReader.ScreenRegistration("Bond","Portfolio Screen"));
        }
    }
    @Test void readsTextAndCsvWithoutChangingCaseOrSpaces() throws Exception {
        var text=new MockMultipartFile("file","screens.txt","text/plain","Portfolio Screen\nAccount Screen".getBytes(StandardCharsets.UTF_8));
        assertThat(reader.readScreens(text)).containsExactly("Portfolio Screen","Account Screen");
        var csv=new MockMultipartFile("file","screens.csv","text/csv","\uFEFFScreen Name,Notes\nPortfolio Screen,ignore\nAccount Screen,ignore".getBytes(StandardCharsets.UTF_8));
        assertThat(reader.readScreens(csv)).containsExactly("Portfolio Screen","Account Screen");
    }
    @Test void readsScreenNameHeaderFromFirstExcelSheet() throws Exception {
        try(var wb=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var sheet=wb.createSheet("Screens");sheet.createRow(0).createCell(1).setCellValue("SCREEN_NAME");
            sheet.createRow(1).createCell(1).setCellValue("Portfolio Screen");
            wb.createSheet("FIle Spec").createRow(0).createCell(0).setCellValue("Ignore this sheet");
            wb.write(output);
            assertThat(reader.readScreens(new MockMultipartFile("file","screens.xlsx","application/octet-stream",output.toByteArray())))
                .containsExactly("Portfolio Screen");
        }
    }
}

package com.edac;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ScreenListReaderTest {
    private final TableListReader reader=new TableListReader();
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

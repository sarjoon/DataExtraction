package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import java.io.*;
import java.sql.DriverManager;
import java.util.*;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:bau_mapping_test;DB_CLOSE_DELAY=-1","spring.datasource.password="})
@AutoConfigureMockMvc @ActiveProfiles("local")
class BauMappingWorkbookTest {
    @Autowired RegistryService registry;
    @Autowired JdbcTemplate db;
    @Autowired BauMappingWorkbookService service;
    @Autowired MappingWorkbookService mapping;
    @Autowired MockMvc mvc;
    private Models.ConnectionProfile profile() {
        String name="bau_"+UUID.randomUUID().toString().replace("-","");
        return registry.save(new Models.ConnectionInput(name,"H2",null,null,"MEMORY",name,"sa","PUBLIC",""));
    }
    private static MockMultipartFile upload(byte[] bytes){return new MockMultipartFile("bauFile","bau.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes);}
    private static byte[] workbook(boolean valid) throws Exception {
        try(var book=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var spec=book.createSheet("File Spec");var header=spec.createRow(2);
            header.createCell(0).setCellValue("sno");header.createCell(1).setCellValue("File Name");
            header.createCell(2).setCellValue("Brief Description of file");header.createCell(3).setCellValue("File Format");header.createCell(4).setCellValue("Legacy owner");
            header.createCell(5).setCellValue("Update Freq");
            var row=spec.createRow(3);row.createCell(0).setCellValue(1);var filename=row.createCell(1);filename.setCellValue("old_table");
            var link=book.getCreationHelper().createHyperlink(HyperlinkType.DOCUMENT);link.setAddress("'Legacy Layout'!A1");filename.setHyperlink(link);
            row.createCell(2).setCellValue("Original BAU description");row.createCell(3).setCellValue("F");row.createCell(4).setCellValue("Operations");
            row.createCell(5).setCellValue("Weekly");
            row=spec.createRow(4);row.createCell(0).setCellValue(2);row.createCell(1).setCellValue("BAU_ONLY");
            if(valid) {
                var legacy=book.createSheet("Legacy Layout");legacy.createRow(0).createCell(0).setCellValue("Keep this title unchanged");
                var data=legacy.createRow(7);data.createCell(0).setCellValue("Old column order");data.createCell(1).setCellValue(12);data.createCell(2).setCellFormula("B8*2");
                var style=book.createCellStyle();style.setFillForegroundColor(IndexedColors.YELLOW.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);data.getCell(0).setCellStyle(style);
                legacy.addMergedRegion(new CellRangeAddress(0,0,0,3));legacy.setColumnWidth(0,36*256);legacy.createFreezePane(1,7);legacy.setRowBreak(15);
                legacy.getHeader().setCenter("BAU header");book.setPrintArea(book.getSheetIndex(legacy),0,3,0,20);
                var drawing=legacy.createDrawingPatriarch();var anchor=book.getCreationHelper().createClientAnchor();anchor.setCol1(0);anchor.setRow1(7);anchor.setCol2(2);anchor.setRow2(10);
                var comment=drawing.createCellComment(anchor);comment.setString(book.getCreationHelper().createRichTextString("Existing BAU note"));data.getCell(0).setCellComment(comment);
                int picture=book.addPicture(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="),Workbook.PICTURE_TYPE_PNG);drawing.createPicture(anchor,picture);
                var helper=legacy.getDataValidationHelper();legacy.addValidationData(helper.createValidation(helper.createExplicitListConstraint(new String[]{"A","B"}),new CellRangeAddressList(8,10,0,0)));
                var only=book.createSheet("BAU_ONLY");only.createRow(0).createCell(0).setCellFormula("'Legacy Layout'!B8");
                book.createSheet("Helper").createRow(0).createCell(0).setCellValue("Keep supporting worksheets");
                book.createSheet("Generation Report").createRow(0).createCell(0).setCellValue("Original BAU report");
                book.createSheet("CHange History");book.createSheet("System Specification");
                book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            }
            book.write(output);return output.toByteArray();
        }
    }
    @Test void preservesBauSheetsOrdersEntriesAndDeletesOnlyMatchingConnectionRegistrations() throws Exception {
        var profile=profile();var other=profile();
        registry.append(profile.id(),List.of("OLD_TABLE","BAU_ONLY","NEW_TABLE"),"MANUAL");registry.append(other.id(),List.of("OLD_TABLE"),"MANUAL");
        var tables=registry.tables(profile.id());long old=tables.stream().filter(t->t.name().equals("OLD_TABLE")).findFirst().orElseThrow().id();
        long fresh=tables.stream().filter(t->t.name().equals("NEW_TABLE")).findFirst().orElseThrow().id();
        db.update("INSERT INTO column_exclusion(table_id,column_name) VALUES(?,?)",old,"OLD_COLUMN");
        byte[] input=workbook(true);
        try(var source=DriverManager.getConnection(profile.jdbcUrl(),"sa","");var sql=source.createStatement()) {
            sql.execute("CREATE TABLE NEW_TABLE(ID INTEGER PRIMARY KEY,NEW_COLUMN VARCHAR(50))");
            byte[] bytes=service.generate(profile.id(),List.of(old,fresh),upload(input));
            try(var original=new XSSFWorkbook(new ByteArrayInputStream(input));var result=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                assertThat(result.getSheetName(0)).isEqualTo("File Spec");assertThat(result.getSheetName(1)).isEqualTo("Legacy Layout");assertThat(result.getSheetName(2)).isEqualTo("BAU_ONLY");assertThat(result.getSheetName(3)).isEqualTo("NEW_TABLE");
                assertThat(result.getSheet("CHange History")).isNull();assertThat(result.getSheet("System Specification")).isNull();assertThat(result.getSheet("Helper")).isNotNull();
                var spec=result.getSheetAt(0);assertThat(spec.getRow(2).getCell(36).getStringCellValue()).isEqualTo("Comment");
                assertThat(spec.getRow(3).getCell(1).getStringCellValue()).isEqualTo("old_table");assertThat(spec.getRow(3).getCell(1).getHyperlink().getAddress()).isEqualTo("'Legacy Layout'!A1");
                assertThat(spec.getRow(3).getCell(2).getStringCellValue()).isEqualTo("Original BAU description");assertThat(spec.getRow(3).getCell(6).getStringCellValue()).isEqualTo("F");
                assertThat(spec.getRow(2).getCell(3).getStringCellValue()).isEqualTo("Update Freq");assertThat(spec.getRow(3).getCell(3).getStringCellValue()).isEqualTo("Weekly");
                assertThat(spec.getRow(3).getCell(37).getStringCellValue()).isEqualTo("Operations");
                assertThat(spec.getRow(3).getCell(36).getStringCellValue()).isEqualTo("BAU");assertThat(spec.getRow(4).getCell(36).getStringCellValue()).isEqualTo("BAU");assertThat(spec.getRow(5).getCell(36).getStringCellValue()).isEqualTo("New");
                assertThat(spec.getRow(5).getCell(1).getStringCellValue()).isEqualTo("NEW_TABLE");assertThat(spec.getRow(5).getCell(0).getNumericCellValue()).isEqualTo(3);
                assertThat(spec.getDataValidations().get(0).getRegions().getCellRangeAddresses()[0].formatAsString()).isEqualTo("O6:AJ6");
                assertThat(spec.getRow(3).getCell(36).getCellStyle().getFillForegroundColor()).isEqualTo(IndexedColors.PALE_BLUE.getIndex());assertThat(spec.getRow(5).getCell(36).getCellStyle().getFillForegroundColor()).isEqualTo(IndexedColors.LIGHT_GREEN.getIndex());
                var legacy=result.getSheet("Legacy Layout");var before=original.getSheet("Legacy Layout");
                assertThat(legacy.getCTWorksheet().getSheetData().xmlText()).isEqualTo(before.getCTWorksheet().getSheetData().xmlText());
                assertThat(legacy.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Keep this title unchanged");assertThat(legacy.getRow(7).getCell(2).getCellFormula()).isEqualTo("B8*2");
                assertThat(legacy.getRow(7).getCell(2).getNumericCellValue()).isEqualTo(24);assertThat(legacy.getColumnWidth(0)).isEqualTo(36*256);
                assertThat(legacy.getRow(7).getCell(0).getCellComment().getString().getString()).isEqualTo("Existing BAU note");assertThat(result.getAllPictures()).hasSize(1);
                assertThat(result.getAllPictures().get(0).getData()).isEqualTo(original.getAllPictures().get(0).getData());
                assertThat(legacy.getDataValidations()).hasSize(1);assertThat(legacy.getMergedRegion(0).formatAsString()).isEqualTo("A1:D1");assertThat(legacy.getRowBreaks()).contains(15);
                assertThat(legacy.getHeader().getCenter()).isEqualTo("BAU header");assertThat(result.getPrintArea(1)).isEqualTo(original.getPrintArea(1));
                assertThat(result.getSheet("BAU_ONLY").getRow(0).getCell(0).getCellFormula()).isEqualTo("'Legacy Layout'!B8");
                assertThat(legacy.getTabColor().getRGB()).containsExactly(0x00,0x70,(byte)0xC0);assertThat(result.getSheet("NEW_TABLE").getTabColor().getRGB()).containsExactly(0x00,(byte)0x80,0x00);
                assertThat(result.getSheet("NEW_TABLE").getRow(0).getCell(0).getHyperlink().getAddress()).isEqualTo("'File Spec'!A1");
            }
        }
        assertThat(registry.tables(profile.id())).extracting(Models.TableEntry::name).containsExactly("NEW_TABLE");
        assertThat(registry.tables(other.id())).extracting(Models.TableEntry::name).containsExactly("OLD_TABLE");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM column_exclusion WHERE table_id=?",Integer.class,old)).isZero();
    }
    @Test void invalidUploadsDoNotDeleteAndBauOnlyMultipartWorksWithoutTableSelection() throws Exception {
        var profile=profile();registry.append(profile.id(),List.of("OLD_TABLE"),"MANUAL");
        mvc.perform(multipart("/api/connections/"+profile.id()+"/mapping-workbook").file(upload(workbook(false))).with(csrf())).andExpect(status().isBadRequest());
        assertThat(registry.tables(profile.id())).hasSize(1);
        var response=mvc.perform(multipart("/api/connections/"+profile.id()+"/mapping-workbook").file(upload(workbook(true))).with(csrf())).andExpect(status().isOk()).andReturn().getResponse();
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getRow(4).getCell(36).getStringCellValue()).isEqualTo("BAU");
            assertThat(book.getSheet("Legacy Layout")).isNotNull();
        }
        assertThat(registry.tables(profile.id())).isEmpty();
    }
    @Test void generationFailureAndWrongConnectionSelectionNeverDeleteDuplicates() throws Exception {
        var profile=profile();registry.append(profile.id(),List.of("OLD_TABLE","BAD_SOURCE"),"MANUAL");var other=profile();registry.append(other.id(),List.of("FOREIGN_TABLE"),"MANUAL");
        long foreign=registry.tables(other.id()).get(0).id();
        assertThatThrownBy(()->service.generate(profile.id(),List.of(foreign),upload(workbook(true)))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(registry.tables(profile.id())).hasSize(2);
        // A malformed XLSX is rejected before a registry write.
        assertThatThrownBy(()->service.generate(profile.id(),List.of(),upload(new byte[]{1,2,3}))).isInstanceOf(Exception.class);
        assertThat(registry.tables(profile.id())).hasSize(2);
    }
    @Test void deletionFailureRollsBackAllDuplicateRemovals() throws Exception {
        var profile=profile();registry.append(profile.id(),List.of("OLD_TABLE","BAU_ONLY"),"MANUAL");
        long old=registry.tables(profile.id()).stream().filter(t->t.name().equals("OLD_TABLE")).findFirst().orElseThrow().id();
        db.execute("CREATE TABLE IF NOT EXISTS bau_delete_guard(table_id BIGINT REFERENCES table_registry(id))");
        db.update("INSERT INTO bau_delete_guard(table_id) VALUES(?)",old);
        try {
            assertThatThrownBy(()->service.generate(profile.id(),List.of(),upload(workbook(true)))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(registry.tables(profile.id())).extracting(Models.TableEntry::name).containsExactly("BAU_ONLY","OLD_TABLE");
        } finally {db.update("DELETE FROM bau_delete_guard WHERE table_id=?",old);}
    }
    @Test void reservedBauSheetNamesDoNotRenameOrOverwriteLegacySheets() throws Exception {
        var profile=profile();registry.append(profile.id(),List.of("Helper","MISSING_TABLE"),"MANUAL");
        try(var source=DriverManager.getConnection(profile.jdbcUrl(),"sa","");var sql=source.createStatement()) {
            sql.execute("CREATE TABLE HELPER(ID INTEGER)");
            byte[] bytes=service.generate(profile.id(),registry.tables(profile.id()).stream().map(Models.TableEntry::id).toList(),upload(workbook(true)));
            try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                assertThat(book.getSheet("Helper").getRow(0).getCell(0).getStringCellValue()).isEqualTo("Keep supporting worksheets");
                assertThat(book.getSheet("HELPER_2")).isNotNull();assertThat(book.getSheet("Generation Report_2")).isNotNull();
                assertThat(book.getSheet("Generation Report").getRow(0).getCell(0).getStringCellValue()).isEqualTo("Original BAU report");
            }
        }
    }
}

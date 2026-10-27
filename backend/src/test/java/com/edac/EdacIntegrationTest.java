package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.List;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:edac_test;DB_CLOSE_DELAY=-1","spring.datasource.password="})
@AutoConfigureMockMvc @ActiveProfiles("local")
class EdacIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired TestRestTemplate http;
    @Autowired CredentialCipher cipher;
    @Autowired MappingWorkbookService workbooks;
    @Autowired RegistryService registry;
    @MockitoBean OracleSource oracle;
    @MockitoBean TriggerAnalyzer triggers;
    @MockitoBean ScreenFieldSource screenFieldsSource;
    @Autowired TriggerService triggerService;
    @Test void configuresReadableFieldsFromScreenResultsAndPersistsDeselection() throws Exception {
        long id=connection(),other=connection();triggerService.append(id,"Bond",List.of("Screen One"),"MANUAL");
        long screenId=triggerService.screens(id).get(0).id();String base="/api/connections/"+id+"/screens";
        when(screenFieldsSource.discover(argThat(p->p.id()==id),eq("test-secret"),eq("Screen One"))).thenReturn(List.of(
            new ReadableFieldService.FieldName("Screen One","PORTFOLIO_ID"),new ReadableFieldService.FieldName("Screen One","VALUE_D"),new ReadableFieldService.FieldName("Screen One","UNKNOWN_FIELD")));
        String saved="{\"fields\":[{\"screenName\":\"Screen One\",\"fieldName\":\"PORTFOLIO_ID\"},{\"screenName\":\"Screen One\",\"fieldName\":\"VALUE_D\"},{\"screenName\":\"Other Screen\",\"fieldName\":\"KEEP_ME\"}]}";
        mvc.perform(post(base+"/readable-fields").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(saved)).andExpect(status().isOk());
        String path=base+"/"+screenId+"/readable-fields";
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].configured").value(true)).andExpect(jsonPath("$[2].selectable").value(true)).andExpect(jsonPath("$[0].screenName").value("Screen One"));
        String selected="{\"fields\":[{\"screenName\":\"Screen One\",\"fieldName\":\"PORTFOLIO_ID\"}]}";
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(selected)).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(jsonPath("$[0].configured").value(true)).andExpect(jsonPath("$[1].configured").value(false));
        mvc.perform(get(base+"/readable-fields")).andExpect(jsonPath("$.length()").value(2));
        triggerService.append(id,"Equity",List.of("Screen One"),"MANUAL");
        long sameScreen=triggerService.screens(id).stream().filter(s->s.instrument().equals("Equity")).findFirst().orElseThrow().id();
        mvc.perform(get(base+"/"+sameScreen+"/readable-fields")).andExpect(jsonPath("$[0].configured").value(true));
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[{\"screenName\":\"Other Screen\",\"fieldName\":\"NOT_FROM_SCREEN\"}]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(base+"/readable-fields")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/connections/"+other+"/screens/"+screenId+"/readable-fields")).andExpect(status().isNotFound());
        mvc.perform(post("/api/connections/"+other+"/screens/"+screenId+"/readable-fields").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(selected)).andExpect(status().isNotFound());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(selected)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[]}")).andExpect(status().isOk());
        mvc.perform(get(base+"/readable-fields")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].fieldName").value("KEEP_ME"));
    }
    @Test void managesReadableFieldsWithAtomicUploadsAndConnectionIsolation() throws Exception {
        long id=connection(),other=connection();String path="/api/connections/"+id+"/screens/readable-fields";
        var upload=new MockMultipartFile("file","readable.csv","text/csv","Screen Name,Field Name\nPortfolio Screen,portfolio_id\nPortfolio Screen,PORTFOLIO_ID\nAccount Screen,OP_CURRENCY_ID".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(path+"/import").file(upload).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.added").value(2)).andExpect(jsonPath("$.skipped").value(1));
        var response=mvc.perform(get(path)).andExpect(jsonPath("$.length()").value(2)).andReturn();
        long fieldId=json.readTree(response.getResponse().getContentAsString()).get(0).get("id").asLong();
        mvc.perform(get("/api/connections/"+other+"/screens/readable-fields")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/connections/"+other+"/screens/readable-fields/"+fieldId+"/remove").with(csrf())).andExpect(status().isNotFound());
        var invalid=new MockMultipartFile("file","bad.csv","text/csv","Screen Name,Field Name\nNew Screen,NEW_FIELD\nOther Screen,BAD-FIELD".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(path+"/import").file(invalid).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get(path)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[{\"screenName\":\"Portfolio Screen\",\"fieldName\":\"VALUE_D\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.added").value(1));
        mvc.perform(post(path+"/"+fieldId+"/remove").with(csrf())).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[]}")).andExpect(status().isForbidden());
    }
    @Test void uploadsInstrumentScreenPairsWithoutAnInstrumentParameter() throws Exception {
        long id=connection();String path="/api/connections/"+id+"/screens";
        var upload=new MockMultipartFile("file","screens.csv","text/csv","Instrument,Screen Name\nBond,Portfolio Screen\nBond,Account Screen\nEquity,Portfolio Screen\nBond,Portfolio Screen".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(path+"/import").file(upload).with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.added").value(3)).andExpect(jsonPath("$.skipped").value(1));
        assertThat(triggerService.screens(id,"Bond")).hasSize(2);assertThat(triggerService.screens(id,"Equity")).hasSize(1);
        var invalid=new MockMultipartFile("file","bad.csv","text/csv","Instrument,Screen Name\nFund,New Screen\n,Missing Instrument".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(path+"/import").file(invalid).with(csrf())).andExpect(status().isBadRequest());
        assertThat(triggerService.screens(id,"Fund")).isEmpty();
        assertThatThrownBy(()->triggerService.register(id,List.of(new TableListReader.ScreenRegistration("Fund","New Screen"),new TableListReader.ScreenRegistration("Fund"," ")),"UPLOAD"))
            .hasMessageContaining("Screen names");
        assertThat(triggerService.screens(id,"Fund")).isEmpty();
    }
    @Test void registersScreensAndExportsTriggersWithConnectionIsolation() throws Exception {
        long id=connection(),other=connection();String path="/api/connections/"+id+"/screens";
        var upload=new MockMultipartFile("file","screens.csv","text/csv","Screen Name\nPortfolio Screen\nPortfolio Screen\nPortfolio/Screen\nPortfolio:Screen\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart(path+"/import").file(upload).param("instrument","Bond").with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.added").value(3)).andExpect(jsonPath("$.skipped").value(1));
        var rows=triggerService.screens(id);assertThat(rows).hasSize(3);
        assertThat(registry.tables(id)).isEmpty();assertThat(triggerService.screens(other)).isEmpty();
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new TriggerController.ScreenNames("Bond",List.of("Valid"," "))))).andExpect(status().isBadRequest());
        assertThat(triggerService.screens(id)).hasSize(3);
        when(triggers.analyze(argThat(p->p.id()==id),eq("test-secret"),anyString())).thenReturn(List.of(
            TriggerAnalyzer.describe("portfolio_id","IF(source_id <> NULL, source.code, source_id)"),
            TriggerAnalyzer.describe("nature_e","ENUM_LIST(0,1,4,6)"),
            new TriggerAnalyzer.TriggerField("NEWLINES","one\\ntwo\r\nthree")));
        var body=json.writeValueAsString(new TriggerController.ScreenSelection(rows.stream().map(TriggerService.ScreenEntry::id).toList()));
        var response=mvc.perform(post(path+"/workbook").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=Complete_Call_Trigger.xlsx")).andReturn();
        try(var wb=new XSSFWorkbook(new java.io.ByteArrayInputStream(response.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheet("Portfolio Screen")).isNotNull();
            assertThat(java.util.stream.IntStream.range(0,3).mapToObj(wb::getSheetName).distinct().count()).isEqualTo(3);
            for(var sheet:wb) {
                assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("TAP Field Name");
                assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Complete Call Trigger");
                assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("portfolio_id");
                assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("source_id\nsource.code");
                assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Drop Down Nature");
                assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).isEqualTo("Complete Rule");
                assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("IF(source_id <> NULL, source.code, source_id)");
                assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("ENUM_LIST(0,1,4,6)");
                assertThat(sheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("one\ntwo\nthree");
                assertThat(sheet.getRow(3).getCell(1).getCellStyle().getWrapText()).isTrue();
                assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short)5);
                assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("Screen Name");
                assertThat(rows).extracting(TriggerService.ScreenEntry::name).contains(sheet.getRow(1).getCell(4).getStringCellValue());
                assertThat(wb.getAllPictures()).isEmpty();
            }
        }
        mvc.perform(post(path+"/workbook").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/connections/"+other+"/screens/workbook").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post(path+"/workbook").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"screenIds\":[]}")).andExpect(status().isBadRequest());
        assertThatThrownBy(()->triggerService.generate(id,List.of(rows.get(0).id(),rows.get(0).id()))).hasMessageContaining("distinct screens");
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new TriggerController.ScreenNames("Equity",List.of("Portfolio Screen")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.added").value(1));
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new TriggerController.ScreenNames("Equity",List.of("Portfolio Screen")))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.skipped").value(1));
        mvc.perform(get(path).param("instrument","Bond")).andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[0].instrument").value("Bond"));
        mvc.perform(get(path).param("instrument","Equity")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/connections/"+other+"/screens").param("instrument","Bond")).andExpect(jsonPath("$.length()").value(0));
        long equity=triggerService.screens(id,"Equity").get(0).id();
        assertThatThrownBy(()->triggerService.generate(id,List.of(rows.get(0).id(),equity))).hasMessageContaining("one instrument");
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"names\":[\"Missing Instrument\"]}")).andExpect(status().isBadRequest());
    }
    Models.ConnectionInput input(String name, String password) {
        return new Models.ConnectionInput(name,"ORACLE","oracle.example.com",1521,"SERVICE_NAME","ORCLPDB1","app_user","APP",password);
    }
    long connection() throws Exception {
        var request=input("Test "+UUID.randomUUID(),"test-secret");
        var response=mvc.perform(post("/api/connections").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
            .andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    }
    @Test void usesH2ConfigurationAndSavedCredentialsForOracle() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isOk()).andExpect(jsonPath("$.migrationVersion").value("17"));
        long id=connection();
        when(oracle.test(any(),eq("test-secret"))).thenReturn(new Models.TestResult(true,"Oracle test",List.of()));
        when(oracle.discover(any(),eq("test-secret"))).thenReturn(List.of(new Models.TableName("APP","CUSTOMER"),new Models.TableName("APP","ACCOUNT")));
        mvc.perform(post("/api/connections/"+id+"/test").with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(true));
        mvc.perform(post("/api/connections/"+id+"/tables/discover").with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.added").value(2));
        verify(oracle).test(argThat(p->p.id()==id && p.databaseType().equals("ORACLE")),eq("test-secret"));
        verify(oracle).discover(argThat(p->p.id()==id && p.schema().equals("APP")),eq("test-secret"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM table_registry WHERE connection_id=?",Integer.class,id)).isEqualTo(2);
        String stored=db.queryForObject("SELECT password_ciphertext FROM connection_profile WHERE id=?",String.class,id);
        assertThat(stored).startsWith("v1:").doesNotContain("test-secret");
        assertThat(cipher.decrypt(stored)).isEqualTo("test-secret");
        var listing=mvc.perform(get("/api/connections")).andReturn().getResponse().getContentAsString();
        assertThat(listing).doesNotContain("test-secret",stored,"password_ciphertext");
    }
    @Test void updatesConnectionAndRetainsOrReplacesEncryptedPassword() throws Exception {
        long id=connection();
        var original=db.queryForObject("SELECT password_ciphertext FROM connection_profile WHERE id=?",String.class,id);
        mvc.perform(post("/api/connections/"+id).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("Updated "+UUID.randomUUID(),""))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.passwordSaved").value(true));
        assertThat(db.queryForObject("SELECT password_ciphertext FROM connection_profile WHERE id=?",String.class,id)).isEqualTo(original);
        mvc.perform(post("/api/connections/"+id).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("Replaced "+UUID.randomUUID(),"new-secret"))))
            .andExpect(status().isOk());
        assertThat(cipher.decrypt(db.queryForObject("SELECT password_ciphertext FROM connection_profile WHERE id=?",String.class,id))).isEqualTo("new-secret");
    }
    @Test void persistsSchemaOnlyEditsWithoutReplacingOrDecryptingThePassword()throws Exception{
        long id=connection();
        db.update("UPDATE connection_profile SET password_ciphertext='v1:unreadable' WHERE id=?",id);
        var p=registry.connection(id);
        var edit=new Models.ConnectionInput(p.name(),p.databaseType(),p.host(),p.port(),p.connectionType(),p.databaseName(),p.username(),"NEW_SCHEMA","",p.readOnly());
        mvc.perform(post("/api/connections/"+id).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.schema").value("NEW_SCHEMA"));
        assertThat(registry.connection(id).schema()).isEqualTo("NEW_SCHEMA");
        assertThat(db.queryForObject("SELECT schema_name FROM connection_profile WHERE id=?",String.class,id)).isEqualTo("NEW_SCHEMA");
        assertThat(db.queryForObject("SELECT password_ciphertext FROM connection_profile WHERE id=?",String.class,id)).isEqualTo("v1:unreadable");
        assertThatThrownBy(()->registry.connectionPassword(id)).hasMessageContaining("Cannot decrypt");
    }
    @Test void hidesLegacyH2SourcesWithoutDeletingThem() throws Exception {
        db.update("INSERT INTO connection_profile(name,jdbc_url,username,schema_name) VALUES('Legacy H2','jdbc:h2:mem:legacy','sa','PUBLIC')");
        long id=db.queryForObject("SELECT id FROM connection_profile WHERE name='Legacy H2'",Long.class);
        mvc.perform(get("/api/connections")).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Legacy H2"))));
        mvc.perform(post("/api/connections/"+id+"/test").with(csrf())).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM connection_profile WHERE id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void appendDeduplicatesAndRejectsWholeInvalidBatch() throws Exception {
        long id=connection();String path="/api/connections/"+id+"/tables";
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"names\":[\"customer\",\"CUSTOMER\",\"ACCOUNT\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.added").value(2)).andExpect(jsonPath("$.skipped").value(1));
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"names\":[\"VALID_NEW\",\"invalid;drop\"]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(path)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"names\":[null]}"))
            .andExpect(status().isBadRequest());
    }
    @Test void csvAppendPreservesExistingAndSeparatesConnections() throws Exception {
        long id=connection(),other=connection();
        var file=new MockMultipartFile("file","tables.csv","text/csv","Table Name,Schema\nCUSTOMER,PUBLIC\nACCOUNT,PUBLIC\nCUSTOMER,PUBLIC".getBytes(StandardCharsets.UTF_8));
        String path="/api/connections/"+id+"/tables/import";
        mvc.perform(multipart(path).file(file).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.added").value(2));
        mvc.perform(multipart(path).file(file).with(csrf())).andExpect(jsonPath("$.added").value(0)).andExpect(jsonPath("$.skipped").value(3));
        mvc.perform(get("/api/connections/"+other+"/tables")).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void readsMappingWorkbookFileSpecAndRejectsBadUploads() throws Exception {
        long id=connection();byte[] bytes;
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var sheet=workbook.createSheet("FIle Spec");sheet.createRow(1).createCell(14).setCellValue("Applicable to ctry (Y/N)");
            sheet.createRow(2).createCell(1).setCellValue("File Name");sheet.createRow(3).createCell(1).setCellValue("CUSTOMER");
            workbook.write(output);bytes=output.toByteArray();
        }
        String path="/api/connections/"+id+"/tables/import";
        mvc.perform(multipart(path).file(new MockMultipartFile("file","mapping.xlsx","application/octet-stream",bytes)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.added").value(1));
        mvc.perform(multipart(path).file(new MockMultipartFile("file","bad.xlsx","application/octet-stream",new byte[]{1,2,3})).with(csrf()))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart(path).file(new MockMultipartFile("file","bad.csv","text/csv","Table Name\nOK_NAME\nBAD-NAME".getBytes())).with(csrf()))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/connections/"+id+"/tables")).andExpect(jsonPath("$.length()").value(1));
    }
    @Test void csrfCorsAndConsolePolicy() throws Exception {
        mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(options("/api/connections").header("Origin","http://127.0.0.1:4300").header("Access-Control-Request-Method","POST"))
            .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin","http://127.0.0.1:4300"));
        mvc.perform(options("/api/connections").header("Origin","https://untrusted.example").header("Access-Control-Request-Method","POST"))
            .andExpect(status().isForbidden());
    }
    @Test void rejectsInvalidHostAndMissingPassword() throws Exception {
        var request=new Models.ConnectionInput("Unsafe","ORACLE","host;INIT=SQL",1521,"SERVICE_NAME","ORCL","app","APP","secret");
        mvc.perform(post("/api/connections").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/connections").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input("Missing password",""))))
            .andExpect(status().isBadRequest());
    }
    @Test void columnSelectionsPersistAndRemainScopedToConfiguredTable() throws Exception {
        long id=connection(),other=connection();
        db.update("INSERT INTO table_registry(connection_id,schema_name,table_name,source) VALUES(?,?,?,'MANUAL')",id,"APP","CUSTOMER");
        db.update("INSERT INTO table_registry(connection_id,schema_name,table_name,source) VALUES(?,?,?,'MANUAL')",id,"OTHER","CUSTOMER");
        long table=db.queryForObject("SELECT id FROM table_registry WHERE connection_id=? AND schema_name='APP'",Long.class,id);
        long otherSchema=db.queryForObject("SELECT id FROM table_registry WHERE connection_id=? AND schema_name='OTHER'",Long.class,id);
        when(oracle.columns(any(),eq("test-secret"),any())).thenReturn(List.of(
            new Models.ColumnDetail(1,"ID","NUMBER",22,null,10,0,false,true),
            new Models.ColumnDetail(2,"NAME","VARCHAR2",100,100,null,null,true,true)));
        String path="/api/connections/"+id+"/tables/"+table+"/columns";
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$[1].included").value(true));
        String exclude=json.writeValueAsString(new Models.ColumnSelection("NAME",false));
        mvc.perform(post(path+"/selection").contentType(MediaType.APPLICATION_JSON).content(exclude)).andExpect(status().isForbidden());
        for(int i=0;i<2;i++) mvc.perform(post(path+"/selection").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(exclude)).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM column_exclusion WHERE table_id=?",Integer.class,table)).isEqualTo(1);
        mvc.perform(get(path)).andExpect(jsonPath("$[0].included").value(true)).andExpect(jsonPath("$[1].included").value(false));
        mvc.perform(get("/api/connections/"+id+"/tables/"+otherSchema+"/columns")).andExpect(jsonPath("$[1].included").value(true));
        mvc.perform(get("/api/connections/"+other+"/tables/"+table+"/columns")).andExpect(status().isNotFound());
        mvc.perform(post("/api/connections/"+other+"/tables/"+table+"/columns/selection").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(exclude)).andExpect(status().isNotFound());
        mvc.perform(post(path+"/selection").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"columnName\":\"NAME\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(path+"/selection").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Models.ColumnSelection("NAME",true)))).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM column_exclusion WHERE table_id=?",Integer.class,table)).isZero();
        mvc.perform(get(path)).andExpect(jsonPath("$[1].included").value(true));
        verify(oracle,atLeastOnce()).columns(argThat(p->p.id()==id),eq("test-secret"),eq(new Models.TableName("APP","CUSTOMER")));
    }
    @Test void h2SourceSupportsRealDiscoveryColumnsAndExclusions() throws Exception {
        String database="source_"+UUID.randomUUID().toString().replace("-","");
        var input=new Models.ConnectionInput("H2 "+database,"H2",null,null,"MEMORY",database,"sa","PUBLIC","");
        var response=mvc.perform(post("/api/connections").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.databaseType").value("H2")).andReturn();
        long id=json.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        String base="/api/connections/"+id;
        mvc.perform(post(base+"/test").with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(true));
        try(var source=java.sql.DriverManager.getConnection("jdbc:h2:mem:"+database,"sa","");var sql=source.createStatement()) {
            sql.execute("CREATE TABLE CUSTOMER(ID BIGINT PRIMARY KEY, NAME VARCHAR(80), BALANCE DECIMAL(12,2))");
            sql.execute("CREATE SCHEMA OTHER");sql.execute("CREATE TABLE OTHER.HIDDEN(ID INT)");
            mvc.perform(post(base+"/tables/discover").with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.added").value(1));
            long table=db.queryForObject("SELECT id FROM table_registry WHERE connection_id=?",Long.class,id);
            String columns=base+"/tables/"+table+"/columns";
            mvc.perform(get(columns)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("ID")).andExpect(jsonPath("$[0].nullable").value(false))
                .andExpect(jsonPath("$[1].characterLength").value(80)).andExpect(jsonPath("$[2].precision").value(12)).andExpect(jsonPath("$[2].scale").value(2));
            mvc.perform(post(columns+"/selection").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Models.ColumnSelection("NAME",false)))).andExpect(status().isOk());
            mvc.perform(get(columns)).andExpect(jsonPath("$[1].included").value(false));
            mvc.perform(post(base).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input))).andExpect(status().isOk());
            sql.execute("SHUTDOWN");
        }
        verifyNoInteractions(oracle);
    }
    @Test void tableFileOptionsFlowThroughManualUploadDiscoveryEditingAndWorkbook()throws Exception{
        String database="file_options_"+UUID.randomUUID().toString().replace("-","");
        var profile=registry.save(new Models.ConnectionInput(database,"H2",null,null,"MEMORY",database,"sa","PUBLIC",""));
        String base="/api/connections/"+profile.id()+"/tables";
        try(var c=java.sql.DriverManager.getConnection(profile.jdbcUrl(),"sa","");var s=c.createStatement()){
            for(String name:List.of("MANUAL_A","CSV_A","DISCOVER_A"))s.execute("CREATE TABLE "+name+"(ID INT)");
            mvc.perform(post(base).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"names\":[\"MANUAL_A\"],\"fileType\":\"Master\",\"loadStrategy\":\"Full\"}"))
                .andExpect(status().isOk());
            var file=new MockMultipartFile("file","tables.csv","text/csv","Schema,Table Name,File Type,Load Strategy,Update Frequency\nPUBLIC,CSV_A,Enriched,Delta,Weekly".getBytes(StandardCharsets.UTF_8));
            mvc.perform(multipart(base+"/import").file(file).with(csrf())).andExpect(status().isOk());
            mvc.perform(post(base+"/discover").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"fileType\":\"Logs\",\"loadStrategy\":\"Delta\",\"updateFrequency\":\"Weekly\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.added").value(1)).andExpect(jsonPath("$.skipped").value(2));
            var entries=registry.tables(profile.id());var manual=entries.stream().filter(t->t.name().equals("MANUAL_A")).findFirst().orElseThrow();
            assertThat(manual.fileType()).isEqualTo("Master");assertThat(manual.loadStrategy()).isEqualTo("Full");
            assertThat(manual.updateFrequency()).isEqualTo("Daily");
            var csv=entries.stream().filter(t->t.name().equals("CSV_A")).findFirst().orElseThrow();
            assertThat(csv.fileType()).isEqualTo("Enriched");assertThat(csv.loadStrategy()).isEqualTo("Delta");
            assertThat(csv.updateFrequency()).isEqualTo("Weekly");
            String options=base+"/"+csv.id()+"/options";
            mvc.perform(post(options).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"fileType\":\"Tech Recon\",\"loadStrategy\":\"Full\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fileType").value("Tech Recon")).andExpect(jsonPath("$.updateFrequency").value("Weekly"));
            mvc.perform(post(options).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"fileType\":\"Tech Recon\",\"loadStrategy\":\"Full\",\"updateFrequency\":\"Monthly\"}"))
                .andExpect(status().isBadRequest());
            mvc.perform(post(options).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"fileType\":\"Other\",\"loadStrategy\":\"Full\"}"))
                .andExpect(status().isBadRequest());
            mvc.perform(post(options).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
            long other=connection();mvc.perform(post("/api/connections/"+other+"/tables/"+csv.id()+"/options").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isNotFound());
            var invalid=new MockMultipartFile("file","bad.csv","text/csv","Table Name,File Type,Load Strategy\nNEW_A,Master,Full\nNEW_B,Unknown,Delta".getBytes(StandardCharsets.UTF_8));
            mvc.perform(multipart(base+"/import").file(invalid).with(csrf())).andExpect(status().isBadRequest());assertThat(registry.tables(profile.id())).hasSize(3);
            byte[] workbook=workbooks.generate(profile.id(),List.of(manual.id(),csv.id(),entries.stream().filter(t->t.name().equals("DISCOVER_A")).findFirst().orElseThrow().id()));
            try(var wb=new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook))){
                var spec=wb.getSheetAt(0);var f=new org.apache.poi.ss.usermodel.DataFormatter();
                assertThat(f.formatCellValue(spec.getRow(2).getCell(4)).toLowerCase(java.util.Locale.ROOT)).startsWith("file type");
                assertThat(f.formatCellValue(spec.getRow(2).getCell(5)).toLowerCase(java.util.Locale.ROOT)).startsWith("load strategy");
                assertThat(f.formatCellValue(spec.getRow(2).getCell(3)).toLowerCase(java.util.Locale.ROOT)).startsWith("update frequency");
                assertThat(spec.getRow(3).getCell(3).getStringCellValue()).isEqualTo("Daily");
                assertThat(spec.getRow(4).getCell(3).getStringCellValue()).isEqualTo("Weekly");
                assertThat(spec.getRow(5).getCell(3).getStringCellValue()).isEqualTo("Weekly");
                assertThat(spec.getRow(3).getCell(4).getStringCellValue()).isEqualTo("Master");assertThat(spec.getRow(3).getCell(5).getStringCellValue()).isEqualTo("Full");
                assertThat(spec.getRow(4).getCell(4).getStringCellValue()).isEqualTo("Tech Recon");assertThat(spec.getRow(5).getCell(5).getStringCellValue()).isEqualTo("Delta");
            }
            mvc.perform(multipart("/api/connections/"+other+"/tables/import").file(new MockMultipartFile("file","roundtrip.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",workbook)).with(csrf())).andExpect(status().isOk());
            assertThat(registry.tables(other)).extracting(Models.TableEntry::fileType).containsExactly("Tech Recon","Logs","Master");
            assertThat(registry.tables(other)).extracting(Models.TableEntry::updateFrequency).containsExactly("Weekly","Weekly","Daily");
            s.execute("SHUTDOWN");
        }
    }
    @Test void workbookUsesTemplateSelectedTablesAndSavedExclusions() throws Exception {
        String database="workbook_"+UUID.randomUUID().toString().replace("-","");
        var profile=registry.save(new Models.ConnectionInput(database,"H2",null,null,"MEMORY",database,"sa","PUBLIC",""));
        try(var source=java.sql.DriverManager.getConnection(profile.jdbcUrl(),"sa","");var sql=source.createStatement()) {
            sql.execute("CREATE TABLE CUSTOMER(ID BIGINT PRIMARY KEY,NAME VARCHAR(80),BALANCE DECIMAL(15,2) DEFAULT 0,BUSINESS_DATE DATE,UPDATED_AT TIMESTAMP)");
            sql.execute("CREATE TABLE ACCOUNT(ID BIGINT,CUSTOMER_ID BIGINT REFERENCES CUSTOMER(ID),PRIMARY KEY(ID,CUSTOMER_ID))");
            registry.append(profile.id(),List.of("CUSTOMER","ACCOUNT"),"MANUAL");
            var tables=registry.tables(profile.id());long customer=tables.stream().filter(t->t.name().equals("CUSTOMER")).findFirst().orElseThrow().id();
            long account=tables.stream().filter(t->t.name().equals("ACCOUNT")).findFirst().orElseThrow().id();
            db.update("INSERT INTO column_exclusion(table_id,column_name) VALUES(?,?)",customer,"NAME");
            byte[] bytes=workbooks.generate(profile.id(),List.of(customer,account));
            try(var wb=new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));var template=new XSSFWorkbook(new org.springframework.core.io.ClassPathResource("templates/EDAC_Mapping_Template.xlsx").getInputStream())) {
                assertThat(wb.getNumberOfSheets()).isEqualTo(3);assertThat(wb.getSheetName(0)).isEqualTo("FIle Spec");
                var spec=wb.getSheetAt(0);assertThat(spec.getMergedRegion(0).formatAsString()).isEqualTo("O2:AJ2");
                assertThat(spec.getRow(3).getCell(1).getStringCellValue()).isEqualTo("CUSTOMER");
                assertThat(spec.getRow(3).getCell(2).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                assertThat(spec.getRow(3).getCell(3).getStringCellValue()).isEqualTo("Daily");
                assertThat(spec.getRow(3).getCell(7).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                assertThat(spec.getRow(2).getCell(2).getStringCellValue()).isEqualTo("Brief Description of file");
                for(int r=3;r<=4;r++) {
                    var link=spec.getRow(r).getCell(1);
                    assertThat(link.getStringCellValue()).isEqualTo(wb.getSheetName(r-2));
                    assertThat(link.getHyperlink().getType()).isEqualTo(org.apache.poi.common.usermodel.HyperlinkType.DOCUMENT);
                    assertThat(link.getHyperlink().getAddress()).isEqualTo("'"+wb.getSheetName(r-2)+"'!A1");
                    assertThat(spec.getRow(r).getCell(6).getStringCellValue()).isEqualTo("B"); assertThat(spec.getRow(r).getCell(8).getStringCellValue()).isEqualTo(r==3?"ID":"ID,CUSTOMER_ID");
                }
                assertThat(spec.getRow(2).getCell(6).getStringCellValue()).isEqualTo("File Format (F - Fixed Width, P - Pipe Delimited, T - Tab Delimited, PQ - Pipe With Quotes, B - Bell Delimited, || delimited )");
                for(int c=2;c<36;c++) {
                    if(c==6)continue;
                    assertThat(spec.getColumnWidth(c)).isEqualTo(template.getSheetAt(0).getColumnWidth(c));
                    assertThat(spec.getRow(2).getCell(c).getStringCellValue()).isEqualTo(template.getSheetAt(0).getRow(2).getCell(c).getStringCellValue());
                }
                assertThat(spec.getDataValidations().get(0).getRegions().getCellRangeAddresses()[0].formatAsString()).isEqualTo("O4:AJ5");
                var customerSheet=wb.getSheet("CUSTOMER");var accountSheet=wb.getSheet("ACCOUNT");
                assertThat(customerSheet.getRow(9).getCell(1).getStringCellValue()).isEqualTo("ID");
                assertThat(customerSheet.getRow(9).getCell(3).getNumericCellValue()).isEqualTo(19);
                assertThat(customerSheet.getRow(9).getCell(5).getStringCellValue()).isEqualTo("PK");
                assertThat(customerSheet.getRow(10).getCell(1).getStringCellValue()).isEqualTo("BALANCE");
                assertThat(customerSheet.getRow(10).getCell(3).getNumericCellValue()).isEqualTo(15);
                assertThat(customerSheet.getRow(10).getCell(4).getNumericCellValue()).isEqualTo(2);
                assertThat(customerSheet.getRow(10).getCell(9).getStringCellValue()).isEqualTo("0");
                assertThat(customerSheet.getRow(11).getCell(3).getNumericCellValue()).isEqualTo(10);
                assertThat(customerSheet.getRow(12).getCell(3).getNumericCellValue()).isEqualTo(19);
                assertThat(customerSheet.getRow(11).getCell(8).getStringCellValue()).isEqualTo("YYYY-MM-DD");
                assertThat(customerSheet.getRow(12).getCell(8).getStringCellValue()).isEqualTo("YYYY-MM-DD HH:MI:SS");
                for(var sheet:wb) {
                    assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Go to File Spec");
                    assertThat(sheet.getRow(0).getCell(0).getHyperlink().getAddress()).isEqualTo("'FIle Spec'!A1");
                }
                assertThat(customerSheet.getRow(13).getCell(0).getStringCellValue()).isEqualTo("Trailer");
                assertThat(accountSheet.getRow(10).getCell(5).getStringCellValue()).isEqualTo("PK,FK");
                var formatter=new org.apache.poi.ss.usermodel.DataFormatter();
                for(int r=0;r<7;r++)for(int c=0;c<11;c++)assertThat(formatter.formatCellValue(customerSheet.getRow(r).getCell(c))).isEqualTo(formatter.formatCellValue(accountSheet.getRow(r).getCell(c)));
                for(int r=0;r<5;r++)for(int c=0;c<11;c++)assertThat(formatter.formatCellValue(customerSheet.getRow(13+r).getCell(c))).isEqualTo(formatter.formatCellValue(accountSheet.getRow(11+r).getCell(c)));
                assertThat(customerSheet.getRow(5).getCell(3).getNumericCellValue()).isEqualTo(8);
                assertThat(customerSheet.getRow(6).getCell(3).getNumericCellValue()).isEqualTo(14);
                assertThat(customerSheet.getColumnWidth(10)).isEqualTo(template.getSheetAt(1).getColumnWidth(10));
                assertThat(customerSheet.getRow(8).getCell(0).getCellStyle().getFillForegroundXSSFColor().getARGBHex()).isEqualTo(template.getSheetAt(1).getRow(8).getCell(0).getCellStyle().getFillForegroundXSSFColor().getARGBHex());
                for(var sheet:wb)for(var row:sheet)for(var cell:row)assertThat(formatter.formatCellValue(cell)).isNotEqualToIgnoringCase("NULL");
            }
            java.nio.file.Files.createDirectories(java.nio.file.Path.of("../outputs/workbook-verification"));
            java.nio.file.Files.write(java.nio.file.Path.of("../outputs/workbook-verification/EDAC_Mapping_Workbook.xlsx"),bytes);
            for(var column:List.of("ID","BALANCE","BUSINESS_DATE","UPDATED_AT"))db.update("INSERT INTO column_exclusion(table_id,column_name) VALUES(?,?)",customer,column);
            try(var wb=new XSSFWorkbook(new java.io.ByteArrayInputStream(workbooks.generate(profile.id(),List.of(customer))))) {
                assertThat(wb.getNumberOfSheets()).isEqualTo(2);assertThat(wb.getSheetAt(1).getRow(9).getCell(0).getStringCellValue()).isEqualTo("Trailer");
            }
            sql.execute("SHUTDOWN");
        }
    }
    @Test void workbookSkipsMissingSourceTablesAndReportsAllMissing() throws Exception {
        String database="missing_"+UUID.randomUUID().toString().replace("-","");
        var profile=registry.save(new Models.ConnectionInput(database,"H2",null,null,"MEMORY",database,"sa","PUBLIC",""));
        try(var source=java.sql.DriverManager.getConnection(profile.jdbcUrl(),"sa","");var sql=source.createStatement()) {
            sql.execute("CREATE TABLE PRESENT_TABLE(ID INTEGER)");
            registry.append(profile.id(),List.of("MISSING_TABLE","PRESENT_TABLE"),"MANUAL");
            var tables=registry.tables(profile.id());
            long missing=tables.stream().filter(t->t.name().equals("MISSING_TABLE")).findFirst().orElseThrow().id();
            long present=tables.stream().filter(t->t.name().equals("PRESENT_TABLE")).findFirst().orElseThrow().id();
            try(var wb=new XSSFWorkbook(new java.io.ByteArrayInputStream(workbooks.generate(profile.id(),List.of(missing,present))))) {
                assertThat(wb.getNumberOfSheets()).isEqualTo(3);
                assertThat(wb.getSheet("MISSING_TABLE")).isNull();
                assertThat(wb.getSheet("PRESENT_TABLE").getRow(9).getCell(1).getStringCellValue()).isEqualTo("ID");
                assertThat(wb.getSheetAt(0).getRow(3).getCell(1).getHyperlink().getAddress()).isEqualTo("'PRESENT_TABLE'!A1");
                var report=wb.getSheet("Generation Report");
                assertThat(report.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Selected: 2 | Generated: 1 | Skipped: 1");
                assertThat(wb.getSheetAt(0).getRow(0).getCell(3).getHyperlink().getAddress()).isEqualTo("'Generation Report'!A1");
                assertThat(report.getRow(0).getCell(0).getHyperlink().getAddress()).isEqualTo("'FIle Spec'!A1");
                assertThat(report.getRow(3).getCell(1).getStringCellValue()).isEqualTo("MISSING_TABLE");
                java.nio.file.Files.createDirectories(java.nio.file.Path.of("../outputs/workbook-verification"));
                java.nio.file.Files.write(java.nio.file.Path.of("../outputs/workbook-verification/missing-tables.xlsx"),workbooks.generate(profile.id(),List.of(missing,present)));
            }
            try(var wb=new XSSFWorkbook(new java.io.ByteArrayInputStream(workbooks.generate(profile.id(),List.of(missing))))) {
                assertThat(wb.getNumberOfSheets()).isEqualTo(2);
                assertThat((Object)wb.getSheetAt(0).getRow(3)).isNull();
                assertThat(wb.getSheet("Generation Report").getRow(1).getCell(0).getStringCellValue()).contains("Generated: 0");
            }
            sql.execute("SHUTDOWN");
        }
    }
    @Test void workbookDoesNotSkipConnectionOrQueryFailures() throws Exception {
        long id=connection();registry.append(id,List.of("CUSTOMER"),"MANUAL");long table=registry.tables(id).get(0).id();
        when(oracle.mappingFields(any(),anyString(),any())).thenThrow(new IllegalArgumentException("Oracle connection or query failed"));
        assertThatThrownBy(()->workbooks.generate(id,List.of(table))).hasMessage("Oracle connection or query failed");
    }
    @Test void workbookPreservesCustomSourceMetadataAndExplicitNulls() throws Exception {
        long id=connection();registry.append(id,List.of("CUSTOMER"),"MANUAL");long table=registry.tables(id).get(0).id();
        var column=new Models.ColumnDetail(1,"ID","NUMBER",22,null,10,0,false,true);
        var blanks=new java.util.HashMap<String,Object>();blanks.put("SOURCE_DATA_TYPE",null);blanks.put("SOURCE_LENGTH",null);blanks.put("SOURCE_DECIMAL",null);
        when(oracle.mappingFields(any(),anyString(),any())).thenReturn(List.of(
            new Models.MappingField(column,null,null,null,"M",java.util.Map.of("SOURCE_DATA_TYPE","CUSTOM_NUMBER","SOURCE_LENGTH",28,"SOURCE_DECIMAL",7)),
            new Models.MappingField(column,null,null,null,"M",blanks),
            new Models.MappingField(column,null,null,null)));
        try(var wb=new XSSFWorkbook(new java.io.ByteArrayInputStream(workbooks.generate(id,List.of(table))))) {
            var sheet=wb.getSheet("CUSTOMER");
            assertThat(sheet.getRow(9).getCell(12).getStringCellValue()).isEqualTo("CUSTOM_NUMBER");
            assertThat(sheet.getRow(9).getCell(13).getNumericCellValue()).isEqualTo(28);
            assertThat(sheet.getRow(9).getCell(14).getNumericCellValue()).isEqualTo(7);
            assertThat(sheet.getRow(10).getCell(14).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            assertThat(sheet.getRow(9).getCell(2).getStringCellValue()).isEqualTo("N");
            assertThat(sheet.getRow(9).getCell(3).getNumericCellValue()).isEqualTo(10);
            assertThat(sheet.getRow(10).getCell(12).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            assertThat(sheet.getRow(10).getCell(13).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            assertThat(sheet.getRow(11).getCell(12).getStringCellValue()).isEqualTo("NUMBER");
            assertThat(sheet.getRow(11).getCell(13).getNumericCellValue()).isEqualTo(10);
        }
    }
    @Test void workbookDownloadValidatesSelectionAndSupportsOraclePlaceholder() throws Exception {
        long id=connection(),other=connection();registry.append(id,List.of("CUSTOMER"),"MANUAL");long table=registry.tables(id).get(0).id();
        when(oracle.mappingFields(any(),anyString(),any())).thenReturn(List.of(new Models.MappingField(new Models.ColumnDetail(1,"ID","NUMBER",22,null,10,0,false,true),null,null,null)));
        String path="/api/connections/"+id+"/mapping-workbook";String body=json.writeValueAsString(new Models.WorkbookRequest(List.of(table)));
        var response=mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=Extraction_Framework_Mapping.xlsx")).andReturn();
        try(var wb=new XSSFWorkbook(new java.io.ByteArrayInputStream(response.getResponse().getContentAsByteArray()))) {assertThat(wb.getSheet("CUSTOMER").getRow(9).getCell(1).getStringCellValue()).isEqualTo("ID");}
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"tableIds\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new Models.WorkbookRequest(List.of(table,table))))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/connections/"+other+"/mapping-workbook").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
    }
}

package com.edac;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:schema_tests;DB_CLOSE_DELAY=-1","spring.datasource.password="})
@ActiveProfiles("local") @AutoConfigureMockMvc
class SchemaComparisonIntegrationTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("edac.snapshot.directory",()->directory.toString());}
    @Autowired RegistryService registry;@Autowired SchemaMetadata metadata;@Autowired SchemaComparisonService service;@Autowired SchemaComparisonReports reports;@Autowired MockMvc mvc;@Autowired ObjectMapper json;
    long connection(){return registry.save(new Models.ConnectionInput("Schema "+UUID.randomUUID(),"H2","localhost",9092,"MEMORY","schema_"+UUID.randomUUID().toString().replace("-",""),"sa","PUBLIC","",true)).id();}
    void sql(long id,String...sql)throws Exception{try(var c=metadata.open(registry.connection(id));var s=c.createStatement()){for(String command:sql)s.execute(command);}}
    SchemaComparisonService.Job await(SchemaComparisonService.Job job)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);while(service.job(job.id()).status().equals("RUNNING")&&System.nanoTime()<deadline)Thread.sleep(20);return service.job(job.id());}
    @Test void comparesObjectsStructuresRowsAndPersistsHighlightedSixSheetReport()throws Exception{
        long a=connection(),b=connection();
        sql(a,"CREATE TABLE ITEMS(ID INT,PART INT,AMOUNT DECIMAL(12,2),NOTE VARCHAR(80),FLAG INT DEFAULT 0 NOT NULL,CONSTRAINT PK_ITEMS PRIMARY KEY(ID,PART),CONSTRAINT UQ_NOTE UNIQUE(NOTE),CONSTRAINT CK_AMOUNT CHECK(AMOUNT>=0))","INSERT INTO ITEMS VALUES(1,1,20,'old',0),(2,1,30,'second',0)","CREATE TABLE ONLY_OLD(VALUE_N INT)","CREATE VIEW VW_OLD AS SELECT ID FROM ITEMS");
        sql(b,"CREATE TABLE ITEMS(ID INT,PART INT,AMOUNT DECIMAL(12,2) NOT NULL,NOTE VARCHAR(120),FLAG INT DEFAULT 1 NOT NULL,EXTRA DATE,CONSTRAINT PK_ITEMS PRIMARY KEY(PART,ID),CONSTRAINT UQ_NOTE UNIQUE(NOTE),CONSTRAINT CK_AMOUNT CHECK(AMOUNT>=10))","INSERT INTO ITEMS VALUES(1,1,20,'old',1,NULL)","CREATE TABLE ONLY_NEW(VALUE_N INT)","CREATE VIEW VW_NEW AS SELECT ID FROM ITEMS");
        sql(a,"CREATE ALIAS PROC_OLD FOR \"java.lang.System.gc\"");
        var input=new SchemaComparisonService.Request(a,b,true,true);
        mvc.perform(post("/api/snapshot/schema/compare").contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isForbidden());
        var job=await(service.start(input));assertThat(job.status()).as(job.message()).isEqualTo("COMPLETED");assertThat(job.beforeTablesRead()).isEqualTo(2);assertThat(job.afterTablesRead()).isEqualTo(2);
        var summary=reports.summary(job.id());assertThat(summary.get("status")).isEqualTo("Differences exist");assertThat(summary.get("includeRowCounts")).isEqualTo(true);
        var counts=json.valueToTree(reports.page(job.id(),"objectCounts",0,10)).path("rows");
        assertThat(counts.findValuesAsText("status")).contains("Names differ","Count mismatch");
        var changes=json.valueToTree(reports.page(job.id(),"structureDifferences",0,100,"ITEMS")).path("rows");
        assertThat(changes.findValuesAsText("property")).contains("Length","Nullable","Default","Constraint","Column");assertThat(changes.findValuesAsText("tableName")).allMatch("ITEMS"::equals);
        var rows=json.valueToTree(reports.page(job.id(),"rowCounts",0,10)).path("rows");assertThat(rows.findValuesAsText("status")).contains("Count mismatch","Only Before","Only After");
        assertThat(reports.page(job.id(),"structureDifferences",0,1).get("rows")).asList().hasSize(1);assertThat(reports.page(job.id(),"structureDifferences",1,1).get("rows")).asList().hasSize(1);
        Path zip=reports.zip(job.id());assertThat(directory.resolve("snapshot/schema/json/"+job.id()+".json")).exists();
        try(var entries=new ZipFile(zip.toFile());var book=new XSSFWorkbook(entries.getInputStream(entries.getEntry(job.id()+".xlsx")))){
            assertThat(book.getNumberOfSheets()).isEqualTo(6);assertThat(book.getSheet("ObjectCounts")).isNotNull();var sheet=book.getSheet("StructureDifferences");assertThat(sheet.getLastRowNum()).isGreaterThan(5);
            assertThat(sheet.getRow(1).getCell(0).getCellStyle().getFillPattern()).isEqualTo(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);
        }
        sql(a,"DROP ALL OBJECTS");sql(b,"DROP ALL OBJECTS"); // History and downloads must not query sources again.
        assertThat(reports.history()).anyMatch(r->r.get("id").equals(job.id()));assertThat(reports.summary(job.id()).get("status")).isEqualTo("Differences exist");
        mvc.perform(get("/api/snapshot/schema/reports/"+job.id()+"/structureDifferences").param("size","10").param("table","ITEMS")).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(10));
        var download=mvc.perform(get("/api/snapshot/schema/reports/"+job.id()+"/download")).andExpect(request().asyncStarted()).andReturn();mvc.perform(asyncDispatch(download)).andExpect(status().isOk()).andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }
    @Test void sameSchemaCanSkipExpensiveRowCountsAndRejectInvalidRequests()throws Exception{
        long a=connection(),b=connection();for(long id:List.of(a,b))sql(id,"CREATE TABLE SIMPLE_TABLE(ID INT,NAME VARCHAR(40))");
        var job=await(service.start(new SchemaComparisonService.Request(a,b,false,true)));assertThat(job.status()).as(job.message()).isEqualTo("COMPLETED");
        assertThat(reports.summary(job.id()).get("status")).isEqualTo("No differences in comparable metadata");assertThat(reports.page(job.id(),"rowCounts",0,10).get("total")).isEqualTo(0L);
        assertThatThrownBy(()->service.start(new SchemaComparisonService.Request(a,a,false,true))).hasMessageContaining("different");
        assertThatThrownBy(()->reports.page(job.id(),"invalid",0,10)).hasMessageContaining("report tab");assertThatThrownBy(()->reports.zip("../escape")).hasMessageContaining("Invalid");
    }
    @Test void savesSybaseProfileWithoutDriverAndExplainsMissingExternalJar()throws Exception{
        var input=new Models.ConnectionInput("Company Sybase "+UUID.randomUUID(),"SYBASE","ase.example",5000,"ASE","AAAMAINDB","company_user","dbo","secret",true);
        var response=mvc.perform(post("/api/connections").with(csrf()).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isOk()).andExpect(jsonPath("$.databaseType").value("SYBASE")).andExpect(jsonPath("$.schema").value("dbo")).andExpect(jsonPath("$.readOnly").value(true)).andReturn();
        long id=json.readTree(response.getResponse().getContentAsString()).path("id").asLong();
        assertThat(registry.connection(id).jdbcUrl()).isEqualTo("jdbc:sybase:Tds:ase.example:5000/AAAMAINDB");assertThatThrownBy(()->registry.test(id)).hasMessageContaining("edac.sybase.driver-path");
        mvc.perform(get("/snapshot/schema")).andExpect(status().isOk());mvc.perform(get("/snapshot/schema/history")).andExpect(status().isOk());
    }
}

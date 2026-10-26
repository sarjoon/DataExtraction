package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:snapshot_tests;DB_CLOSE_DELAY=-1","spring.datasource.password="})
@AutoConfigureMockMvc @ActiveProfiles("local")
class SnapshotIntegrationTest {
    @Autowired org.springframework.jdbc.core.JdbcTemplate configDb;
    @TempDir static Path directory;
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry){registry.add("edac.snapshot.directory",()->directory.toString());}
    @Autowired SnapshotService service;@Autowired SnapshotReports reports;@Autowired SnapshotDatabase databases;@Autowired RegistryService registry;@Autowired ObjectMapper json;@Autowired MockMvc mvc;
    long connection(String database){return registry.save(new Models.ConnectionInput("Snapshot "+UUID.randomUUID(),"H2","localhost",9092,"MEMORY",database,"sa","","",false)).id();}
    List<SnapshotService.Config> configure(long connection,List<SnapshotService.Config> configs) throws SQLException {service.registerTables(connection,configs.stream().map(SnapshotService.Config::tableName).toList());return service.configure(connection,configs);}
    String database(){return "snapshot_"+UUID.randomUUID().toString().replace("-","");}
    void access(long id,Boolean readOnly){var p=registry.connection(id);registry.update(id,new Models.ConnectionInput(p.name(),p.databaseType(),p.host(),p.port(),p.connectionType(),p.databaseName(),p.username(),p.schema(),"",readOnly));}
    SnapshotService.Job await(SnapshotService.Job job) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
        while(service.job(job.id()).status().equals("RUNNING")&&System.nanoTime()<deadline)Thread.sleep(25);
        return service.job(job.id());
    }
    void success(SnapshotService.Job job) throws Exception {assertThat(await(job)).extracting(SnapshotService.Job::status).as(service.job(job.id()).message()).isEqualTo("COMPLETED");}
    @Test void capturesAndComparesCompositeKeysInBatchesAndReopensJsonWithFourSheetExcel() throws Exception {
        long before=connection(database()),after=connection(database());
        try(var c=databases.open(before);var s=c.createStatement()){
            s.execute("CREATE TABLE ITEMS(ID INT,PART VARCHAR(20),AMOUNT DECIMAL(14,2),NOTE VARCHAR(100),AUDIT_TS TIMESTAMP,PRIMARY KEY(ID,PART))");
            s.execute("CREATE TABLE ITEMS_EXTRA(OTHER_ID INT PRIMARY KEY)");
            s.execute("INSERT INTO ITEMS SELECT X,'A',X/10.0,'name'||X,TIMESTAMP '2026-01-01 00:00:00' FROM SYSTEM_RANGE(1,640)");
        }
        configure(before,List.of(new SnapshotService.Config("ITEMS",List.of("AUDIT_TS"))));
        var capture=new SnapshotService.Request(before,after,List.of("ITEMS"),"CREATE");
        assertThat(service.inspect(capture).get(0)).containsEntry("exists",false);
        var captureJob=service.start(capture,false);success(captureJob);
        assertThat(service.job(captureJob.id()).processed()).isEqualTo(640);
        assertThat(service.config(after)).containsExactly(new SnapshotService.Config("ITEMS",List.of("AUDIT_TS")));
        try(var c=databases.open(after);var s=c.createStatement()){
            s.execute("DELETE FROM ITEMS WHERE ID IN (1,2)");s.execute("INSERT INTO ITEMS VALUES(641,'A',64.1,'new',NULL),(642,'A',64.2,'new',NULL)");
            s.execute("UPDATE ITEMS SET AMOUNT=99.9,NOTE='changed' WHERE ID=3");s.execute("UPDATE ITEMS SET NOTE=NULL WHERE ID=5");s.execute("UPDATE ITEMS SET AUDIT_TS=CURRENT_TIMESTAMP");
        }
        var job=service.start(new SnapshotService.Request(before,after,List.of("ITEMS"),null),true);success(job);
        var completed=service.job(job.id());assertThat(completed.processed()).isEqualTo(638);
        assertThat(completed.beforeKeysRead()).isEqualTo(640);assertThat(completed.afterKeysRead()).isEqualTo(640);
        assertThat(completed.beforeOnly()).isEqualTo(2);assertThat(completed.afterOnly()).isEqualTo(2);
        var report=reports.summary(job.id());var tables=(List<?>)report.get("tables");var table=(Map<?,?>)tables.get(0);
        assertThat(table.get("beforeCount")).isEqualTo(640);assertThat(table.get("afterCount")).isEqualTo(640);
        assertThat(table.get("beforeOnlyCount")).isEqualTo(2);assertThat(table.get("afterOnlyCount")).isEqualTo(2);assertThat(table.get("differenceCount")).isEqualTo(2);assertThat(table.get("unchangedCount")).isEqualTo(636);
        assertThat(table.get("status")).isEqualTo("Difference exists");
        var page=reports.page(job.id(),"ITEMS","differences",0,1);assertThat(page.get("total")).isEqualTo(2L);assertThat((List<?>)page.get("rows")).hasSize(1);
        assertThat((List<?>)reports.page(job.id(),"ITEMS","differences",1,1).get("rows")).hasSize(1);
        assertThat(reports.history()).anyMatch(r->r.get("id").equals(job.id()));
        Path zip=reports.zip(job.id());assertThat(zip.getFileName().toString()).isEqualTo(job.id()+".zip");assertThat(directory.resolve("snapshot/json/"+job.id()+".json")).exists();
        try(var entries=new ZipFile(zip.toFile())){assertThat(entries.size()).isEqualTo(1);var entry=entries.entries().nextElement();assertThat(entry.getName()).isEqualTo("ITEMS_"+report.get("timestamp")+".xlsx");
            try(var book=new XSSFWorkbook(entries.getInputStream(entry))){
                assertThat(book.getNumberOfSheets()).isEqualTo(4);assertThat(book.getSheetName(0)).isEqualTo("Summary");assertThat(book.getSheet("Records Only Before").getLastRowNum()).isEqualTo(2);
                var differences=book.getSheet("Differences");assertThat(differences.getLastRowNum()).isEqualTo(3);
                var headers=new ArrayList<String>();differences.getRow(0).forEach(cell->headers.add(cell.getStringCellValue()));
                assertThat(headers).containsExactly("ID","PART","Column Name","Before","After");
                var changes=new ArrayList<List<String>>();for(int i=1;i<=differences.getLastRowNum();i++){var values=new ArrayList<String>();differences.getRow(i).forEach(cell->values.add(cell.getStringCellValue()));changes.add(values);}
                assertThat(changes).containsExactlyInAnyOrder(List.of("3","A","AMOUNT","0.3","99.9"),List.of("3","A","NOTE","name3","changed"),List.of("5","A","NOTE","name5","<NULL>"));
            }}
        mvc.perform(get("/api/snapshot/reports/"+job.id()+"/ITEMS/beforeOnly")).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(10)).andExpect(jsonPath("$.rows.length()").value(2));
        mvc.perform(post("/api/snapshot/capture").contentType("application/json").content(json.writeValueAsString(capture))).andExpect(status().isForbidden());
        // Append restores deleted records, updates existing keys, and retains target-only rows.
        success(service.start(new SnapshotService.Request(before,after,List.of("ITEMS"),"APPEND"),false));
        try(var c=databases.open(after);var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*),MAX(CASE WHEN ID=3 THEN NOTE END) FROM ITEMS")){r.next();assertThat(r.getInt(1)).isEqualTo(642);assertThat(r.getString(2)).isEqualTo("name3");}
        success(service.start(new SnapshotService.Request(before,after,List.of("ITEMS"),"TRUNCATE"),false));
        var equal=service.start(new SnapshotService.Request(before,after,List.of("ITEMS"),null),true);success(equal);
        assertThat(service.job(equal.id()).processed()).isEqualTo(640);
        assertThat(service.job(equal.id()).beforeOnly()).isZero();assertThat(service.job(equal.id()).afterOnly()).isZero();
        var equalTable=(Map<?,?>)((List<?>)reports.summary(equal.id()).get("tables")).get(0);assertThat(equalTable.get("status")).isEqualTo("No difference");assertThat(equalTable.get("unchangedCount")).isEqualTo(640);
        assertThat(await(service.start(capture,false)).status()).isEqualTo("FAILED");
    }
    @Test void rejectsMissingKeysIgnoredKeysAndSameDatabaseAliasesAndSupportsEmptyTables() throws Exception {
        String name=database();long first=connection(name),alias=connection(name),other=connection(database());
        try(var c=databases.open(first);var s=c.createStatement()){s.execute("CREATE TABLE EMPTY_TABLE(ID INT PRIMARY KEY,VALUE_N DECIMAL(10,2))");s.execute("CREATE TABLE NO_KEY(VALUE_N INT)");}
        assertThatThrownBy(()->configure(first,List.of(new SnapshotService.Config("NO_KEY",List.of())))).hasMessageContaining("primary key");
        assertThatThrownBy(()->configure(first,List.of(new SnapshotService.Config("EMPTY_TABLE",List.of("ID"))))).hasMessageContaining("cannot be ignored");
        configure(first,List.of(new SnapshotService.Config("EMPTY_TABLE",List.of())));
        assertThatThrownBy(()->service.inspect(new SnapshotService.Request(first,alias,List.of("EMPTY_TABLE"),"CREATE"))).hasMessageContaining("same database");
        success(service.start(new SnapshotService.Request(first,other,List.of("EMPTY_TABLE"),"CREATE"),false));
        var compare=service.start(new SnapshotService.Request(first,other,List.of("EMPTY_TABLE"),null),true);success(compare);
        assertThat(((Map<?,?>)((List<?>)reports.summary(compare.id()).get("tables")).get(0)).get("status")).isEqualTo("No difference");
        assertThatThrownBy(()->reports.page(compare.id(),"EMPTY_TABLE","differences",-1,10)).hasMessageContaining("page size");
        assertThatThrownBy(()->reports.summary("../outside")).hasMessageContaining("Invalid snapshot");
        assertThat(configure(first,List.of())).isEmpty();
    }
    @Test void registersDictionaryTablesSeparatelyFromEdacAndEnforcesSelection() throws Exception {
        long first=connection(database()),second=connection(database());
        try(var c=databases.open(first);var s=c.createStatement()){
            s.execute("CREATE TABLE SNAP_ONLY(ID INT PRIMARY KEY,AUDIT_N INT)");s.execute("CREATE TABLE OTHER_TABLE(ID INT PRIMARY KEY)");s.execute("CREATE TABLE NO_KEY(VALUE_N INT)");
            s.execute("CREATE SCHEMA OTHER_OWNER");s.execute("CREATE TABLE OTHER_OWNER.HIDDEN_TABLE(ID INT PRIMARY KEY)");s.execute("CREATE VIEW TABLE_VIEW AS SELECT * FROM SNAP_ONLY");
        }
        registry.append(first,List.of("EDAC_ONLY"),"MANUAL");
        assertThat(service.discoverTables(first)).containsExactly("NO_KEY","OTHER_TABLE","SNAP_ONLY");assertThat(service.registeredTables(first)).isEmpty();
        assertThatThrownBy(()->service.configure(first,List.of(new SnapshotService.Config("SNAP_ONLY",List.of())))).hasMessageContaining("registered Snapshot table");
        mvc.perform(post("/api/snapshot/tables/"+first).with(csrf()).contentType("application/json").content("[\"SNAP_ONLY\"]")).andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("SNAP_ONLY"));
        service.configure(first,List.of(new SnapshotService.Config("SNAP_ONLY",List.of("AUDIT_N"))));
        assertThat(service.registeredTables(second)).isEmpty();assertThat(registry.tables(first)).extracting(Models.TableEntry::name).containsExactly("EDAC_ONLY");
        assertThatThrownBy(()->service.registerTables(first,List.of("OTHER_TABLE","NO_KEY"))).hasMessageContaining("primary key");
        assertThat(service.registeredTables(first)).containsExactly("SNAP_ONLY");assertThat(service.config(first)).hasSize(1);
        assertThatThrownBy(()->service.columns(first,"OTHER_TABLE")).hasMessageContaining("registered Snapshot table");
        mvc.perform(get("/api/snapshot/tables/"+first+"/discover")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        service.registerTables(first,List.of("OTHER_TABLE"));assertThat(service.config(first)).isEmpty();assertThat(registry.tables(first)).hasSize(1);
        mvc.perform(post("/api/snapshot/tables/"+first).contentType("application/json").content("[]")).andExpect(status().isForbidden());
    }
    @Test void runsNamedBatchesWithMoreThanThreeTablesAndPreservesReportBatchNames() throws Exception {
        long first=connection(database()),second=connection(database()),other=connection(database());
        List<String> names=List.of("BATCH_A","BATCH_B","BATCH_C","BATCH_D");
        try(var c=databases.open(first);var s=c.createStatement()){for(String name:names){s.execute("CREATE TABLE "+name+"(ID INT PRIMARY KEY,VALUE_N INT)");s.execute("INSERT INTO "+name+" VALUES(1,10)");}}
        service.registerTables(first,names);service.configure(first,names.stream().map(name->new SnapshotService.Config(name,List.of())).toList());
        var batch=service.saveBatch(first,new SnapshotService.BatchInput(null,"Release checks",names));
        service.saveBatch(first,new SnapshotService.BatchInput(null,"Shared table",List.of("BATCH_A")));
        assertThat(service.registeredTables(first)).hasSize(4);assertThat(service.config(first)).hasSize(4);
        assertThatThrownBy(()->service.saveBatch(first,new SnapshotService.BatchInput(null,"Release checks",names))).hasMessageContaining("already exists");
        assertThatThrownBy(()->service.saveBatch(first,new SnapshotService.BatchInput(null,"../invalid",names))).hasMessageContaining("Batch names");
        assertThatThrownBy(()->service.saveBatch(first,new SnapshotService.BatchInput(null,"Empty",List.of()))).hasMessageContaining("at least one");
        assertThatThrownBy(()->service.start(new SnapshotService.Request(first,second,List.of("BATCH_A"),null,batch.id()),true)).hasMessageContaining("complete saved batch");
        assertThatThrownBy(()->service.start(new SnapshotService.Request(other,second,List.of(),null,batch.id()),true)).hasMessageContaining("batch not found");
        var capture=new SnapshotService.Request(first,second,List.of(),"CREATE",batch.id());assertThat(service.inspect(capture)).hasSize(4);success(service.start(capture,false));
        assertThat(service.batches(second)).anySatisfy(b->{assertThat(b.name()).isEqualTo("Release checks");assertThat(b.tables()).containsExactlyElementsOf(names);});
        var job=service.start(new SnapshotService.Request(first,second,List.of(),null,batch.id()),true);success(job);assertThat(job.id()).startsWith("Release_checks_");
        var report=reports.summary(job.id());assertThat(((Map<?,?>)report.get("batch")).get("name")).isEqualTo("Release checks");assertThat((List<?>)report.get("tables")).hasSize(4);
        assertThat(directory.resolve("snapshot/json/"+job.id()+".json")).exists();assertThat(reports.zip(job.id()).getFileName().toString()).isEqualTo(job.id()+".zip");
        try(var zip=new ZipFile(reports.zip(job.id()).toFile())){assertThat(zip.size()).isEqualTo(4);}
        service.saveBatch(first,new SnapshotService.BatchInput(batch.id(),"Renamed",names));service.removeBatch(first,batch.id());
        assertThat(((Map<?,?>)reports.summary(job.id()).get("batch")).get("name")).isEqualTo("Release checks");assertThat(service.registeredTables(first)).hasSize(4);
        mvc.perform(post("/api/snapshot/batches/"+first).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void readOnlyConnectionsRejectEveryCaptureModeBeforeAnyDestinationChanges() throws Exception {
        long source=connection(database()),target=connection(database());
        try(var c=databases.open(source);var s=c.createStatement()){s.execute("CREATE TABLE PROTECTED_TABLE(ID INT PRIMARY KEY)");s.execute("INSERT INTO PROTECTED_TABLE VALUES(1)");s.execute("CREATE TABLE NEW_TABLE(ID INT PRIMARY KEY)");}
        try(var c=databases.open(target);var s=c.createStatement()){s.execute("CREATE TABLE PROTECTED_TABLE(ID INT PRIMARY KEY)");s.execute("INSERT INTO PROTECTED_TABLE VALUES(99)");}
        configure(source,List.of(new SnapshotService.Config("PROTECTED_TABLE",List.of()),new SnapshotService.Config("NEW_TABLE",List.of())));
        access(source,true);access(target,true);
        for(String mode:List.of("CREATE","APPEND","TRUNCATE")){
            var request=new SnapshotService.Request(source,target,List.of("PROTECTED_TABLE","NEW_TABLE"),mode);
            assertThatThrownBy(()->service.start(request,false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("read-only");
            mvc.perform(post("/api/snapshot/capture").with(csrf()).contentType("application/json").content(json.writeValueAsString(request))).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("read-only")));
        }
        assertThatThrownBy(()->service.inspect(new SnapshotService.Request(source,target,List.of("PROTECTED_TABLE"),"TRUNCATE"))).hasMessageContaining("read-only");
        try(var c=databases.open(target);var s=c.createStatement();var r=s.executeQuery("SELECT ID FROM PROTECTED_TABLE")){assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(99);assertThat(r.next()).isFalse();assertThat(databases.exists(c,target,"NEW_TABLE")).isFalse();}
        // Both comparison sides may be read-only; capturing from a read-only source is also valid.
        success(service.start(new SnapshotService.Request(source,target,List.of("PROTECTED_TABLE"),null),true));
        access(target,false);access(target,null);assertThat(registry.connection(target).readOnly()).isFalse();
        success(service.start(new SnapshotService.Request(source,target,List.of("PROTECTED_TABLE","NEW_TABLE"),"TRUNCATE"),false));
        assertThat(registry.connection(source).readOnly()).isTrue();
        access(target,true);access(target,null);assertThat(registry.connection(target).readOnly()).isTrue();
        var defaultProfile=registry.save(new Models.ConnectionInput("Default readonly "+UUID.randomUUID(),"H2",null,null,"MEMORY",database(),"sa","PUBLIC",""));assertThat(defaultProfile.readOnly()).isTrue();
    }
    @Test void explicitlyResetsUnreadableH2CredentialToEmptyAndPreservesItOnLaterEdits() throws Exception {
        long id=connection(database());var p=registry.connection(id);
        configDb.update("UPDATE connection_profile SET password_ciphertext='v1:unreadable' WHERE id=?",id);
        registry.update(id,new Models.ConnectionInput(p.name(),"H2",p.host(),p.port(),p.connectionType(),p.databaseName(),p.username(),"CHANGED_SCHEMA","",null));
        assertThat(registry.connection(id).schema()).isEqualTo("CHANGED_SCHEMA");
        assertThatThrownBy(()->registry.connectionPassword(id)).hasMessageContaining("Cannot decrypt");
        registry.update(id,new Models.ConnectionInput(p.name(),"H2",p.host(),p.port(),p.connectionType(),p.databaseName(),p.username(),"","",null,true));
        assertThat(registry.test(id).connected()).isTrue();
        registry.update(id,new Models.ConnectionInput(p.name()+" edited","H2",p.host(),p.port(),p.connectionType(),p.databaseName(),p.username(),p.schema(),"",null));
        assertThat(registry.test(id).connected()).isTrue();
        assertThatThrownBy(()->registry.update(id,new Models.ConnectionInput(p.name(),"H2",p.host(),p.port(),p.connectionType(),p.databaseName(),p.username(),p.schema(),"secret",null,true))).hasMessageContaining("empty password field");
    }
    @Test void inspectionIdentifiesUnreadableSourceOrDestinationAndSucceedsAfterBlankPasswordReset() throws Exception {
        long source=connection(database()),target=connection(database());
        try(var c=databases.open(source);var s=c.createStatement()){s.execute("CREATE TABLE INSPECT_CREDENTIALS(ID INT PRIMARY KEY)");}
        configure(source,List.of(new SnapshotService.Config("INSPECT_CREDENTIALS",List.of())));
        var request=new SnapshotService.Request(source,target,List.of("INSPECT_CREDENTIALS"),"CREATE");
        for(long id:List.of(source,target)){
            var profile=registry.connection(id);
            configDb.update("UPDATE connection_profile SET password_ciphertext='v1:unreadable' WHERE id=?",id);
            long other=id==source?target:source;assertThat(registry.test(other).connected()).isTrue();
            assertThatThrownBy(()->service.inspect(request)).hasMessageContaining(profile.name()).hasMessageContaining("Use blank password");
            registry.update(id,new Models.ConnectionInput(profile.name(),"H2",profile.host(),profile.port(),profile.connectionType(),profile.databaseName(),profile.username(),profile.schema(),"",null,true));
            assertThat(registry.test(id).connected()).isTrue();
            assertThat(service.inspect(request)).singleElement().satisfies(row->assertThat(row).containsEntry("exists",false));
        }
        success(service.start(request,false));
        // Recreate credential readers with a different key, as after restarting from another folder.
        var environment=new org.springframework.mock.env.MockEnvironment();environment.setActiveProfiles("local");
        var replacementCipher=new CredentialCipher("",directory.resolve("replacement-"+UUID.randomUUID()+".key").toString(),environment);
        var oracleFactory=new OracleConnectionFactory();
        var restartedRegistry=new RegistryService(configDb,replacementCipher,new OracleSource(oracleFactory),new H2Source());
        var restartedDatabases=new SnapshotDatabase(restartedRegistry,configDb,replacementCipher,oracleFactory);
        for(long id:List.of(source,target)){
            assertThat(restartedRegistry.test(id).connected()).isTrue();
            try(var c=restartedDatabases.open(id);var s=c.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM INSPECT_CREDENTIALS")){assertThat(rows.next()).isTrue();}
        }
        success(service.start(new SnapshotService.Request(source,target,List.of("INSPECT_CREDENTIALS"),null),true));
    }
    @Test void rollsBackDestinationRowsWhenTruncateImportFails() throws Exception {
        long first=connection(database()),second=connection(database());
        try(var c=databases.open(first);var s=c.createStatement()){s.execute("CREATE TABLE LIMITED_TABLE(ID INT PRIMARY KEY,NOTE VARCHAR(100))");s.execute("INSERT INTO LIMITED_TABLE VALUES(1,'too long for destination')");}
        try(var c=databases.open(second);var s=c.createStatement()){s.execute("CREATE TABLE LIMITED_TABLE(ID INT PRIMARY KEY,NOTE VARCHAR(3))");s.execute("INSERT INTO LIMITED_TABLE VALUES(99,'old')");}
        configure(first,List.of(new SnapshotService.Config("LIMITED_TABLE",List.of())));
        var failed=await(service.start(new SnapshotService.Request(first,second,List.of("LIMITED_TABLE"),"TRUNCATE"),false));assertThat(failed.status()).isEqualTo("FAILED");
        try(var c=databases.open(second);var s=c.createStatement();var r=s.executeQuery("SELECT ID,NOTE FROM LIMITED_TABLE")){assertThat(r.next()).isTrue();assertThat(r.getInt(1)).isEqualTo(99);assertThat(r.getString(2)).isEqualTo("old");assertThat(r.next()).isFalse();}
    }
    @Test void streamsMoreThanOneHundredDifferenceRowsToJsonAndExcel() throws Exception {
        long first=connection(database()),second=connection(database());
        for(long id:List.of(first,second))try(var c=databases.open(id);var s=c.createStatement()){s.execute("CREATE TABLE MANY_CHANGES(ID INT PRIMARY KEY,NOTE VARCHAR(50))");s.execute("INSERT INTO MANY_CHANGES SELECT X,'old' FROM SYSTEM_RANGE(1,250)");}
        try(var c=databases.open(second);var s=c.createStatement()){s.execute("UPDATE MANY_CHANGES SET NOTE='new'");}
        configure(first,List.of(new SnapshotService.Config("MANY_CHANGES",List.of())));var job=service.start(new SnapshotService.Request(first,second,List.of("MANY_CHANGES"),null),true);success(job);
        assertThat(reports.page(job.id(),"MANY_CHANGES","differences",24,10).get("total")).isEqualTo(250L);assertThat((List<?>)reports.page(job.id(),"MANY_CHANGES","differences",24,10).get("rows")).hasSize(10);
        try(var zip=new ZipFile(reports.zip(job.id()).toFile());var book=new XSSFWorkbook(zip.getInputStream(zip.entries().nextElement()))){assertThat(book.getSheet("Differences").getLastRowNum()).isEqualTo(250);assertThat(book.getSheet("Differences").getRow(0).getCell(0).getStringCellValue()).isEqualTo("ID");}
    }
    @Test void preservesBinaryNullAndTimestampValuesOnCopyAndExportsMultipleWorkbooks() throws Exception {
        long first=connection(database()),second=connection(database());
        try(var c=databases.open(first);var s=c.createStatement()){
            s.execute("CREATE TABLE PAYLOADS(ID INT PRIMARY KEY,DATA_B BLOB,DETAIL_C CLOB,WHEN_D TIMESTAMP(9))");
            s.execute("INSERT INTO PAYLOADS VALUES(1,X'0102FF','text',TIMESTAMP '2026-01-01 12:34:56.123456789'),(2,NULL,NULL,NULL)");
            s.execute("CREATE TABLE SECOND_TABLE(ID INT PRIMARY KEY,VALUE_N DECIMAL(10,2))");
        }
        configure(first,List.of(new SnapshotService.Config("PAYLOADS",List.of()),new SnapshotService.Config("SECOND_TABLE",List.of())));
        success(service.start(new SnapshotService.Request(first,second,List.of("PAYLOADS","SECOND_TABLE"),"CREATE"),false));
        var job=service.start(new SnapshotService.Request(first,second,List.of("PAYLOADS","SECOND_TABLE"),null),true);success(job);
        try(var entries=new ZipFile(reports.zip(job.id()).toFile())){assertThat(entries.size()).isEqualTo(2);}
        assertThat((List<?>)reports.summary(job.id()).get("tables")).hasSize(2);
    }
}

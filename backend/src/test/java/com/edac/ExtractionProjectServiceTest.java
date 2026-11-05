package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.edac.Models.*;
import static com.edac.ExtractionProjectService.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import java.util.zip.*;
import javax.tools.ToolProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class ExtractionProjectServiceTest {
 @TempDir Path directory;
 RegistryService registry=mock(RegistryService.class);
 JdbcTemplate db=mock(JdbcTemplate.class);
 ObjectMapper json=new ObjectMapper();
 ExtractionProjectService service=new ExtractionProjectService(registry,db,json);
 @BeforeEach void fixture(){
  when(registry.connection(1)).thenReturn(new ConnectionProfile(1,"Oracle","ORACLE","localhost",1521,"SERVICE","DB","jdbc:oracle:thin:@localhost:1521/DB","APP","APP",true));
  when(registry.table(1,10)).thenReturn(new TableEntry(10,1,"APP","CUSTOMERS","MANUAL","", "Delimited","Full","Daily"));
  when(registry.mappingFields(1,10)).thenReturn(List.of(field(1,"ID","NUMBER"),field(2,"NAME","VARCHAR2"),field(3,"NOTES","CLOB"),field(4,"EXCLUDED","VARCHAR2")));
  when(db.queryForList("SELECT column_name FROM column_exclusion WHERE table_id=?",String.class,10L)).thenReturn(List.of("EXCLUDED"));
 }
 MappingField field(int pos,String name,String type){return new MappingField(new ColumnDetail(pos,name,type,100,100,20,0,true,true),"","","");}
 Request request(String engine,Delta delta){return new Request(List.of(10L),engine,"\u0007",2,"PBI","SG","./output","","{schema}_{table}_{businessDate}.dat",engine.equals("SQLCL")?"/opt/sqlcl/bin/sql":null,Map.of("10",new TableOption("Daily","ID",delta)));}
 Map<String,String> unzip(byte[] bytes) throws Exception {
  var files=new LinkedHashMap<String,String>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){for(ZipEntry entry;(entry=zip.getNextEntry())!=null;)files.put(entry.getName().substring(entry.getName().indexOf('/')+1),new String(zip.readAllBytes(),StandardCharsets.UTF_8));}return files;
 }
 @Test void generatesOnlySelectedMethodAndExactPrivateParent() throws Exception {
  for(String engine:List.of("JDBC","SQLCL")){
   var files=unzip(service.generate(1,request(engine,null)));String pom=files.get("pom.xml");
   assertThat(pom).contains("<groupId>com.uob.ufw</groupId>","<artifactId>uob-ms-starter</artifactId>","<version>3.6.15.2.RELEASE</version>","<groupId>com.edac</groupId>","<artifactId>extraction</artifactId>");
   assertThat(files.keySet()).contains("src/main/java/com/edac/extraction/"+(engine.equals("JDBC")?"JdbcExtractor.java":"SqlclExtractor.java")).doesNotContain("src/main/java/com/edac/extraction/"+(engine.equals("JDBC")?"SqlclExtractor.java":"JdbcExtractor.java"));
   String sql=files.get("src/main/resources/sql/t10.sql");assertThat(sql).contains("\"APP\".\"CUSTOMERS\"").doesNotContain("EXCLUDED","WHERE");
   assertThat(files.get("src/main/resources/application.properties")).doesNotContain("test-secret");
   if(engine.equals("SQLCL"))assertThat(pom).doesNotContain("ojdbc11","spring-boot-starter-jdbc");
   else assertThat(pom).doesNotContain("commons-csv");
   compile(files,engine);
  }
 }
 @Test void predicatesUseBindsAndExcludedColumnsCannotBecomeDeltaOrSum() throws Exception {
  assertThat(ExtractionProjectService.sqlclExpression(new ColumnDetail(1,"TS","TIMESTAMP WITH TIME ZONE",13,null,null,null,true,true))).contains("SYS_EXTRACT_UTC");
  assertThat(ExtractionProjectService.sqlclExpression(new ColumnDetail(1,"RAW_VALUE","RAW",2000,null,null,null,true,true))).contains("TO_CLOB('V')");
  when(registry.table(1,10)).thenReturn(new TableEntry(10,1,"APP","CUSTOMERS","MANUAL","", "Delimited","Delta","Daily"));
  Delta rule=new Delta("RANGE","ID","CHECKPOINT","PARAMETER","0","",">","<=");
  assertThat(unzip(service.generate(1,request("JDBC",rule))).get("src/main/resources/sql/t10.sql")).contains("WHERE \"ID\" > ? AND \"ID\" <= ?");
  assertThat(unzip(service.generate(1,request("SQLCL",rule))).get("src/main/resources/sql/t10.sql")).contains("TO_NUMBER(:edac_from)","TO_NUMBER(:edac_to)");
  assertThatThrownBy(()->service.generate(1,request("JDBC",new Delta("FLAG","EXCLUDED","PARAMETER","PARAMETER","","Y",">","<=")))).hasMessageContaining("included delta column");
  assertThatThrownBy(()->service.generate(1,request("JDBC",new Delta("RANGE","ID","BUSINESS_DATE","BUSINESS_DATE_END","","",">=","<")))).hasMessageContaining("Numeric ranges");
 }
 @Test void derivesStrategyFrequencyAndFirstIncludedNumericFieldFromRegistry() throws Exception {
  when(registry.mappingFields(1,10)).thenReturn(List.of(field(1,"NAME","VARCHAR2"),field(2,"EXCLUDED","NUMBER"),field(3,"AMOUNT","NUMBER"),field(4,"ID","NUMBER")));
  var stale=new Request(List.of(10L),"JDBC","|",1,"PBI","SG","./output","","{table}.dat",null,Map.of("10",new TableOption("WRONG","ID",new Delta("FLAG","ID","PARAMETER","PARAMETER","","1",">","<="))));
  var defs=json.readTree(unzip(service.generate(1,stale)).get("src/main/resources/extraction-definitions.json")).get("tables").get(0);
  assertThat(defs.get("controlSumColumn").asText()).isEqualTo("AMOUNT");assertThat(defs.get("group").asText()).isEqualTo("Daily");assertThat(defs.get("delta").get("mode").asText()).isEqualTo("FULL");
  when(registry.mappingFields(1,10)).thenReturn(List.of(field(1,"NAME","VARCHAR2")));
  assertThat(json.readTree(unzip(service.generate(1,request("JDBC",null))).get("src/main/resources/extraction-definitions.json")).get("tables").get(0).get("controlSumColumn").asText()).isEmpty();
 }
 @Test void deltaTablesRequireRangeOrFlagAndExplicitDateConversions() throws Exception {
  when(registry.table(1,10)).thenReturn(new TableEntry(10,1,"APP","CUSTOMERS","MANUAL","", "Delimited","Delta","Daily"));
  assertThatThrownBy(()->service.generate(1,request("JDBC",null))).hasMessageContaining("Range or Flag");
  when(registry.mappingFields(1,10)).thenReturn(List.of(field(1,"UPDATED","DATE")));
  var date=new Delta("RANGE","UPDATED","BUSINESS_DATE","BUSINESS_DATE_END","","",">=","<");
  assertThat(unzip(service.generate(1,request("SQLCL",date))).get("src/main/resources/sql/t10.sql")).contains("TO_DATE(:edac_from, 'YYYY-MM-DD HH24:MI:SS')","TO_DATE(:edac_to, 'YYYY-MM-DD HH24:MI:SS')");
 }
 @Test void generatedJdbcRunsAcrossFetchBoundaryAndResumesDeliveryWithoutRequery() throws Exception {
  var files=unzip(service.generate(1,request("JDBC",null)));
  files.put("src/test/java/com/edac/extraction/RuntimeHarness.java",harness());
  Path classes=compile(files,"runtime");
  try(var loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader())){
   loader.loadClass("com.edac.extraction.RuntimeHarness").getMethod("verify",Path.class).invoke(null,directory.resolve("run"));
  }
 }
 @Test void sqlclUsesExternalProcessAndDiskCsvWithoutPersistingCredentials() throws Exception {
  var files=unzip(service.generate(1,request("SQLCL",null)));Path classes=compile(files,"sqlcl-process");Path work=directory.resolve("sqlcl-work");Files.createDirectories(work);
  var stdin=new ByteArrayOutputStream();Process process=mock(Process.class);when(process.getOutputStream()).thenReturn(stdin);when(process.waitFor()).thenReturn(0);when(process.exitValue()).thenReturn(0);
  try(var loader=new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},getClass().getClassLoader());
      var mocked=mockConstruction(ProcessBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->{
       when(builder.start()).thenAnswer(call->{Files.writeString(work.resolve("data.csv"),"C0001,C0002,C0003\nV12,Vname,Vnote~EDAC_LOB_END~\n");return process;});
      })) {
   var definitionsType=loader.loadClass("com.edac.extraction.Definitions");Object definitions=json.readValue(files.get("src/main/resources/extraction-definitions.json"),definitionsType);
   Object table=((List<?>)definitionsType.getMethod("tables").invoke(definitions)).get(0);
   var env=new org.springframework.mock.env.MockEnvironment().withProperty("extraction.sqlcl.executable","/external/sqlcl/bin/sql").withProperty("extraction.sqlcl.connect","APP/test-secret@DB");
   var extractorType=loader.loadClass("com.edac.extraction.SqlclExtractor");Object extractor=extractorType.getConstructor(org.springframework.core.env.Environment.class).newInstance(env);
   var windowType=loader.loadClass("com.edac.extraction.Definitions$Window");Object window=windowType.getConstructor(List.class,String.class).newInstance(List.of(),null);
   Path part=directory.resolve("sqlcl-output.dat");Object stats=extractorType.getMethod("extract",table.getClass(),windowType,Path.class,Path.class,java.time.LocalDate.class).invoke(extractor,table,window,part,work,java.time.LocalDate.of(2026,11,4));
   assertThat(stats.getClass().getMethod("records").invoke(stats)).isEqualTo(1L);
   assertThat(Files.readString(part)).contains("D\u000712\u0007name\u0007\"note\"\nT\u00071\u000712\n");
   assertThat(Files.readString(work.resolve("extract.sql"))).contains("SET TERMOUT OFF","SET MAXSPOOLROWSTRUNCATE OFF","SPOOL data.csv").doesNotContain("test-secret","CONNECT");
   assertThat(stdin.toString(StandardCharsets.UTF_8)).contains("CONNECT APP/test-secret@DB","@extract.sql");assertThat(mocked.constructed()).hasSize(1);
  }
 }
 Path compile(Map<String,String> files,String name) throws Exception {
  Path root=directory.resolve(name),classes=root.resolve("classes");Files.createDirectories(classes);var args=new ArrayList<String>(List.of("--release","17","-encoding","UTF-8","-classpath",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),"-d",classes.toString()));
  for(var entry:files.entrySet()){
   Path path=root.resolve(entry.getKey());Files.createDirectories(path.getParent());Files.writeString(path,entry.getValue());
   if(entry.getKey().endsWith(".java"))args.add(path.toString());
   else if(entry.getKey().startsWith("src/main/resources/")){Path to=classes.resolve(entry.getKey().substring("src/main/resources/".length()));Files.createDirectories(to.getParent());Files.copy(path,to,StandardCopyOption.REPLACE_EXISTING);}
  }
  var errors=new ByteArrayOutputStream();int result=ToolProvider.getSystemJavaCompiler().run(null,null,errors,args.toArray(String[]::new));assertThat(result).withFailMessage(errors.toString(StandardCharsets.UTF_8)).isZero();return classes;
 }
 String harness(){return """
package com.edac.extraction;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.DefaultApplicationArguments;
public class RuntimeHarness {
 public static void verify(Path root) throws Exception {
  Files.createDirectories(root);
  var ds=new DriverManagerDataSource("jdbc:h2:mem:extract_"+UUID.randomUUID()+";MODE=Oracle;DB_CLOSE_DELAY=-1","sa","");
  var db=new JdbcTemplate(ds);db.execute("CREATE SCHEMA APP");db.execute("CREATE TABLE APP.CUSTOMERS(ID NUMBER, NAME VARCHAR2(100), NOTES CLOB)");
  for(int i=1;i<=2001;i++)db.update("INSERT INTO APP.CUSTOMERS VALUES(?,?,?)",i,i==1?"a|b\\n\\\"c":i==2?null:"name","notes");
  Definitions defs;try(var in=RuntimeHarness.class.getResourceAsStream("/extraction-definitions.json")){defs=new ObjectMapper().readValue(in,Definitions.class);}
  var table=defs.tables().get(0);var env=new MockEnvironment().withProperty("extraction.delimiter","|").withProperty("extraction.business-date","20261104").withProperty("extraction.output-directory",root.resolve("output").toString()).withProperty("extraction.request-id","test").withProperty("extraction.delivery-directory",root.resolve("delivery").toString());
  var jdbc=new JdbcExtractor(ds,env);var calls=new AtomicInteger();
  Extractor counted=(t,w,p,work,date)->{calls.incrementAndGet();return jdbc.extract(t,w,p,work,date);};
  Path delivery=root.resolve("delivery");Files.createDirectories(delivery);Path delivered=delivery.resolve("APP_CUSTOMERS_20261104.dat");Files.writeString(delivered,"existing");
  var first=new RunCoordinator(env,counted);first.run(new DefaultApplicationArguments());assertEquals(1,first.exitCode());assertEquals(1,calls.get());
  Path output=root.resolve("output/runs/20261104/test/APP_CUSTOMERS_20261104.dat");String content=Files.readString(output);
  assertTrue(content.startsWith("H|PBI|SG|20261104|"));assertTrue(content.contains("D|1|\\\"a|b \\\"\\\"c\\\"|\\\"notes\\\""));assertTrue(content.endsWith("T|2001|2003001\\n"));
  assertTrue(Files.exists(root.resolve("output/runs/20261104/test/manifest.json")));
  Files.delete(delivered);db.execute("DROP TABLE APP.CUSTOMERS");
  var second=new RunCoordinator(env,counted);second.run(new DefaultApplicationArguments());assertEquals(0,second.exitCode());assertEquals(1,calls.get());assertEquals(content,Files.readString(delivered));
  var third=new RunCoordinator(env,counted);third.run(new DefaultApplicationArguments());assertEquals(0,third.exitCode());assertEquals(1,calls.get());
  var delta=new Definitions.Delta("RANGE","ID","NUMBER","CHECKPOINT","PARAMETER","0","",">","<=");
  var deltaTable=new Definitions.Table(table.id(),table.schema(),table.name(),table.group(),table.columns(),delta,table.controlSumColumn(),table.sqlResource());
  Path defsPath=Path.of(RuntimeHarness.class.getResource("/extraction-definitions.json").toURI());
  Files.writeString(defsPath,new ObjectMapper().writeValueAsString(new Definitions("JDBC",List.of(deltaTable))));
  Files.writeString(Path.of(RuntimeHarness.class.getResource(table.sqlResource()).toURI()),"SELECT ID, NAME, NOTES FROM APP.CUSTOMERS WHERE ID > ? AND ID <= ?");
  db.execute("CREATE TABLE APP.CUSTOMERS(ID NUMBER, NAME VARCHAR2(100), NOTES CLOB)");db.update("INSERT INTO APP.CUSTOMERS VALUES(1,'one','note')");db.update("INSERT INTO APP.CUSTOMERS VALUES(2,'two','note')");
  env.withProperty("extraction.request-id","delta-1").withProperty("extraction.parameters.t10-to","1");
  var deltaFailure=new RunCoordinator(env,counted);deltaFailure.run(new DefaultApplicationArguments());assertEquals(1,deltaFailure.exitCode());assertEquals(2,calls.get());
  Path checkpoint=root.resolve("output/checkpoints/t10.properties");assertFalse(Files.exists(checkpoint));
  Files.delete(delivered);
  var deltaRetry=new RunCoordinator(env,counted);deltaRetry.run(new DefaultApplicationArguments());assertEquals(0,deltaRetry.exitCode());assertEquals(2,calls.get());assertEquals("1",RunCoordinator.load(checkpoint).getProperty("value"));
  env.withProperty("extraction.request-id","delta-2").withProperty("extraction.parameters.t10-to","2").withProperty("extraction.delivery-directory","");
  var nextDelta=new RunCoordinator(env,counted);nextDelta.run(new DefaultApplicationArguments());assertEquals(0,nextDelta.exitCode());assertEquals(3,calls.get());assertEquals("2",RunCoordinator.load(checkpoint).getProperty("value"));
  assertTrue(Files.readString(root.resolve("output/runs/20261104/delta-2/APP_CUSTOMERS_20261104.dat")).endsWith("T|1|2\\n"));
  env.withProperty("extraction.request-id","delta-empty");var emptyDelta=new RunCoordinator(env,counted);emptyDelta.run(new DefaultApplicationArguments());assertEquals(0,emptyDelta.exitCode());assertEquals(4,calls.get());
  assertTrue(Files.readString(root.resolve("output/runs/20261104/delta-empty/APP_CUSTOMERS_20261104.dat")).endsWith("T|0|0\\n"));
  for(String type:List.of("DATE","TIMESTAMP")) {
   var businessDelta=new Definitions.Delta("RANGE","UPDATED",type,"BUSINESS_DATE","BUSINESS_DATE_END","","",">=","<");
   var businessTable=new Definitions.Table(table.id(),table.schema(),table.name(),table.group(),table.columns(),businessDelta,table.controlSumColumn(),table.sqlResource());
   Files.writeString(defsPath,new ObjectMapper().writeValueAsString(new Definitions("JDBC",List.of(businessTable))));
   var dates=new java.util.ArrayList<Definitions.Window>();Extractor capture=(t,w,p,work,day)->{dates.add(w);try(var out=new DelimitedOutput(p,"|","PBI","SG",day,t)){return out.finish();}};
   for(String date:List.of("20261105","2026-12-31")) {
    env.withProperty("extraction.business-date",date).withProperty("extraction.request-id","business-"+type+"-"+date).withProperty("extraction.parameters.t10-to","");
    var run=new RunCoordinator(env,capture);run.run(new DefaultApplicationArguments());assertEquals(0,run.exitCode());
   }
   String suffix=type.equals("TIMESTAMP")?".0":"";
   assertEquals(List.of("2026-11-05 00:00:00"+suffix,"2026-11-06 00:00:00"+suffix),dates.get(0).values());
   assertEquals(List.of("2026-12-31 00:00:00"+suffix,"2027-01-01 00:00:00"+suffix),dates.get(1).values());
  }
  // Exercise the SQLcl marker decoder and custom multi-character delimiter without launching SQLcl.
  Path decoded=root.resolve("decoded.dat");try(var out=new DelimitedOutput(decoded,"||","PBI","SG",LocalDate.of(2026,11,4),table)){out.sqlclRow(Arrays.asList("V1","N","Vquote\\\"line\\n~EDAC_LOB_END~"));out.finish();}
  Path truncated=root.resolve("truncated.dat");try(var out=new DelimitedOutput(truncated,"|","PBI","SG",LocalDate.of(2026,11,4),table)){assertThrows(java.io.IOException.class,()->out.sqlclRow(Arrays.asList("V1","N","Vtruncated")));}
  assertTrue(Files.readString(decoded).contains("D||1||\\\\N||\\\"quote\\\"\\\"line \\\"\\nT||1||1\\n"));
 }
}
""";}
}

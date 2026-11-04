package com.edac;

import static com.edac.Models.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExtractionProjectService {
    public record Delta(String mode,String column,String lowerSource,String upperSource,String initialValue,String flagValue,String lowerOperator,String upperOperator) {}
    public record TableOption(String group,String controlSumColumn,Delta delta) {}
    public record Request(@NotEmpty List<@NotNull @Positive Long> tableIds,@NotNull @Pattern(regexp="JDBC|SQLCL") String engine,
        @NotEmpty String delimiter,@Min(1) int parallelJobs,@NotBlank String systemCode,@NotBlank String countryCode,
        @NotBlank String outputDirectory,String deliveryDirectory,@NotBlank String fileNamePattern,
        String sqlclExecutable,@NotNull Map<String,TableOption> tableOptions) {}
    public record ColumnOption(String name,String dataType,boolean numeric,String deltaType) {}
    private final RegistryService registry;private final JdbcTemplate db;private final ObjectMapper json;
    public ExtractionProjectService(RegistryService registry,JdbcTemplate db,ObjectMapper json){this.registry=registry;this.db=db;this.json=json;}
    static boolean numeric(String type){return type.toUpperCase(Locale.ROOT).matches("NUMBER|NUMERIC|DECIMAL|INTEGER|INT|SMALLINT|BIGINT|TINYINT|FLOAT|BINARY_FLOAT|BINARY_DOUBLE|DOUBLE PRECISION|REAL");}
    private static String q(String name){return "\""+RegistryService.identifier(name)+"\"";}
    private static String deltaType(String type){return numeric(type)?"NUMBER":type.equalsIgnoreCase("DATE")?"DATE":type.toUpperCase(Locale.ROOT).startsWith("TIMESTAMP")?"TIMESTAMP":Set.of("CHAR","NCHAR","VARCHAR","VARCHAR2","NVARCHAR2").contains(type.toUpperCase(Locale.ROOT))?"TEXT":"";}
    private ConnectionProfile oracle(long id){var profile=registry.connection(id);if(!profile.databaseType().equals("ORACLE"))throw new IllegalArgumentException("Extraction project generation requires an Oracle connection.");return profile;}
    private List<ColumnDetail> included(long connection,long id) {
        registry.table(connection,id);var excluded=new HashSet<>(db.queryForList("SELECT column_name FROM column_exclusion WHERE table_id=?",String.class,id));
        return registry.mappingFields(connection,id).stream().map(MappingField::column).filter(c->!excluded.contains(c.name())).toList();
    }
    public List<ColumnOption> columns(long connection,long table){oracle(connection);return included(connection,table).stream().map(c->new ColumnOption(c.name(),c.dataType(),numeric(c.dataType()),deltaType(c.dataType()))).toList();}
    public byte[] generate(long connection,Request input) throws IOException {
        var profile=oracle(connection);
        if(!Set.of("JDBC","SQLCL").contains(input.engine()))throw new IllegalArgumentException("Choose JDBC Cursor or SQLcl.");
        if(input.tableIds().size()!=new HashSet<>(input.tableIds()).size())throw new IllegalArgumentException("Select each table only once.");
        String delimiter=input.delimiter();
        if(delimiter.isEmpty()||delimiter.contains("\"")||delimiter.contains("\r")||delimiter.contains("\n")||delimiter.contains("\0"))throw new IllegalArgumentException("Provide a delimiter without quotes, CR, LF or NUL.");
        if(input.parallelJobs()<1)throw new IllegalArgumentException("Parallel jobs must be positive.");
        if(input.fileNamePattern().contains("/")||input.fileNamePattern().contains("\\")||input.fileNamePattern().isBlank())throw new IllegalArgumentException("Provide a filename pattern without directory separators.");
        if(input.engine().equals("SQLCL")&&(input.sqlclExecutable()==null||input.sqlclExecutable().isBlank()))throw new IllegalArgumentException("Provide the installed SQLcl executable path.");
        var selected=input.tableIds().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        if(!selected.containsAll(input.tableOptions().keySet()))throw new IllegalArgumentException("Table options must belong to selected tables.");
        var tables=new ArrayList<Map<String,Object>>();var queries=new LinkedHashMap<String,String>();
        for(long id:input.tableIds()) {
            var table=registry.table(connection,id);var columns=included(connection,id);if(columns.isEmpty())throw new IllegalArgumentException("No included columns for "+table.name());
            var option=input.tableOptions().getOrDefault(Long.toString(id),new TableOption(table.updateFrequency(),"",null));
            String sum=option.controlSumColumn()==null?"":option.controlSumColumn();
            if(!sum.isBlank()&&columns.stream().noneMatch(c->c.name().equals(sum)&&numeric(c.dataType())))throw new IllegalArgumentException("Control sum must be an included numeric column for "+table.name());
            var delta=delta(table,columns,option.delta());String key="t"+id;var columnDefinitions=new ArrayList<Map<String,Object>>();
            var projection=new ArrayList<String>();boolean sqlcl=input.engine().equals("SQLCL"),hasBlob=false;
            for(int i=0;i<columns.size();i++) {
                var c=columns.get(i);String type=c.dataType().toUpperCase(Locale.ROOT);boolean lob=Set.of("CLOB","NCLOB","BLOB").contains(type);
                // Validate support in both engines before creating a downloadable project.
                String expression=sqlclExpression(c);
                columnDefinitions.add(Map.of("name",c.name(),"type",c.dataType(),"numeric",numeric(c.dataType()),"lob",lob));
                projection.add(sqlcl?expression+" AS \""+String.format(Locale.ROOT,"C%04d",i+1)+"\"":q(c.name()));hasBlob|=type.equals("BLOB");
            }
            String sql="SELECT\n  "+String.join(",\n  ",projection)+"\nFROM "+q(table.schema())+"."+q(table.name())+where(delta,sqlcl)+"\n";
            if(sqlcl&&hasBlob)sql="SET SQLTERMINATOR OFF\nWITH\n"+blobFunction()+"\n"+sql+"/\nSET SQLTERMINATOR ON\n";
            else if(sqlcl)sql+=";\n";
            queries.put("src/main/resources/sql/"+key+".sql",sql);
            var definition=new LinkedHashMap<String,Object>();definition.put("id",key);definition.put("schema",table.schema());definition.put("name",table.name());
            definition.put("group",option.group()==null||option.group().isBlank()?table.updateFrequency():option.group().trim());definition.put("columns",columnDefinitions);
            definition.put("delta",delta);definition.put("controlSumColumn",sum);definition.put("sqlResource","/sql/"+key+".sql");tables.add(definition);
        }
        String root="extraction-"+input.engine().toLowerCase(Locale.ROOT)+"/";var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            put(zip,root+"pom.xml",pom(input.engine()));
            put(zip,root+"src/main/resources/application.properties",configuration(profile,input));
            put(zip,root+"src/main/resources/extraction-definitions.json",json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("engine",input.engine(),"tables",tables)));
            for(var query:queries.entrySet())put(zip,root+query.getKey(),query.getValue());
            for(String file:List.of("Definitions.java","Extractor.java","ExtractionApplication.java","DelimitedOutput.java","RunCoordinator.java"))copy(zip,root,"common",file);
            copy(zip,root,input.engine().toLowerCase(Locale.ROOT),input.engine().equals("JDBC")?"JdbcExtractor.java":"SqlclExtractor.java");
            put(zip,root+"README.md",readme(input.engine(),profile));
            put(zip,root+"start.sh","#!/usr/bin/env bash\nset -euo pipefail\nROOT=\"$(cd -- \"$(dirname -- \"${BASH_SOURCE[0]}\")\" && pwd)\"\nexec java -jar \"$ROOT/target/extraction.jar\" \"$@\"\n");
            put(zip,root+".gitignore","target/\noutput/\n*.log\n.env\n");
            put(zip,root+"src/test/java/com/edac/extraction/OutputContractTest.java",testSource());
        }
        return bytes.toByteArray();
    }
    private static Map<String,String> delta(TableEntry table,List<ColumnDetail> columns,Delta input) {
        if(input==null) {
            if("Delta".equalsIgnoreCase(table.loadStrategy()))throw new IllegalArgumentException("Configure a delta rule for "+table.name()+" or explicitly select Full.");
            input=new Delta("FULL","","BUSINESS_DATE","BUSINESS_DATE_END","","",">=","<");
        }
        String mode=Objects.toString(input.mode(),"");if(!Set.of("FULL","RANGE","FLAG").contains(mode))throw new IllegalArgumentException("Choose Full, Range or Flag for "+table.name());
        if(mode.equals("FULL"))return Map.of("mode","FULL","column","","type","TEXT","lowerSource","PARAMETER","upperSource","PARAMETER","initialValue","","flagValue","","lowerOperator",">=","upperOperator","<");
        String deltaColumn=input.column();
        var column=columns.stream().filter(c->c.name().equals(deltaColumn)).findFirst().orElseThrow(()->new IllegalArgumentException("Choose an included delta column for "+table.name()));
        String type=deltaType(column.dataType());if(type.isEmpty() || mode.equals("RANGE")&&type.equals("TEXT"))throw new IllegalArgumentException("Delta range requires a numeric, date or timestamp column.");
        String lower=Objects.toString(input.lowerSource(),"PARAMETER"),upper=Objects.toString(input.upperSource(),"PARAMETER");
        String lowOp=Objects.toString(input.lowerOperator(),">="),highOp=Objects.toString(input.upperOperator(),"<");
        if(!Set.of("BUSINESS_DATE","CHECKPOINT","PARAMETER").contains(lower)||!Set.of("BUSINESS_DATE_END","PARAMETER").contains(upper))throw new IllegalArgumentException("Invalid delta bound source.");
        if(!Set.of(">",">=").contains(lowOp)||!Set.of("<","<=").contains(highOp))throw new IllegalArgumentException("Invalid delta range operator.");
        if(mode.equals("RANGE")&&type.equals("NUMBER")&&(lower.equals("BUSINESS_DATE")||upper.equals("BUSINESS_DATE_END")))throw new IllegalArgumentException("Numeric ranges require numeric run parameters/checkpoints.");
        if(mode.equals("FLAG")&&(input.flagValue()==null||input.flagValue().isBlank()))throw new IllegalArgumentException("Provide a flag value for "+table.name());
        return Map.of("mode",mode,"column",column.name(),"type",type,"lowerSource",lower,"upperSource",upper,"initialValue",Objects.toString(input.initialValue(),""),"flagValue",Objects.toString(input.flagValue(),""),"lowerOperator",lowOp,"upperOperator",highOp);
    }
    private static String where(Map<String,String> delta,boolean sqlcl) {
        if(delta.get("mode").equals("FULL"))return "";
        String column=q(delta.get("column")),type=delta.get("type");
        if(delta.get("mode").equals("FLAG"))return "\nWHERE "+column+" = "+bind("edac_flag",type,sqlcl);
        return "\nWHERE "+column+" "+delta.get("lowerOperator")+" "+bind("edac_from",type,sqlcl)+" AND "+column+" "+delta.get("upperOperator")+" "+bind("edac_to",type,sqlcl);
    }
    private static String bind(String name,String type,boolean sqlcl) {
        if(!sqlcl)return "?";String bind=":"+name;
        return switch(type){case "NUMBER"->"TO_NUMBER("+bind+")";case "DATE"->"TO_DATE("+bind+", 'YYYY-MM-DD HH24:MI:SS')";case "TIMESTAMP"->"TO_TIMESTAMP("+bind+", 'YYYY-MM-DD HH24:MI:SS.FF')";default->bind;};
    }
    static String sqlclExpression(ColumnDetail c) {
        String name=q(c.name()),type=c.dataType().toUpperCase(Locale.ROOT),value;
        if(numeric(type))value="TO_CHAR("+name+", 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''')";
        else if(type.equals("DATE"))value="TO_CHAR("+name+", 'YYYY-MM-DD', 'NLS_DATE_LANGUAGE=English')";
        else if(type.startsWith("TIMESTAMP"))value="TO_CHAR("+(type.contains("WITH TIME ZONE")?"SYS_EXTRACT_UTC("+name+")":name)+", 'YYYY-MM-DD HH24:MI:SS', 'NLS_DATE_LANGUAGE=English')";
        else if(type.equals("RAW"))value="RAWTOHEX("+name+")";
        else if(type.equals("BLOB"))value="EDAC_BLOB_HEX("+name+")";
        else if(Set.of("VARCHAR2","NVARCHAR2","CHAR","NCHAR","VARCHAR","CLOB","NCLOB").contains(type))value="REPLACE(REPLACE("+name+", CHR(13), ' '), CHR(10), ' ')";
        else throw new IllegalArgumentException("Unsupported extraction type "+type+" for "+c.name()+". Register an extraction view converting it to a supported type.");
        // Scalar values stay scalar. Widen only fields that may exceed Oracle concatenation limits.
        boolean wide=Set.of("CLOB","NCLOB","BLOB").contains(type)||c.characterLength()!=null&&c.characterLength().longValue()>=3900||c.byteLength()!=null&&c.byteLength().longValue()>=(type.equals("RAW")?1950:3900);
        String convert=Set.of("NCHAR","NVARCHAR2","NCLOB").contains(type)?"TO_NCLOB":"TO_CLOB";
        if(Set.of("CLOB","NCLOB","BLOB").contains(type))value+=" || '~EDAC_LOB_END~'";
        return "CASE WHEN "+name+" IS NULL THEN "+(wide?convert+"('N')":"'N'")+" ELSE "+(wide?convert+"('V')":"'V'")+" || "+value+" END";
    }
    private static String pom(String engine) {
        return """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>com.uob.ufw</groupId>
    <artifactId>uob-ms-starter</artifactId>
    <version>3.6.15.2.RELEASE</version>
    <relativePath/>
  </parent>
  <groupId>com.edac</groupId>
  <artifactId>extraction</artifactId>
  <version>1.0.0</version>
  <properties><java.version>17</java.version><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId></dependency>
    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
"""+(engine.equals("JDBC")?"    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>\n    <dependency><groupId>com.oracle.database.jdbc</groupId><artifactId>ojdbc11</artifactId><scope>runtime</scope></dependency>\n":"    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-csv</artifactId><version>1.14.1</version></dependency>\n")+"""
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build><finalName>extraction</finalName><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version><configuration><release>17</release></configuration></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>
    <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId><configuration><mainClass>com.edac.extraction.ExtractionApplication</mainClass></configuration><executions><execution><goals><goal>repackage</goal></goals></execution></executions></plugin>
  </plugins></build>
</project>
""";
    }
    private String configuration(ConnectionProfile profile,Request input) throws IOException {
        var properties=new Properties();properties.setProperty("logging.file.name","${extraction.output-directory}/extraction.log");properties.setProperty("spring.main.web-application-type","none");properties.setProperty("spring.application.name","edac-extraction");
        properties.setProperty("extraction.output-directory",input.outputDirectory());properties.setProperty("extraction.delivery-directory",Objects.toString(input.deliveryDirectory(),""));
        properties.setProperty("extraction.parallel-jobs",Integer.toString(input.parallelJobs()));properties.setProperty("extraction.delimiter",input.delimiter());properties.setProperty("extraction.system-code",input.systemCode());properties.setProperty("extraction.country-code",input.countryCode());
        properties.setProperty("extraction.file-name-pattern",input.fileNamePattern());properties.setProperty("extraction.group","ALL");properties.setProperty("extraction.retry-attempts","0");properties.setProperty("extraction.query-timeout-seconds","0");properties.setProperty("extraction.replace-existing-delivery","false");
        if(input.engine().equals("JDBC")) {
            properties.setProperty("spring.datasource.url","${EXTRACTION_JDBC_URL:"+profile.jdbcUrl()+"}");properties.setProperty("spring.datasource.username","${EXTRACTION_DB_USERNAME:"+profile.username()+"}");properties.setProperty("spring.datasource.password","${EXTRACTION_DB_PASSWORD}");properties.setProperty("spring.datasource.driver-class-name","oracle.jdbc.OracleDriver");properties.setProperty("spring.datasource.hikari.maximum-pool-size",Integer.toString(input.parallelJobs()));
        }else {
            properties.setProperty("extraction.sqlcl.executable",input.sqlclExecutable());properties.setProperty("extraction.sqlcl.connect","${SQLCL_CONNECT:/@YOUR_WALLET_ALIAS}");properties.setProperty("extraction.sqlcl.java-home","${SQLCL_JAVA_HOME:}");
        }
        var writer=new StringWriter();properties.store(writer,"EDAC generated settings. Supply business date and credentials at runtime. Query timeout 0 disables the timeout.");return writer.toString();
    }
    private static void copy(ZipOutputStream zip,String root,String folder,String name) throws IOException {
        try(var input=new ClassPathResource("extraction-project/"+folder+"/"+name).getInputStream()){put(zip,root+"src/main/java/com/edac/extraction/"+name,new String(input.readAllBytes(),StandardCharsets.UTF_8));}
    }
    private static void put(ZipOutputStream zip,String name,String contents) throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(contents.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
    private static String readme(String engine,ConnectionProfile profile) {
        return """
# EDAC extraction source project

This project contains only the selected extraction method: ENGINE.
Source connection: CONNECTION. Saved passwords are not exported.

## Build and launch

Use JDK 17 or 21 and Maven. Configure your internal Maven repository/settings.xml so the exact parent
com.uob.ufw:uob-ms-starter:3.6.15.2.RELEASE and its managed dependencies resolve. No replacement parent is provided.
Run `mvn clean package`. The executable is `target/extraction.jar`.

The external scheduler launches a process and waits for its exit code. There is no internal scheduler or HTTP server.
Example: `java -jar target/extraction.jar --extraction.business-date=20261104 --extraction.group=Daily --extraction.request-id=daily-20261104`
The optional start.sh does the same after `chmod +x start.sh`.
Business dates accept YYYYMMDD or YYYY-MM-DD. Omit group or use ALL to run all generated definitions.
Default request ID is group-businessDate. Repeating the same ID resumes failed tables and skips completed files.
Use a new request ID for an intentional new run. Do not edit definitions for an existing request ID.
Exit codes: 0 complete, 1 run/table/delivery failure, 2 invalid run settings, 4 duplicate active request.
Table-lock contention is recorded as a table failure (exit 1). Inspect output/runs/date/request/run.properties and manifest.json.

## Configuration and files

application.properties contains editable defaults. External Spring configuration, environment variables or CLI arguments can override them.
output-directory stores run files, .part files, logs, checkpoints and per-request history. Use a persistent filesystem.
delivery-directory is optional and can point to mounted application-server storage. Files publish through a temporary sibling and rename.
Atomic rename is used where supported. Validate filesystem semantics on the deployment host.
Delivery never publishes an unfinished extraction. Different existing contents require explicit extraction.replace-existing-delivery=true.
Filename tokens: {schema}, {table}, {businessDate}. Each table produces one file. No sorting or cross-table snapshot is imposed.
Concurrent tables and retries are configurable. Default retries are zero so the external scheduler can own retries.
Runtime retry reuses the saved delta window. Whole-file restart applies to extraction failures.
Delivery retries reuse the validated completed file without re-querying Oracle.
Header: H + delimiter + system + country + business date + generation timestamp (UTC).
Detail: D + delimiter + included columns in metadata order. Trailer: T + delimiter + count [+ delimiter + selected numeric sum].
UTF-8, LF, BEL by default. Delimiters can be changed, including custom multi-character delimiters.
NULL is unquoted \\N, literal \\N is quoted. Quotes double inside quoted values. CR/LF inside values become spaces.
LOB values are quoted. BLOB and RAW are uppercase hexadecimal. DATE is YYYY-MM-DD, TIMESTAMP is YYYY-MM-DD HH:mm:ss in UTC.
Counters, decimal control totals, byte counts and SHA-256 are calculated while writing. No separate table COUNT is executed.

## Delta rules

Full exports have no predicate. Range and flag predicates bind values without string interpolation into extraction SQL.
Numeric/date/timestamp range bounds come from business date, run parameters, or the last successful checkpoint as configured per table.
Runtime overrides: --extraction.parameters.t10-from=... --extraction.parameters.t10-to=... (replace t10 with the generated table ID).
Dates use ISO dates or ISO/local datetime. Numeric bounds use decimal strings. Supply an initial lower bound when no checkpoint exists.
Checkpoints advance only after successful file publication and configured delivery. Use consistent interval operators across runs.
Flag mode uses its configured literal equality value. Physical deletes are not discovered automatically; select a deletion flag/change table where required.
Increasing-ID bounds capture inserts only when the source assigns IDs monotonically; they do not detect updates to older rows.

ENGINE_NOTES
""".replace("ENGINE_NOTES",engine.equals("JDBC")?"## JDBC Cursor\nThe application uses a DataSource, one forward-only cursor per query and fetch size 2000. It writes rows progressively and flushes after every 2000 rows before advancing the cursor again. This is OS-buffer flushing, not a forced fsync or database commit. JDBC/LOB driver behaviour can alter fetch buffering. Streamed CLOB/BLOB fields are not collected as table-sized objects. Set EXTRACTION_DB_PASSWORD before launch. Cancellation closes/cancels active statements.\n":"## SQLcl\nSQLcl is neither bundled nor downloaded by this project. Configure extraction.sqlcl.executable=/opt/sqlcl/bin/sql. Set SQLCL_CONNECT (prefer a wallet alias) and optionally SQLCL_JAVA_HOME. The targeted command set is SQLcl 21.4. Spring Boot launches SQLcl and supplies scripts/credentials through stdin, not process arguments. sqlcl.log and data.csv are disk-backed under each table work directory. SQLcl first spools canonical CSV; Java then processes one CSV record at a time into the final format. This adds staging space and disk traffic. A single very large CSV field/record still consumes parser memory; benchmark LOB-heavy tables. LOB end markers reject output truncated by SQLcl display settings. The command settings follow [Oracle SQLcl 21.4 documentation](https://docs.oracle.com/en/database/oracle/sql-developer-command-line/21.4/sqcug/working-sqlcl.html). Successful delivery removes staging CSV; failures retain it and logs for diagnosis. SQLcl has its own JVM memory in addition to Spring Boot. query-timeout-seconds is a subprocess timeout in this project. Cancellation terminates its process.\n").replace("CONNECTION",profile.name()+" ("+profile.jdbcUrl()+")").replace("ENGINE",engine);
    }
    private static String testSource(){return """
package com.edac.extraction;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class OutputContractTest {
 @TempDir Path directory;
 @Test void writesEnvelopeEscapingAndTotals() throws Exception {
  var table=new Definitions.Table("t1","APP","TEST","Daily",List.of(new Definitions.Column("ID","NUMBER",true,false)),null,"ID","");
  Path file=directory.resolve("test.dat");
  try(var output=new DelimitedOutput(file,"\u0007","PBI","SG",LocalDate.of(2026,11,4),table)) {
   output.sqlclRow(List.of("V12"));var result=output.finish();assertEquals(1,result.records());assertEquals("12",result.controlSum());
  }
  assertTrue(Files.readString(file).contains("D\\u000712\\nT\\u00071\\u000712\\n"));
 }
}
""";}
    private static String blobFunction(){return """
FUNCTION EDAC_BLOB_HEX(p_value BLOB) RETURN CLOB IS
  l_result CLOB; l_position INTEGER := 1; l_raw RAW(8000); l_text VARCHAR2(16000);
BEGIN
  IF p_value IS NULL THEN RETURN NULL; END IF;
  DBMS_LOB.CREATETEMPORARY(l_result, TRUE, DBMS_LOB.CALL);
  WHILE l_position <= DBMS_LOB.GETLENGTH(p_value) LOOP
    l_raw := DBMS_LOB.SUBSTR(p_value, 8000, l_position); l_text := RAWTOHEX(l_raw);
    DBMS_LOB.WRITEAPPEND(l_result, LENGTH(l_text), l_text); l_position := l_position + UTL_RAW.LENGTH(l_raw);
  END LOOP;
  RETURN l_result;
END;
""";}
}


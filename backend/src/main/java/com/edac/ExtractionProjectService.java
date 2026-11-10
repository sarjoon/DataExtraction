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
import org.springframework.transaction.annotation.Transactional;

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
    static boolean numeric(String type){return type.toUpperCase(Locale.ROOT).matches("NUMBER|NUMERIC|DECIMAL|INTEGER|INT|SMALLINT|BIGINT|TINYINT|FLOAT|BINARY_FLOAT|BINARY_DOUBLE|DOUBLE PRECISION|REAL|DECFLOAT");}
    private static String q(String name){return "\""+RegistryService.identifier(name)+"\"";}
    private static String deltaType(String type){return numeric(type)?"NUMBER":type.equalsIgnoreCase("DATE")?"DATE":type.toUpperCase(Locale.ROOT).startsWith("TIMESTAMP")?"TIMESTAMP":Set.of("CHAR","NCHAR","VARCHAR","VARCHAR2","NVARCHAR2").contains(type.toUpperCase(Locale.ROOT))?"TEXT":"";}
    private ConnectionProfile oracle(long id){var profile=registry.connection(id);if(!profile.databaseType().equals("ORACLE"))throw new IllegalArgumentException("Extraction project generation requires an Oracle connection.");return profile;}
    private List<ColumnDetail> included(long connection,long id) {
        registry.table(connection,id);var excluded=new HashSet<>(db.queryForList("SELECT column_name FROM column_exclusion WHERE table_id=?",String.class,id));
        try{var fields=registry.mappingFields(connection,id);
            if(fields.isEmpty())throw new SourceTableUnavailableException("Oracle metadata returned no columns; the table may be missing or inaccessible.");
            return fields.stream().map(MappingField::column).filter(c->!excluded.contains(c.name())).toList();}
        catch(SourceTableUnavailableException failure){throw failure;}
        catch(IllegalArgumentException failure){var table=registry.table(connection,id);throw new IllegalArgumentException("Cannot read extraction metadata for "+table.schema()+"."+table.name()+": "+failure.getMessage(),failure);}
    }
    public List<ColumnOption> columns(long connection,long table){
        oracle(connection);registry.table(connection,table);
        var excluded=new HashSet<>(db.queryForList("SELECT column_name FROM column_exclusion WHERE table_id=?",String.class,table));
        return registry.columns(connection,table).stream().filter(c->!excluded.contains(c.name())).map(c->new ColumnOption(c.name(),c.dataType(),numeric(c.dataType()),deltaType(c.dataType()))).toList();
    }
    public record UnavailableTable(long tableId,String schema,String table,String reason) {}
    public static final class SourceTablesUnavailable extends IllegalArgumentException {
        private final List<UnavailableTable> tables;
        SourceTablesUnavailable(List<UnavailableTable> tables){
            super("Cannot generate extraction project: source tables not found or not accessible: "+tables.stream().map(t->t.schema()+"."+t.table()).collect(java.util.stream.Collectors.joining(", "))+". Check TABLE_REGISTRY, source metadata and Oracle permissions.");this.tables=List.copyOf(tables);
        }
        public List<UnavailableTable> tables(){return tables;}
    }
    private record Prepared(ConnectionProfile profile,List<Map<String,Object>> tables,Map<String,String> queries) {}
    private ConnectionProfile validateConfiguration(long connection,Request input) {
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
        for(long id:input.tableIds()) {
            var table=registry.table(connection,id);var option=input.tableOptions().get(Long.toString(id));
            if(input.tableOptions().containsKey(Long.toString(id))&&option==null)throw new IllegalArgumentException("Table rules cannot be null.");
            if(!"Delta".equalsIgnoreCase(table.loadStrategy()))continue;
            Delta rule=option==null?null:option.delta();
            if(rule==null||!Set.of("RANGE","FLAG").contains(Objects.toString(rule.mode(),"")))throw new IllegalArgumentException("Configure a Range or Flag delta rule for "+table.name());
            if(rule.column()==null||rule.column().isBlank())throw new IllegalArgumentException("Choose a delta column for "+table.name());
            RegistryService.identifier(rule.column());
            if(rule.mode().equals("FLAG")) {
                if(rule.flagValue()==null||rule.flagValue().isBlank())throw new IllegalArgumentException("Provide a flag value for "+table.name());
            }else {
                if(!Set.of("BUSINESS_DATE","CHECKPOINT","PARAMETER").contains(Objects.toString(rule.lowerSource(),""))||!Set.of("BUSINESS_DATE_END","PARAMETER").contains(Objects.toString(rule.upperSource(),"")))throw new IllegalArgumentException("Invalid delta bound source.");
                if(!Set.of(">",">=").contains(Objects.toString(rule.lowerOperator(),""))||!Set.of("<","<=").contains(Objects.toString(rule.upperOperator(),"")))throw new IllegalArgumentException("Invalid delta range operator.");
            }
        }
        return profile;
    }
    private Prepared prepare(long connection,Request input) {
        var profile=validateConfiguration(connection,input);
        var selectedTables=input.tableIds().stream().map(id->registry.table(connection,id)).toList();
        var nameCounts=selectedTables.stream().collect(java.util.stream.Collectors.groupingBy(t->t.name().toUpperCase(Locale.ROOT),java.util.stream.Collectors.counting()));
        var metadata=new LinkedHashMap<Long,List<ColumnDetail>>();var unavailable=new ArrayList<UnavailableTable>();
        for(var table:selectedTables) {
            try{metadata.put(table.id(),included(connection,table.id()));}
            catch(SourceTableUnavailableException failure){unavailable.add(new UnavailableTable(table.id(),table.schema(),table.name(),failure.getMessage()));}
        }
        if(!unavailable.isEmpty())throw new SourceTablesUnavailable(unavailable);
        var tables=new ArrayList<Map<String,Object>>();var queries=new LinkedHashMap<String,String>();
        for(var table:selectedTables) {
            long id=table.id();var columns=metadata.get(id);if(columns.isEmpty())throw new IllegalArgumentException("No included columns for "+table.schema()+"."+table.name()+". All source columns are excluded.");
            String sqlFile=(nameCounts.get(table.name().toUpperCase(Locale.ROOT))>1?RegistryService.identifier(table.schema())+"/":"")+RegistryService.identifier(table.name())+".sql";
            var option=input.tableOptions().getOrDefault(Long.toString(id),new TableOption(table.updateFrequency(),"",null));
            if(option==null)throw new IllegalArgumentException("Table rules cannot be null.");
            String sum=columns.stream().filter(c->numeric(c.dataType())).map(ColumnDetail::name).findFirst().orElse("");
            boolean incremental="Delta".equalsIgnoreCase(table.loadStrategy());
            if(incremental&&(option.delta()==null||!Set.of("RANGE","FLAG").contains(Objects.toString(option.delta().mode(),""))))throw new IllegalArgumentException("Configure a Range or Flag delta rule for "+table.name());
            var delta=delta(table,columns,incremental?option.delta():new Delta("FULL","","PARAMETER","PARAMETER","","",">=","<"));String key="t"+id;var columnDefinitions=new ArrayList<Map<String,Object>>();
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
            queries.put("src/main/resources/sql/"+sqlFile,sql);
            var definition=new LinkedHashMap<String,Object>();definition.put("id",key);definition.put("schema",table.schema());definition.put("name",table.name());
            definition.put("group",table.updateFrequency());definition.put("columns",columnDefinitions);
            definition.put("delta",delta);definition.put("controlSumColumn",sum);definition.put("sqlResource","/sql/"+sqlFile);tables.add(definition);
        }
        return new Prepared(profile,tables,queries);
    }
    public byte[] generate(long connection,Request input) throws IOException {
        var prepared=prepare(connection,input);var profile=prepared.profile();var tables=prepared.tables();var queries=prepared.queries();
        String root="extraction-"+input.engine().toLowerCase(Locale.ROOT)+"/";var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            put(zip,root+"pom.xml",pom(input.engine()));
            put(zip,root+"src/main/resources/application.properties",configuration(profile,input));
            put(zip,root+"src/main/resources/extraction-definitions.json",json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("engine",input.engine(),"tables",tables)));
            for(var query:queries.entrySet())put(zip,root+query.getKey(),query.getValue());
            for(String file:List.of("Definitions.java","Extractor.java","ExtractionApplication.java","DelimitedOutput.java","RunCoordinator.java","ChunkedExtractor.java"))copy(zip,root,"common",file);
            copy(zip,root,input.engine().toLowerCase(Locale.ROOT),input.engine().equals("JDBC")?"JdbcExtractor.java":"SqlclExtractor.java");
            put(zip,root+"README.md",readme(input.engine(),profile));
            put(zip,root+"start.sh","#!/usr/bin/env bash\nset -euo pipefail\nROOT=\"$(cd -- \"$(dirname -- \"${BASH_SOURCE[0]}\")\" && pwd)\"\nexec java -jar \"$ROOT/target/extraction.jar\" \"$@\"\n");
            put(zip,root+".gitignore","target/\noutput/\n*.log\n.env\n");
            put(zip,root+"src/test/java/com/edac/extraction/OutputContractTest.java",testSource());
            try(var tests=new ClassPathResource("extraction-project/common/RecoveryContractTest.java").getInputStream()){put(zip,root+"src/test/java/com/edac/extraction/RecoveryContractTest.java",new String(tests.readAllBytes(),StandardCharsets.UTF_8));}
        }
        persist(connection,input);
        return bytes.toByteArray();
    }
    public record Configuration(Request settings) {}
    public Configuration configuration(long connection) throws IOException {
        oracle(connection);
        var rows=db.queryForList("SELECT settings_json FROM extraction_project_configuration WHERE connection_id=?",String.class,connection);
        if(rows.isEmpty())return new Configuration(null);
        var saved=json.readValue(rows.get(0),Request.class);
        var available=registry.tables(connection).stream().map(t->Long.toString(t.id())).collect(java.util.stream.Collectors.toSet());
        var options=new LinkedHashMap<String,TableOption>();saved.tableOptions().forEach((id,option)->{if(available.contains(id))options.put(id,option);});
        return new Configuration(copy(saved,saved.tableIds().stream().filter(id->available.contains(id.toString())).toList(),options));
    }
    @Transactional public Configuration save(long connection,Request input) throws IOException {
        validateConfiguration(connection,input);persist(connection,input);return configuration(connection);
    }
    private static Request copy(Request input,List<Long> ids,Map<String,TableOption> options) {
        return new Request(ids,input.engine(),input.delimiter(),input.parallelJobs(),input.systemCode(),input.countryCode(),input.outputDirectory(),input.deliveryDirectory(),input.fileNamePattern(),input.sqlclExecutable(),options);
    }
    private void persist(long connection,Request input) throws IOException {
        // Keep rules for deselected tables. Registry metadata remains authoritative for strategy, frequency and sums.
        var old=configuration(connection).settings();var options=new LinkedHashMap<String,TableOption>();
        if(old!=null)options.putAll(old.tableOptions());
        for(long id:input.tableIds()) {
            var table=registry.table(connection,id);var option=input.tableOptions().get(Long.toString(id));
            options.put(Long.toString(id),new TableOption("","","Delta".equalsIgnoreCase(table.loadStrategy())&&option!=null?option.delta():null));
        }
        String value=json.writeValueAsString(copy(input,input.tableIds(),options));
        db.update("MERGE INTO extraction_project_configuration (connection_id,settings_json,updated_at) KEY(connection_id) VALUES (?,?,CURRENT_TIMESTAMP)",connection,value);
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
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.12.1</version><configuration><release>17</release></configuration></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin>
    <plugin>
      <groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId>
      <configuration combine.self="override"><mainClass>com.edac.extraction.ExtractionApplication</mainClass><layout>JAR</layout><skip>false</skip></configuration>
      <executions><execution><id>repackage</id><phase>package</phase><goals><goal>repackage</goal></goals>
        <configuration combine.self="override"><mainClass>com.edac.extraction.ExtractionApplication</mainClass><layout>JAR</layout><skip>false</skip></configuration>
      </execution></executions>
    </plugin>
  </plugins></build>
</project>
""";
    }
    private String configuration(ConnectionProfile profile,Request input) throws IOException {
        var properties=new Properties();properties.setProperty("spring.cloud.config.enabled","false");properties.setProperty("spring.cloud.config.import-check.enabled","false");properties.setProperty("logging.file.name","${extraction.output-directory}/extraction.log");properties.setProperty("spring.main.web-application-type","none");properties.setProperty("spring.application.name","edac-extraction");
        properties.setProperty("extraction.output-directory",input.outputDirectory());properties.setProperty("extraction.delivery-directory",Objects.toString(input.deliveryDirectory(),""));
        properties.setProperty("extraction.parallel-jobs",Integer.toString(input.parallelJobs()));properties.setProperty("extraction.delimiter",input.delimiter());properties.setProperty("extraction.system-code",input.systemCode());properties.setProperty("extraction.country-code",input.countryCode());
        properties.setProperty("extraction.rerun","");properties.setProperty("extraction.run-id","");
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
The repackage goal is explicitly bound to package and enabled, with executable JAR layout and the extraction main class.
Check META-INF/MANIFEST.MF: Main-Class must be the Spring Boot launcher and Start-Class must be com.edac.extraction.ExtractionApplication.
A jar reporting no main manifest attribute has not been packaged as the executable application. Review the Maven repackage output and inherited parent/plugin settings.
Build source archives and .original jars are not executable application jars.

The external scheduler launches a process and waits for its exit code. There is no internal scheduler or HTTP server.
Example: `java -jar target/extraction.jar --extraction.business-date=20261104 --extraction.group=Daily --extraction.request-id=daily-20261104`
The optional start.sh does the same after `chmod +x start.sh`.
Business dates accept YYYYMMDD or YYYY-MM-DD.
Environment example (Linux): export EXTRACTION_BUSINESS_DATE=20261106
For business-date bounds, this derives start=2026-11-06 00:00:00 and end=2026-11-07 00:00:00.
For explicit per-table overrides: export EXTRACTION_PARAMETERS_T10_FROM='2026-11-06 00:00:00'
and export EXTRACTION_PARAMETERS_T10_TO='2026-11-07 00:00:00' (replace T10 with the ID in extraction-definitions.json).
DATE/TIMESTAMP bounds accept an ISO date or local datetime; timestamps can include fractional seconds. Numeric delta bounds require decimal values.
The upper operator controls whether the end bound is excluded; use < for the usual next-day-exclusive range. CLI arguments take precedence over environment variables. Omit group or use ALL to run all generated definitions.
Default request ID is group-businessDate. Repeating the same ID resumes failed tables and skips completed files.
Use a new request ID for an intentional new run. Do not edit definitions for an existing request ID.
Exit codes: 0 complete, 1 run/table/delivery failure, 2 invalid run settings, 4 duplicate active request.
Table-lock contention is recorded as a table failure (exit 1). Inspect output/runs/date/request/run.properties and manifest.json.

## Configuration and files

application.properties contains editable defaults.
The standalone extractor disables inherited Spring Cloud Config client startup and its import check:
spring.cloud.config.enabled=false
spring.cloud.config.import-check.enabled=false
No Config Server is required. Local/external application.properties, environment variables and CLI arguments remain supported.
If your deployment intentionally uses a Config Server, enable the client and configure spring.config.import explicitly. External Spring configuration, environment variables or CLI arguments can override them.
output-directory stores run files, .part files, logs, checkpoints and per-request history. Use a persistent filesystem.
Work directories use work/SCHEMA.TABLE_NAME (for example work/APP.CUSTOMERS). Legacy work/t10 directories are renamed under the table lock with all recovery state retained. Conflicting old/new directories produce an explicit error.
delivery-directory is optional and can point to mounted application-server storage. Files publish through a temporary sibling and rename.
Atomic rename is used where supported. Validate filesystem semantics on the deployment host.
Delivery never publishes an unfinished extraction. Different existing contents require explicit extraction.replace-existing-delivery=true.
SQL resources use sql/TABLE_NAME.sql; same-named tables in different schemas use sql/SCHEMA/TABLE_NAME.sql.
Filename tokens: {schema}, {table}, {businessDate}. Each table produces one file. No sorting or cross-table snapshot is imposed.
Concurrent tables and retries are configurable. Default retries are zero so the external scheduler can own retries.
Runtime retry reuses the saved delta window. Whole-file restart applies to extraction failures unless numeric-range chunking is enabled.
Delivery retries reuse the validated completed file without re-querying Oracle.
Header: H + delimiter + system + country + business date + generation timestamp (UTC).
Detail: D + delimiter + included columns in metadata order. Trailer: T + delimiter + count [+ delimiter + automatically selected first numeric-field sum].
UTF-8, LF, BEL by default. Delimiters can be changed, including custom multi-character delimiters.
NULL is unquoted \\N, literal \\N is quoted. Quotes double inside quoted values. CR/LF inside values become spaces.
LOB values are quoted. BLOB and RAW are uppercase hexadecimal. DATE is YYYY-MM-DD, TIMESTAMP is YYYY-MM-DD HH:mm:ss in UTC.
Counters, decimal control totals, byte counts and SHA-256 are calculated while writing. No separate table COUNT is executed.

## Rerun failed or missing files

Use `java -jar target/extraction.jar --extraction.run-id=daily-20261104 --extraction.rerun=failed-or-missing`.
Supply the same output-directory if it differs from the generated default. The run ID must be unique across business dates.
The application reloads saved business date, group, delta parameters, delimiter, filename/delivery settings and chunk rules.
Use the same generated definitions; changed definitions/settings are rejected for an existing request.
Completed files are verified by size and SHA-256 and skipped; missing/corrupt files are regenerated.
Delivery retries reuse a validated completed file. Attempt counters accumulate across invocations.
Request/table filesystem locks prevent concurrent reruns. Interrupted RUNNING tables resume after the old process releases its lock.
Legacy runs without request.properties can resume by repeating their original launch arguments (without the explicit rerun flag).
Credentials, SQLcl executable, query timeout and retry-attempts remain runtime settings and are not saved in request.properties.

## Optional resumable large-table extraction (both methods)

Enable per table in external application.properties (replace t10 with the ID in extraction-definitions.json):

```properties
extraction.chunks.t10.column=ID
extraction.chunks.t10.range-size=1000000
extraction.chunks.t10.parallel-jobs=2
extraction.parallel-jobs=4
```

No chunk column means the existing single-cursor/single-SQLcl extraction. range-size is a numeric VALUE interval, not a row count.
Choose a numeric column that leads a valid visible index (validated during planning); sparse keys can produce empty ranges. Confirm Oracle's access plan before production use.
The application discovers MIN/MAX and uses disjoint >= lower / < upper ranges plus an IS NULL chunk for nullable columns so nullable and duplicate keys are preserved. A nullable-key IS NULL chunk can need a full scan; prefer a NOT NULL indexed key.
All table chunks use one saved SCN with AS OF SCN. Each table gets its own snapshot; snapshots are not shared between tables.
The source account needs EXECUTE on DBMS_FLASHBACK and SELECT/FLASHBACK access to the table as appropriate.
Oracle undo retention must cover extraction and retries. DDL or expired snapshots can prevent retries: start a new request ID to extract the whole table at a new snapshot.
Chunk workers share the global extraction.parallel-jobs limit with all other extraction queries. For JDBC, size the DataSource pool accordingly.
Completed chunks are retained with record count, control sum, size and SHA-256. Retries validate and reuse them, extracting only missing or corrupt chunks.
Chunk files have private work directories and SQLcl logs/staging. Failed chunks restart from their beginning; there is no arbitrary cursor-position resume.
Final assembly streams detail records into one file with one header and one combined trailer. Empty tables still produce a valid header/trailer.
Final assembly can restart using completed chunks without querying Oracle. Publication and delta-checkpoint advancement happen only after complete assembly/delivery.
Keep output storage persistent and budget space for retained chunks plus the assembled file (and SQLcl staging). No automatic chunk deletion is performed.
Concurrent source updates do not change the saved snapshot. Full exports without chunking still use their query-time snapshot.

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


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
public class ExtractionScriptService {
    public record Request(@NotEmpty @Size(max=10000) List<@NotNull @Positive Long> tableIds,
        @NotNull @Pattern(regexp="DELIMITED|FIXED") String format,@NotNull String delimiter,
        @Min(1) @Max(32) int parallelJobs,boolean envelope,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,20}") String systemCode,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,20}") String countryCode,
        @NotNull Map<String,@NotNull @Min(1) @Max(32767) Integer> widths,@NotNull Map<String,@NotNull String> controlSumColumns) {}
    private final RegistryService registry;private final JdbcTemplate db;private final ObjectMapper json;
    public ExtractionScriptService(RegistryService registry,JdbcTemplate db,ObjectMapper json){this.registry=registry;this.db=db;this.json=json;}
    public static boolean numeric(String type){return type.toUpperCase(Locale.ROOT).matches("NUMBER|NUMERIC|DECIMAL|INTEGER|INT|SMALLINT|FLOAT|BINARY_FLOAT|BINARY_DOUBLE|DOUBLE PRECISION|REAL");}
    public static int width(ColumnDetail c){String t=c.dataType().toUpperCase(Locale.ROOT);if(t.equals("DATE"))return 10;if(t.startsWith("TIMESTAMP"))return t.contains("TIME ZONE")?33:26;if(numeric(t))return 80;return Math.min(32767,Math.max(1,c.byteLength()==null?3000:c.byteLength().intValue()));}
    private static String identifier(String value){return RegistryService.identifier(value);}
    private static String quoted(String value){return "\""+identifier(value)+"\"";}
    static String expression(ColumnDetail c){
        if(!c.name().equals(identifier(c.name())))throw new IllegalArgumentException("Extraction requires unquoted uppercase column names: "+c.name());
        String n=quoted(c.name()),t=c.dataType().toUpperCase(Locale.ROOT),value;
        if(numeric(t))value="TO_CHAR("+n+", 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''')";
        else if(t.equals("DATE"))value="TO_CHAR("+n+", 'YYYY-MM-DD', 'NLS_DATE_LANGUAGE=English')";
        else if(t.startsWith("TIMESTAMP"))value="TO_CHAR("+n+", 'YYYY-MM-DD HH24:MI:SS.FF6"+(t.contains("TIME ZONE")?" TZH:TZM":"")+"', 'NLS_DATE_LANGUAGE=English')";
        else if(t.equals("RAW"))value="RAWTOHEX("+n+")";
        else if(Set.of("VARCHAR2","NVARCHAR2","CHAR","NCHAR","VARCHAR").contains(t))value="REPLACE(REPLACE("+n+", CHR(13), ' '), CHR(10), ' ')";
        else throw new IllegalArgumentException("Unsupported extraction type "+t+" for "+c.name()+". Create an Oracle extraction view that converts this column to a supported type.");
        return "CASE WHEN "+n+" IS NULL THEN TO_CLOB('N') ELSE TO_CLOB('V') || "+value+" END";
    }
    public List<ColumnDetail> columns(long connection,long table){requireOracle(connection);var excluded=new HashSet<>(db.queryForList("SELECT column_name FROM column_exclusion WHERE table_id=?",String.class,registry.table(connection,table).id()));return registry.columns(connection,table).stream().filter(c->!excluded.contains(c.name())).toList();}
    private ConnectionProfile requireOracle(long id){var p=registry.connection(id);if(!p.databaseType().equals("ORACLE"))throw new IllegalArgumentException("Extraction scripts require an Oracle connection.");return p;}
    public byte[] generate(long connection,Request input) throws IOException {
        var profile=requireOracle(connection);
        if(input.tableIds().size()!=new HashSet<>(input.tableIds()).size())throw new IllegalArgumentException("Select each table once.");
        if(input.delimiter().codePointCount(0,input.delimiter().length())!=1||input.delimiter().equals("\"")||input.delimiter().equals("\r")||input.delimiter().equals("\n")||input.delimiter().equals("\0"))throw new IllegalArgumentException("Choose one delimiter character other than a quote, newline or NUL.");
        var entities=new ArrayList<Map<String,Object>>();var queries=new LinkedHashMap<String,String>();var validWidths=new HashSet<String>();var selectedIds=new HashSet<String>();
        for(long id:input.tableIds()){
            var table=registry.table(connection,id);String schema=identifier(table.schema()),name=identifier(table.name()),key="t"+id;selectedIds.add(Long.toString(id));
            var columns=columns(connection,id);if(columns.isEmpty())throw new IllegalArgumentException("No included columns for "+schema+"."+name);
            var fields=new ArrayList<Map<String,Object>>();var select=new ArrayList<String>();int index=0;String sum=input.controlSumColumns().getOrDefault(Long.toString(id),"");boolean sumFound=sum.isEmpty();
            for(var c:columns){String widthKey=id+":"+c.name();validWidths.add(widthKey);int width=input.widths().getOrDefault(widthKey,width(c));if(width<1||width>32767)throw new IllegalArgumentException("Width must be between 1 and 32767 bytes for "+widthKey);
                String alias=String.format(Locale.ROOT,"C%04d",++index);select.add(expression(c)+" AS \""+alias+"\"");
                fields.add(Map.of("name",c.name(),"alias",alias,"width",width,"numeric",numeric(c.dataType())));
                if(c.name().equals(sum)){if(!numeric(c.dataType()))throw new IllegalArgumentException("Control sum column must be numeric: "+sum);sumFound=true;}
            }
            if(!sumFound)throw new IllegalArgumentException("Control sum column is not included in "+name+": "+sum);
            String sql="WHENEVER OSERROR EXIT FAILURE\nWHENEVER SQLERROR EXIT SQL.SQLCODE\nSET ECHO OFF\nSET VERIFY OFF\nSET FEEDBACK OFF\nSET TERMOUT OFF\nSET HEADING ON\nSET SQLFORMAT CSV\nSET LONG 16777216\nSET LONGCHUNKSIZE 32767\nALTER SESSION SET TIME_ZONE = '+00:00';\nSPOOL \"&1\"\nSELECT\n  "+String.join(",\n  ",select)+"\nFROM "+quoted(schema)+"."+quoted(name)+";\nSPOOL OFF\nEXIT SUCCESS\n";
            sql="SET ENCODING UTF-8\nSET DEFINE ON\n"+sql.replace("EXIT SQL.SQLCODE","EXIT FAILURE");
            String filename=schema+"_"+name;if(filename.length()>200)filename=filename.substring(0,180)+"_"+key;
            queries.put("sql/"+key+".sql",sql);entities.add(Map.of("id",key,"schema",schema,"name",name,"filename",filename,"columns",fields,"controlSumColumn",sum));
        }
        if(!validWidths.containsAll(input.widths().keySet())||!selectedIds.containsAll(input.controlSumColumns().keySet()))throw new IllegalArgumentException("Column options must belong to selected, included tables and columns.");
        var config=new LinkedHashMap<String,Object>();config.put("format",input.format());config.put("delimiter",input.delimiter());config.put("envelope",input.envelope());config.put("systemCode",input.systemCode());config.put("countryCode",input.countryCode());config.put("entities",entities);
        var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes,StandardCharsets.UTF_8)){
            put(zip,"extraction.json",json.writerWithDefaultPrettyPrinter().writeValueAsString(config));
            put(zip,"extraction.config","# Bash configuration. Never commit credentials. Prefer an Oracle wallet alias.\nSQLCL=${SQLCL:-sql}\nPYTHON=${PYTHON:-python3}\nEDAG_CONNECT=${EDAG_CONNECT:-/@YOUR_WALLET_ALIAS}\nMAX_PARALLEL_JOBS="+input.parallelJobs()+"\nOUTPUT_DIR=${OUTPUT_DIR:-\"$ROOT/output\"}\n# Optional delivery directory; files are copied only after successful generation.\nTRANSFER_DIR=${TRANSFER_DIR:-}\n# Set the processing date explicitly as YYYYMMDD before running.\nBUSINESS_DATE=${BUSINESS_DATE:-}\n");
            for(var e:queries.entrySet())put(zip,e.getKey(),e.getValue());
            for(String file:List.of("run.sh","extract.sh","format.py","README.txt"))try(var stream=new ClassPathResource("extraction/"+file).getInputStream()){put(zip,file,new String(stream.readAllBytes(),StandardCharsets.UTF_8));}
            put(zip,"connection.txt","Generated for "+profile.name()+"\nSource: "+profile.jdbcUrl()+"\nUsername: "+profile.username()+"\nNo saved password is included. Configure EDAG_CONNECT on the execution server.\n");
        }return bytes.toByteArray();
    }
    private static void put(ZipOutputStream zip,String name,String value) throws IOException {zip.putNextEntry(new ZipEntry(name));zip.write(value.getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
}

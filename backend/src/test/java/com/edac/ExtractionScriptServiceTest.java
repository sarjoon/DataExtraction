package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ExtractionScriptServiceTest {
    RegistryService registry=mock(RegistryService.class);JdbcTemplate db=mock(JdbcTemplate.class);ObjectMapper json=new ObjectMapper();
    ExtractionScriptService service=new ExtractionScriptService(registry,db,json);
    @BeforeEach void setup(){
        when(registry.connection(1)).thenReturn(new Models.ConnectionProfile(1,"Oracle test","ORACLE","host",1521,"SERVICE_NAME","ORCL","jdbc:oracle:thin:@//host:1521/ORCL","APP","APP",true,true));
        when(registry.table(1,10)).thenReturn(new Models.TableEntry(10,1,"APP","CUSTOMERS","MANUAL",""));
        when(registry.table(1,11)).thenReturn(new Models.TableEntry(11,1,"APP","ACCOUNTS","MANUAL",""));
        when(registry.columns(1,10)).thenReturn(List.of(column("ID","NUMBER",22),column("NAME","VARCHAR2",80),column("AUDIT_D","DATE",7)));
        when(registry.columns(1,11)).thenReturn(List.of(column("AMOUNT","NUMBER",22)));
        when(registry.mappingFields(anyLong(),anyLong())).thenAnswer(call->registry.columns(call.getArgument(0),call.getArgument(1)).stream().map(c->new Models.MappingField(c,null,null,null)).toList());
        when(db.queryForList("SELECT column_name FROM column_exclusion WHERE table_id=?",String.class,10L)).thenReturn(List.of("AUDIT_D"));
    }
    Models.ColumnDetail column(String name,String type,int width){return new Models.ColumnDetail(1,name,type,width,width,10,2,true,true);}
    ExtractionScriptService.Request request(String format,String delimiter,Map<String,Integer> widths,Map<String,String> sums){return new ExtractionScriptService.Request(List.of(10L,11L),format,delimiter,3,true,"PBI","SG",widths,sums);}
    Map<String,String> unzip(byte[] bytes)throws IOException{var entries=new LinkedHashMap<String,String>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry())entries.put(e.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8));}return entries;}
    @Test void generatesAllSelectedTablesWithExclusionsSafeNamesAndNoSavedCredentials()throws Exception{
        var zip=unzip(service.generate(1,request("DELIMITED"," ",Map.of(),Map.of("11","AMOUNT"))));
        assertThat(zip.keySet()).containsExactlyInAnyOrder("extraction.json","extraction.config","sql/t10.sql","sql/t11.sql","run.sh","extract.sh","format.py","README.txt","connection.txt");
        assertThat(zip.get("sql/t10.sql")).contains("FROM \"APP\".\"CUSTOMERS\"","TO_CLOB('N')","TO_CLOB('V')","SET SQLFORMAT CSV","WHENEVER SQLERROR EXIT FAILURE").doesNotContain("AUDIT_D","SELECT *");
        assertThat(zip.get("extraction.config")).contains("MAX_PARALLEL_JOBS=3","/@YOUR_WALLET_ALIAS");
        assertThat(json.readTree(zip.get("extraction.json")).path("entities")).hasSize(2);
        verify(registry,never()).connectionPassword(anyLong());
        var output=System.getProperty("edac.script.test.output");if(output!=null){var dir=java.nio.file.Path.of(output);java.nio.file.Files.createDirectories(dir);java.nio.file.Files.write(dir.resolve("sample.zip"),service.generate(1,request("DELIMITED"," ",Map.of(),Map.of("11","AMOUNT"))));}
    }
    @Test void derivesFixedLengthsFromMappingAndPreservesControlSumSelection()throws Exception{
        var zip=unzip(service.generate(1,request("FIXED","\u0007",Map.of(),Map.of("11","AMOUNT"))));var manifest=json.readTree(zip.get("extraction.json"));
        assertThat(manifest.path("format").asText()).isEqualTo("FIXED");assertThat(manifest.path("entities").get(0).path("columns").get(1).path("width").asInt()).isEqualTo(80);
        assertThat(manifest.path("entities").get(1).path("controlSumColumn").asText()).isEqualTo("AMOUNT");
    }
    @Test void rejectsInvalidDelimiterWidthsAndUnselectedOptions(){
        assertThatThrownBy(()->service.generate(1,request("DELIMITED","\n",Map.of(),Map.of()))).hasMessageContaining("delimiter");
        assertThatThrownBy(()->service.generate(1,request("FIXED","|",Map.of("10:NAME",100),Map.of()))).hasMessageContaining("cannot be overridden");
        assertThatThrownBy(()->service.generate(1,request("FIXED","|",Map.of(),Map.of("10","NAME")))).hasMessageContaining("numeric");
        assertThatThrownBy(()->service.generate(1,request("FIXED","|",Map.of(),Map.of("10","AUDIT_D")))).hasMessageContaining("not included");
    }
    @Test void rejectsUnsupportedTypesInsteadOfSilentlyTruncatingLobs(){
        for(String type:List.of("LONG","XMLTYPE"))assertThatThrownBy(()->ExtractionScriptService.expression(column("PAYLOAD",type,4000))).hasMessageContaining("Unsupported extraction type");
    }
    @Test void generatesClobAndNclobWithoutSubstringTruncation()throws Exception{
        when(registry.columns(1,10)).thenReturn(List.of(column("BODY","CLOB",4000),column("UNICODE_BODY","NCLOB",4000)));
        var zip=unzip(service.generate(1,request("DELIMITED"," ",Map.of(),Map.of())));
        assertThat(zip.get("sql/t10.sql")).contains("REPLACE(REPLACE(\"BODY\"","TO_NCLOB('V')","SET LONG 2000000000").doesNotContain("SUBSTR","DBMS_LOB.SUBSTR");
    }
    @Test void usesExplicitDateAndNumericFormats(){
        assertThat(ExtractionScriptService.expression(column("AMOUNT","NUMBER",22))).contains("NLS_NUMERIC_CHARACTERS");
        assertThat(ExtractionScriptService.expression(column("DATE_D","DATE",7))).contains("YYYY-MM-DD");
        assertThat(ExtractionScriptService.expression(column("TS_D","TIMESTAMP(6)",13))).contains("YYYY-MM-DD HH24:MI:SS").doesNotContain("FF6");
    }
    @Test void enforcesTimestampDateLobAndDictionaryLengthsAndGeneratesFullBlobHex()throws Exception{
        assertThat(ExtractionScriptService.width(column("T","TIMESTAMP(6)",11))).isEqualTo(19);
        assertThat(ExtractionScriptService.width(column("D","DATE",7))).isEqualTo(10);
        for(String type:List.of("CLOB","NCLOB","BLOB"))assertThat(ExtractionScriptService.width(column("BODY",type,4000))).isEqualTo(3000);
        assertThat(ExtractionScriptService.width(column("NAME","VARCHAR2",123))).isEqualTo(123);
        when(registry.columns(1,10)).thenReturn(List.of(column("BODY","BLOB",4000)));
        var zip=unzip(service.generate(1,request("FIXED","|",Map.of(),Map.of())));
        assertThat(zip.get("sql/t10.sql")).contains("FUNCTION EDAC_BLOB_HEX","WHILE l_position <= DBMS_LOB.GETLENGTH","RAWTOHEX(l_raw)","\n/\nSPOOL OFF");
    }
    @Test void endpointValidatesRequestAndDownloadsZip()throws Exception{
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ExtractionScriptController(service)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/connections/1/extraction-scripts").contentType("application/json").content(json.writeValueAsString(request("FIXED","|",Map.of(),Map.of())))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType("application/zip"));
        var bad=new ExtractionScriptService.Request(List.of(10L),"OTHER","|",0,true,"PBI","SG",Map.of(),Map.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/connections/1/extraction-scripts").contentType("application/json").content(json.writeValueAsString(bad))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
}

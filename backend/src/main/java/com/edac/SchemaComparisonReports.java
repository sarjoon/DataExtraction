package com.edac;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.JsonToken;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SchemaComparisonReports {
    static final Map<String,List<String>> SECTIONS=new LinkedHashMap<>();
    static {SECTIONS.put("objectCounts",List.of("objectType","beforeCount","afterCount","difference","status"));SECTIONS.put("objectDifferences",List.of("objectType","objectName","before","after","status"));SECTIONS.put("rowCounts",List.of("tableName","beforeCount","afterCount","difference","status"));SECTIONS.put("structureSummary",List.of("tableName","beforeColumns","afterColumns","differenceCount","status","coverage"));SECTIONS.put("structureDifferences",List.of("tableName","columnOrObject","property","before","after","status"));}
    private final ObjectMapper json;private final Path root;
    public SchemaComparisonReports(ObjectMapper json,@Value("${edac.snapshot.directory:}") String directory){this.json=json;root=SnapshotReports.base(directory).resolve("snapshot/schema");}
    Path file(String id,String kind){if(id==null||!id.matches("Schema_[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("Invalid schema report name.");return root.resolve(kind).resolve(id+"."+kind);}
    public Path zip(String id){Path path=file(id,"zip");if(!Files.isRegularFile(path)||!Files.isRegularFile(file(id,"json")))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Schema comparison report not found.");return path;}
    public void write(String id,Map<String,Object> metadata,Map<String,List<Map<String,Object>>> sections)throws IOException{
        Path jp=file(id,"json"),zp=file(id,"zip"),jt=jp.resolveSibling(id+".json.part"),zt=zp.resolveSibling(id+".zip.part");Files.createDirectories(jp.getParent());Files.createDirectories(zp.getParent());
        try{
            var report=new LinkedHashMap<String,Object>(metadata);report.putAll(sections);json.writeValue(jt.toFile(),report);
            try(var zip=new ZipOutputStream(Files.newOutputStream(zt))){zip.putNextEntry(new ZipEntry(id+".xlsx"));workbook(zip,metadata,sections);zip.closeEntry();}
            Files.move(zt,zp,StandardCopyOption.ATOMIC_MOVE);Files.move(jt,jp,StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception e){Files.deleteIfExists(jt);Files.deleteIfExists(zt);Files.deleteIfExists(jp);Files.deleteIfExists(zp);throw e;}
    }
    private static void workbook(OutputStream output,Map<String,Object> metadata,Map<String,List<Map<String,Object>>> sections)throws IOException{
        var book=new SXSSFWorkbook(100);book.setCompressTempFiles(true);
        try{
            var header=book.createCellStyle();var font=book.createFont();font.setBold(true);header.setFont(font);header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var styles=new HashMap<String,CellStyle>();for(var entry:Map.of("Added",IndexedColors.LIGHT_GREEN,"Removed",IndexedColors.ROSE,"Changed",IndexedColors.LIGHT_YELLOW,"Review",IndexedColors.LIGHT_ORANGE).entrySet()){var style=book.createCellStyle();style.setFillForegroundColor(entry.getValue().getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);styles.put(entry.getKey(),style);}
            var summary=book.createSheet("Summary");int row=0;writeRow(summary,row++,List.of("Property","Value"),header);for(var e:metadata.entrySet())writeRow(summary,row++,List.of(e.getKey(),Objects.toString(e.getValue(),"")),null);
            var names=Map.of("objectCounts","ObjectCounts","objectDifferences","ObjectDifferences","rowCounts","TableRowCounts","structureSummary","StructureSummary","structureDifferences","StructureDifferences");
            for(var section:SECTIONS.entrySet()){
                Sheet sheet=book.createSheet(names.get(section.getKey()));writeRow(sheet,0,section.getValue(),header);int position=1;
                for(var item:sections.get(section.getKey())){
                    if(position>=1048576)throw new IllegalArgumentException("Excel row limit exceeded; compare a smaller schema.");
                    String status=Objects.toString(item.get("status"),"");CellStyle style=status.equals("Added")||status.equals("Only After")?styles.get("Added"):status.equals("Removed")||status.equals("Only Before")?styles.get("Removed"):status.equals("Review required")||status.equals("Not comparable")?styles.get("Review"):Set.of("Changed","Names differ","Count mismatch","Structure differs").contains(status)?styles.get("Changed"):null;
                    writeRow(sheet,position++,section.getValue().stream().map(k->display(item.get(k))).toList(),style);
                }
                sheet.createFreezePane(0,1);sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,position-1),0,section.getValue().size()-1));
                for(int i=0;i<section.getValue().size();i++)sheet.setColumnWidth(i,(section.getValue().get(i).equals("before")||section.getValue().get(i).equals("after")?60:25)*256);
            }
            summary.setColumnWidth(0,28*256);summary.setColumnWidth(1,100*256);book.write(output);
        }finally{book.close();book.dispose();}
    }
    private static String display(Object value){return value==null?"—":value.toString();}
    private static void writeRow(Sheet sheet,int index,List<String> values,CellStyle style){var row=sheet.createRow(index);for(int i=0;i<values.size();i++){String value=values.get(i);var cell=row.createCell(i);cell.setCellValue(value.length()>32767?value.substring(0,32730)+" [truncated; full value in JSON]":value);if(style!=null)cell.setCellStyle(style);}}
    public Map<String,Object> summary(String id)throws IOException{
        zip(id);var result=new LinkedHashMap<String,Object>();var totals=new LinkedHashMap<String,Integer>();
        try(var parser=json.getFactory().createParser(file(id,"json").toFile())){parser.nextToken();while(parser.nextToken()!=JsonToken.END_OBJECT){String name=parser.currentName();parser.nextToken();if(SECTIONS.containsKey(name)){int count=0;while(parser.nextToken()!=JsonToken.END_ARRAY){parser.skipChildren();count++;}totals.put(name,count);}else result.put(name,json.readValue(parser,Object.class));}}
        result.put("totals",totals);return result;
    }
    public Map<String,Object> page(String id,String section,int page,int size)throws IOException{
        return page(id,section,page,size,"");
    }
    public Map<String,Object> page(String id,String section,int page,int size,String table)throws IOException{
        if(!SECTIONS.containsKey(section)||page<0||size<1||size>100)throw new IllegalArgumentException("Choose a report tab and a page size between 1 and 100.");zip(id);
        long offset=(long)page*size,total=0;var rows=new ArrayList<JsonNode>();
        try(var parser=json.getFactory().createParser(file(id,"json").toFile())){parser.nextToken();while(parser.nextToken()!=JsonToken.END_OBJECT){String name=parser.currentName();parser.nextToken();if(name.equals(section)){while(parser.nextToken()!=JsonToken.END_ARRAY){JsonNode item=json.readTree(parser);if(table!=null&&!table.isBlank()&&!item.path("tableName").asText().equalsIgnoreCase(table))continue;if(total>=offset&&rows.size()<size)rows.add(item);total++;}}else parser.skipChildren();}}
        return Map.of("rows",rows,"total",total,"page",page,"size",size);
    }
    public List<Map<String,Object>> history()throws IOException{
        var result=new ArrayList<Map<String,Object>>();if(!Files.isDirectory(root.resolve("json")))return result;
        try(var files=Files.list(root.resolve("json"))){for(var path:files.filter(p->p.getFileName().toString().matches("Schema_[A-Za-z0-9_-]+\\.json")).sorted(Comparator.reverseOrder()).toList()){
            String name=path.getFileName().toString().replaceFirst("\\.json$","");if(Files.isRegularFile(file(name,"zip")))result.add(summary(name));
        }}return result;
    }
}

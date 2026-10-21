package com.edac;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SnapshotReports {
    private final JdbcTemplate db;private final ObjectMapper json;private final Path root;
    static final List<String> CATEGORIES=List.of("beforeOnly","afterOnly","differences");
    public SnapshotReports(JdbcTemplate db,ObjectMapper json,@Value("${edac.snapshot.directory:}") String directory){
        this.db=db;this.json=json;this.root=base(directory).resolve("snapshot");
    }
    static Path base(String directory){
        if(directory!=null&&!directory.isBlank())return Path.of(directory).toAbsolutePath().normalize();
        var source=new org.springframework.boot.system.ApplicationHome(SnapshotReports.class).getSource();
        if(source!=null&&source.isFile())return source.toPath().toAbsolutePath().getParent();
        return Path.of("").toAbsolutePath();
    }
    private Path file(String id,String kind){
        if(id==null||!id.matches("[A-Za-z0-9_$#-]{1,450}"))throw new IllegalArgumentException("Invalid snapshot report name.");
        return root.resolve(kind).resolve(id+"."+(kind.equals("json")?"json":"zip"));
    }
    public Path zip(String id){Path p=file(id,"zip");if(!Files.isRegularFile(p)||!Files.isRegularFile(file(id,"json")))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Snapshot report not found.");return p;}
    public void write(String runId,Map<String,Object> metadata,List<Map<String,Object>> tables) throws IOException {
        Path jsonPath=file(runId,"json"),zipPath=file(runId,"zip");Files.createDirectories(jsonPath.getParent());Files.createDirectories(zipPath.getParent());
        Path jsonTemp=jsonPath.resolveSibling(runId+".json.part"),zipTemp=zipPath.resolveSibling(runId+".zip.part");
        try {
            try(var g=json.getFactory().createGenerator(Files.newOutputStream(jsonTemp));var zip=new ZipOutputStream(Files.newOutputStream(zipTemp))){
                g.writeStartObject();for(var entry:metadata.entrySet())g.writeObjectField(entry.getKey(),entry.getValue());
                g.writeArrayFieldStart("tables");
                for(var table:tables){String name=(String)table.get("tableName");g.writeStartObject();for(var e:table.entrySet())g.writeObjectField(e.getKey(),e.getValue());
                    for(String category:CATEGORIES){g.writeArrayFieldStart(category);rows(runId,name,category,row->{try{g.writeTree(row);}catch(IOException e){throw new UncheckedIOException(e);}});g.writeEndArray();}
                    g.writeEndObject();zip.putNextEntry(new ZipEntry(name+"_"+metadata.get("timestamp")+".xlsx"));workbook(zip,runId,metadata,table);zip.closeEntry();
                }
                g.writeEndArray();g.writeEndObject();
            }
            Files.move(zipTemp,zipPath,StandardCopyOption.ATOMIC_MOVE);Files.move(jsonTemp,jsonPath,StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception e){Files.deleteIfExists(jsonTemp);Files.deleteIfExists(zipTemp);Files.deleteIfExists(zipPath);Files.deleteIfExists(jsonPath);throw e;}
    }
    private void rows(String run,String table,String category,java.util.function.Consumer<JsonNode> consumer){
        db.execute((org.springframework.jdbc.core.ConnectionCallback<Void>)connection->{
            try(var setting=connection.createStatement()){setting.execute("SET LAZY_QUERY_EXECUTION TRUE");}
            try(var statement=connection.prepareStatement("SELECT row_json FROM snapshot_row_stage USE INDEX (snapshot_rows_by_run) WHERE run_id=? AND table_name=? AND category=? ORDER BY run_id,table_name,category,id")){
                statement.setString(1,run);statement.setString(2,table);statement.setString(3,category);statement.setFetchSize(200);
                try(var rows=statement.executeQuery()){while(rows.next())try{consumer.accept(json.readTree(rows.getString(1)));}catch(IOException e){throw new UncheckedIOException(e);}}
            }finally{try(var setting=connection.createStatement()){setting.execute("SET LAZY_QUERY_EXECUTION FALSE");}}return null;
        });
    }
    @SuppressWarnings("unchecked")
    private void workbook(OutputStream output,String run,Map<String,Object> metadata,Map<String,Object> table) throws IOException {
        var book=new SXSSFWorkbook(100);book.setCompressTempFiles(true);
        try{
            var summary=book.createSheet("Summary");int line=0;
            for(var e:metadata.entrySet())writeRow(summary,line++,List.of(e.getKey(),String.valueOf(e.getValue())));
            for(var e:table.entrySet())writeRow(summary,line++,List.of(e.getKey(),String.valueOf(e.getValue())));
            var before=book.createSheet("Records Only Before");var after=book.createSheet("Records Only After");var differences=book.createSheet("Differences");
            List<String> columns=(List<String>)table.get("columns"),keys=(List<String>)table.get("primaryKeys");
            if(keys.size()+3>16384)throw new IllegalArgumentException("Too many primary-key columns for the Excel differences sheet.");
            writeRow(before,0,columns);writeRow(after,0,columns);
            var headers=new ArrayList<>(keys);headers.addAll(List.of("Column Name","Before","After"));writeRow(differences,0,headers);
            String name=(String)table.get("tableName");
            for(String category:CATEGORIES){Sheet sheet=category.equals("beforeOnly")?before:category.equals("afterOnly")?after:differences;int[] index={1};
                rows(run,name,category,row->{
                    if(!category.equals("differences")){
                        if(index[0]>=1048576)throw new IllegalArgumentException("Excel row limit exceeded for "+name+". Compare a smaller table.");
                        var values=new ArrayList<String>();for(String col:columns)values.add(display(row.path("values").get(col)));
                        writeRow(sheet,index[0]++,values);
                    }else for(var change:row.path("changes")){
                        if(index[0]>=1048576)throw new IllegalArgumentException("Excel row limit exceeded for "+name+". Compare a smaller table.");
                        var values=new ArrayList<String>();for(String key:keys)values.add(display(row.path("primaryKey").get(key)));
                        values.add(change.path("column").asText());values.add(display(change.get("before")));values.add(display(change.get("after")));
                        writeRow(sheet,index[0]++,values);
                    }
                });
            }
            for(Sheet sheet:book){int width=sheet==summary?2:sheet==differences?headers.size():columns.size();sheet.createFreezePane(0,1);for(int i=0;i<width;i++)sheet.setColumnWidth(i,24*256);if(sheet!=summary)sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,sheet.getLastRowNum()),0,width-1));}
            book.write(output);
        }finally{book.close();book.dispose();}
    }
    static String display(JsonNode node){return node==null||node.isNull()?"<NULL>":node.asText();}
    private static void writeRow(Sheet sheet,int number,List<String> values){var row=sheet.createRow(number);for(int i=0;i<values.size();i++){String value=values.get(i);row.createCell(i).setCellValue(value==null?"<NULL>":value.length()>32767?value.substring(0,32740)+" [truncated in Excel]":value);}}
    public List<Map<String,Object>> history() throws IOException {
        Path dir=root.resolve("json");if(!Files.isDirectory(dir))return List.of();var history=new ArrayList<Map<String,Object>>();
        try(var files=Files.list(dir)){for(Path path:files.filter(p->p.getFileName().toString().endsWith(".json")).sorted(Comparator.reverseOrder()).toList()){
            String id=path.getFileName().toString().replaceFirst("\\.json$","");if(Files.isRegularFile(file(id,"zip")))history.add(summary(id));
        }}history.sort(Comparator.comparing(m->m.get("timestamp").toString(),Comparator.reverseOrder()));return history;
    }
    public Map<String,Object> summary(String id) throws IOException {
        Path path=file(id,"json");if(!Files.isRegularFile(path))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Snapshot report not found.");
        var result=new LinkedHashMap<String,Object>();var tables=new ArrayList<Map<String,Object>>();
        try(var p=json.getFactory().createParser(path.toFile())){p.nextToken();while(p.nextToken()!=JsonToken.END_OBJECT){String field=p.currentName();p.nextToken();
            if(field.equals("tables")){while(p.nextToken()!=JsonToken.END_ARRAY){var table=new LinkedHashMap<String,Object>();while(p.nextToken()!=JsonToken.END_OBJECT){String key=p.currentName();p.nextToken();if(CATEGORIES.contains(key))p.skipChildren();else table.put(key,json.readValue(p,Object.class));}tables.add(table);}}
            else result.put(field,json.readValue(p,Object.class));
        }}result.put("tables",tables);return result;
    }
    public Map<String,Object> page(String id,String table,String category,int page,int size) throws IOException {
        if(!CATEGORIES.contains(category)||page<0||size<1||size>100)throw new IllegalArgumentException("Choose a report tab and a page size between 1 and 100.");
        Path path=file(id,"json");if(!Files.isRegularFile(path))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Snapshot report not found.");
        long offset=(long)page*size,total=0;var rows=new ArrayList<JsonNode>();boolean found=false;
        try(var p=json.getFactory().createParser(path.toFile())){p.nextToken();while(p.nextToken()!=JsonToken.END_OBJECT){String field=p.currentName();p.nextToken();if(!field.equals("tables")){p.skipChildren();continue;}
            while(p.nextToken()!=JsonToken.END_ARRAY){boolean selected=false;while(p.nextToken()!=JsonToken.END_OBJECT){String key=p.currentName();p.nextToken();
                if(key.equals("tableName")){selected=p.getValueAsString().equals(table);if(selected)found=true;}
                else if(selected&&key.equals(category)){while(p.nextToken()!=JsonToken.END_ARRAY){if(total>=offset&&rows.size()<size)rows.add(json.readTree(p));else p.skipChildren();total++;}}
                else p.skipChildren();
            }}
        }}if(!found)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Table not found in report.");return Map.of("rows",rows,"total",total,"page",page,"size",size);
    }
}

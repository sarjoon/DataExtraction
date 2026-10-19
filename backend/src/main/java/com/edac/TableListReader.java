package com.edac;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class TableListReader {
    public List<String> read(MultipartFile file) throws IOException {
        return read(file,false);
    }
    public List<String> readScreens(MultipartFile file) throws IOException {
        return read(file,true);
    }
    private List<String> read(MultipartFile file,boolean screens) throws IOException {
        if(file.isEmpty()) throw new IllegalArgumentException("The uploaded file is empty.");
        String name=Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
        List<List<String>> rows=new ArrayList<>();
        if(name.endsWith(".xlsx")) {
            try(var workbook=WorkbookFactory.create(file.getInputStream())) {
                Sheet sheet=screens?null:workbook.getSheet("FIle Spec");
                if(sheet==null) sheet=workbook.getSheetAt(0);
                var formatter=new DataFormatter(Locale.ROOT);
                for(Row row:sheet) {
                    if(row.getRowNum()>10010 || row.getLastCellNum()>100) throw new IllegalArgumentException("Upload exceeds the 10,000-row / 100-column limit.");
                    List<String> cells=new ArrayList<>();
                    for(int i=0;i<row.getLastCellNum();i++)cells.add(formatter.formatCellValue(row.getCell(i)));
                    rows.add(cells);
                }
            } catch(IllegalArgumentException e) { throw e; }
            catch(Exception e) { throw new IllegalArgumentException("Cannot read this Excel file. Upload a valid, unencrypted .xlsx workbook."); }
        } else if(name.endsWith(".csv") || name.endsWith(".txt")) {
            try(var reader=new InputStreamReader(file.getInputStream(),StandardCharsets.UTF_8);
                var parser=CSVFormat.DEFAULT.parse(reader)) {
                for(var record:parser) {
                    if(rows.size()>10010 || record.size()>100) throw new IllegalArgumentException("Upload exceeds the 10,000-row / 100-column limit.");
                    rows.add(record.toList());
                }
            }
        } else throw new IllegalArgumentException("Upload a .csv, .txt or .xlsx table list.");
        rows.replaceAll(row -> row.stream().map(v->v.replace("\uFEFF","").trim()).toList());
        int start=0,nameColumn=0,schemaColumn=-1;
        boolean headerFound=false;
        for(int r=0;r<Math.min(rows.size(),5);r++) {
            for(int c=0;c<rows.get(r).size();c++) {
                String normalized=normalize(rows.get(r).get(c));
                if((screens?Set.of("screenname"):Set.of("tablename","filename")).contains(normalized)) {
                    start=r+1;nameColumn=c;headerFound=true;
                    if(!screens)for(int j=0;j<rows.get(r).size();j++)if(Set.of("schema","schemaname").contains(normalize(rows.get(r).get(j))))schemaColumn=j;
                    break;
                }
            }
            if(headerFound)break;
        }
        List<String> names=new ArrayList<>();
        for(int r=start;r<rows.size();r++) {
            var row=rows.get(r);
            if(row.stream().allMatch(String::isBlank))continue;
            if(!headerFound && row.stream().filter(v->!v.isBlank()).count()>1)
                throw new IllegalArgumentException(screens?"For multiple columns, include a Screen Name header.":"For multiple columns, include a Table Name or File Name header.");
            if(row.size()<=nameColumn || row.get(nameColumn).isBlank())throw new IllegalArgumentException("Missing "+(screens?"screen":"table")+" name at row "+(r+1)+".");
            String table=row.get(nameColumn);
            if(schemaColumn>=0 && row.size()>schemaColumn && !row.get(schemaColumn).isBlank())table=row.get(schemaColumn)+"."+table;
            names.add(table);
        }
        return names;
    }
    private String normalize(String text){return text.toLowerCase(Locale.ROOT).replaceAll("[ _]","");}
}

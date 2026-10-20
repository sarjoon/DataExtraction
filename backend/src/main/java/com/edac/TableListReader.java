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
    public List<ReadableFieldService.FieldName> readReadableFields(MultipartFile file) throws IOException {
        var rows=readRows(file,true);int header=-1,table=-1,field=-1;
        for(int r=0;r<Math.min(5,rows.size());r++) {
            int candidateTable=-1,candidateField=-1;
            for(int c=0;c<rows.get(r).size();c++) {
                String label=normalize(rows.get(r).get(c));
                if(label.equals("tablename"))candidateTable=c;
                if(Set.of("fieldname","columnname","sqlnamec").contains(label))candidateField=c;
            }
            if(candidateTable>=0 && candidateField>=0){header=r;table=candidateTable;field=candidateField;break;}
        }
        if(header<0)throw new IllegalArgumentException("Include Table Name and Field Name headers in the readable-field upload.");
        var result=new ArrayList<ReadableFieldService.FieldName>();
        for(int r=header+1;r<rows.size();r++) {
            var row=rows.get(r);if(row.stream().allMatch(String::isBlank))continue;
            if(row.size()<=Math.max(table,field) || row.get(table).isBlank() || row.get(field).isBlank())
                throw new IllegalArgumentException("Missing table or field name at row "+(r+1)+".");
            result.add(new ReadableFieldService.FieldName(row.get(table),row.get(field)));
        }
        return result;
    }
    public record ScreenRegistration(String instrument,String name) {}
    public List<ScreenRegistration> readInstrumentScreens(MultipartFile file,String fallbackInstrument) throws IOException {
        var rows=readRows(file,true);int header=-1,instrumentColumn=-1,nameColumn=-1;
        for(int r=0;r<Math.min(rows.size(),5);r++) {
            int candidateName=-1,candidateInstrument=-1;
            for(int c=0;c<rows.get(r).size();c++) {
                String label=normalize(rows.get(r).get(c));
                if(label.equals("screenname"))candidateName=c;
                if(Set.of("instrument","instrumentname").contains(label))candidateInstrument=c;
            }
            if(candidateName>=0){header=r;nameColumn=candidateName;instrumentColumn=candidateInstrument;break;}
        }
        if(instrumentColumn<0) {
            if(fallbackInstrument==null || fallbackInstrument.isBlank())throw new IllegalArgumentException("Include Instrument and Screen Name columns, or enter an instrument for a screen-only file.");
            return screenNames(rows,true).stream().map(name->new ScreenRegistration(fallbackInstrument,name)).toList();
        }
        List<ScreenRegistration> result=new ArrayList<>();
        for(int r=header+1;r<rows.size();r++) {
            var row=rows.get(r);if(row.stream().allMatch(String::isBlank))continue;
            if(row.size()<=instrumentColumn || row.get(instrumentColumn).isBlank())throw new IllegalArgumentException("Missing instrument at row "+(r+1)+".");
            if(row.size()<=nameColumn || row.get(nameColumn).isBlank())throw new IllegalArgumentException("Missing screen name at row "+(r+1)+".");
            result.add(new ScreenRegistration(row.get(instrumentColumn),row.get(nameColumn)));
        }
        return result;
    }
    public List<String> read(MultipartFile file) throws IOException {
        return read(file,false);
    }
    public List<String> readScreens(MultipartFile file) throws IOException {
        return read(file,true);
    }
    private List<String> read(MultipartFile file,boolean screens) throws IOException {
        return screenNames(readRows(file,screens),screens);
    }
    private List<List<String>> readRows(MultipartFile file,boolean screens) throws IOException {
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
        return rows;
    }
    private List<String> screenNames(List<List<String>> rows,boolean screens) {
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

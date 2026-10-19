package com.edac;

import static com.edac.Models.*;
import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MappingWorkbookService {
    private final RegistryService registry;
    private final JdbcTemplate db;
    public MappingWorkbookService(RegistryService registry,JdbcTemplate db){this.registry=registry;this.db=db;}
    public byte[] generate(long connectionId,List<Long> ids) throws IOException {
        if(ids==null || ids.isEmpty() || ids.size()>200)throw new IllegalArgumentException("Select between 1 and 200 tables.");
        if(new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("Select each table only once.");
        var profile=registry.connection(connectionId);
        var tables=ids.stream().map(id->registry.table(connectionId,id)).toList();
        // Skip unavailable source tables and disclose each omission in the workbook.
        var skipped=new LinkedHashMap<TableEntry,String>();
        var fields=new LinkedHashMap<Long,List<MappingField>>();int count=0;
        for(var table:tables) {
            var excluded=new HashSet<>(db.queryForList("SELECT column_name FROM column_exclusion WHERE table_id=?",String.class,table.id()));
            List<MappingField> included;
            try {included=registry.mappingFields(connectionId,table.id()).stream().filter(f->!excluded.contains(f.column().name())).toList();}
            catch(SourceTableUnavailableException e) {skipped.put(table,e.getMessage());continue;}
            count+=included.size();if(count>50000)throw new IllegalArgumentException("Select fewer tables: the workbook limit is 50,000 included columns.");
            fields.put(table.id(),included);
        }
        try(var input=new ClassPathResource("templates/EDAC_Mapping_Template.xlsx").getInputStream();var wb=new XSSFWorkbook(input);var output=new ByteArrayOutputStream()) {
            var spec=wb.getSheet("FIle Spec");var prototype=wb.getSheetAt(1);
            int trailer=findTrailer(prototype);var specRow=spec.getRow(3);
            short specHeight=specRow.getHeight();CellStyle[] specStyles=new CellStyle[36];
            for(int c=0;c<36;c++)specStyles[c]=specRow.getCell(c).getCellStyle();
            Object[] defaults=new Object[36];
            for(int c=3;c<36;c++)if(c<7||c>11) {
                Object value=value(spec.getRow(3).getCell(c));
                if(Objects.equals(value,value(spec.getRow(4).getCell(c))))defaults[c]=value;
            }
            for(int r=spec.getLastRowNum();r>=3;r--)if(spec.getRow(r)!=null)spec.removeRow(spec.getRow(r));
            if(spec.getCTWorksheet().isSetDataValidations())spec.getCTWorksheet().unsetDataValidations();
            // Keep the reference sheets until every row/style has been copied.
            int originalCount=wb.getNumberOfSheets();Set<String> used=new HashSet<>();used.add("file spec");
            if(!skipped.isEmpty())used.add("generation report");
            var reserved=new HashSet<String>();
            for(var table:tables){reserved.add(WorkbookUtil.createSafeSheetName(table.name()).toLowerCase(Locale.ROOT));reserved.add(WorkbookUtil.createSafeSheetName(table.schema()+"."+table.name()).toLowerCase(Locale.ROOT));}
            for(int i=1;i<originalCount;i++){String reference="__template_"+i;while(reserved.contains(reference.toLowerCase(Locale.ROOT))||wb.getSheet(reference)!=null)reference+="_";wb.setSheetName(i,reference);}
            int serial=0;
            for(var table:tables) {
                if(!fields.containsKey(table.id()))continue;
                String label=tables.stream().filter(t->t.name().equalsIgnoreCase(table.name())).count()>1?table.schema()+"."+table.name():table.name();
                String name=uniqueName(label,used);
                var sheet=wb.cloneSheet(wb.getSheetIndex(prototype));
                wb.setSheetName(wb.getSheetIndex(sheet),name);
                for(int r=sheet.getLastRowNum();r>=9;r--)if(sheet.getRow(r)!=null)sheet.removeRow(sheet.getRow(r));
                if(sheet.getCTWorksheet().isSetDataValidations())sheet.getCTWorksheet().unsetDataValidations();
                var rows=fields.get(table.id());int index=0;
                for(var field:rows) {
                    var row=copyRow(prototype.getRow(9),sheet,9+index,15,false);
                    Object[] values=detail(field,++index,profile.databaseType(),prototype);
                    for(int c=0;c<values.length;c++)put(row.getCell(c),values[c]);
                }
                int trailerRow=9+rows.size();
                for(int i=0;i<5;i++)copyRow(prototype.getRow(trailer+i),sheet,trailerRow+i,11,true);
                if(!rows.isEmpty()) {
                    validation(sheet,9,trailerRow-1,6,6,new String[]{"M","O"});
                    validation(sheet,9,trailerRow-1,11,11,new String[]{"Y","N"});
                }
                sheet.setSelected(false);
                var row=spec.createRow(3+serial);row.setHeight(specHeight);
                for(int c=0;c<36;c++)row.createCell(c).setCellStyle(specStyles[c]);
                for(int c=0;c<36;c++)put(row.getCell(c),defaults[c]);
                put(row.getCell(0),++serial);put(row.getCell(1),label);
                var link=wb.getCreationHelper().createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.DOCUMENT);
                link.setAddress("'"+name.replace("'","''")+"'!A1");row.getCell(1).setHyperlink(link);
            }
            addNavigationColumn(wb,spec);
            if(serial>0)validation(spec,3,2+serial,15,36,new String[]{"Y","N"});
            for(int i=originalCount-1;i>=1;i--)wb.removeSheetAt(i);
            if(!skipped.isEmpty()) {
                var report=wb.createSheet("Generation Report");
                var summary=report.createRow(1);summary.createCell(0).setCellValue("Selected: "+tables.size()+" | Generated: "+serial+" | Skipped: "+skipped.size());
                report.addMergedRegion(new CellRangeAddress(1,1,0,3));
                String[] headers={"Schema","Table Name","Status","Reason"};var heading=report.createRow(2);
                for(int c=0;c<headers.length;c++){var cell=heading.createCell(c);cell.setCellValue(headers[c]);cell.setCellStyle(spec.getRow(2).getCell(1).getCellStyle());}
                int r=3;
                for(var entry:skipped.entrySet()) {
                    var row=report.createRow(r++);row.setHeightInPoints(42);
                    Object[] values={entry.getKey().schema(),entry.getKey().name(),"Skipped",entry.getValue()};
                    for(int c=0;c<values.length;c++){var cell=row.createCell(c);cell.setCellStyle(specStyles[2]);put(cell,values[c]);}
                }
                report.setColumnWidth(0,24*256);report.setColumnWidth(1,40*256);report.setColumnWidth(2,16*256);report.setColumnWidth(3,80*256);
                report.createFreezePane(0,3);
            }
            for(var sheet:wb)navigation(wb,(XSSFSheet)sheet,0,"Go to File Spec",spec.getSheetName());
            if(wb.getSheet("Generation Report")!=null)navigation(wb,spec,3,"Generation Report","Generation Report");
            wb.setActiveSheet(0);spec.setSelected(true);
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            wb.write(output);return output.toByteArray();
        }
    }
    private static void navigation(XSSFWorkbook wb,XSSFSheet sheet,int column,String text,String target) {
        var row=sheet.getRow(0);if(row==null)row=sheet.createRow(0);
        var cell=row.getCell(column);if(cell==null)cell=row.createCell(column);
        cell.setCellValue(text);
        var link=wb.getCreationHelper().createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.DOCUMENT);
        link.setAddress("'"+target.replace("'","''")+"'!A1");cell.setHyperlink(link);
        var style=wb.createCellStyle();style.cloneStyleFrom(cell.getCellStyle());
        var font=wb.createFont();font.setColor(IndexedColors.BLUE.getIndex());font.setUnderline(Font.U_SINGLE);
        style.setFont(font);cell.setCellStyle(style);
    }
    private static void addNavigationColumn(XSSFWorkbook wb,XSSFSheet spec) {
        // The reference template has 36 columns. Insert Link after File Name.
        int[] widths=new int[36];
        for(int c=2;c<36;c++)widths[c]=spec.getColumnWidth(c);
        spec.shiftColumns(2,35,1);
        for(int c=2;c<36;c++)spec.setColumnWidth(c+1,widths[c]);
        spec.setColumnWidth(2,16*256);
        var header=spec.getRow(2).createCell(2);
        header.setCellValue("Link");header.setCellStyle(spec.getRow(2).getCell(1).getCellStyle());
        if(spec.getLastRowNum()<3)return;
        var style=wb.createCellStyle();style.cloneStyleFrom(spec.getRow(3).getCell(1).getCellStyle());
        var font=wb.createFont();var original=wb.getFontAt(style.getFontIndex());
        font.setFontName(original.getFontName());font.setFontHeight(original.getFontHeight());
        font.setColor(IndexedColors.BLUE.getIndex());font.setUnderline(Font.U_SINGLE);style.setFont(font);
        for(int r=3;r<=spec.getLastRowNum();r++) {
            var row=spec.getRow(r);var filename=row.getCell(1);
            var link=wb.getCreationHelper().createHyperlink(org.apache.poi.common.usermodel.HyperlinkType.DOCUMENT);
            link.setAddress(filename.getHyperlink().getAddress());filename.removeHyperlink();
            var cell=row.createCell(2);cell.setCellValue("Go to sheet");cell.setCellStyle(style);cell.setHyperlink(link);
        }
    }
    private static Object[] detail(MappingField field,int serial,String databaseType,XSSFSheet prototype) {
        var c=field.column();String source=c.dataType()==null?"":c.dataType().toUpperCase(Locale.ROOT);
        String type=null,date=null;Number length=null,sourceLength=c.characterLength();Integer decimal=null;
        Integer integerLength=switch(source){case "TINYINT"->3;case "SMALLINT"->5;case "INTEGER","INT"->10;case "BIGINT"->19;default->null;};
        if(source.equals("DATE")){type="D";date="YYYY-MM-DD";length=c.byteLength();sourceLength=c.byteLength();}
        else if(source.startsWith("TIMESTAMP")){type="T";date="YYYY-MM-DD HH:MI:SS";length=c.byteLength();sourceLength=c.byteLength();}
        else if(integerLength!=null){type="N";length=integerLength;sourceLength=integerLength;decimal=c.scale();}
        else if(Set.of("NUMERIC","DECIMAL","NUMBER").contains(source)) {
            type="N";length=c.precision();sourceLength=c.precision();decimal=c.scale();
            if(length!=null && decimal!=null && decimal<0)length=length.intValue()-decimal;
        }
        else if(Set.of("REAL","DOUBLE PRECISION","FLOAT","BINARY_FLOAT","BINARY_DOUBLE","DECFLOAT").contains(source)) {
            type="N";sourceLength=c.precision();decimal=c.scale();
            // Floating-point precision is not a fixed output width; leave Length blank.
        }
        else if(Set.of("CLOB","NCLOB","BLOB").contains(source)) {type="A";length=c.byteLength();sourceLength=c.byteLength();}
        else if(source.contains("CHAR") || Set.of("VARCHAR2","NVARCHAR2").contains(source)) {type="A";length=c.characterLength();}
        else if(source.equals("BOOLEAN")){type="A";length=5;}
        else if(sourceLength==null)sourceLength=c.byteLength();
        return new Object[]{serial,c.name(),type,length,decimal,field.key(),field.mandatory(),field.description(),date,field.defaultValue(),null,null,
            field.sourceMetadata().getOrDefault("SOURCE_DATA_TYPE",c.dataType()),
            field.sourceMetadata().getOrDefault("SOURCE_LENGTH",sourceLength),field.sourceMetadata().getOrDefault("SOURCE_DECIMAL",c.scale())};
    }
    private static int findTrailer(XSSFSheet sheet){for(var row:sheet)if("Trailer".equals(new DataFormatter().formatCellValue(row.getCell(0))))return row.getRowNum();throw new IllegalStateException("Template Trailer section is missing.");}
    private static String uniqueName(String value,Set<String> used) {
        String base=WorkbookUtil.createSafeSheetName(value);if(base.isBlank())base="Table";
        String name=base;int suffix=1;
        while(!used.add(name.toLowerCase(Locale.ROOT))) {String end="_"+(++suffix);name=base.substring(0,Math.min(base.length(),31-end.length()))+end;}
        return name;
    }
    private static XSSFRow copyRow(XSSFRow source,XSSFSheet target,int rowNumber,int columns,boolean contents) {
        var row=target.createRow(rowNumber);row.setHeight(source.getHeight());
        for(int i=0;i<columns;i++) {var old=source.getCell(i);var cell=row.createCell(i);if(old!=null){cell.setCellStyle(old.getCellStyle());if(contents){if(old.getCellType()==CellType.FORMULA)cell.setCellFormula(old.getCellFormula());else put(cell,value(old));}}}
        return row;
    }
    private static Object value(Cell cell){if(cell==null)return null;return switch(cell.getCellType()){case STRING->cell.getStringCellValue();case NUMERIC->cell.getNumericCellValue();case BOOLEAN->cell.getBooleanCellValue();default->null;};}
    private static void put(Cell cell,Object value){
        cell.setBlank();
        if(value==null || value instanceof String s && (s.isBlank()||s.trim().equalsIgnoreCase("NULL")))return;
        if(value instanceof Number n)cell.setCellValue(n.doubleValue());else if(value instanceof Boolean b)cell.setCellValue(b);else cell.setCellValue(value.toString());
    }
    private static void validation(XSSFSheet sheet,int first,int last,int startColumn,int endColumn,String[] values) {
        var helper=sheet.getDataValidationHelper();var rule=helper.createExplicitListConstraint(values);
        var validation=helper.createValidation(rule,new CellRangeAddressList(first,last,startColumn,endColumn));validation.setShowErrorBox(true);sheet.addValidationData(validation);
    }
}

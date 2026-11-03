package com.edac;

import java.io.*;
import java.util.*;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Keep BAU worksheets in their original workbook, importing only EDAC-generated sheets. */
@Service
public class BauMappingWorkbookService {
    private final RegistryService registry;
    private final MappingWorkbookService mapping;
    private final JdbcTemplate db;
    public BauMappingWorkbookService(RegistryService registry,MappingWorkbookService mapping,JdbcTemplate db) {
        this.registry=registry;this.mapping=mapping;this.db=db;
    }
    private record Entry(String name,XSSFSheet sheet,XSSFRow row) {}
    private record Layout(XSSFSheet spec,int header,int fileName,LinkedHashMap<Integer,String> headers,List<Entry> entries) {}

    @Transactional(rollbackFor=Exception.class)
    public byte[] generate(long connectionId,List<Long> ids,MultipartFile file) throws IOException {
        registry.connection(connectionId);
        if(ids==null || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size()!=ids.size())
            throw new IllegalArgumentException("Provide a valid table selection without duplicates.");
        // Check ownership even for selected entries that BAU will supersede.
        for(long id:ids)registry.table(connectionId,id);
        if(file==null || file.isEmpty())
            throw new IllegalArgumentException("Upload a BAU .xlsx workbook.");
        try(var input=file.getInputStream();var bau=new XSSFWorkbook(input)) {
            if(bau.isMacroEnabled())throw new IllegalArgumentException("Upload an .xlsx workbook without macros.");
            var layout=readLayout(bau);
            Set<String> names=new HashSet<>();layout.entries().forEach(e->names.add(normalize(e.name())));
            var duplicates=registry.tables(connectionId).stream().filter(t->names.contains(normalize(t.name())) || names.contains(normalize(t.schema()+"."+t.name()))).toList();
            var duplicateIds=new HashSet<Long>();duplicates.forEach(t->duplicateIds.add(t.id()));
            var remaining=ids.stream().filter(id->!duplicateIds.contains(id)).toList();
            var reserved=new HashSet<String>();
            for(var sheet:bau)if(!ignored(sheet.getSheetName()))reserved.add(sheet.getSheetName());
            byte[] generated=mapping.generateForBau(connectionId,remaining,reserved,layout.spec().getSheetName());
            try(var fresh=new XSSFWorkbook(new ByteArrayInputStream(generated))) {
                var spec=fresh.getSheetAt(0);
                mergeSpec(layout,spec);
                // Discard only the two explicitly ignored sheets. BAU helper sheets stay intact.
                for(int i=bau.getNumberOfSheets()-1;i>=0;i--)if(ignored(bau.getSheetName(i)))bau.removeSheetAt(i);
                var generatedNames=new ArrayList<String>();
                for(int i=1;i<fresh.getNumberOfSheets();i++) {
                    var source=fresh.getSheetAt(i);String name=source.getSheetName();
                    if(bau.getSheet(name)!=null)throw new IllegalArgumentException("BAU worksheet name conflicts with generated worksheet: "+name);
                    copyGeneratedSheet(source,bau.createSheet(name));generatedNames.add(name);
                }
                copyGeneratedSheet(spec,layout.spec());
                bau.setSheetOrder(layout.spec().getSheetName(),0);
                int position=1;
                for(var entry:layout.entries()) {
                    bau.setSheetOrder(entry.sheet().getSheetName(),position++);
                    entry.sheet().setTabColor(new XSSFColor(new byte[]{0x00,0x70,(byte)0xC0},null));
                }
                for(String name:generatedNames)bau.setSheetOrder(name,position++);
                bau.setActiveSheet(0);layout.spec().setSelected(true);
                // Do not evaluate or rewrite BAU formulas, cached values, comments or drawings.
                byte[] result;
                try(var output=new ByteArrayOutputStream()){bau.write(output);result=output.toByteArray();}
                // Permanent deletion happens only after successful validation and serialization.
                for(var duplicate:duplicates)db.update("DELETE FROM table_registry WHERE connection_id=? AND id=?",connectionId,duplicate.id());
                return result;
            }
        }
    }
    private static Layout readLayout(XSSFWorkbook book) {
        XSSFSheet spec=null;
        for(var sheet:book)if(key(sheet.getSheetName()).equals("filespec")) {
            if(spec!=null)throw new IllegalArgumentException("BAU workbook contains multiple File Spec tabs.");
            spec=(XSSFSheet)sheet;
        }
        if(spec==null)throw new IllegalArgumentException("BAU workbook must contain a File Spec tab.");
        var formatter=new DataFormatter();formatter.setUseCachedValuesForFormulaCells(true);
        int header=-1,fileName=-1;
        for(int r=0;r<=Math.min(19,spec.getLastRowNum());r++) {
            var row=spec.getRow(r);if(row==null)continue;
            for(var cell:row)if(key(formatter.formatCellValue(cell)).equals("filename")){header=r;fileName=cell.getColumnIndex();break;}
            if(header>=0)break;
        }
        if(header<0)throw new IllegalArgumentException("BAU File Spec must contain a File Name column in the first 20 rows.");
        var headers=new LinkedHashMap<Integer,String>();var headerKeys=new HashSet<String>();
        for(var cell:spec.getRow(header)) {
            String title=formatter.formatCellValue(cell).trim();if(title.isBlank())continue;
            if(!headerKeys.add(key(title)))throw new IllegalArgumentException("Duplicate BAU File Spec header: "+title);
            headers.put(cell.getColumnIndex(),title);
        }
        var entries=new ArrayList<Entry>();var names=new HashSet<String>();var sheets=new HashSet<String>();
        for(int r=header+1;r<=spec.getLastRowNum();r++) {
            var row=spec.getRow(r);if(row==null)continue;
            var cell=row.getCell(fileName);String name=formatter.formatCellValue(cell).trim();if(name.isBlank())continue;
            if(!names.add(normalize(name)))throw new IllegalArgumentException("Duplicate BAU File Name: "+name);
            XSSFSheet target=null;
            if(cell.getHyperlink()!=null && cell.getHyperlink().getType()==HyperlinkType.DOCUMENT) {
                String address=cell.getHyperlink().getAddress();
                if(address!=null && address.contains("!")) {
                    String sheetName=address.substring(address.startsWith("#")?1:0,address.lastIndexOf('!'));
                    if(sheetName.startsWith("'") && sheetName.endsWith("'"))sheetName=sheetName.substring(1,sheetName.length()-1).replace("''","'");
                    target=book.getSheet(sheetName);
                }
            }
            if(target==null)target=book.getSheet(name);
            if(target==null || target==spec || ignored(target.getSheetName()))throw new IllegalArgumentException("No BAU table worksheet found for File Name: "+name);
            if(!sheets.add(normalize(target.getSheetName())))throw new IllegalArgumentException("Multiple BAU File Names refer to the same worksheet: "+target.getSheetName());
            entries.add(new Entry(name,target,row));
            if(entries.size()>10000)throw new IllegalArgumentException("BAU workbook exceeds 10,000 table entries.");
        }
        if(entries.isEmpty())throw new IllegalArgumentException("BAU File Spec contains no table entries.");
        return new Layout(spec,header,fileName,headers,entries);
    }
    private static boolean ignored(String name){return Set.of("changehistory","systemspecification").contains(key(name));}
    private static String normalize(String value){return value.trim().toUpperCase(Locale.ROOT);}
    private static String key(String value) {
        String key=value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");
        for(String prefix:List.of("fileformat","filetype","loadstrategy","updatefrequency"))if(key.startsWith(prefix))return prefix;
        return key;
    }
    private static void mergeSpec(Layout bau,XSSFSheet target) {
        var book=target.getWorkbook();int count=bau.entries().size();
        if(target.getLastRowNum()>=3)target.shiftRows(3,target.getLastRowNum(),count);
        // POI shifts rows and formulas, but validation ranges need an explicit offset.
        if(target.getCTWorksheet().isSetDataValidations())for(var rule:target.getCTWorksheet().getDataValidations().getDataValidationArray()) {
            var ranges=new ArrayList<String>();
            for(Object reference:rule.getSqref())for(String address:reference.toString().split("\\s+")) {
                var range=CellRangeAddress.valueOf(address);range.setFirstRow(range.getFirstRow()+count);range.setLastRow(range.getLastRow()+count);ranges.add(range.formatAsString());
            }
            rule.setSqref(ranges);
        }
        var formatter=new DataFormatter();var columns=new HashMap<String,Integer>();
        for(var cell:target.getRow(2))columns.put(key(formatter.formatCellValue(cell)),cell.getColumnIndex());
        int next=37;var destinations=new LinkedHashMap<Integer,Integer>();
        for(var header:bau.headers().entrySet()) {
            String key=key(header.getValue());Integer column=columns.get(key);
            if(column==null) {
                column=next++;columns.put(key,column);
                var cell=target.getRow(2).createCell(column);cell.setCellValue(header.getValue());cell.setCellStyle(target.getRow(2).getCell(1).getCellStyle());
                target.setColumnWidth(column,bau.spec().getColumnWidth(header.getKey()));
            }
            destinations.put(header.getKey(),column);
        }
        var styles=new HashMap<Integer,CellStyle>();
        var bauStyle=book.createCellStyle();bauStyle.cloneStyleFrom(target.getRow(2).getCell(1).getCellStyle());
        bauStyle.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());bauStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        for(int i=0;i<count;i++) {
            var entry=bau.entries().get(i);var row=target.createRow(3+i);row.setHeight(entry.row().getHeight());
            for(var column:destinations.entrySet()) {
                var source=entry.row().getCell(column.getKey());if(source!=null)copyCell(source,row.createCell(column.getValue()),styles);
            }
            var serial=row.getCell(0);if(serial==null)serial=row.createCell(0);serial.setCellValue(i+1);
            var file=row.getCell(1);if(file==null)file=row.createCell(1);file.setCellValue(entry.name());
            var link=book.getCreationHelper().createHyperlink(HyperlinkType.DOCUMENT);link.setAddress("'"+entry.sheet().getSheetName().replace("'","''")+"'!A1");file.setHyperlink(link);
            var comment=row.getCell(36);if(comment==null)comment=row.createCell(36);comment.setCellValue("BAU");comment.setCellStyle(bauStyle);
        }
        for(int r=3+count;r<=target.getLastRowNum();r++)target.getRow(r).getCell(0).setCellValue(r-2);
    }
    private static void copyCell(Cell source,Cell target,Map<Integer,CellStyle> styles) {
        var book=target.getSheet().getWorkbook();
        target.setCellStyle(styles.computeIfAbsent((int)source.getCellStyle().getIndex(),i->{var style=book.createCellStyle();style.cloneStyleFrom(source.getCellStyle());return style;}));
        switch(source.getCellType()) {
            case STRING->target.setCellValue(source.getRichStringCellValue().getString());
            case NUMERIC->target.setCellValue(source.getNumericCellValue());
            case BOOLEAN->target.setCellValue(source.getBooleanCellValue());
            case ERROR->target.setCellErrorValue(source.getErrorCellValue());
            case FORMULA->{target.setCellFormula(source.getCellFormula());
                var from=((XSSFCell)source).getCTCell();var to=((XSSFCell)target).getCTCell();
                if(from.isSetV())to.setV(from.getV());if(from.isSetT())to.setT(from.getT());}
            default->target.setBlank();
        }
        if(source.getHyperlink()!=null) {
            var link=book.getCreationHelper().createHyperlink(source.getHyperlink().getType());link.setAddress(source.getHyperlink().getAddress());target.setHyperlink(link);
        }
    }
    /** Generated sheets have no drawings or external relationships; BAU sheets never pass here. */
    private static void copyGeneratedSheet(XSSFSheet source,XSSFSheet target) {
        for(int i=target.getLastRowNum();i>=0;i--)if(target.getRow(i)!=null) {
            for(var cell:target.getRow(i))if(cell.getHyperlink()!=null)cell.removeHyperlink();
            target.removeRow(target.getRow(i));
        }
        for(int i=target.getNumMergedRegions()-1;i>=0;i--)target.removeMergedRegion(i);
        var styles=new HashMap<Integer,CellStyle>();
        for(var old:source) {
            var row=target.createRow(old.getRowNum());row.setHeight(old.getHeight());row.setZeroHeight(old.getZeroHeight());
            for(var cell:old)copyCell(cell,row.createCell(cell.getColumnIndex()),styles);
        }
        for(var merge:source.getMergedRegions())target.addMergedRegion(merge.copy());
        var from=source.getCTWorksheet();var to=target.getCTWorksheet();
        to.setColsArray(from.getColsArray());
        if(from.isSetSheetPr())to.setSheetPr(from.getSheetPr());
        if(from.isSetSheetViews())to.setSheetViews(from.getSheetViews());
        if(from.isSetSheetFormatPr())to.setSheetFormatPr(from.getSheetFormatPr());
        if(to.isSetDataValidations())to.unsetDataValidations();
        if(from.isSetDataValidations())to.setDataValidations(from.getDataValidations());
        if(from.isSetPageMargins())to.setPageMargins(from.getPageMargins());
        if(from.isSetPageSetup())to.setPageSetup(from.getPageSetup());
        if(from.isSetPrintOptions())to.setPrintOptions(from.getPrintOptions());
        if(from.isSetHeaderFooter())to.setHeaderFooter(from.getHeaderFooter());
    }
}

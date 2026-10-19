package com.edac;

import java.io.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TriggerService {
    public record ScreenEntry(long id,long connectionId,String instrument,String name,String source,String createdAt) {}
    private final RegistryService registry;
    private final JdbcTemplate db;
    private final CredentialCipher cipher;
    private final TriggerAnalyzer analyzer;
    public TriggerService(RegistryService registry,JdbcTemplate db,CredentialCipher cipher,TriggerAnalyzer analyzer) {
        this.registry=registry;this.db=db;this.cipher=cipher;this.analyzer=analyzer;
    }
    private Models.ConnectionProfile profile(long id) {
        var profile=registry.connection(id);
        if(!"ORACLE".equals(profile.databaseType()))throw new IllegalArgumentException("Complete Call Trigger requires an Oracle source connection.");
        return profile;
    }
    public List<ScreenEntry> screens(long id) {
        return screens(id,null);
    }
    public List<ScreenEntry> screens(long id,String instrument) {
        profile(id);
        String filter=instrument==null?"":" AND instrument=?";
        Object[] parameters=instrument==null?new Object[]{id}:new Object[]{id,instrumentName(instrument)};
        return db.query("SELECT * FROM screen_registry WHERE connection_id=?"+filter+" ORDER BY instrument,screen_name",(r,n)->
            new ScreenEntry(r.getLong("id"),id,r.getString("instrument"),r.getString("screen_name"),r.getString("source"),r.getString("created_at")),parameters);
    }
    private static String instrumentName(String name) {
        if(name==null || name.isBlank() || name.trim().length()>256 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Instrument must contain 1 to 256 characters without control characters.");
        return name.trim();
    }
    @Transactional
    public Models.AppendResult append(long id,String instrument,List<String> names,String source) {
        if(names==null)throw new IllegalArgumentException("Provide screen names.");
        var result=register(id,names.stream().map(name->new TableListReader.ScreenRegistration(instrument,name)).toList(),source);
        return new Models.AppendResult(result.added(),result.skipped(),screens(id,instrumentName(instrument)).size());
    }
    @Transactional
    public Models.AppendResult register(long id,List<TableListReader.ScreenRegistration> entries,String source) {
        profile(id);
        if(entries==null || entries.isEmpty() || entries.size()>10000)throw new IllegalArgumentException("Provide between 1 and 10,000 screen names.");
        List<TableListReader.ScreenRegistration> cleaned=new ArrayList<>();
        for(var entry:entries) {
            String group=instrumentName(entry.instrument());String name=entry.name();
            if(name==null || name.isBlank() || name.trim().length()>256 || name.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Screen names must contain 1 to 256 characters without control characters.");
            cleaned.add(new TableListReader.ScreenRegistration(group,name.trim()));
        }
        int added=0;
        for(var entry:cleaned)try {
            db.update("INSERT INTO screen_registry(connection_id,instrument,screen_name,source) VALUES(?,?,?,?)",id,entry.instrument(),entry.name(),source);added++;
        }catch(DuplicateKeyException duplicate){/* Preserve existing screen registrations. */}
        return new Models.AppendResult(added,entries.size()-added,screens(id).size());
    }
    public byte[] generate(long id,List<Long> ids) throws IOException {
        var profile=profile(id);
        if(ids==null || ids.isEmpty() || ids.size()>200 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size()!=ids.size())
            throw new IllegalArgumentException("Select between 1 and 200 distinct screens.");
        var available=new HashMap<Long,ScreenEntry>();screens(id).forEach(screen->available.put(screen.id(),screen));
        var selected=ids.stream().map(screenId->{
            var screen=available.get(screenId);
            if(screen==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Screen not found for this connection.");
            return screen;
        }).toList();
        if(selected.stream().map(ScreenEntry::instrument).distinct().count()>1)
            throw new IllegalArgumentException("Select screens from one instrument per report.");
        String password=cipher.decrypt(db.queryForObject("SELECT password_ciphertext FROM connection_profile WHERE id=?",String.class,id));
        try(var wb=new XSSFWorkbook();var output=new ByteArrayOutputStream()) {
            var heading=wb.createCellStyle();var font=wb.createFont();font.setBold(true);heading.setFont(font);
            var body=wb.createCellStyle();body.setWrapText(true);body.setVerticalAlignment(VerticalAlignment.TOP);
            Set<String> used=new HashSet<>();int count=0;
            for(var screen:selected) {
                var fields=analyzer.analyze(profile,password,screen.name());
                count+=fields.size();if(count>50000)throw new IllegalArgumentException("Select fewer screens: the workbook limit is 50,000 attributes.");
                var sheet=wb.createSheet(sheetName(screen.name(),used));
                var header=sheet.createRow(0);header.createCell(0).setCellValue("TAP Field Name");header.createCell(1).setCellValue("Complete Call Trigger");
                header.forEach(cell->cell.setCellStyle(heading));
                int rowNumber=1;
                for(var field:fields) {
                    if(field.completeCallTrigger().length()>32767)throw new IllegalArgumentException("Complete Call Trigger exceeds Excel's cell limit for "+screen.name()+" / "+field.attributeName());
                    var row=sheet.createRow(rowNumber++);
                    row.createCell(0).setCellValue(field.attributeName());row.createCell(1).setCellValue(field.completeCallTrigger());
                    row.forEach(cell->cell.setCellStyle(body));
                    row.setHeightInPoints(Math.min(409,15*Math.max(1,field.completeCallTrigger().split("\n",-1).length)));
                }
                sheet.setColumnWidth(0,40*256);sheet.setColumnWidth(1,90*256);sheet.createFreezePane(0,1);
                sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,rowNumber-1),0,1));
                // Retain the full lookup name if Excel required a shortened or sanitized tab name.
                var comment=sheet.createDrawingPatriarch().createCellComment(wb.getCreationHelper().createClientAnchor());
                comment.setString(wb.getCreationHelper().createRichTextString("Screen: "+screen.name()));header.getCell(0).setCellComment(comment);
            }
            wb.write(output);return output.toByteArray();
        }
    }
    private static String sheetName(String screen,Set<String> used) {
        String base=WorkbookUtil.createSafeSheetName(screen);if(base.isBlank())base="Screen";
        String name=base;int suffix=1;
        while(!used.add(name.toLowerCase(Locale.ROOT))) {String end="_"+(++suffix);name=base.substring(0,Math.min(base.length(),31-end.length()))+end;}
        return name;
    }
}

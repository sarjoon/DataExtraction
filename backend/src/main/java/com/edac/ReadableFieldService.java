package com.edac;

import java.util.*;
import jakarta.validation.constraints.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReadableFieldService {
    public record FieldName(@NotBlank @Size(max=128) String tableName,@NotBlank @Size(max=128) String fieldName) {
        public static FieldName normalized(String table,String field){return new FieldName(RegistryService.identifier(table),RegistryService.identifier(field));}
    }
    public record Entry(long id,String tableName,String fieldName) {}
    public record ScreenField(String tableName,String fieldName,boolean selectable,boolean configured) {}
    private final RegistryService registry;
    private final JdbcTemplate db;
    private final CredentialCipher cipher;
    private final ScreenFieldSource source;
    public ReadableFieldService(RegistryService registry,JdbcTemplate db,CredentialCipher cipher,ScreenFieldSource source){this.registry=registry;this.db=db;this.cipher=cipher;this.source=source;}
    private void check(long id) {
        if(!"ORACLE".equals(registry.connection(id).databaseType()))throw new IllegalArgumentException("Readable trigger fields require an Oracle source connection.");
    }
    public List<Entry> list(long id) {
        check(id);
        return db.query("SELECT id,table_name,field_name FROM readable_trigger_field WHERE connection_id=? ORDER BY table_name,field_name",
            (r,n)->new Entry(r.getLong(1),r.getString(2),r.getString(3)),id);
    }
    public Set<FieldName> names(long id) {
        var names=new HashSet<FieldName>();list(id).forEach(e->names.add(FieldName.normalized(e.tableName(),e.fieldName())));return names;
    }
    private List<FieldName> sourceFields(long connectionId,long screenId) {
        check(connectionId);
        var screens=db.queryForList("SELECT screen_name FROM screen_registry WHERE connection_id=? AND id=?",String.class,connectionId,screenId);
        if(screens.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Screen not found for this connection.");
        String password=cipher.decrypt(db.queryForObject("SELECT password_ciphertext FROM connection_profile WHERE id=?",String.class,connectionId));
        return source.discover(registry.connection(connectionId),password,screens.get(0));
    }
    private static FieldName validName(FieldName field) {
        try{return FieldName.normalized(field.tableName(),field.fieldName());}catch(IllegalArgumentException e){return null;}
    }
    public List<ScreenField> screenFields(long connectionId,long screenId) {
        var saved=names(connectionId);var result=new ArrayList<ScreenField>();
        for(var field:sourceFields(connectionId,screenId)) {
            var name=validName(field);
            result.add(new ScreenField(name==null?field.tableName():name.tableName(),name==null?field.fieldName():name.fieldName(),name!=null,name!=null&&saved.contains(name)));
        }
        return result.stream().distinct().toList();
    }
    @Transactional
    public Models.AppendResult saveScreenFields(long connectionId,long screenId,List<FieldName> selected) {
        if(selected==null || selected.size()>10000 || selected.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Select no more than 10,000 screen fields.");
        var requested=new LinkedHashSet<FieldName>();selected.forEach(field->requested.add(FieldName.normalized(field.tableName(),field.fieldName())));
        var available=new LinkedHashSet<FieldName>();
        for(var field:sourceFields(connectionId,screenId)){var valid=validName(field);if(valid!=null)available.add(valid);}
        if(!available.containsAll(requested))throw new IllegalArgumentException("Select only fields returned for this screen. Reload its fields and retry.");
        for(var field:available)if(!requested.contains(field))db.update("DELETE FROM readable_trigger_field WHERE connection_id=? AND table_name=? AND field_name=?",connectionId,field.tableName(),field.fieldName());
        int added=0;
        for(var field:requested)try {
            db.update("INSERT INTO readable_trigger_field(connection_id,table_name,field_name) VALUES(?,?,?)",connectionId,field.tableName(),field.fieldName());added++;
        }catch(DuplicateKeyException duplicate){/* Previously selected fields are retained. */}
        return new Models.AppendResult(added,requested.size()-added,list(connectionId).size());
    }
    @Transactional
    public Models.AppendResult append(long id,List<FieldName> fields) {
        check(id);
        if(fields==null || fields.isEmpty() || fields.size()>10000 || fields.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Provide between 1 and 10,000 readable fields.");
        var validated=fields.stream().map(f->FieldName.normalized(f.tableName(),f.fieldName())).toList();int added=0;
        for(var field:validated)try {
            db.update("INSERT INTO readable_trigger_field(connection_id,table_name,field_name) VALUES(?,?,?)",id,field.tableName(),field.fieldName());added++;
        }catch(DuplicateKeyException duplicate){/* Keep the existing registration. */}
        return new Models.AppendResult(added,fields.size()-added,list(id).size());
    }
    public void remove(long connectionId,long fieldId) {
        check(connectionId);
        if(db.update("DELETE FROM readable_trigger_field WHERE connection_id=? AND id=?",connectionId,fieldId)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Readable field not found for this connection.");
    }
}

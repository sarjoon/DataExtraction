package com.edac;

import static com.edac.Models.*;
import java.sql.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RegistryService {
    private final JdbcTemplate db;
    private final CredentialCipher cipher;
    private final OracleSource oracle;
    private final H2Source h2;
    public RegistryService(JdbcTemplate db, CredentialCipher cipher, OracleSource oracle, H2Source h2) {
        this.db = db; this.cipher = cipher; this.oracle = oracle; this.h2 = h2;
    }
    public List<ConnectionProfile> connections() {
        return db.query("SELECT * FROM connection_profile WHERE database_type='ORACLE' OR (database_type='H2' AND connection_type IN ('FILE','MEMORY','TCP')) ORDER BY id", (r,n) -> new ConnectionProfile(
            r.getLong("id"),r.getString("name"),r.getString("database_type"),r.getString("host"),r.getObject("port")==null?9092:r.getInt("port"),
            r.getString("connection_type"),r.getString("database_name"),r.getString("jdbc_url"),r.getString("username"),
            r.getString("schema_name"),r.getString("password_ciphertext") != null,r.getBoolean("read_only")));
    }
    public ConnectionProfile connection(long id) {
        return connections().stream().filter(c -> c.id()==id).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Connection not found."));
    }
    public void requireWritable(long id){var profile=connection(id);if(profile.readOnly())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Connection '"+profile.name()+"' is read-only and cannot be used as a Snapshot destination. Enable writes in Database connections for the intended destination.");}
    public ConnectionProfile save(ConnectionInput input) {
        validateBlankPassword(input);
        if ("ORACLE".equals(input.databaseType()) && (input.password() == null || input.password().isBlank())) throw new IllegalArgumentException("Password is required for a new Oracle connection.");
        String url = sourceUrl(input);
        String password=Boolean.TRUE.equals(input.useBlankPassword())?"":Objects.toString(input.password(),"");
        String encrypted = cipher.encrypt(password);
        String schema=connectionSchema(input,password);
        var keys=new GeneratedKeyHolder();
        try {
            db.update(c -> {
                var p=c.prepareStatement("INSERT INTO connection_profile(name,jdbc_url,username,schema_name,database_type,host,port,connection_type,database_name,password_ciphertext,read_only) VALUES(?,?,?,?,?,?,?,?,?,?,?)",new String[]{"id"});
                p.setString(1,input.name().trim());p.setString(2,url);p.setString(3,input.username().trim());p.setString(4,schema);
                p.setString(5,input.databaseType());p.setString(6,input.host());p.setObject(7,input.port());p.setString(8,input.connectionType());p.setString(9,input.databaseName());p.setString(10,encrypted);p.setBoolean(11,input.readOnly()==null||input.readOnly());return p;
            },keys);
        } catch (DuplicateKeyException e) { throw new IllegalArgumentException("A connection with that name already exists."); }
        return connection(Objects.requireNonNull(keys.getKey()).longValue());
    }
    public ConnectionProfile update(long id, ConnectionInput input) {
        validateBlankPassword(input);
        var existing=connection(id);
        if(!existing.databaseType().equals(input.databaseType())) throw new IllegalArgumentException("Create a new profile to change database type.");
        String url = sourceUrl(input);
        String encrypted = Boolean.TRUE.equals(input.useBlankPassword()) ? cipher.encrypt("") : input.password() == null || input.password().isBlank() ? storedPassword(id) : cipher.encrypt(input.password());
        String schema=connectionSchema(input,Boolean.TRUE.equals(input.useBlankPassword())?"":cipher.decrypt(encrypted));
        try {
            db.update("UPDATE connection_profile SET name=?,jdbc_url=?,username=?,schema_name=?,host=?,port=?,connection_type=?,database_name=?,password_ciphertext=?,read_only=? WHERE id=?",
                input.name().trim(),url,input.username().trim(),schema,input.host(),input.port(),input.connectionType(),input.databaseName(),encrypted,input.readOnly()==null?existing.readOnly():input.readOnly(),id);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("A connection with that name already exists.");
        }
        return connection(id);
    }
    private static void validateBlankPassword(ConnectionInput input){
        if(Boolean.TRUE.equals(input.useBlankPassword())&&(!"H2".equals(input.databaseType())||(input.password()!=null&&!input.password().isEmpty())))throw new IllegalArgumentException("Use blank password is only available for H2 with an empty password field.");
    }
    private String storedPassword(long id) {
        return db.queryForObject("SELECT password_ciphertext FROM connection_profile WHERE id=?",String.class,id);
    }
    public String connectionPassword(long id) {
        var profile=connection(id);
        try{return cipher.decrypt(storedPassword(id));}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Cannot decrypt the saved password for connection '"+profile.name()+"' (ID "+id+"). "+("H2".equals(profile.databaseType())?"If this database has no password, edit this connection, select Use blank password and save. Otherwise restore the original encryption key or enter and save its password.":"Restore the original encryption key or enter and save its password."));}
    }
    public TestResult test(long id) {
        var profile = connection(id);
        String password=connectionPassword(id);
        return "H2".equals(profile.databaseType())?h2.test(profile,password):oracle.test(profile,password);
    }
    public List<TableName> discover(long id) {
        var profile = connection(id);
        return "H2".equals(profile.databaseType())?h2.discover(profile,cipher.decrypt(storedPassword(id))):oracle.discover(profile,cipher.decrypt(storedPassword(id)));
    }
    public List<TableEntry> tables(long id) {
        connection(id);
        return db.query("SELECT * FROM table_registry WHERE connection_id=? ORDER BY schema_name,table_name",(r,n)->new TableEntry(
            r.getLong("id"),r.getLong("connection_id"),r.getString("schema_name"),r.getString("table_name"),r.getString("source"),r.getString("created_at")),id);
    }
    public TableEntry table(long connectionId,long tableId) {
        return tables(connectionId).stream().filter(t->t.id()==tableId).findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Configured table not found for this connection."));
    }
    public List<ColumnDetail> columns(long connectionId,long tableId) {
        var table=table(connectionId,tableId);
        var profile=connection(connectionId);
        var name=new TableName(table.schema(),table.name());
        var password=cipher.decrypt(storedPassword(connectionId));
        return "H2".equals(profile.databaseType())?h2.columns(profile,password,name):oracle.columns(profile,password,name);
    }
    public List<MappingField> mappingFields(long connectionId,long tableId) {
        var table=table(connectionId,tableId);var profile=connection(connectionId);
        var name=new TableName(table.schema(),table.name());var password=cipher.decrypt(storedPassword(connectionId));
        return "H2".equals(profile.databaseType())?h2.mappingFields(profile,password,name):oracle.mappingFields(profile,password,name);
    }
    @Transactional
    public AppendResult append(long id,List<String> names,String source) {
        var profile=connection(id);
        if(names.isEmpty() || names.size()>10000) throw new IllegalArgumentException("Provide between 1 and 10,000 table names.");
        // Validate the complete upload before any write, so a bad row never partially appends a file.
        List<TableName> parsed=new ArrayList<>();
        for(String name:names) {
            String[] parts=name.trim().split("\\.",-1);
            if(parts.length>2) throw new IllegalArgumentException("Use TABLE_NAME or SCHEMA.TABLE_NAME: "+name);
            parsed.add(new TableName(parts.length==2?identifier(parts[0]):profile.schema(),identifier(parts[parts.length-1])));
        }
        int added=0;
        for(var table:parsed) {
            try { db.update("INSERT INTO table_registry(connection_id,schema_name,table_name,source) VALUES(?,?,?,?)",id,table.schema(),table.name(),source);added++; }
            catch(DuplicateKeyException duplicate) { /* Existing rows are retained unchanged. */ }
        }
        return new AppendResult(added,names.size()-added,tables(id).size());
    }
    private static String connectionSchema(ConnectionInput input,String password) {
        if(input.schema()!=null&&!input.schema().isBlank())return identifier(input.schema());
        var properties=new Properties();properties.setProperty("user",input.username().trim());properties.setProperty("password",password);
        if("ORACLE".equals(input.databaseType())){properties.setProperty("oracle.net.CONNECT_TIMEOUT","10000");properties.setProperty("oracle.jdbc.ReadTimeout","30000");}
        try(var connection=DriverManager.getConnection(sourceUrl(input),properties)){
            String schema=connection.getSchema();return identifier(schema==null||schema.isBlank()?("H2".equals(input.databaseType())?"PUBLIC":input.username()):schema);
        }catch(SQLException e){throw new IllegalArgumentException("Could not determine the default schema. Enter the schema explicitly or check the connection credentials.");}
    }
    private static String sourceUrl(ConnectionInput input) {
        if("H2".equals(input.databaseType()))return H2Source.url(input.host(),input.port(),input.connectionType(),input.databaseName());
        if(input.port()==null)throw new IllegalArgumentException("Oracle port is required.");
        return OracleConnectionFactory.url(input.host(),input.port(),input.connectionType(),input.databaseName());
    }
    static String identifier(String value) {
        if(value==null || !value.trim().matches("[A-Za-z_][A-Za-z0-9_$#]{0,127}"))
            throw new IllegalArgumentException("Names must start with a letter or underscore and contain only letters, digits, underscores, $ or # (128 characters maximum).");
        return value.trim().toUpperCase(Locale.ROOT);
    }
}

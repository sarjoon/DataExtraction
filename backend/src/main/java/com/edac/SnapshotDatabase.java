package com.edac;

import java.sql.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SnapshotDatabase {
    public record Column(String name,int type,String typeName,int size,int scale,boolean nullable) {}
    public record Table(String schema,String name,List<Column> columns,List<String> keys) {
        String sql(){return quote(schema)+"."+quote(name);}
        Column column(String name){return columns.stream().filter(c->c.name().equals(name)).findFirst().orElseThrow(()->new IllegalArgumentException("Column not found: "+name));}
    }
    private final RegistryService registry;private final JdbcTemplate db;private final CredentialCipher cipher;private final OracleConnectionFactory oracle;
    public SnapshotDatabase(RegistryService registry,JdbcTemplate db,CredentialCipher cipher,OracleConnectionFactory oracle){this.registry=registry;this.db=db;this.cipher=cipher;this.oracle=oracle;}
    public Connection open(long id) throws SQLException {
        var p=registry.connection(id);String password=cipher.decrypt(db.queryForObject("SELECT password_ciphertext FROM connection_profile WHERE id=?",String.class,id));
        if("ORACLE".equals(p.databaseType()))return oracle.open(p,password);
        var connection=DriverManager.getConnection(H2Source.url(p.host(),p.port(),p.connectionType(),p.databaseName()),p.username(),password);
        try(var statement=connection.createStatement()){statement.execute("SET LAZY_QUERY_EXECUTION TRUE");return connection;}catch(SQLException e){connection.close();throw e;}
    }
    public String schema(Connection c,long id) throws SQLException {
        String schema=registry.connection(id).schema();if(schema==null||schema.isBlank())schema=c.getSchema();
        if(schema==null||schema.isBlank())schema=c.getMetaData().getUserName();return RegistryService.identifier(schema);
    }
    static String quote(String name){return "\""+RegistryService.identifier(name)+"\"";}
    public List<String> discover(Connection c,long id) throws SQLException {
        String schema=schema(c,id);var names=new TreeSet<String>();
        try(var rows=c.getMetaData().getTables(null,schema,"%",new String[]{"TABLE"})){
            while(rows.next()){
                String name=rows.getString("TABLE_NAME");
                if(!schema.equals(rows.getString("TABLE_SCHEM"))||name==null||!name.matches("[A-Z_][A-Z0-9_$#]{0,127}"))continue;
                names.add(name);if(names.size()>10000)throw new IllegalArgumentException("More than 10,000 accessible tables. Select a more specific connection schema.");
            }
        }
        return List.copyOf(names);
    }
    public Table describe(Connection c,long id,String name) throws SQLException {
        name=RegistryService.identifier(name);String schema=schema(c,id);var metadata=c.getMetaData();
        var columns=new ArrayList<Column>();
        try(var rs=metadata.getColumns(null,schema,name,null)){while(rs.next()){
            if(!name.equals(rs.getString("TABLE_NAME"))||!schema.equals(rs.getString("TABLE_SCHEM")))continue;
            String column=rs.getString("COLUMN_NAME");if(!RegistryService.identifier(column).equals(column))throw new IllegalArgumentException("Snapshot requires unquoted uppercase column names.");
            columns.add(new Column(column,rs.getInt("DATA_TYPE"),rs.getString("TYPE_NAME"),rs.getInt("COLUMN_SIZE"),rs.getInt("DECIMAL_DIGITS"),rs.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls));
        }}
        if(columns.isEmpty())throw new IllegalArgumentException("Table not found: "+schema+"."+name);
        var keys=new TreeMap<Integer,String>();try(var rs=metadata.getPrimaryKeys(null,schema,name)){while(rs.next())keys.put(rs.getInt("KEY_SEQ"),rs.getString("COLUMN_NAME"));}
        if(keys.isEmpty())throw new IllegalArgumentException("A primary key is required for "+name+" (composite keys are supported).");
        return new Table(schema,name,List.copyOf(columns),List.copyOf(keys.values()));
    }
    public boolean exists(Connection c,long id,String name) throws SQLException {
        String schema=schema(c,id);name=RegistryService.identifier(name);
        try(var rs=c.getMetaData().getTables(null,schema,name,new String[]{"TABLE"})){while(rs.next())if(name.equals(rs.getString("TABLE_NAME"))&&schema.equals(rs.getString("TABLE_SCHEM")))return true;return false;}
    }
    static long count(Connection c,Table table) throws SQLException {try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table.sql())){r.next();return r.getLong(1);}}
    static String names(List<String> names){return String.join(",",names.stream().map(SnapshotDatabase::quote).toList());}
    static String value(ResultSet rows,int i,Column column) throws SQLException {
        return switch(column.type()) {
            case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB -> {byte[] v=rows.getBytes(i);yield v==null?null:Base64.getEncoder().encodeToString(v);}
            case Types.NUMERIC,Types.DECIMAL,Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT,Types.FLOAT,Types.DOUBLE,Types.REAL -> {BigDecimal n=rows.getBigDecimal(i);yield n==null?null:n.stripTrailingZeros().toPlainString();}
            case Types.DATE -> {var d=rows.getTimestamp(i);yield d==null?null:d.toLocalDateTime().toString();}
            case Types.TIMESTAMP -> {var t=rows.getTimestamp(i);yield t==null?null:t.toLocalDateTime().toString();}
            case Types.TIMESTAMP_WITH_TIMEZONE -> {var t=rows.getObject(i,OffsetDateTime.class);yield t==null?null:t.toInstant().toString();}
            case Types.BOOLEAN,Types.BIT -> {boolean b=rows.getBoolean(i);yield rows.wasNull()?null:b?"1":"0";}
            default -> rows.getString(i);
        };
    }
    static void bind(PreparedStatement s,int index,Column column,String value) throws SQLException {
        if(value==null){s.setNull(index,column.type());return;}
        switch(column.type()) {
            case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY -> s.setBytes(index,Base64.getDecoder().decode(value));
            case Types.BLOB -> s.setBlob(index,new java.io.ByteArrayInputStream(Base64.getDecoder().decode(value)));
            case Types.CLOB,Types.NCLOB,Types.LONGVARCHAR,Types.LONGNVARCHAR -> s.setCharacterStream(index,new java.io.StringReader(value));
            case Types.NUMERIC,Types.DECIMAL,Types.BIGINT,Types.INTEGER,Types.SMALLINT,Types.TINYINT,Types.FLOAT,Types.DOUBLE,Types.REAL -> s.setBigDecimal(index,new BigDecimal(value));
            case Types.DATE,Types.TIMESTAMP -> s.setTimestamp(index,Timestamp.valueOf(LocalDateTime.parse(value)));
            case Types.TIMESTAMP_WITH_TIMEZONE -> s.setObject(index,OffsetDateTime.parse(value));
            case Types.BOOLEAN,Types.BIT -> s.setBoolean(index,value.equals("1")||value.equalsIgnoreCase("true"));
            default -> s.setString(index,value);
        }
    }
    static String ddlType(Column c,boolean oracle) {
        return switch(c.type()) {
            case Types.NUMERIC,Types.DECIMAL -> c.size()>0 && c.size()<=38 && c.scale()>=0 && c.scale()<=c.size()?"DECIMAL("+c.size()+","+c.scale()+")":oracle?"NUMBER":"DECFLOAT(40)";
            case Types.INTEGER,Types.SMALLINT,Types.TINYINT -> "INTEGER";
            case Types.BIGINT -> "DECIMAL(19,0)";
            case Types.FLOAT,Types.REAL,Types.DOUBLE -> oracle?"BINARY_DOUBLE":"DOUBLE PRECISION";
            case Types.BOOLEAN,Types.BIT -> "DECIMAL(1,0)";
            case Types.DATE,Types.TIMESTAMP -> "TIMESTAMP(9)";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP(9) WITH TIME ZONE";
            case Types.BLOB -> "BLOB";
            case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY -> c.size()>0&&c.size()<=(oracle?2000:1000000)?(oracle?"RAW(":"VARBINARY(")+c.size()+")":"BLOB";
            case Types.CLOB,Types.NCLOB,Types.LONGVARCHAR,Types.LONGNVARCHAR -> "CLOB";
            case Types.CHAR,Types.NCHAR,Types.VARCHAR,Types.NVARCHAR -> c.size()>0&&c.size()<= (oracle?1000:1000000)?(oracle?"VARCHAR2(":"VARCHAR(")+c.size()+(oracle?" CHAR)":")"):"CLOB";
            default -> throw new IllegalArgumentException("Unsupported snapshot column type: "+c.name()+" ("+c.typeName()+")");
        };
    }
    static void create(Connection destination,String schema,Table source,boolean oracle) throws SQLException {
        var definitions=new ArrayList<String>();for(var c:source.columns()){String type=ddlType(c,oracle);if(source.keys().contains(c.name())&&(type.equals("CLOB")||type.equals("BLOB")))throw new IllegalArgumentException("Primary-key column cannot be copied losslessly to this destination: "+c.name());definitions.add(quote(c.name())+" "+type+(c.nullable()?"":" NOT NULL"));}
        definitions.add("PRIMARY KEY ("+names(source.keys())+")");
        try(var s=destination.createStatement()){s.executeUpdate("CREATE TABLE "+quote(schema)+"."+quote(source.name())+" ("+String.join(",",definitions)+")");}
    }
    static String mergeSql(Table target,List<String> columns,boolean oracle) {
        String placeholders=String.join(",",Collections.nCopies(columns.size(),"?"));
        if(!oracle)return "MERGE INTO "+target.sql()+" ("+names(columns)+") KEY ("+names(target.keys())+") VALUES ("+placeholders+")";
        String select=String.join(",",columns.stream().map(n->"? "+quote(n)).toList());
        String on=String.join(" AND ",target.keys().stream().map(n->"t."+quote(n)+"=s."+quote(n)).toList());
        var updates=columns.stream().filter(n->!target.keys().contains(n)).map(n->"t."+quote(n)+"=s."+quote(n)).toList();
        return "MERGE INTO "+target.sql()+" t USING (SELECT "+select+" FROM dual) s ON ("+on+")"+(updates.isEmpty()?"":" WHEN MATCHED THEN UPDATE SET "+String.join(",",updates))+" WHEN NOT MATCHED THEN INSERT ("+names(columns)+") VALUES ("+String.join(",",columns.stream().map(n->"s."+quote(n)).toList())+")";
    }
}

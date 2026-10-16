package com.edac;

import static com.edac.Models.*;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class H2Source {
    static String url(String host,Integer port,String mode,String name) {
        if(name==null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
            throw new IllegalArgumentException("H2 database names must contain only letters, digits and underscores, starting with a letter or underscore.");
        if(mode==null) throw new IllegalArgumentException("Choose an H2 connection mode.");
        return switch(mode) {
            case "FILE" -> "jdbc:h2:file:./data/sources/"+name;
            case "MEMORY" -> "jdbc:h2:mem:"+name+";DB_CLOSE_DELAY=-1";
            case "TCP" -> {
                if(host==null || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || port==null || port<1 || port>65535)
                    throw new IllegalArgumentException("Provide a valid H2 server host and port.");
                yield "jdbc:h2:tcp://"+host+":"+port+"/"+name;
            }
            default -> throw new IllegalArgumentException("Choose File, In-memory or TCP for H2.");
        };
    }
    private Connection open(ConnectionProfile profile,String password) throws SQLException {
        return DriverManager.getConnection(url(profile.host(),profile.port(),profile.connectionType(),profile.databaseName()),profile.username(),password);
    }
    public TestResult test(ConnectionProfile profile,String password) {
        try(var connection=open(profile,password);var statement=connection.prepareStatement("SELECT 1")) {
            statement.setQueryTimeout(15);
            try(var rows=statement.executeQuery()){if(!rows.next() || rows.getInt(1)!=1)throw new SQLException("Validation failed");}
            return new TestResult(true,"Connected to H2 using the saved source connection details.",List.of());
        }catch(SQLException e){throw failure(e);}
    }
    public List<TableName> discover(ConnectionProfile profile,String password) {
        try(var connection=open(profile,password);var statement=connection.prepareStatement(
            "SELECT TABLE_SCHEMA,TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")) {
            statement.setString(1,profile.schema());statement.setQueryTimeout(30);statement.setMaxRows(10001);
            List<TableName> result=new ArrayList<>();
            try(var rows=statement.executeQuery()){while(rows.next())result.add(new TableName(rows.getString(1),rows.getString(2)));}
            if(result.size()>10000)throw new IllegalArgumentException("More than 10,000 tables. Upload a smaller table list.");
            if(result.stream().anyMatch(t->!t.name().matches("[A-Z_][A-Z0-9_$#]{0,127}")))
                throw new IllegalArgumentException("Quoted or mixed-case table names are not supported. Configure unquoted tables instead.");
            return result;
        }catch(SQLException e){throw failure(e);}
    }
    public List<ColumnDetail> columns(ConnectionProfile profile,String password,TableName table) {
        try(var connection=open(profile,password);var statement=connection.prepareStatement(
            "SELECT ORDINAL_POSITION,COLUMN_NAME,DATA_TYPE,CHARACTER_OCTET_LENGTH,CHARACTER_MAXIMUM_LENGTH,NUMERIC_PRECISION,NUMERIC_SCALE,IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION")) {
            statement.setString(1,table.schema());statement.setString(2,table.name());statement.setQueryTimeout(30);statement.setMaxRows(5001);
            List<ColumnDetail> result=new ArrayList<>();
            try(var rows=statement.executeQuery()){while(rows.next())result.add(new ColumnDetail(rows.getInt(1),rows.getString(2),rows.getString(3),
                (Number)rows.getObject(4),(Number)rows.getObject(5),number(rows.getObject(6)),number(rows.getObject(7)),"YES".equals(rows.getString(8)),true));}
            if(result.isEmpty())throw new IllegalArgumentException("Table not found or no accessible columns in the selected H2 source.");
            if(result.size()>5000)throw new IllegalArgumentException("Table exceeds the supported limit of 5,000 columns.");
            return result;
        }catch(SQLException e){throw failure(e);}
    }
    public List<MappingField> mappingFields(ConnectionProfile profile,String password,TableName table) {
        String query=MappingMetadata.sql(new org.springframework.core.io.ClassPathResource("sql/h2-mapping.sql"));
        try(var connection=open(profile,password)){return MappingMetadata.read(connection,query,table);}
        catch(SQLException e){throw failure(e);}
    }
    private static Integer number(Object value){return value==null?null:((Number)value).intValue();}
    private IllegalArgumentException failure(SQLException e){return new IllegalArgumentException("H2 source connection or query failed (database error "+e.getErrorCode()+"). Check the saved database name, mode, credentials and schema.");}
}

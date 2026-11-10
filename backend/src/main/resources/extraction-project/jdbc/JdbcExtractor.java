package com.edac.extraction;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import com.edac.extraction.Definitions.*;

@Component
public class JdbcExtractor implements Extractor {
    private final DataSource dataSource;private final Environment env;
    private final Set<Statement> active=ConcurrentHashMap.newKeySet();
    public JdbcExtractor(DataSource dataSource,Environment env){this.dataSource=dataSource;this.env=env;}
    @Override public Stats extract(Table table,Window window,Path part,Path work,LocalDate date) throws Exception {
        String sql;try(var input=getClass().getResourceAsStream(table.sqlResource())){if(input==null)throw new IOException("Missing SQL: "+table.sqlResource());sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
        sql=ChunkedExtractor.chunkSql(table,sql,work);
        try(var connection=dataSource.getConnection()) {
            connection.setReadOnly(true);connection.setAutoCommit(false);
            if(connection.getMetaData().getDatabaseProductName().toUpperCase(Locale.ROOT).contains("ORACLE"))try(var session=connection.createStatement()){session.execute("ALTER SESSION SET TIME_ZONE = '+00:00'");}
            try(var statement=connection.prepareStatement(sql,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)) {
                statement.setFetchSize(2000);
                statement.setFetchDirection(ResultSet.FETCH_FORWARD);
                statement.setQueryTimeout(Integer.parseInt(env.getProperty("extraction.query-timeout-seconds","0")));
                for(int i=0;i<window.values().size();i++) {
                    String value=window.values().get(i);String type=table.delta().type();
                    if(type.equals("NUMBER"))statement.setBigDecimal(i+1,new BigDecimal(value));
                    else if(type.equals("DATE")||type.equals("TIMESTAMP"))statement.setTimestamp(i+1,Timestamp.valueOf(value),Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                    else statement.setString(i+1,value);
                }
                active.add(statement);
                try(var output=new DelimitedOutput(part,RunCoordinator.outputSetting(work,env,"delimiter","\u0007"),RunCoordinator.outputSetting(work,env,"system-code","PBI"),RunCoordinator.outputSetting(work,env,"country-code","SG"),date,table);var rows=statement.executeQuery()) {
                    while(rows.next()){if(Thread.currentThread().isInterrupted()){statement.cancel();throw new InterruptedException();}output.jdbcRow(rows);}
                    return output.finish();
                }finally{active.remove(statement);}
            }finally{connection.rollback();}
        }
    }
    @Override public List<List<String>> query(String sql,Path work) throws Exception {
        try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
            statement.setQueryTimeout(Integer.parseInt(env.getProperty("extraction.query-timeout-seconds","0")));
            active.add(statement);
            try(var rows=statement.executeQuery(sql)) {
                var result=new ArrayList<List<String>>();
                while(rows.next()){var row=new ArrayList<String>();for(int i=1;i<=rows.getMetaData().getColumnCount();i++)row.add(rows.getString(i));result.add(row);}
                return result;
            }finally{active.remove(statement);}
        }
    }
    @Override public void cancel(){for(var statement:active)try{statement.cancel();}catch(SQLException ignored){}}
}

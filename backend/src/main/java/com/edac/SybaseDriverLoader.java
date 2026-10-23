package com.edac;

import java.net.URLClassLoader;
import java.nio.file.*;
import java.sql.*;
import java.util.Properties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SybaseDriverLoader {
    private final String path;private URLClassLoader loader;private Driver driver;
    public SybaseDriverLoader(@Value("${edac.sybase.driver-path:}") String path){this.path=path;}
    private synchronized Driver driver(){
        if(driver!=null)return driver;
        if(path==null||path.isBlank())throw new IllegalArgumentException("Set -Dedac.sybase.driver-path=/path/to/jconn42.jar before -jar and restart EDAC to use Sybase connections.");
        Path file=Path.of(path).toAbsolutePath().normalize();
        if(!Files.isRegularFile(file)||!Files.isReadable(file))throw new IllegalArgumentException("The configured Sybase driver JAR is missing or unreadable. Check edac.sybase.driver-path.");
        try{
            loader=new URLClassLoader(new java.net.URL[]{file.toUri().toURL()},SybaseDriverLoader.class.getClassLoader());
            Class<?> driverClass;
            try{driverClass=Class.forName("com.sybase.jdbc42.jdbc.SybDriver",false,loader);}
            catch(ClassNotFoundException absent){driverClass=Class.forName("com.sybase.jdbc4.jdbc.SybDriver",false,loader);}
            driver=driverClass.asSubclass(Driver.class).getConstructor().newInstance();return driver;
        }catch(Exception|LinkageError e){close();throw new IllegalArgumentException("Cannot load a Sybase driver from the configured JAR. Supply a Java-compatible jconn4.jar (com.sybase.jdbc4.jdbc.SybDriver) or jconn42.jar (com.sybase.jdbc42.jdbc.SybDriver) and restart EDAC.",e);}
    }
    public Connection open(Models.ConnectionProfile profile,String password)throws SQLException{
        var properties=new Properties();properties.setProperty("user",profile.username());properties.setProperty("password",password);
        // Direct invocation avoids DriverManager's filtering of drivers loaded by a child class loader.
        Connection connection=driver().connect(url(profile.host(),profile.port(),profile.databaseName()),properties);
        if(connection==null)throw new SQLException("Sybase driver did not accept the connection URL.");
        try(var statement=connection.createStatement()){statement.setQueryTimeout(30);statement.execute("SET QUOTED_IDENTIFIER ON");return connection;}
        catch(SQLException e){connection.close();throw e;}
    }
    static String url(String host,Integer port,String database){
        if(host==null||!host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}")||port==null||port<1||port>65535||database==null||!database.matches("[A-Za-z0-9_][A-Za-z0-9_.$#-]{0,127}"))throw new IllegalArgumentException("Provide a valid Sybase host, port and database name.");
        return "jdbc:sybase:Tds:"+host+":"+port+"/"+database;
    }
    @PreDestroy public synchronized void close(){driver=null;if(loader!=null){try{loader.close();}catch(java.io.IOException ignored){}loader=null;}}
}

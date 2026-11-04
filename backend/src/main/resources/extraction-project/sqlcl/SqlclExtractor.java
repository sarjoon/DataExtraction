package com.edac.extraction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.apache.commons.csv.CSVFormat;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import com.edac.extraction.Definitions.*;

@Component
public class SqlclExtractor implements Extractor {
    private final Environment env;private final Set<Process> active=ConcurrentHashMap.newKeySet();
    public SqlclExtractor(Environment env){this.env=env;}
    private static String literal(String value){return "'"+value.replace("'","''").replace("\r","'||CHR(13)||'").replace("\n","'||CHR(10)||'")+"'";}
    @Override public Stats extract(Table table,Window window,Path part,Path work,LocalDate date) throws Exception {
        String executable=env.getProperty("extraction.sqlcl.executable","");String connect=env.getProperty("extraction.sqlcl.connect","");
        if(executable.isBlank()||connect.isBlank())throw new IllegalArgumentException("Configure extraction.sqlcl.executable and extraction.sqlcl.connect.");
        if(connect.contains("\r")||connect.contains("\n"))throw new IllegalArgumentException("SQLcl connection must be one line.");
        Files.createDirectories(work);Path csv=work.resolve("data.csv");Files.deleteIfExists(csv);
        String sql;try(var input=getClass().getResourceAsStream(table.sqlResource())){if(input==null)throw new IOException("Missing SQL resource.");sql=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
        var script=new StringBuilder("WHENEVER OSERROR EXIT FAILURE\nWHENEVER SQLERROR EXIT FAILURE\nSET DEFINE OFF\nSET ECHO OFF\nSET VERIFY OFF\nSET FEEDBACK OFF\nSET HEADING ON\nSET TERMOUT OFF\nSET ENCODING UTF-8\nSET SQLFORMAT CSV\nSET ARRAYSIZE 2000\nSET MAXSPOOLROWSTRUNCATE OFF\nSET LONG 2000000000\nSET LONGCHUNKSIZE 32767\nALTER SESSION SET TIME_ZONE = '+00:00';\nALTER SESSION SET NLS_NUMERIC_CHARACTERS = '.,';\n");
        String[] bindNames=table.delta().mode().equals("FLAG")?new String[]{"edac_flag"}:new String[]{"edac_from","edac_to"};
        for(int i=0;i<window.values().size();i++)script.append("VARIABLE ").append(bindNames[i]).append(" VARCHAR2(32767)\nBEGIN :").append(bindNames[i]).append(" := ").append(literal(window.values().get(i))).append("; END;\n/\n");
        script.append("SPOOL data.csv\n").append(sql).append("\nSPOOL OFF\nEXIT SUCCESS\n");
        Files.writeString(work.resolve("extract.sql"),script.toString(),StandardCharsets.UTF_8);
        var builder=new ProcessBuilder(executable,"-L","-S","/nolog").directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("sqlcl.log").toFile());
        String javaHome=env.getProperty("extraction.sqlcl.java-home","");if(!javaHome.isBlank())builder.environment().put("JAVA_HOME",javaHome);
        Process process=builder.start();active.add(process);
        try {
            try(var stdin=new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8)){stdin.write("WHENEVER OSERROR EXIT FAILURE\nWHENEVER SQLERROR EXIT FAILURE\nSET DEFINE OFF\nSET ECHO OFF\nCONNECT "+connect+"\n@extract.sql\n");}
            long timeout=Long.parseLong(env.getProperty("extraction.query-timeout-seconds","0"));
            if(timeout>0 && !process.waitFor(timeout,TimeUnit.SECONDS))throw new IOException("SQLcl timed out. See "+work.resolve("sqlcl.log"));
            if(timeout<=0)process.waitFor();
            if(process.exitValue()!=0 || !Files.isRegularFile(csv))throw new IOException("SQLcl failed. See "+work.resolve("sqlcl.log"));
            // SQLcl logs and staging are disk-backed. Iterate CSV records; never call getRecords().
            try(var reader=Files.newBufferedReader(csv,StandardCharsets.UTF_8);var parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader);
                var output=new DelimitedOutput(part,env.getProperty("extraction.delimiter","\u0007"),env.getProperty("extraction.system-code","PBI"),env.getProperty("extraction.country-code","SG"),date,table)) {
                var expected=java.util.stream.IntStream.rangeClosed(1,table.columns().size()).mapToObj(i->String.format(Locale.ROOT,"C%04d",i)).toList();
                if(!parser.getHeaderNames().equals(expected))throw new IOException("SQLcl CSV header differs from the selected columns. See sqlcl.log.");
                for(var record:parser){if(Thread.currentThread().isInterrupted())throw new InterruptedException();var values=new ArrayList<String>();for(String value:record)values.add(value);output.sqlclRow(values);}
                return output.finish();
            }
        } finally {if(process.isAlive())terminate(process);active.remove(process);}
    }
    private static void terminate(Process process){process.descendants().forEach(child->child.destroyForcibly());process.destroyForcibly();}
    @Override public void cancel(){for(var process:active)terminate(process);}
}


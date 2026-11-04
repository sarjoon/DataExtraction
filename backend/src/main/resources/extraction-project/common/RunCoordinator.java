package com.edac.extraction;

import java.io.*;
import java.math.BigDecimal;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import com.edac.extraction.Definitions.*;

@Component
public class RunCoordinator implements ApplicationRunner {
    private static final Logger LOG=LoggerFactory.getLogger(RunCoordinator.class);
    private final Environment env;private final Extractor extractor;private final ObjectMapper json=new ObjectMapper();
    private volatile int exitCode=1;private ExecutorService pool;
    public RunCoordinator(Environment env,Extractor extractor){this.env=env;this.extractor=extractor;}
    public int exitCode(){return exitCode;}
    private String setting(String key,String fallback){return env.getProperty("extraction."+key,fallback);}
    private static String segment(String value){if(value==null || !value.matches("[A-Za-z0-9_.-]+") || value.equals(".") || value.equals(".."))throw new IllegalArgumentException("Use letters, numbers, dots, underscores or hyphens in group/request IDs.");return value;}
    @Override public void run(ApplicationArguments args) {
        try{exitCode=execute();}
        catch(IllegalArgumentException e){exitCode=2;LOG.error("Invalid extraction request: {}",e.getMessage());}
        catch(OverlappingFileLockException e){exitCode=4;LOG.error("This request or table is already running.");}
        catch(Exception e){exitCode=1;LOG.error("Extraction run failed",e);}
    }
    private int execute() throws Exception {
        String inputDate=setting("business-date","");
        if(inputDate.isBlank())throw new IllegalArgumentException("Supply --extraction.business-date=YYYYMMDD.");
        LocalDate date=LocalDate.parse(inputDate,inputDate.contains("-")?DateTimeFormatter.ISO_LOCAL_DATE:DateTimeFormatter.BASIC_ISO_DATE);
        String dateText=date.format(DateTimeFormatter.BASIC_ISO_DATE),group=setting("group","ALL").trim();
        String request=segment(setting("request-id",group.replaceAll("[^A-Za-z0-9_.-]","_")+"-"+dateText));
        int concurrency=Integer.parseInt(setting("parallel-jobs","1"));if(concurrency<1)throw new IllegalArgumentException("Parallel jobs must be positive.");
        int retries=Integer.parseInt(setting("retry-attempts","0"));if(retries<0)throw new IllegalArgumentException("Retry attempts cannot be negative.");
        byte[] definitionBytes;
        try(var input=getClass().getResourceAsStream("/extraction-definitions.json")){if(input==null)throw new IOException("Missing extraction definitions.");definitionBytes=input.readAllBytes();}
        var definitions=json.readValue(definitionBytes,Definitions.class);
        var parameters=new TreeMap<String,String>();
        for(var t:definitions.tables()){parameters.put(t.id()+"-from",setting("parameters."+t.id()+"-from",""));parameters.put(t.id()+"-to",setting("parameters."+t.id()+"-to",""));}
        var tables=definitions.tables().stream().filter(t->group.equalsIgnoreCase("ALL")||group.equalsIgnoreCase(t.group())).toList();
        if(tables.isEmpty())throw new IllegalArgumentException("No tables belong to group "+group);
        var filenames=new HashSet<String>();
        for(var table:tables)if(!filenames.add(filename(table,date)))throw new IllegalArgumentException("Filename pattern produces duplicate output names. Include {schema} and {table}.");
        Path output=Path.of(setting("output-directory","./output")).toAbsolutePath().normalize();
        Path run=output.resolve("runs").resolve(dateText).resolve(request);Files.createDirectories(run);Files.createDirectories(output.resolve("locks"));
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((new String(definitionBytes,StandardCharsets.UTF_8)+"\n"+dateText+"\n"+group+"\n"+setting("delimiter","\u0007")+"\n"+setting("file-name-pattern","{schema}_{table}_{businessDate}.dat")+"\n"+setting("system-code","PBI")+"\n"+setting("country-code","SG")+"\n"+setting("delivery-directory","")+"\n"+parameters).getBytes(StandardCharsets.UTF_8)));
        Path statePath=run.resolve("run.properties");var state=load(statePath);
        if(state.containsKey("fingerprint")&&!fingerprint.equals(state.getProperty("fingerprint")))throw new IllegalArgumentException("This request ID belongs to different settings. Supply a new request ID.");
        try(var lockChannel=FileChannel.open(run.resolve(".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=lockChannel.tryLock()) {
            if(lock==null)return 4;
            state.setProperty("fingerprint",fingerprint);state.setProperty("status","RUNNING");save(state,statePath);
            pool=Executors.newFixedThreadPool(concurrency);var futures=new ArrayList<Future<Boolean>>();
            for(var table:tables)futures.add(pool.submit(()->extractTable(table,date,output,run,state,statePath,retries)));
            pool.shutdown();boolean success=true;
            for(var future:futures)try{success&=future.get();}catch(ExecutionException e){success=false;LOG.error("Worker failed",e.getCause());}
            synchronized(state){state.setProperty("status",success?"SUCCESS":"FAILED");save(state,statePath);writeManifest(state,run.resolve("manifest.json"));}
            return success?0:1;
        } finally {if(pool!=null)pool.shutdownNow();}
    }
    private boolean extractTable(Table table,LocalDate date,Path output,Path run,Properties state,Path statePath,int retries) {
        try(var channel=FileChannel.open(output.resolve("locks").resolve(table.id()+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null)throw new IOException("Table is already running: "+table.name());
            String filename=filename(table,date);
            Path target=run.resolve(filename),part=run.resolve(filename+".part"),work=run.resolve("work").resolve(table.id());Files.createDirectories(work);
            String key=table.id()+".";
            synchronized(state){if("SUCCESS".equals(state.getProperty(key+"status"))&&Files.isRegularFile(target)&&Long.toString(Files.size(target)).equals(state.getProperty(key+"bytes"))&&sha256(target).equals(state.getProperty(key+"sha256"))){LOG.info("Already completed {}",table.name());return true;}}
            Window window=window(table,date,output,work.resolve("window.properties"));
            for(int attempt=0;attempt<=retries;attempt++) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                try {
                    synchronized(state){state.setProperty(key+"status","RUNNING");state.setProperty(key+"attempt",Integer.toString(attempt+1));save(state,statePath);}
                    LOG.info("Starting {}.{} attempt {}",table.schema(),table.name(),attempt+1);
                    Path preparedPath=work.resolve("prepared.properties");var prepared=load(preparedPath);
                    Stats stats=null;
                    if(prepared.containsKey("sha256")&&Files.isRegularFile(target)&&Long.toString(Files.size(target)).equals(prepared.getProperty("bytes"))&&sha256(target).equals(prepared.getProperty("sha256"))) {
                        stats=new Stats(Long.parseLong(prepared.getProperty("records")),prepared.getProperty("sum"),Long.parseLong(prepared.getProperty("bytes")),prepared.getProperty("sha256"));
                        LOG.info("Reusing completed file for delivery: {}",target);
                    }
                    if(stats==null) {
                        stats=extractor.extract(table,window,part,work,date);
                        if(Files.size(part)!=stats.bytes())throw new IOException("Output size changed before publication.");
                        move(part,target);
                        prepared.setProperty("records",Long.toString(stats.records()));prepared.setProperty("sum",stats.controlSum());prepared.setProperty("bytes",Long.toString(stats.bytes()));prepared.setProperty("sha256",stats.sha256());save(prepared,preparedPath);
                    }
                    String destination=setting("delivery-directory","");
                    if(!destination.isBlank()) {
                        Path directory=Path.of(destination).toAbsolutePath().normalize();Files.createDirectories(directory);
                        Path delivered=directory.resolve(filename);
                        if(Files.exists(delivered)) {
                            if(!sha256(delivered).equals(stats.sha256())&&!Boolean.parseBoolean(setting("replace-existing-delivery","false")))throw new IOException("Delivery file already exists with different contents. Use a distinct destination or explicitly enable replacement.");
                        }
                        Path transfer=directory.resolve("."+table.id()+"-"+run.getFileName()+".part");Files.copy(target,transfer,StandardCopyOption.REPLACE_EXISTING);move(transfer,delivered);
                    }
                    if(window.checkpointTo()!=null){var checkpoint=new Properties();checkpoint.setProperty("value",window.checkpointTo());save(checkpoint,output.resolve("checkpoints").resolve(table.id()+".properties"));}
                    synchronized(state){state.remove(key+"error");state.setProperty(key+"status","SUCCESS");state.setProperty(key+"records",Long.toString(stats.records()));state.setProperty(key+"sum",stats.controlSum());state.setProperty(key+"bytes",Long.toString(stats.bytes()));state.setProperty(key+"sha256",stats.sha256());state.setProperty(key+"file",target.toString());save(state,statePath);writeManifest(state,run.resolve("manifest.json"));}
                    Files.deleteIfExists(work.resolve("data.csv"));LOG.info("Completed {}: {} records",table.name(),stats.records());return true;
                }catch(Exception e) {
                    LOG.error("Failed table {} attempt {}",table.name(),attempt+1,e);
                    synchronized(state){state.setProperty(key+"status","FAILED");state.setProperty(key+"error",e.getClass().getSimpleName());save(state,statePath);}
                    if(e instanceof InterruptedException){Thread.currentThread().interrupt();return false;}
                }
            }
        }catch(Exception e){LOG.error("Table {} could not execute",table.name(),e);}
        return false;
    }
    private String filename(Table table,LocalDate date) {
        String value=setting("file-name-pattern","{schema}_{table}_{businessDate}.dat").replace("{schema}",table.schema()).replace("{table}",table.name()).replace("{businessDate}",date.format(DateTimeFormatter.BASIC_ISO_DATE));
        if(value.isBlank()||value.contains("/")||value.contains("\\")||value.contains("{")||value.equals(".")||value.equals(".."))throw new IllegalArgumentException("Provide a filename pattern without directories or unknown tokens.");
        return value;
    }
    private Window window(Table table,LocalDate date,Path output,Path saved) throws IOException {
        var old=load(saved);var delta=table.delta();
        if(delta.mode().equals("FULL"))return new Window(List.of(),null);
        if(old.containsKey("count")){var values=new ArrayList<String>();for(int i=0;i<Integer.parseInt(old.getProperty("count"));i++)values.add(old.getProperty("v"+i));return new Window(values,old.getProperty("checkpointTo"));}
        var values=new ArrayList<String>();String checkpointTo=null;
        if(delta.mode().equals("FLAG"))values.add(normalize(delta.flagValue(),delta.type()));
        else {
            String from=setting("parameters."+table.id()+"-from","");
            if(from.isBlank()) {
                if(delta.lowerSource().equals("BUSINESS_DATE"))from=date.atStartOfDay().toString();
                else if(delta.lowerSource().equals("CHECKPOINT"))from=load(output.resolve("checkpoints").resolve(table.id()+".properties")).getProperty("value",Objects.toString(delta.initialValue(),""));
                else from=Objects.toString(delta.initialValue(),"");
            }
            String to=setting("parameters."+table.id()+"-to","");
            if(to.isBlank()&&delta.upperSource().equals("BUSINESS_DATE_END"))to=date.plusDays(1).atStartOfDay().toString();
            from=normalize(from,delta.type());to=normalize(to,delta.type());
            if(delta.type().equals("NUMBER")?new BigDecimal(from).compareTo(new BigDecimal(to))>0:from.compareTo(to)>0)throw new IllegalArgumentException("Delta lower bound cannot exceed upper bound for "+table.name());
            values.add(from);values.add(to);if(delta.lowerSource().equals("CHECKPOINT"))checkpointTo=to;
        }
        var properties=new Properties();properties.setProperty("count",Integer.toString(values.size()));for(int i=0;i<values.size();i++)properties.setProperty("v"+i,values.get(i));if(checkpointTo!=null)properties.setProperty("checkpointTo",checkpointTo);save(properties,saved);
        return new Window(values,checkpointTo);
    }
    static String normalize(String value,String type) {
        if(value==null||value.isBlank())throw new IllegalArgumentException("A delta bound/value is missing. Supply the configured run parameters or initial checkpoint.");
        if(type.equals("NUMBER"))return new BigDecimal(value).toPlainString();
        if(type.equals("DATE")||type.equals("TIMESTAMP")) {
            LocalDateTime date=value.length()==10?LocalDate.parse(value).atStartOfDay():LocalDateTime.parse(value.replace(' ','T'));
            return type.equals("DATE")?date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")):Timestamp.valueOf(date).toString();
        }
        return value;
    }
    static Properties load(Path path) throws IOException{var result=new Properties();if(Files.exists(path))try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){result.load(reader);}return result;}
    static void save(Properties value,Path path) throws IOException{Files.createDirectories(path.getParent());Path temporary=path.resolveSibling(path.getFileName()+".tmp");try(var writer=Files.newBufferedWriter(temporary,StandardCharsets.UTF_8)){value.store(writer,"EDAC extraction state");}move(temporary,path);}
    static void move(Path from,Path to) throws IOException{try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}}
    private void writeManifest(Properties state,Path path) throws IOException{Path temporary=path.resolveSibling("manifest.json.tmp");json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),new TreeMap<>(state));move(temporary,path);}
    private static String sha256(Path file) throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var stream=Files.newInputStream(file)){byte[] bytes=new byte[65536];for(int n;(n=stream.read(bytes))!=-1;)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest());}
    @PreDestroy public void close(){extractor.cancel();if(pool!=null)pool.shutdownNow();}
}

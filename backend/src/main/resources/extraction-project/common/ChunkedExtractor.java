package com.edac.extraction;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import com.edac.extraction.Definitions.*;

/** Optional numeric ranges. Each table owns one persisted Oracle snapshot. */
public final class ChunkedExtractor implements Extractor {
    private static final Logger LOG=LoggerFactory.getLogger(ChunkedExtractor.class);
    private final Extractor delegate;private final Environment env;private final Properties settings;private final Semaphore slots;
    public ChunkedExtractor(Extractor delegate,Environment env,Properties settings){this.delegate=delegate;this.env=env;this.settings=settings;slots=new Semaphore(Integer.parseInt(value("parallel-jobs","1")),true);}
    private String value(String key,String fallback){return settings.getProperty(key,env.getProperty("extraction."+key,fallback));}
    private String option(Table table,String name,String fallback){return value("chunks."+table.id()+"."+name,fallback);}
    static String quoted(String name){return "\""+name.replace("\"","\"\"")+"\"";}
    @Override public List<List<String>> query(String sql,Path work) throws Exception {
        slots.acquire();try{return delegate.query(sql,work);}finally{slots.release();}
    }
    private Stats extractSingle(Table table,Window window,Path part,Path work,LocalDate date) throws Exception {
        var format=new Properties();format.setProperty("delimiter",value("delimiter","\u0007"));format.setProperty("system-code",value("system-code","PBI"));format.setProperty("country-code",value("country-code","SG"));RunCoordinator.save(format,work.resolve("output.properties"));
        slots.acquire();try{return delegate.extract(table,window,part,work,date);}finally{slots.release();}
    }
    private static String literal(String value){return "'"+value.replace("'","''")+"'";}
    @Override public Stats extract(Table table,Window window,Path part,Path work,LocalDate date) throws Exception {
        String column=option(table,"column","");
        if(column.isBlank())return extractSingle(table,window,part,work,date);
        if(table.columns().stream().noneMatch(c->c.name().equals(column)&&c.numeric()))throw new IllegalArgumentException("Chunk column must be an included numeric column: "+table.name()+"."+column);
        BigDecimal size=new BigDecimal(option(table,"range-size","1000000"));if(size.signum()<=0)throw new IllegalArgumentException("Chunk range-size must be positive.");
        int workers=Integer.parseInt(option(table,"parallel-jobs","1"));if(workers<1)throw new IllegalArgumentException("Chunk parallel-jobs must be positive.");
        Path root=work.resolve("chunks"),planPath=root.resolve("plan.properties");Files.createDirectories(root);
        Properties plan=RunCoordinator.load(planPath);
        if(plan.isEmpty()) {
            var metadata=query("SELECT c.NULLABLE, (SELECT COUNT(*) FROM ALL_IND_COLUMNS ic JOIN ALL_INDEXES ix ON ix.OWNER=ic.INDEX_OWNER AND ix.INDEX_NAME=ic.INDEX_NAME WHERE ic.TABLE_OWNER=c.OWNER AND ic.TABLE_NAME=c.TABLE_NAME AND ic.COLUMN_NAME=c.COLUMN_NAME AND ic.COLUMN_POSITION=1 AND ix.VISIBILITY='VISIBLE' AND ix.STATUS IN ('VALID','N/A')) FROM ALL_TAB_COLUMNS c WHERE c.OWNER="+literal(table.schema())+" AND c.TABLE_NAME="+literal(table.name())+" AND c.COLUMN_NAME="+literal(column),root);
            if(metadata.size()!=1||new BigDecimal(metadata.get(0).get(1).trim()).signum()==0)throw new IllegalArgumentException("Chunking requires a visible valid index with the numeric column first: "+table.name()+"."+column);
            boolean nullable="Y".equalsIgnoreCase(metadata.get(0).get(0).trim());
            String scn=query("SELECT TO_CHAR(DBMS_FLASHBACK.GET_SYSTEM_CHANGE_NUMBER,'TM9') FROM dual",root).get(0).get(0).trim();
            if(!scn.matches("[0-9]+"))throw new IOException("Oracle returned an invalid snapshot SCN.");
            String source=quoted(table.schema())+"."+quoted(table.name());
            var bounds=query("SELECT (SELECT TO_CHAR(MIN("+quoted(column)+"),'TM9','NLS_NUMERIC_CHARACTERS=''.,''') FROM "+source+" AS OF SCN "+scn+"), (SELECT TO_CHAR(MAX("+quoted(column)+"),'TM9','NLS_NUMERIC_CHARACTERS=''.,''') FROM "+source+" AS OF SCN "+scn+") FROM dual",root).get(0);
            plan.setProperty("scn",scn);plan.setProperty("column",column);plan.setProperty("range-size",size.toPlainString());
            int count=0;
            if(bounds.get(0)!=null&&!bounds.get(0).isBlank()) {
                BigDecimal lower=new BigDecimal(bounds.get(0).trim()),max=new BigDecimal(bounds.get(1).trim());
                while(lower.compareTo(max)<=0){plan.setProperty("c"+count+".lower",lower.toPlainString());lower=lower.add(size);plan.setProperty("c"+count+".upper",lower.toPlainString());count=Math.incrementExact(count);}
            }
            if(nullable){plan.setProperty("c"+count+".null","true");count=Math.incrementExact(count);}
            if(count==0){plan.setProperty("c0.empty","true");count=1;}plan.setProperty("count",Integer.toString(count));RunCoordinator.save(plan,planPath);
        }
        if(!column.equals(plan.getProperty("column"))||size.compareTo(new BigDecimal(plan.getProperty("range-size")))!=0)throw new IllegalArgumentException("Chunk settings changed for an existing run. Use a new request ID.");
        final Properties savedPlan=plan;int count=Integer.parseInt(plan.getProperty("count"));var next=new AtomicInteger();
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(workers,count));var futures=new ArrayList<Future<Void>>();
        try {
            for(int worker=0;worker<Math.min(workers,count);worker++)futures.add(pool.submit(()->{
                for(int index;(index=next.getAndIncrement())<count;) {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                    Path directory=root.resolve(Integer.toString(index));Files.createDirectories(directory);
                    Path chunk=directory.resolve("chunk.dat"),statePath=directory.resolve("complete.properties");var state=RunCoordinator.load(statePath);
                    if(valid(chunk,state)){LOG.info("Reusing {} chunk {}",table.name(),index);continue;}
                    var spec=new Properties();spec.setProperty("scn",savedPlan.getProperty("scn"));spec.setProperty("column",column);
                    if(savedPlan.containsKey("c"+index+".null"))spec.setProperty("null","true");
                    else if(savedPlan.containsKey("c"+index+".empty"))spec.setProperty("empty","true");
                    else {spec.setProperty("lower",savedPlan.getProperty("c"+index+".lower"));spec.setProperty("upper",savedPlan.getProperty("c"+index+".upper"));}
                    RunCoordinator.save(spec,directory.resolve("chunk.properties"));
                    LOG.info("Starting {} chunk {} at SCN {}",table.name(),index,spec.getProperty("scn"));
                    Path temporary=directory.resolve("chunk.dat.part");Stats stats;
                    stats=extractSingle(table,window,temporary,directory,date);
                    if(Files.size(temporary)!=stats.bytes()||!hash(temporary).equals(stats.sha256()))throw new IOException("Chunk validation failed: "+index);
                    RunCoordinator.move(temporary,chunk);state.setProperty("records",Long.toString(stats.records()));state.setProperty("sum",stats.controlSum());state.setProperty("bytes",Long.toString(stats.bytes()));state.setProperty("sha256",stats.sha256());RunCoordinator.save(state,statePath);
                    Files.deleteIfExists(directory.resolve("data.csv"));LOG.info("Completed {} chunk {}: {} records",table.name(),index,stats.records());
                }
                return null;
            }));
            pool.shutdown();
            Exception failure=null;for(var future:futures)try{future.get();}catch(ExecutionException e){if(failure==null)failure=new IOException("Chunk extraction failed for "+table.name()+". Completed chunks are retained; retry with the same run ID. An expired snapshot requires a new run ID.",e.getCause());}
            if(failure!=null)throw failure;
        }catch(InterruptedException interrupted){for(var future:futures)future.cancel(true);delegate.cancel();throw interrupted;}
        finally{pool.shutdownNow();boolean interrupted=Thread.interrupted();for(;;)try{if(pool.awaitTermination(1,TimeUnit.SECONDS))break;}catch(InterruptedException e){interrupted=true;delegate.cancel();}if(interrupted)Thread.currentThread().interrupt();}
        try(var output=new DelimitedOutput(part,value("delimiter","\u0007"),value("system-code","PBI"),value("country-code","SG"),date,table)) {
            for(int i=0;i<count;i++){
                Path directory=root.resolve(Integer.toString(i)),chunk=directory.resolve("chunk.dat");var state=RunCoordinator.load(directory.resolve("complete.properties"));
                if(!valid(chunk,state))throw new IOException("Completed chunk is missing or invalid: "+i);
                output.appendChunk(chunk,new Stats(Long.parseLong(state.getProperty("records")),state.getProperty("sum"),Long.parseLong(state.getProperty("bytes")),state.getProperty("sha256")));
            }
            return output.finish();
        }
    }
    static boolean valid(Path file,Properties state) throws Exception {return state.containsKey("sha256")&&Files.isRegularFile(file)&&Long.toString(Files.size(file)).equals(state.getProperty("bytes"))&&hash(file).equals(state.getProperty("sha256"));}
    static String hash(Path file) throws Exception {var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] bytes=new byte[65536];for(int n;(n=input.read(bytes))!=-1;)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest());}
    static String chunkSql(Table table,String sql,Path work) throws IOException {
        var chunk=RunCoordinator.load(work.resolve("chunk.properties"));if(chunk.isEmpty())return sql;
        String scn=chunk.getProperty("scn"),column=chunk.getProperty("column");
        if(!scn.matches("[0-9]+")||table.columns().stream().noneMatch(c->c.numeric()&&c.name().equals(column)))throw new IOException("Invalid saved chunk specification.");
        String predicate=Boolean.parseBoolean(chunk.getProperty("empty","false"))?"1=0":quoted(column)+(Boolean.parseBoolean(chunk.getProperty("null","false"))?" IS NULL":" >= "+new BigDecimal(chunk.getProperty("lower")).toPlainString()+" AND "+quoted(column)+" < "+new BigDecimal(chunk.getProperty("upper")).toPlainString());
        String source="\nFROM "+quoted(table.schema())+"."+quoted(table.name());int position=sql.lastIndexOf(source);
        if(position<0)throw new IOException("Chunking requires the generated table SELECT structure.");
        int end=position+source.length();String tail=sql.substring(end).stripLeading();boolean where=tail.startsWith("WHERE ");if(where)tail=tail.substring(6);
        return sql.substring(0,end)+" AS OF SCN "+scn+" WHERE "+predicate+(where?" AND ":"\n")+tail;
    }
    @Override public void cancel(){delegate.cancel();}
}

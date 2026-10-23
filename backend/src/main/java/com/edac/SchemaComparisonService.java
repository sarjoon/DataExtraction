package com.edac;
import java.sql.SQLException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SchemaComparisonService {
    public record Request(@Positive long beforeId,@Positive long afterId,boolean includeRowCounts,boolean normalizeTypes){}
    public record Job(String id,String status,String message,int beforeTablesRead,int afterTablesRead,String reportId){}
    private final RegistryService registry;private final SchemaMetadata metadata;private final SchemaComparisonReports reports;
    private final Map<String,Job> jobs=new ConcurrentHashMap<>();private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var thread=new Thread(r,"schema-comparison");thread.setDaemon(true);return thread;});private String active;
    public SchemaComparisonService(RegistryService registry,SchemaMetadata metadata,SchemaComparisonReports reports){this.registry=registry;this.metadata=metadata;this.reports=reports;}
    public synchronized Job start(Request request){
        if(request.beforeId()==request.afterId())throw new IllegalArgumentException("Choose two different database connections.");
        var before=registry.connection(request.beforeId());var after=registry.connection(request.afterId());
        if(before.jdbcUrl().equalsIgnoreCase(after.jdbcUrl())&&before.schema().equals(after.schema()))throw new IllegalArgumentException("Choose different databases or schemas.");
        if(active!=null)throw new IllegalArgumentException("A schema comparison is already running. Wait for it to finish.");
        String timestamp=DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").format(LocalDateTime.now()),id="Schema_"+timestamp+"_"+UUID.randomUUID().toString().substring(0,8);
        var job=new Job(id,"RUNNING","Reading Before metadata",0,0,null);active=id;jobs.put(id,job);jobs.entrySet().removeIf(e->!e.getKey().equals(active)&&!e.getValue().status().equals("RUNNING")&&jobs.size()>50);
        worker.submit(()->run(id,timestamp,request,before,after));return job;
    }
    private void run(String id,String timestamp,Request request,Models.ConnectionProfile before,Models.ConnectionProfile after){
        Instant started=Instant.now();try{
            var left=metadata.read(before.id(),request.includeRowCounts(),table->progress(id,true,table));
            var right=metadata.read(after.id(),request.includeRowCounts(),table->progress(id,false,table));
            var sections=SchemaComparisonEngine.compare(left,right,request.normalizeTypes(),request.includeRowCounts());
            boolean differs=!sections.get("objectDifferences").isEmpty()||!sections.get("structureDifferences").isEmpty()||sections.get("rowCounts").stream().anyMatch(r->!r.get("status").equals("Same count"));
            var data=SchemaComparisonEngine.row("id",id,"timestamp",timestamp,"startedAt",started.toString(),"completedAt",Instant.now().toString(),"before",connection(before),"after",connection(after),"includeRowCounts",request.includeRowCounts(),"normalizeTypes",request.normalizeTypes(),"status",differs?"Differences exist":"No differences in comparable metadata",
                "notes",List.of("Counts include objects visible to each login in the selected schema/owner; permission restrictions can affect coverage.","Object names are matched without case. Names differing only by case are rejected as ambiguous.","Type normalization compares logical families and declared capacities, not complete vendor storage semantics.","Procedure/view/trigger source code, grants, physical storage and index rebuild statistics are not compared.","Constraint and index names are compared literally, including generated names. Unique enforcement is compared through index definitions.","Unsupported object categories have no numeric count; missing driver attributes require review.","Keep schemas stable while comparing. Row counts are optional exact queries; no table data is loaded into application memory."));
            reports.write(id,data,sections);finish(id,"COMPLETED","Comparison completed",id);
        }catch(Exception e){String message=e instanceof SQLException sql?"Schema metadata/count query failed (database error "+sql.getErrorCode()+"). Check metadata permissions, schema/owner and connection settings.":Objects.toString(e.getMessage(),"Schema comparison failed.");finish(id,"FAILED",message,null);}
    }
    private Map<String,Object> connection(Models.ConnectionProfile p){return Map.of("id",p.id(),"name",p.name(),"databaseType",p.databaseType(),"database",p.databaseName(),"schema",p.schema());}
    private void progress(String id,boolean before,String table){if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Schema comparison interrupted.");var p=jobs.get(id);jobs.put(id,new Job(id,"RUNNING","Inspecting "+(before?"Before: ":"After: ")+table,p.beforeTablesRead()+(before?1:0),p.afterTablesRead()+(before?0:1),null));}
    private synchronized void finish(String id,String status,String message,String report){var p=jobs.get(id);active=null;jobs.put(id,new Job(id,status,message,p.beforeTablesRead(),p.afterTablesRead(),report));}
    public Job job(String id){var job=jobs.get(id);if(job==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Schema job not found. Completed reports remain in schema history.");return job;}
    @PreDestroy void close(){worker.shutdownNow();}
}

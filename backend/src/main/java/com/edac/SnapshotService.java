package com.edac;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static com.edac.SnapshotDatabase.*;

@Service
public class SnapshotService {
    static final int BATCH=200;
    public record Config(String tableName,List<String> ignoredColumns) {}
    public record Request(long sourceId,long targetId,List<String> tables,String mode,Long batchId) {
        public Request(long sourceId,long targetId,List<String> tables,String mode){this(sourceId,targetId,tables,mode,null);}
    }
    public record Batch(long id,String name,List<String> tables) {}
    public record BatchInput(Long id,String name,List<String> tables) {}
    public record Job(String id,String operation,String status,String message,long processed,String reportId) {}
    private final JdbcTemplate db;private final RegistryService registry;private final SnapshotDatabase databases;private final SnapshotReports reports;private final ObjectMapper json;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"snapshot-worker");t.setDaemon(true);return t;});
    private final Map<String,Job> jobs=new ConcurrentHashMap<>();private String active;
    public SnapshotService(JdbcTemplate db,RegistryService registry,SnapshotDatabase databases,SnapshotReports reports,ObjectMapper json){this.db=db;this.registry=registry;this.databases=databases;this.reports=reports;this.json=json;this.transactions=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));}
    @PreDestroy void close(){worker.shutdownNow();}
    String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Cannot serialize snapshot data.",e);}}
    Map<String,String> decode(String text){try{return json.readValue(text,new TypeReference<LinkedHashMap<String,String>>(){});}catch(Exception e){throw new IllegalArgumentException("Cannot read snapshot working data.",e);}}
    public List<String> registeredTables(long connection){registry.connection(connection);return db.queryForList("SELECT table_name FROM snapshot_table_registry WHERE connection_id=? ORDER BY table_name",String.class,connection);}
    public List<Batch> batches(long connection){
        registry.connection(connection);
        return db.query("SELECT id,batch_name FROM snapshot_batch WHERE connection_id=? ORDER BY batch_name",(r,n)->new Batch(r.getLong(1),r.getString(2),db.queryForList("SELECT table_name FROM snapshot_batch_table WHERE batch_id=? ORDER BY table_name",String.class,r.getLong(1))),connection);
    }
    private Batch batch(long connection,long id){return batches(connection).stream().filter(b->b.id()==id).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Snapshot batch not found for this connection."));}
    @org.springframework.transaction.annotation.Transactional
    public synchronized Batch saveBatch(long connection,BatchInput input){
        if(active!=null)throw new IllegalArgumentException("Wait for the active Snapshot job before changing batches.");
        registry.connection(connection);
        if(input==null||input.name()==null||!input.name().trim().matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,79}"))throw new IllegalArgumentException("Batch names must start with a letter or digit and contain up to 80 letters, digits, spaces, underscores or hyphens.");
        if(input.tables()==null||input.tables().isEmpty())throw new IllegalArgumentException("Assign at least one registered table to the batch.");
        var tables=input.tables().stream().map(RegistryService::identifier).toList();
        if(new HashSet<>(tables).size()!=tables.size())throw new IllegalArgumentException("Assign each table once.");
        if(!new HashSet<>(registeredTables(connection)).containsAll(tables))throw new IllegalArgumentException("Assign only registered Snapshot tables for this connection.");
        if(input.id()!=null)batch(connection,input.id());
        if(batches(connection).stream().anyMatch(b->b.name().equals(input.name().trim())&&!Objects.equals(input.id(),b.id())))throw new IllegalArgumentException("A batch with that name already exists. Edit the existing batch or choose another name.");
        return storeBatch(connection,new BatchInput(input.id(),input.name().trim(),tables));
    }
    private Batch storeBatch(long connection,BatchInput input){
        Long id=input.id();
        if(id==null){var existing=db.queryForList("SELECT id FROM snapshot_batch WHERE connection_id=? AND batch_name=?",Long.class,connection,input.name());if(!existing.isEmpty())id=existing.get(0);}
        if(id==null){var holder=new org.springframework.jdbc.support.GeneratedKeyHolder();db.update(c->{var statement=c.prepareStatement("INSERT INTO snapshot_batch(connection_id,batch_name) VALUES(?,?)",new String[]{"id"});statement.setLong(1,connection);statement.setString(2,input.name());return statement;},holder);id=Objects.requireNonNull(holder.getKey()).longValue();}
        else db.update("UPDATE snapshot_batch SET batch_name=? WHERE id=? AND connection_id=?",input.name(),id,connection);
        db.update("DELETE FROM snapshot_batch_table WHERE batch_id=?",id);
        for(String table:input.tables())db.update("INSERT INTO snapshot_batch_table(batch_id,connection_id,table_name) VALUES(?,?,?)",id,connection,table);
        return new Batch(id,input.name(),input.tables().stream().sorted().toList());
    }
    @org.springframework.transaction.annotation.Transactional
    public synchronized void removeBatch(long connection,long id){if(active!=null)throw new IllegalArgumentException("Wait for the active Snapshot job before removing batches.");batch(connection,id);db.update("DELETE FROM snapshot_batch WHERE id=? AND connection_id=?",id,connection);}
    public List<String> discoverTables(long connection) throws SQLException {try(var c=databases.open(connection)){return databases.discover(c,connection);}}
    @org.springframework.transaction.annotation.Transactional
    public synchronized List<String> registerTables(long connection,List<String> names) throws SQLException {
        if(active!=null)throw new IllegalArgumentException("Wait for the active Snapshot job before changing registered tables.");
        registry.connection(connection);
        if(names==null)throw new IllegalArgumentException("Provide the selected Snapshot tables.");
        var selected=new LinkedHashSet<String>();for(String name:names)if(!selected.add(RegistryService.identifier(name)))throw new IllegalArgumentException("Select each table once.");
        // Validate the complete selection against the source dictionary before updating H2.
        if(!selected.isEmpty())try(var c=databases.open(connection)){for(String name:selected)databases.describe(c,connection,name);}
        for(String name:selected)db.update("MERGE INTO snapshot_table_registry(connection_id,table_name) KEY(connection_id,table_name) VALUES(?,?)",connection,name);
        for(String name:registeredTables(connection))if(!selected.contains(name))db.update("DELETE FROM snapshot_table_registry WHERE connection_id=? AND table_name=?",connection,name);
        return registeredTables(connection);
    }
    private String registeredTable(long connection,String name){String normalized=RegistryService.identifier(name);if(!registeredTables(connection).contains(normalized))throw new IllegalArgumentException("Select a registered Snapshot table. Use Snapshot Table Definition first.");return normalized;}
    public List<Config> config(long connection){registry.connection(connection);return db.query("SELECT table_name,ignored_columns FROM snapshot_table WHERE connection_id=? ORDER BY table_name",(r,n)->{
        try{return new Config(r.getString(1),json.readValue(r.getString(2),new TypeReference<List<String>>(){}));}catch(Exception e){throw new IllegalStateException(e);}
    },connection);}
    @org.springframework.transaction.annotation.Transactional
    public synchronized List<Config> configure(long connection,List<Config> configs) throws SQLException {
        if(active!=null)throw new IllegalArgumentException("Wait for the active Snapshot job before changing configuration.");
        if(configs==null)throw new IllegalArgumentException("Provide Snapshot table configurations.");
        var validated=new ArrayList<Config>();var unique=new HashSet<String>();
        try(var c=databases.open(connection)){for(var input:configs){if(input==null||input.ignoredColumns()==null)throw new IllegalArgumentException("Provide a table and ignored columns.");String name=registeredTable(connection,input.tableName());if(!unique.add(name))throw new IllegalArgumentException("Each table can be configured once.");var table=databases.describe(c,connection,name);var ignored=input.ignoredColumns().stream().map(RegistryService::identifier).distinct().toList();for(String col:ignored){table.column(col);if(table.keys().contains(col))throw new IllegalArgumentException("Primary-key columns cannot be ignored: "+col);}validated.add(new Config(name,ignored));}}
        // MERGE then remove obsolete entries; validation completes before configuration is changed.
        for(var item:validated)db.update("MERGE INTO snapshot_table(connection_id,table_name,ignored_columns) KEY(connection_id,table_name) VALUES(?,?,?)",connection,item.tableName(),encode(item.ignoredColumns()));
        for(var old:config(connection))if(!unique.contains(old.tableName()))db.update("DELETE FROM snapshot_table WHERE connection_id=? AND table_name=?",connection,old.tableName());
        return config(connection);
    }
    public Map<String,Object> columns(long connection,String table) throws SQLException {String name=registeredTable(connection,table);try(var c=databases.open(connection)){var t=databases.describe(c,connection,name);return Map.of("tableName",t.name(),"schema",t.schema(),"primaryKeys",t.keys(),"columns",t.columns());}}
    private List<Config> validate(Request request){
        if(request.sourceId()==request.targetId())throw new IllegalArgumentException("Choose different Before/Source and After/Target connections.");
        registry.connection(request.sourceId());registry.connection(request.targetId());
        List<String> tables=request.batchId()==null?request.tables():batch(request.sourceId(),request.batchId()).tables();
        if(tables==null||tables.isEmpty())throw new IllegalArgumentException("Select configured tables or a non-empty Snapshot batch.");
        var selected=tables.stream().map(RegistryService::identifier).distinct().toList();if(selected.size()!=tables.size())throw new IllegalArgumentException("Select each table once.");
        if(request.batchId()!=null&&request.tables()!=null&&!request.tables().isEmpty()&&!new HashSet<>(request.tables()).equals(new HashSet<>(tables)))throw new IllegalArgumentException("Run the complete saved batch or select tables manually.");
        var configs=config(request.sourceId());var result=new ArrayList<Config>();for(String name:selected)result.add(configs.stream().filter(c->c.tableName().equals(name)).findFirst().orElseThrow(()->new IllegalArgumentException("Configure the source table first: "+name)));return result;
    }
    private void different(Connection source,long sourceId,Connection target,long targetId) throws SQLException {
        String a=source.getMetaData().getURL(),b=target.getMetaData().getURL();
        if(a.equalsIgnoreCase(b)&&databases.schema(source,sourceId).equals(databases.schema(target,targetId)))throw new IllegalArgumentException("Source and target resolve to the same database and schema.");
    }
    public List<Map<String,Object>> inspect(Request request) throws SQLException {
        var configs=validate(request);var result=new ArrayList<Map<String,Object>>();
        try(var source=databases.open(request.sourceId());var target=databases.open(request.targetId())){different(source,request.sourceId(),target,request.targetId());
            for(var config:configs){var t=databases.describe(source,request.sourceId(),config.tableName());boolean exists=databases.exists(target,request.targetId(),t.name());long count=exists?count(target,databases.describe(target,request.targetId(),t.name())):0;result.add(Map.of("tableName",t.name(),"exists",exists,"recordCount",count));}}
        return result;
    }
    public synchronized Job start(Request request,boolean compare){
        List<Config> configs=validate(request);if(active!=null)throw new IllegalArgumentException("A Snapshot job is already running. Wait for it to finish.");
        if(!compare&&!Set.of("CREATE","APPEND","TRUNCATE").contains(Objects.toString(request.mode(),"")))throw new IllegalArgumentException("Choose truncate and import, or append and update duplicate keys.");
        String timestamp=DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").format(LocalDateTime.now());
        Batch selectedBatch=request.batchId()==null?null:batch(request.sourceId(),request.batchId());
        String prefix=selectedBatch==null?configs.get(0).tableName()+(configs.size()>1?"_plus_"+(configs.size()-1)+"_tables":""):selectedBatch.name().replace(' ','_');
        String id=prefix+"_"+timestamp+"_"+UUID.randomUUID().toString().substring(0,8);
        boolean reclaim=jobs.isEmpty();
        Job job=new Job(id,compare?"COMPARE":"CAPTURE","RUNNING","Starting",0,null);active=id;jobs.put(id,job);
        jobs.entrySet().removeIf(e->!e.getKey().equals(active)&&!e.getValue().status().equals("RUNNING")&&jobs.size()>50);
        worker.submit(()->{
            String failure=null;
            try{if(reclaim)clearWorkingData();if(compare)compare(id,timestamp,request,configs,selectedBatch);else capture(id,request,configs,selectedBatch);}
            catch(Exception e){failure=e instanceof SQLException s?"Database operation failed (error "+s.getErrorCode()+"). Check table permissions, column types and connection settings.":e instanceof org.springframework.dao.DataAccessException?"Snapshot working data could not be stored. Check configuration database availability and disk space.":Objects.toString(e.getMessage(),"Snapshot operation failed.");}
            finally{
                try{clearWorkingData();}catch(Exception cleanup){if(failure==null)failure="Snapshot finished, but working data cleanup failed. Check configuration database availability.";}
                synchronized(this){active=null;update(id,failure==null?"COMPLETED":"FAILED",failure==null?"Completed":failure,jobs.get(id).processed(),compare&&failure==null?id:null);}
            }
        });
        return job;
    }
    public Job job(String id){Job job=jobs.get(id);if(job==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Snapshot job not found. Completed reports remain in History.");return job;}
    private void clearWorkingData(){
        // These two EDAC scratch tables belong exclusively to the single Snapshot worker.
        db.execute("TRUNCATE TABLE snapshot_key_stage");db.execute("TRUNCATE TABLE snapshot_row_stage");
    }
    private void update(String id,String status,String message,long processed,String report){Job previous=jobs.get(id);jobs.put(id,new Job(id,previous.operation(),status,message,processed,report));}
    private void progress(String id,String message,long increment){if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Snapshot job interrupted.");var previous=jobs.get(id);update(id,"RUNNING",message,previous.processed()+increment,null);}
    private static void compatible(Table before,Table after){
        if(!before.keys().equals(after.keys()))throw new IllegalArgumentException("Primary keys differ for "+before.name());
        if(!new HashSet<>(before.columns().stream().map(Column::name).toList()).equals(new HashSet<>(after.columns().stream().map(Column::name).toList())))throw new IllegalArgumentException("Column names differ for "+before.name());
    }
    private void capture(String id,Request request,List<Config> configs,Batch selectedBatch) throws SQLException {
        try(var source=databases.open(request.sourceId());var target=databases.open(request.targetId())){
            different(source,request.sourceId(),target,request.targetId());boolean oracle=registry.connection(request.targetId()).databaseType().equals("ORACLE");
            // Validate every table before the first destination mutation.
            for(var config:configs){var table=databases.describe(source,request.sourceId(),config.tableName());for(var col:table.columns())ddlType(col,oracle);
                if(databases.exists(target,request.targetId(),table.name())){var dest=databases.describe(target,request.targetId(),table.name());compatible(table,dest);if("CREATE".equals(request.mode())&&count(target,dest)>0)throw new IllegalArgumentException("Destination now contains data. Inspect again and choose truncate or append.");}}
            // Oracle DDL commits implicitly. Finish all table creation before changing any data.
            for(var config:configs)if(!databases.exists(target,request.targetId(),config.tableName()))create(target,databases.schema(target,request.targetId()),databases.describe(source,request.sourceId(),config.tableName()),oracle);
            target.setAutoCommit(false);
            try{for(var config:configs){var table=databases.describe(source,request.sourceId(),config.tableName());
                var dest=databases.describe(target,request.targetId(),table.name());
                if("TRUNCATE".equals(request.mode()))try(var statement=target.createStatement()){statement.executeUpdate("DELETE FROM "+dest.sql());} // Transactional truncate-and-import semantics.
                List<String> columns=table.columns().stream().map(Column::name).toList();
                try(var read=source.prepareStatement("SELECT "+names(columns)+" FROM "+table.sql(),ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY);var write=target.prepareStatement(mergeSql(dest,columns,oracle))){
                    read.setFetchSize(BATCH);read.setQueryTimeout(300);write.setQueryTimeout(300);int batch=0;
                    try(var rows=read.executeQuery()){while(rows.next()){for(int i=0;i<columns.size();i++)bind(write,i+1,dest.column(columns.get(i)),value(rows,i+1,table.columns().get(i)));write.addBatch();if(++batch==BATCH){write.executeBatch();progress(id,"Copying "+table.name(),batch);batch=0;}}}
                    if(batch>0){write.executeBatch();progress(id,"Copying "+table.name(),batch);}
                }
            }target.commit();}catch(Exception e){target.rollback();throw e;}
        }
        // A captured database can immediately be selected as Before, with the same exclusions.
        transactions.executeWithoutResult(status->{for(var config:configs){db.update("MERGE INTO snapshot_table_registry(connection_id,table_name) KEY(connection_id,table_name) VALUES(?,?)",request.targetId(),config.tableName());db.update("MERGE INTO snapshot_table(connection_id,table_name,ignored_columns) KEY(connection_id,table_name) VALUES(?,?,?)",request.targetId(),config.tableName(),encode(config.ignoredColumns()));}if(selectedBatch!=null)storeBatch(request.targetId(),new BatchInput(null,selectedBatch.name(),configs.stream().map(Config::tableName).toList()));});
    }
    private String hash(Map<String,String> key){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(key).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private void stageKeys(String id,Connection connection,Table table,String side) throws SQLException {
        String sql="SELECT "+names(table.keys())+" FROM "+table.sql();
        var batch=new ArrayList<Object[]>();
        try(var statement=connection.prepareStatement(sql,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
            statement.setFetchSize(BATCH);statement.setQueryTimeout(300);
            try(var rows=statement.executeQuery()){while(rows.next()){var key=new LinkedHashMap<String,String>();for(int i=0;i<table.keys().size();i++)key.put(table.keys().get(i),value(rows,i+1,table.column(table.keys().get(i))));batch.add(new Object[]{id,table.name(),hash(key),encode(key)});if(batch.size()==BATCH){insertKeys(batch,side);progress(id,"Reading "+(side.equals("B")?"Before":"After")+" keys: "+table.name(),batch.size());batch.clear();}}}
        }if(!batch.isEmpty()){insertKeys(batch,side);progress(id,"Reading keys: "+table.name(),batch.size());}
    }
    private void insertKeys(List<Object[]> batch,String side){String column=side.equals("B")?"before_key_json":"after_key_json";transactions.executeWithoutResult(status->db.batchUpdate("MERGE INTO snapshot_key_stage(run_id,table_name,key_hash,"+column+") KEY(run_id,table_name,key_hash) VALUES(?,?,?,?)",batch));}
    private Map<String,Map<String,String>> fetch(Connection c,Table table,List<Map<String,String>> keys,List<String> columns) throws SQLException {
        String select="SELECT "+names(columns)+" FROM "+table.sql()+" WHERE ";
        String predicate=String.join(" AND ",table.keys().stream().map(k->quote(k)+"=?").toList());
        // IN and individual UNION ALL key lookups remain indexable on both H2 and Oracle.
        String sql=table.keys().size()==1?select+quote(table.keys().get(0))+" IN ("+String.join(",",Collections.nCopies(keys.size(),"?"))+")":String.join(" UNION ALL ",Collections.nCopies(keys.size(),select+predicate));
        var result=new HashMap<String,Map<String,String>>();
        try(var statement=c.prepareStatement(sql)){int index=1;for(var key:keys)for(String name:table.keys())bind(statement,index++,table.column(name),key.get(name));statement.setFetchSize(BATCH);statement.setQueryTimeout(300);
            try(var rows=statement.executeQuery()){while(rows.next()){var record=new LinkedHashMap<String,String>();for(int i=0;i<columns.size();i++)record.put(columns.get(i),value(rows,i+1,table.column(columns.get(i))));var key=new LinkedHashMap<String,String>();for(String name:table.keys())key.put(name,record.get(name));result.put(hash(key),record);}}
        }if(result.size()!=keys.size())throw new IllegalArgumentException("Records changed while comparing "+table.name()+". Pause modifications and run again.");return result;
    }
    private record Pair(String hash,Map<String,String> before,Map<String,String> after) {}
    private List<Pair> keyBatch(String id,String table,String category,String cursor){
        int classification=category.equals("matched")?2:category.equals("beforeOnly")?0:1;
        // Include the complete index order: H2 otherwise sorts all matching keys on every page.
        String query="SELECT key_hash,before_key_json,after_key_json FROM snapshot_key_stage USE INDEX (snapshot_key_categories) WHERE run_id=? AND table_name=? AND key_category="+classification+" AND key_hash>? ORDER BY run_id,table_name,key_category,key_hash FETCH FIRST "+BATCH+" ROWS ONLY";
        return db.query(query,(r,n)->new Pair(r.getString(1),r.getString(2)==null?null:decode(r.getString(2)),r.getString(3)==null?null:decode(r.getString(3))),id,table,cursor);
    }
    private void stageRows(String id,String table,String category,List<Object[]> rows){if(!rows.isEmpty())transactions.executeWithoutResult(status->db.batchUpdate("INSERT INTO snapshot_row_stage(run_id,table_name,category,row_json) VALUES(?,?,?,?)",rows));}
    private void compare(String id,String timestamp,Request request,List<Config> configs,Batch selectedBatch) throws Exception {
        Instant started=Instant.now();var summaries=new ArrayList<Map<String,Object>>();
        try(var before=databases.open(request.sourceId());var after=databases.open(request.targetId())){
            different(before,request.sourceId(),after,request.targetId());
            for(var config:configs){var left=databases.describe(before,request.sourceId(),config.tableName());var right=databases.describe(after,request.targetId(),config.tableName());compatible(left,right);
                stageKeys(id,before,left,"B");stageKeys(id,after,right,"A");
                List<String> columns=left.columns().stream().map(Column::name).toList(),compared=columns.stream().filter(c->!left.keys().contains(c)&&!config.ignoredColumns().contains(c)).toList();
                var selected=new ArrayList<>(left.keys());selected.addAll(compared);long unchanged=0;var counts=new HashMap<String,Long>();
                for(String category:List.of("beforeOnly","afterOnly","matched")){String cursor="";long count=0;
                    while(true){List<Pair> batch=keyBatch(id,left.name(),category,cursor);if(batch.isEmpty())break;cursor=batch.get(batch.size()-1).hash();
                        List<String> readColumns=category.equals("matched")?selected:columns;
                        var oldRows=category.equals("afterOnly")?Map.<String,Map<String,String>>of():fetch(before,left,batch.stream().map(Pair::before).toList(),readColumns);
                        var newRows=category.equals("beforeOnly")?Map.<String,Map<String,String>>of():fetch(after,right,batch.stream().map(Pair::after).toList(),readColumns);
                        var output=new ArrayList<Object[]>();
                        for(var pair:batch){if(pair.before()!=null&&pair.after()!=null&&!pair.before().equals(pair.after()))throw new IllegalArgumentException("Primary-key hash collision detected.");
                            var key=pair.before()!=null?pair.before():pair.after();var row=new LinkedHashMap<String,Object>();row.put("primaryKey",key);
                            if(category.equals("matched")){var changes=new ArrayList<Map<String,Object>>();for(String col:compared){String a=oldRows.get(pair.hash()).get(col),b=newRows.get(pair.hash()).get(col);if(!Objects.equals(a,b)){var change=new LinkedHashMap<String,Object>();change.put("column",col);change.put("before",a);change.put("after",b);changes.add(change);}}
                                if(changes.isEmpty()){unchanged++;continue;}row.put("changes",changes);
                            }else row.put("values",category.equals("beforeOnly")?oldRows.get(pair.hash()):newRows.get(pair.hash()));
                            output.add(new Object[]{id,left.name(),category.equals("matched")?"differences":category,encode(row)});count++;
                        }
                        stageRows(id,left.name(),category,output);progress(id,"Comparing "+left.name()+": "+category,batch.size());
                    }counts.put(category,count);
                }
                long beforeCount=counts.get("beforeOnly")+counts.get("matched")+unchanged;
                long afterCount=counts.get("afterOnly")+counts.get("matched")+unchanged;
                var summary=new LinkedHashMap<String,Object>();summary.put("tableName",left.name());summary.put("primaryKeys",left.keys());summary.put("columns",columns);summary.put("comparedColumns",compared);summary.put("ignoredColumns",config.ignoredColumns());summary.put("beforeCount",beforeCount);summary.put("afterCount",afterCount);summary.put("beforeOnlyCount",counts.get("beforeOnly"));summary.put("afterOnlyCount",counts.get("afterOnly"));summary.put("differenceCount",counts.get("matched"));summary.put("unchangedCount",unchanged);summary.put("status",counts.values().stream().mapToLong(Long::longValue).sum()==0?"No difference":"Difference exists");summaries.add(summary);
            }
            var metadata=new LinkedHashMap<String,Object>();metadata.put("id",id);metadata.put("timestamp",timestamp);if(selectedBatch!=null)metadata.put("batch",selectedBatch);metadata.put("startedAt",started.toString());metadata.put("completedAt",Instant.now().toString());metadata.put("before",Map.of("id",request.sourceId(),"name",registry.connection(request.sourceId()).name(),"schema",databases.schema(before,request.sourceId())));metadata.put("after",Map.of("id",request.targetId(),"name",registry.connection(request.targetId()).name(),"schema",databases.schema(after,request.targetId())));
            progress(id,"Saving JSON and Excel ZIP",0);reports.write(id,metadata,summaries);
        }
    }
}

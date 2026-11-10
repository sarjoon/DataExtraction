package com.edac.extraction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.DefaultApplicationArguments;
import com.edac.extraction.Definitions.*;

class RecoveryContractTest {
    @TempDir Path root;
    private final Table table=new Table("t1","APP","BIG_TABLE","Daily",List.of(new Column("ID","NUMBER",true,false),new Column("TEXT","VARCHAR2",false,false)),new Delta("FULL","","","","","","",">=","<"),"ID","/sql/BIG_TABLE.sql");
    private final LocalDate date=LocalDate.of(2026,11,10);
    private static final Window WINDOW=new Window(List.of(),null);
    private static final String LONG_TEXT="??\"|".repeat(20000);
    private static class FakeOracle implements Extractor {
        final ConcurrentMap<String,AtomicInteger> calls=new ConcurrentHashMap<>();final AtomicInteger plans=new AtomicInteger();
        final AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();volatile boolean fail=true;
        public List<List<String>> query(String sql,Path work) {
            plans.incrementAndGet();
            if(sql.contains("ALL_TAB_COLUMNS"))return List.of(List.of("Y","1"));
            if(sql.contains("GET_SYSTEM_CHANGE_NUMBER"))return List.of(List.of("123456"));
            return List.of(List.of("-1.5","3"));
        }
        public Stats extract(Table table,Window window,Path part,Path work,LocalDate date) throws Exception {
            String id=work.getFileName().toString();calls.computeIfAbsent(id,k->new AtomicInteger()).incrementAndGet();
            int running=active.incrementAndGet();peak.accumulateAndGet(running,Math::max);
            try {
                if(fail&&id.equals("1"))throw new IOException("Simulated connection loss");
                var spec=RunCoordinator.load(work.resolve("chunk.properties"));assertEquals("123456",spec.getProperty("scn"));
                try(var output=new DelimitedOutput(part,RunCoordinator.outputSetting(work,new MockEnvironment(),"delimiter","|"),"PBI","SG",date,table)) {
                    for(String value:Arrays.asList("-1.5","-1.5","0","1.5","3",null)) {
                        boolean include=!spec.containsKey("empty")&&(value==null?Boolean.parseBoolean(spec.getProperty("null","false")):!spec.containsKey("null")&&new BigDecimal(value).compareTo(new BigDecimal(spec.getProperty("lower")))>=0&&new BigDecimal(value).compareTo(new BigDecimal(spec.getProperty("upper")))<0);
                        if(include)output.sqlclRow(List.of(value==null?"N":"V"+value,"V"+LONG_TEXT));
                    }
                    Thread.sleep(10);return output.finish();
                }
            }finally{active.decrementAndGet();}
        }
        int total(){return calls.values().stream().mapToInt(AtomicInteger::get).sum();}
    }
    private Properties settings(){var p=new Properties();p.setProperty("delimiter","|");p.setProperty("parallel-jobs","2");p.setProperty("chunks.t1.column","ID");p.setProperty("chunks.t1.range-size","2");p.setProperty("chunks.t1.parallel-jobs","1");return p;}
    @Test void springContextCreatesCoordinatorWithInjectedDependencies() throws Exception {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(Extractor.class,()->(table,window,part,work,date)->{throw new AssertionError("Startup must not query the source");});
            context.register(RunCoordinator.class);context.refresh();
            assertNotNull(context.getBean(RunCoordinator.class));
        }
    }
    @Test void failedChunkResumesAtOriginalSnapshotAndAssemblesOneEnvelope() throws Exception {
        var fake=new FakeOracle();var extraction=new ChunkedExtractor(fake,new MockEnvironment(),settings());Path work=root.resolve("work"),part=root.resolve("result.part");
        assertThrows(IOException.class,()->extraction.extract(table,WINDOW,part,work,date));assertEquals(1,fake.calls.get("0").get());assertEquals(3,fake.plans.get());
        fake.fail=false;var stats=extraction.extract(table,WINDOW,part,work,date);
        assertEquals(6,stats.records());assertEquals("1.5",stats.controlSum());assertEquals(1,fake.calls.get("0").get());assertEquals(2,fake.calls.get("1").get());assertEquals(3,fake.plans.get());
        String content=Files.readString(part);assertEquals(1,content.lines().filter(s->s.startsWith("H|")).count());assertEquals(6,content.lines().filter(s->s.startsWith("D|")).count());assertEquals(1,content.lines().filter(s->s.startsWith("T|")).count());assertTrue(content.endsWith("T|6|1.5\n"));assertTrue(content.contains("??\"\"|"));
        int before=fake.total();Files.delete(part);extraction.extract(table,WINDOW,part,work,date);assertEquals(before,fake.total());assertEquals(3,fake.plans.get());
        Files.writeString(work.resolve("chunks/2/chunk.dat"),"corrupt");extraction.extract(table,WINDOW,part,work,date);assertEquals(before+1,fake.total());assertEquals(2,fake.calls.get("2").get());
    }
    @Test void emptyNonNullableTableStillProducesEnvelope() throws Exception {
        var fake=new FakeOracle(){@Override public List<List<String>> query(String sql,Path work){if(sql.contains("ALL_TAB_COLUMNS"))return List.of(List.of("N","1"));if(sql.contains("GET_SYSTEM_CHANGE_NUMBER"))return List.of(List.of("123456"));return List.of(Arrays.asList(null,null));}};
        fake.fail=false;var extraction=new ChunkedExtractor(fake,new MockEnvironment(),settings());Path part=root.resolve("empty.part");
        var stats=extraction.extract(table,WINDOW,part,root.resolve("empty"),date);assertEquals(0,stats.records());assertTrue(Files.readString(part).endsWith("T|0|0\n"));
    }
    @Test void workFoldersUseSchemaAndTableAndRetainLegacyRecoveryState() throws Exception {
        Path run=root.resolve("run"),legacy=run.resolve("work/t1");Files.createDirectories(legacy.resolve("chunks/0"));Files.writeString(legacy.resolve("chunks/0/chunk.dat"),"saved chunk");
        Path named=RunCoordinator.workDirectory(run,table);assertEquals(run.toAbsolutePath().resolve("work/APP.BIG_TABLE"),named);assertFalse(Files.exists(legacy));assertEquals("saved chunk",Files.readString(named.resolve("chunks/0/chunk.dat")));
        assertEquals(named,RunCoordinator.workDirectory(run,table));
        var other=new Table("t2","OTHER",table.name(),table.group(),table.columns(),table.delta(),table.controlSumColumn(),table.sqlResource());assertNotEquals(named,RunCoordinator.workDirectory(run,other));
        var reserved=new Table("t3","APP","CON",table.group(),table.columns(),table.delta(),table.controlSumColumn(),table.sqlResource());assertEquals("APP.%43ON",RunCoordinator.workDirectory(run,reserved).getFileName().toString());
        Files.createDirectories(legacy);assertThrows(IOException.class,()->RunCoordinator.workDirectory(run,table));assertEquals("saved chunk",Files.readString(named.resolve("chunks/0/chunk.dat")));
    }
    @Test void chunkQueriesKeepDeltaBindsAndSqlclTerminators() throws Exception {
        Path work=root.resolve("query");var spec=new Properties();spec.setProperty("scn","123456");spec.setProperty("column","ID");spec.setProperty("lower","-1.5");spec.setProperty("upper","0.5");RunCoordinator.save(spec,work.resolve("chunk.properties"));
        String jdbc=ChunkedExtractor.chunkSql(table,"SELECT ID\nFROM \"APP\".\"BIG_TABLE\" WHERE UPDATED >= ? AND UPDATED < ?\n",work);
        assertTrue(jdbc.contains("AS OF SCN 123456 WHERE \"ID\" >= -1.5 AND \"ID\" < 0.5 AND UPDATED >= ? AND UPDATED < ?"));
        String sqlcl=ChunkedExtractor.chunkSql(table,"SET SQLTERMINATOR OFF\nWITH FUNCTION X RETURN NUMBER IS BEGIN RETURN 1; END;\nSELECT X\nFROM \"APP\".\"BIG_TABLE\"\n/\nSET SQLTERMINATOR ON\n",work);
        assertTrue(sqlcl.contains("AS OF SCN 123456 WHERE"));assertTrue(sqlcl.endsWith("/\nSET SQLTERMINATOR ON\n"));
    }
    @Test void chunkConcurrencyRespectsGlobalQueryBudget() throws Exception {
        var fake=new FakeOracle();fake.fail=false;var settings=settings();settings.setProperty("chunks.t1.parallel-jobs","4");var extraction=new ChunkedExtractor(fake,new MockEnvironment(),settings);
        extraction.extract(table,WINDOW,root.resolve("parallel.part"),root.resolve("parallel"),date);assertTrue(fake.peak.get()<=2);assertEquals(6,Files.readString(root.resolve("parallel.part")).lines().filter(s->s.startsWith("D|")).count());
    }
    @Test void explicitRerunRestoresSettingsAndRepairsMissingFiles() throws Exception {
        var calls=new AtomicInteger();
        Extractor fake=(table,window,part,work,date)->{
            if(calls.incrementAndGet()==1)throw new IOException("Simulated extraction failure");
            try(var out=new DelimitedOutput(part,RunCoordinator.outputSetting(work,new MockEnvironment(),"delimiter","wrong"),"PBI","SG",date,table)){return out.finish();}
        };
        var firstEnv=new MockEnvironment().withProperty("extraction.business-date","20261110").withProperty("extraction.request-id","retry-contract").withProperty("extraction.delimiter","||").withProperty("extraction.output-directory",root.toString());
        var first=new RunCoordinator(firstEnv,fake,new Definitions("TEST",List.of(table)));first.run(new DefaultApplicationArguments());assertEquals(1,first.exitCode());
        var retryEnv=new MockEnvironment().withProperty("extraction.run-id","retry-contract").withProperty("extraction.rerun","failed-or-missing").withProperty("extraction.delimiter","wrong").withProperty("extraction.output-directory",root.toString());
        var retry=new RunCoordinator(retryEnv,fake,new Definitions("TEST",List.of(table)));retry.run(new DefaultApplicationArguments());assertEquals(0,retry.exitCode());assertEquals(2,calls.get());
        Path run=root.resolve("runs/20261110/retry-contract"),file;
        try(var paths=Files.list(run)){file=paths.filter(p->p.toString().endsWith(".dat")).findFirst().orElseThrow();}
        assertTrue(Files.readString(file).startsWith("H||PBI||SG||20261110||"));
        new RunCoordinator(retryEnv,fake,new Definitions("TEST",List.of(table))).run(new DefaultApplicationArguments());assertEquals(2,calls.get());
        Files.delete(file);new RunCoordinator(retryEnv,fake,new Definitions("TEST",List.of(table))).run(new DefaultApplicationArguments());assertEquals(3,calls.get());
        Files.writeString(file,"corrupt");new RunCoordinator(retryEnv,fake,new Definitions("TEST",List.of(table))).run(new DefaultApplicationArguments());assertEquals(4,calls.get());
        var state=RunCoordinator.load(run.resolve("run.properties"));assertEquals("4",state.getProperty("t1.attempt"));
    }
}

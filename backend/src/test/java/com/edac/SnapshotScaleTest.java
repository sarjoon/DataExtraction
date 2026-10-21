package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named="edac.snapshot.scale",matches="true")
class SnapshotScaleTest {
    @TempDir Path directory;
    @Test void comparesHalfAMillionRecordsUsingDiskBackedPrimaryKeyBatches() throws Exception {
        var settings=new HikariConfig();settings.setJdbcUrl("jdbc:h2:file:"+directory.resolve("working").toString().replace('\\','/')+";MAX_MEMORY_ROWS=10000;CACHE_SIZE=65536");settings.setUsername("sa");settings.setPassword("");settings.setMaximumPoolSize(4);
        try(var pool=new HikariDataSource(settings)){
            Flyway.configure().dataSource(pool).load().migrate();var db=new JdbcTemplate(pool);
            var cipher=mock(CredentialCipher.class);when(cipher.encrypt(anyString())).thenAnswer(c->c.getArgument(0));when(cipher.decrypt(anyString())).thenAnswer(c->c.getArgument(0));
            var oracle=new OracleConnectionFactory();var registry=new RegistryService(db,cipher,new OracleSource(oracle),new H2Source());var databases=new SnapshotDatabase(registry,db,cipher,oracle);var json=new ObjectMapper();var reports=new SnapshotReports(db,json,directory.toString());var service=new SnapshotService(db,registry,databases,reports,json);
            var ids=new ArrayList<Long>();for(int i=0;i<2;i++){String name="scale_"+UUID.randomUUID().toString().replace("-","");ids.add(registry.save(new Models.ConnectionInput(name,"H2","localhost",9092,"MEMORY",name,"sa","PUBLIC","")).id());}
            try{
                for(long id:ids)try(var c=databases.open(id);var s=c.createStatement()){s.execute("CREATE TABLE SCALE_DATA(ID BIGINT PRIMARY KEY,VALUE_N DECIMAL(14,2),AUDIT_N INT)");s.execute("INSERT INTO SCALE_DATA SELECT X,X/10.0,0 FROM SYSTEM_RANGE(1,500000)");}
                try(var c=databases.open(ids.get(1));var s=c.createStatement()){s.execute("DELETE FROM SCALE_DATA WHERE ID=1");s.execute("INSERT INTO SCALE_DATA VALUES(500001,1,0)");s.execute("UPDATE SCALE_DATA SET VALUE_N=99 WHERE ID=3");s.execute("UPDATE SCALE_DATA SET AUDIT_N=1");}
                service.configure(ids.get(0),List.of(new SnapshotService.Config("SCALE_DATA",List.of("AUDIT_N"))));
                long started=System.nanoTime();var job=service.start(new SnapshotService.Request(ids.get(0),ids.get(1),List.of("SCALE_DATA"),null),true);long deadline=started+java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
                while(service.job(job.id()).status().equals("RUNNING")&&System.nanoTime()<deadline)Thread.sleep(100);
                assertThat(service.job(job.id()).status()).as(service.job(job.id()).message()).isEqualTo("COMPLETED");
                var table=(Map<?,?>)((List<?>)reports.summary(job.id()).get("tables")).get(0);
                assertThat(table.get("beforeCount")).isEqualTo(500000);assertThat(table.get("afterCount")).isEqualTo(500000);assertThat(table.get("beforeOnlyCount")).isEqualTo(1);assertThat(table.get("afterOnlyCount")).isEqualTo(1);assertThat(table.get("differenceCount")).isEqualTo(1);assertThat(table.get("unchangedCount")).isEqualTo(499998);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM snapshot_key_stage",Long.class)).isZero();
                System.out.println("Snapshot 500,000-row comparison completed in "+java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)+" seconds.");
            }finally{service.close();for(long id:ids)try(var c=databases.open(id);var s=c.createStatement()){s.execute("SHUTDOWN");}}
        }
    }
}

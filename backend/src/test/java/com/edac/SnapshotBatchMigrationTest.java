package com.edac;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class SnapshotBatchMigrationTest {
    @Test void existingConfigurationsBecomeDefaultBatchesWithoutLosingExclusions() throws Exception {
        String url="jdbc:h2:mem:batch_upgrade_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("10").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
            s.execute("INSERT INTO connection_profile(id,name,jdbc_url,username,schema_name) VALUES(1,'Existing','jdbc:h2:mem:test','sa','PUBLIC')");
            s.execute("INSERT INTO snapshot_table_registry(connection_id,table_name) VALUES(1,'TABLE_A'),(1,'TABLE_B'),(1,'NOT_CONFIGURED')");
            s.execute("INSERT INTO snapshot_table VALUES(1,'TABLE_A','[\"AUDIT_TS\"]'),(1,'TABLE_B','[]')");
            Flyway.configure().dataSource(url,"sa","").load().migrate();
            try(var r=s.executeQuery("SELECT b.batch_name,t.table_name FROM snapshot_batch b JOIN snapshot_batch_table t ON b.id=t.batch_id ORDER BY t.table_name")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("Default");assertThat(r.getString(2)).isEqualTo("TABLE_A");assertThat(r.next()).isTrue();assertThat(r.getString(2)).isEqualTo("TABLE_B");assertThat(r.next()).isFalse();}
            try(var r=s.executeQuery("SELECT ignored_columns FROM snapshot_table WHERE table_name='TABLE_A'")){r.next();assertThat(r.getString(1)).isEqualTo("[\"AUDIT_TS\"]");}
            s.execute("SHUTDOWN");
        }
    }
}

package com.edac;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class SnapshotTableMigrationTest {
    @Test void preservesExistingSnapshotSelectionsWithoutImportingEdacTables() throws Exception {
        String url="jdbc:h2:mem:snapshot_upgrade_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("9").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var sql=connection.createStatement()){
            sql.execute("INSERT INTO connection_profile(id,name,jdbc_url,username,schema_name) VALUES(1,'Existing','jdbc:h2:mem:test','sa','PUBLIC')");
            sql.execute("INSERT INTO table_registry(connection_id,schema_name,table_name,source) VALUES(1,'PUBLIC','EDAC_ONLY','MANUAL')");
            sql.execute("INSERT INTO snapshot_table VALUES(1,'SNAPSHOT_ONLY','[\"AUDIT_TS\"]')");
            Flyway.configure().dataSource(url,"sa","").load().migrate();
            try(var rows=sql.executeQuery("SELECT table_name FROM snapshot_table_registry")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("SNAPSHOT_ONLY");assertThat(rows.next()).isFalse();}
            try(var rows=sql.executeQuery("SELECT ignored_columns FROM snapshot_table")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("[\"AUDIT_TS\"]");}
            sql.execute("DELETE FROM snapshot_table_registry WHERE connection_id=1 AND table_name='SNAPSHOT_ONLY'");
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM snapshot_table")){rows.next();assertThat(rows.getInt(1)).isZero();}
            try(var rows=sql.executeQuery("SELECT table_name FROM table_registry")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("EDAC_ONLY");}
            sql.execute("SHUTDOWN");
        }
    }
}

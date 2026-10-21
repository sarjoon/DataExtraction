package com.edac;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ReadOnlyConnectionMigrationTest {
    @Test void protectsExistingConnectionsDuringUpgrade() throws Exception {
        String url="jdbc:h2:mem:readonly_upgrade_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("11").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
            s.execute("INSERT INTO connection_profile(id,name,jdbc_url,username,schema_name) VALUES(1,'Existing','jdbc:h2:mem:test','sa','PUBLIC')");
            Flyway.configure().dataSource(url,"sa","").load().migrate();
            try(var r=s.executeQuery("SELECT read_only FROM connection_profile WHERE id=1")){assertThat(r.next()).isTrue();assertThat(r.getBoolean(1)).isTrue();}
            s.execute("SHUTDOWN");
        }
    }
}

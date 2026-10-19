package com.edac;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ScreenInstrumentMigrationTest {
    @Test void upgradesExistingScreensAndAllowsScreenReuseAcrossInstruments() throws Exception {
        String url="jdbc:h2:mem:instrument_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("5").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var sql=connection.createStatement()) {
            sql.execute("INSERT INTO connection_profile(id,name,jdbc_url,username,schema_name) VALUES(1,'Legacy','jdbc:h2:mem:test','sa','PUBLIC')");
            sql.execute("INSERT INTO screen_registry(connection_id,screen_name,source) VALUES(1,'Portfolio Screen','UPLOAD')");
            Flyway.configure().dataSource(url,"sa","").load().migrate();
            try(var row=sql.executeQuery("SELECT screen_name,instrument FROM screen_registry")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("Portfolio Screen");assertThat(row.getString(2)).isEqualTo("Unassigned");
            }
            sql.execute("INSERT INTO screen_registry(connection_id,instrument,screen_name,source) VALUES(1,'Bond','Portfolio Screen','UPLOAD')");
            assertThatThrownBy(()->sql.execute("INSERT INTO screen_registry(connection_id,instrument,screen_name,source) VALUES(1,'Bond','Portfolio Screen','MANUAL')"))
                .isInstanceOf(java.sql.SQLException.class);
            sql.execute("SHUTDOWN");
        }
    }
}

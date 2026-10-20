package com.edac;

import static org.assertj.core.api.Assertions.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ReadableScreenMigrationTest {
    @Test void preservesLegacyTableSelectionsAndStartsExplicitScreenSelections() throws Exception {
        String url="jdbc:h2:mem:readable_screen_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("7").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var sql=connection.createStatement()) {
            sql.execute("INSERT INTO connection_profile(id,name,jdbc_url,username,schema_name) VALUES(1,'Legacy','jdbc:h2:mem:test','sa','PUBLIC')");
            sql.execute("INSERT INTO readable_trigger_field(connection_id,table_name,field_name) VALUES(1,'SELL_OPERATION','PORTFOLIO_ID')");
            Flyway.configure().dataSource(url,"sa","").load().migrate();
            try(var rows=sql.executeQuery("SELECT table_name,field_name FROM legacy_readable_trigger_field")) {
                assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("SELL_OPERATION");assertThat(rows.getString(2)).isEqualTo("PORTFOLIO_ID");
            }
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM readable_trigger_field")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isZero();}
            sql.execute("INSERT INTO readable_trigger_field(connection_id,screen_name,field_name) VALUES(1,'Portfolio Screen','PORTFOLIO_ID'),(1,'Other Screen','PORTFOLIO_ID')");
            assertThatThrownBy(()->sql.execute("INSERT INTO readable_trigger_field(connection_id,screen_name,field_name) VALUES(1,'Portfolio Screen','PORTFOLIO_ID')"))
                .isInstanceOf(java.sql.SQLException.class);
            sql.execute("SHUTDOWN");
        }
    }
}

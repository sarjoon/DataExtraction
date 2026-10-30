package com.edac;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class TableBriefDescriptionMigrationTest {
    @Test void preservesExistingTablesAndStoresDescriptions() throws Exception {
        String url="jdbc:h2:mem:table_description_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("17").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()) {
            statement.execute("INSERT INTO connection_profile(id,name,jdbc_url,username,schema_name) VALUES(1,'Existing','jdbc:h2:mem:test','sa','PUBLIC')");
            statement.execute("INSERT INTO table_registry(connection_id,schema_name,table_name,source) VALUES(1,'PUBLIC','CUSTOMER','MANUAL')");
            Flyway.configure().dataSource(url,"sa","").load().migrate();
            try(var result=statement.executeQuery("SELECT table_name,brief_description FROM table_registry")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("table_name")).isEqualTo("CUSTOMER");
                assertThat(result.getString("brief_description")).isNull();
            }
            try(var update=connection.prepareStatement("UPDATE table_registry SET brief_description=?")) {
                update.setString(1,"Customer's master data & account details");update.executeUpdate();
            }
            try(var result=statement.executeQuery("SELECT brief_description FROM table_registry")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("Customer's master data & account details");
            }
            statement.execute("SHUTDOWN");
        }
    }
}

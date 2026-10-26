package com.edac;
import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class RemoveReportUserCodesMigrationTest {
    @Test void dropsThePopulatedUserCodeTableAndPreservesConnections()throws Exception{
        String url="jdbc:h2:mem:remove_report_codes_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("16").load().migrate();
        try(var connection=DriverManager.getConnection(url,"sa","");var statement=connection.createStatement()){
            statement.execute("INSERT INTO connection_profile(id,name,jdbc_url,username,schema_name) VALUES(1,'Existing','jdbc:h2:mem:test','sa','PUBLIC')");
            statement.execute("INSERT INTO report_user_code(connection_id,user_code) VALUES(1,'USER001')");
            Flyway.configure().dataSource(url,"sa","").load().migrate();
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='REPORT_USER_CODE'")){rows.next();assertThat(rows.getInt(1)).isZero();}
            try(var rows=statement.executeQuery("SELECT name FROM connection_profile WHERE id=1")){assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("Existing");}
            statement.execute("SHUTDOWN");
        }
    }
}

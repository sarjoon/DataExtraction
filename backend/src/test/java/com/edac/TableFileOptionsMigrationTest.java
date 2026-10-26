package com.edac;
import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class TableFileOptionsMigrationTest {
    @Test void preservesExistingTablesAndEnforcesAllowedOptions()throws Exception{
        String url="jdbc:h2:mem:table_options_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("13").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
            s.execute("INSERT INTO connection_profile(id,name,jdbc_url,username,schema_name) VALUES(1,'Existing','jdbc:h2:mem:test','sa','PUBLIC')");
            s.execute("INSERT INTO table_registry(connection_id,schema_name,table_name,source) VALUES(1,'PUBLIC','OLD_TABLE','MANUAL')");
            Flyway.configure().dataSource(url,"sa","").load().migrate();
            try(var r=s.executeQuery("SELECT table_name,file_type,load_strategy FROM table_registry")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("OLD_TABLE");assertThat(r.getString(2)).isEmpty();assertThat(r.getString(3)).isEmpty();}
            for(String type:RegistryService.FILE_TYPES)s.execute("UPDATE table_registry SET file_type='"+type+"'");
            assertThatThrownBy(()->s.execute("UPDATE table_registry SET file_type='Other'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.execute("UPDATE table_registry SET load_strategy='Merge'")).isInstanceOf(SQLException.class);
            s.execute("SHUTDOWN");
        }
    }
}

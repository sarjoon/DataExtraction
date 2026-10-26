package com.edac;
import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class TableUpdateFrequencyMigrationTest {
    @Test void defaultsExistingTablesToDailyAndAcceptsOnlyDailyOrWeekly()throws Exception{
        String url="jdbc:h2:mem:table_frequency_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").target("14").load().migrate();
        try(var c=DriverManager.getConnection(url,"sa","");var s=c.createStatement()){
            s.execute("INSERT INTO connection_profile(id,name,jdbc_url,username,schema_name) VALUES(1,'Existing','jdbc:h2:mem:test','sa','PUBLIC')");
            s.execute("INSERT INTO table_registry(connection_id,schema_name,table_name,source,file_type,load_strategy) VALUES(1,'PUBLIC','OLD_TABLE','MANUAL','Master','Full')");
            Flyway.configure().dataSource(url,"sa","").load().migrate();
            try(var r=s.executeQuery("SELECT update_frequency,file_type,load_strategy FROM table_registry")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("Daily");assertThat(r.getString(2)).isEqualTo("Master");assertThat(r.getString(3)).isEqualTo("Full");}
            s.execute("UPDATE table_registry SET update_frequency='Weekly'");
            assertThatThrownBy(()->s.execute("UPDATE table_registry SET update_frequency='Monthly'")).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.execute("UPDATE table_registry SET update_frequency=NULL")).isInstanceOf(SQLException.class);
            s.execute("SHUTDOWN");
        }
    }
}

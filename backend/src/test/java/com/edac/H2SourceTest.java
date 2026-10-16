package com.edac;
import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class H2SourceTest {
    @Test void validatesStructuredUrlsAndRejectsSettingsAndPaths() {
        assertThat(H2Source.url(null,null,"MEMORY","demo")).isEqualTo("jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1");
        assertThat(H2Source.url("localhost",9092,"TCP","demo")).isEqualTo("jdbc:h2:tcp://localhost:9092/demo");
        assertThatThrownBy(()->H2Source.url(null,null,"FILE","../edac")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->H2Source.url(null,null,"MEMORY","demo;INIT=SQL")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->H2Source.url(null,null,"TCP","demo")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void fileSourcePersistsAcrossConnectionsAndReadsMetadata() throws Exception {
        String name="test_"+UUID.randomUUID().toString().replace("-","");
        var profile=new Models.ConnectionProfile(1,"H2","H2","localhost",9092,"FILE",name,H2Source.url(null,null,"FILE",name),"sa","PUBLIC",true);
        var service=new H2Source();
        try {
            assertThat(service.test(profile,"").connected()).isTrue();
            try(var connection=DriverManager.getConnection(profile.jdbcUrl(),"sa","");var sql=connection.createStatement()) {
                sql.execute("CREATE TABLE SAMPLE(ID INT NOT NULL, LABEL VARCHAR(40))");
            }
            assertThat(service.discover(profile,"")).containsExactly(new Models.TableName("PUBLIC","SAMPLE"));
            var columns=service.columns(profile,"",new Models.TableName("PUBLIC","SAMPLE"));
            assertThat(columns).hasSize(2);assertThat(columns.get(1).characterLength().longValue()).isEqualTo(40);
            assertThatThrownBy(()->service.columns(profile,"",new Models.TableName("OTHER","SAMPLE"))).hasMessageContaining("Table not found");
        } finally {
            Files.deleteIfExists(Path.of("data","sources",name+".mv.db"));
            Files.deleteIfExists(Path.of("data","sources",name+".trace.db"));
        }
    }
    @Test void tcpSourceConnectsToExistingServerDatabase(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:file:"+directory.resolve("demo").toAbsolutePath(),"sa","");var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE TCP_SAMPLE(ID INT)");
        }
        var server=org.h2.tools.Server.createTcpServer("-tcpPort","0","-baseDir",directory.toAbsolutePath().toString()).start();
        try {
            var profile=new Models.ConnectionProfile(1,"TCP","H2","127.0.0.1",server.getPort(),"TCP","demo","unused","sa","PUBLIC",true);
            var source=new H2Source();assertThat(source.test(profile,"").connected()).isTrue();
            assertThat(source.discover(profile,"")).containsExactly(new Models.TableName("PUBLIC","TCP_SAMPLE"));
            assertThat(source.columns(profile,"",new Models.TableName("PUBLIC","TCP_SAMPLE"))).hasSize(1);
        } finally {server.stop();}
    }
}

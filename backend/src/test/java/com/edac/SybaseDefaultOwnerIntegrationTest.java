package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:sybase_owner_tests;DB_CLOSE_DELAY=-1","spring.datasource.password="})
@ActiveProfiles("local")
class SybaseDefaultOwnerIntegrationTest {
    @Autowired RegistryService registry;
    @MockitoBean SybaseSource sybase;

    @Test void blankOwnerResolvesCurrentDatabaseUserAndRetainsCase()throws Exception{
        var connection=mock(Connection.class);var statement=mock(Statement.class);var rows=mock(ResultSet.class);
        when(sybase.open(any(),eq("secret"))).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT USER_NAME()")).thenReturn(rows);
        when(rows.next()).thenReturn(true);when(rows.getString(1)).thenReturn("application_owner");
        var profile=registry.save(input(""));
        assertThat(profile.schema()).isEqualTo("application_owner");
        verify(sybase).open(argThat(p->p.schema().isEmpty()),eq("secret"));
    }
    @Test void explicitOwnerIsRetainedWithoutLookingUpTheDatabaseUser(){
        assertThat(registry.save(input("custom_owner")).schema()).isEqualTo("custom_owner");
        verifyNoInteractions(sybase);
    }
    private Models.ConnectionInput input(String owner){
        return new Models.ConnectionInput("Sybase owner "+UUID.randomUUID(),"SYBASE","ase.example",5000,"ASE","APPDB","login_user",owner,"secret",true);
    }
}

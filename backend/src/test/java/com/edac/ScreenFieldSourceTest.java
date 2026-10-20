package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScreenFieldSourceTest {
    @Test void returnsDistinctFieldsForBoundScreenAndPreservesTableIdentity() throws Exception {
        String url="jdbc:h2:mem:screen_fields_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        var factory=mock(OracleConnectionFactory.class);
        var profile=new Models.ConnectionProfile(1,"test","ORACLE","host",1521,"SERVICE_NAME","service","ignored","sa","PUBLIC",true);
        when(factory.open(profile,"secret")).thenAnswer(call->DriverManager.getConnection(url));
        try(var fixture=DriverManager.getConnection(url);var sql=fixture.createStatement()) {
            sql.execute("CREATE TABLE dict_screen(dict_id INT,name VARCHAR)");
            sql.execute("CREATE TABLE dict_entity(dict_id INT,sqlname_c VARCHAR)");
            sql.execute("CREATE TABLE dict_attribute(dict_id INT,entity_dict_id INT,sqlname_c VARCHAR)");
            sql.execute("CREATE TABLE script_definition(object_id INT,attribute_dict_id INT)");
            sql.execute("INSERT INTO dict_screen VALUES(1,'Portfolio''s Screen'),(2,'Other Screen')");
            sql.execute("INSERT INTO dict_entity VALUES(1,'INSTRUMENT'),(2,'ACCOUNT')");
            sql.execute("INSERT INTO dict_attribute VALUES(1,1,'PORTFOLIO_ID'),(2,2,'PORTFOLIO_ID'),(3,NULL,'UNKNOWN_FIELD'),(4,1,'OTHER_FIELD')");
            sql.execute("INSERT INTO script_definition VALUES(1,1),(1,1),(1,2),(1,3),(2,4)");
            var source=new ScreenFieldSource(factory);
            assertThat(source.discover(profile,"secret","Portfolio's Screen")).containsExactlyInAnyOrder(
                new ReadableFieldService.FieldName("INSTRUMENT","PORTFOLIO_ID"),new ReadableFieldService.FieldName("ACCOUNT","PORTFOLIO_ID"),new ReadableFieldService.FieldName(null,"UNKNOWN_FIELD"));
            assertThat(source.discover(profile,"secret","Missing Screen")).isEmpty();
            sql.execute("SHUTDOWN");
        }
    }
    @Test void reportsDatabaseFailuresWithoutExposingConnectionSecrets() throws Exception {
        var factory=mock(OracleConnectionFactory.class);
        var profile=new Models.ConnectionProfile(1,"test","ORACLE","host",1521,"SERVICE_NAME","service","ignored","sa","PUBLIC",true);
        when(factory.open(profile,"secret")).thenThrow(new SQLException("private details","state",123));
        assertThatThrownBy(()->new ScreenFieldSource(factory).discover(profile,"secret","Screen"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("database error 123").hasMessageNotContaining("private details").hasMessageNotContaining("secret");
    }
}

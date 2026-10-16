package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import org.junit.jupiter.api.Test;

class OracleSourceTest {
    Models.ConnectionProfile profile() {
        return new Models.ConnectionProfile(1,"Oracle","ORACLE","localhost",1521,"SERVICE_NAME","ORCLPDB1","jdbc:oracle:thin:@//localhost:1521/ORCLPDB1","app","APP",true);
    }
    @Test void constructsServiceAndSidUrlsAndLoadsOracleDriver() throws Exception {
        String url=OracleConnectionFactory.url("db.example.com",1521,"SERVICE_NAME","sales.example.com");
        assertThat(url).isEqualTo("jdbc:oracle:thin:@//db.example.com:1521/sales.example.com");
        assertThat(OracleConnectionFactory.url("db.example.com",1522,"SID","ORCL")).isEqualTo("jdbc:oracle:thin:@db.example.com:1522:ORCL");
        assertThat(DriverManager.getDriver(url).getClass().getName()).contains("OracleDriver");
        assertThatThrownBy(()->OracleConnectionFactory.url("host)(evil",1521,"SID","ORCL")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void discoveryBindsOwnerAndClosesOracleResources() throws Exception {
        var factory=mock(OracleConnectionFactory.class);var connection=mock(Connection.class);
        var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(factory.open(profile(),"secret")).thenReturn(connection);
        when(connection.prepareStatement("SELECT OWNER, TABLE_NAME FROM ALL_TABLES WHERE OWNER = ? ORDER BY TABLE_NAME")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true,false);
        when(rows.getString("OWNER")).thenReturn("APP");when(rows.getString("TABLE_NAME")).thenReturn("CUSTOMER");
        var result=new OracleSource(factory).discover(profile(),"secret");
        assertThat(result).containsExactly(new Models.TableName("APP","CUSTOMER"));
        verify(statement).setString(1,"APP");verify(statement).setQueryTimeout(30);verify(statement).setMaxRows(10001);
        verify(rows).close();verify(statement).close();verify(connection).close();
    }
    @Test void testUsesOracleDualAndSanitizesDriverErrors() throws Exception {
        var factory=mock(OracleConnectionFactory.class);var connection=mock(Connection.class);var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(factory.open(profile(),"secret")).thenReturn(connection);when(connection.prepareStatement("SELECT 1 FROM DUAL")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true);when(rows.getInt(1)).thenReturn(1);
        assertThat(new OracleSource(factory).test(profile(),"secret").connected()).isTrue();
        verify(statement).setQueryTimeout(15);verify(connection).close();
        when(factory.open(profile(),"secret")).thenThrow(new SQLException("secret driver detail","08000",1017));
        assertThatThrownBy(()->new OracleSource(factory).test(profile(),"secret")).hasMessageContaining("1017").hasMessageNotContaining("secret driver detail");
    }
    @Test void columnsBindTableAndPreserveNullableMetadata() throws Exception {
        var factory=mock(OracleConnectionFactory.class);var connection=mock(Connection.class);
        var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(factory.open(profile(),"secret")).thenReturn(connection);
        when(connection.prepareStatement(contains("FROM ALL_TAB_COLUMNS"))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true,false);
        when(rows.getInt("COLUMN_ID")).thenReturn(1);when(rows.getString("COLUMN_NAME")).thenReturn("NAME");
        when(rows.getString("DATA_TYPE")).thenReturn("VARCHAR2");when(rows.getInt("DATA_LENGTH")).thenReturn(400);
        when(rows.getString("CHAR_USED")).thenReturn("C");when(rows.getObject("CHAR_LENGTH")).thenReturn(new java.math.BigDecimal(100));
        when(rows.getString("NULLABLE")).thenReturn("Y");
        var service=new OracleSource(factory);
        // Exercise the fallback independently of the user-editable production query.
        org.springframework.test.util.ReflectionTestUtils.setField(service,"mappingQuery",
            new org.springframework.core.io.ByteArrayResource("-- No custom query for this test".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var result=service.mappingFields(profile(),"secret",new Models.TableName("SALES","CUSTOMER")).stream().map(Models.MappingField::column).toList();
        assertThat(result).containsExactly(new Models.ColumnDetail(1,"NAME","VARCHAR2",400,100,null,null,true,true));
        verify(statement).setString(1,"SALES");verify(statement).setString(2,"CUSTOMER");verify(statement).setQueryTimeout(30);
        verify(rows).close();verify(statement).close();verify(connection).close();
    }
    @Test void customMappingQueryBindsTableAndReadsMetadataAliases() throws Exception {
        var factory=mock(OracleConnectionFactory.class);var connection=mock(Connection.class);
        var statement=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        String query="SELECT * FROM CUSTOM_METADATA WHERE OWNER = ? AND TABLE_NAME = ?";
        when(factory.open(profile(),"secret")).thenReturn(connection);
        when(connection.prepareStatement(query)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true,false);
        when(rows.getInt("COLUMN_ID")).thenReturn(1);when(rows.getString("COLUMN_NAME")).thenReturn("NAME");
        when(rows.getString("DATA_TYPE")).thenReturn("VARCHAR2");when(rows.getObject("DATA_LENGTH")).thenReturn(400);
        when(rows.getObject("CHAR_LENGTH")).thenReturn(100);when(rows.getString("IS_NULLABLE")).thenReturn("Y");
        when(rows.getString("FIELD_DESCRIPTION")).thenReturn("Customer name");
        var service=new OracleSource(factory);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"mappingQuery",
            new org.springframework.core.io.ByteArrayResource(query.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var result=service.mappingFields(profile(),"secret",new Models.TableName("SALES","CUSTOMER"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).column()).isEqualTo(new Models.ColumnDetail(1,"NAME","VARCHAR2",400,100,null,null,true,true));
        assertThat(result.get(0).description()).isEqualTo("Customer name");
        assertThat(result.get(0).mandatory()).isEqualTo("O");
        verify(connection).prepareStatement(query);
        verify(statement).setString(1,"SALES");verify(statement).setString(2,"CUSTOMER");
        verify(rows).close();verify(statement).close();verify(connection).close();
    }
}

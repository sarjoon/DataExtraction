package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SchemaMetadataOracleTest {
    @ParameterizedTest @NullSource @ValueSource(strings={"'default value'", "TO_DATE('2026-10-26','YYYY-MM-DD')"})
    void consumesLongDefaultBeforeLaterMetadataAndReadsLongValuesOnce(String defaultValue)throws Exception{
        var registry=mock(RegistryService.class);var oracle=mock(OracleConnectionFactory.class);
        var profile=new Models.ConnectionProfile(1,"Oracle","ORACLE","host",1521,"SERVICE_NAME","DB","jdbc:oracle:thin:@host:1521/DB","USER","APP",true,true);
        when(registry.connection(1)).thenReturn(profile);when(registry.connectionPassword(1)).thenReturn("secret");
        var connection=mock(Connection.class);when(oracle.open(profile,"secret")).thenReturn(connection);
        var metadata=mock(DatabaseMetaData.class);when(connection.getMetaData()).thenReturn(metadata);when(metadata.getSearchStringEscape()).thenReturn("\\");
        var consumed=new AtomicBoolean();var closed=new AtomicBoolean();
        var columns=mock(ResultSet.class,invocation->{
            String method=invocation.getMethod().getName();
            if(method.equals("getString")){
                String key=(String)invocation.getArgument(0);
                if(key.equals("COLUMN_DEF")){
                    if(closed.get()||consumed.getAndSet(true))throw new SQLException("Stream has already been closed", "99999",17027);
                    return defaultValue;
                }
                return switch(key){case "TABLE_SCHEM"->"APP";case "TABLE_NAME"->"ITEMS";case "COLUMN_NAME"->"NOTE";case "TYPE_NAME"->"VARCHAR2";case "IS_AUTOINCREMENT","IS_GENERATEDCOLUMN"->"NO";default->null;};
            }
            if(method.equals("getInt")){
                String key=(String)invocation.getArgument(0);
                if(key.equals("ORDINAL_POSITION"))closed.set(true);
                return switch(key){case "DATA_TYPE"->Types.VARCHAR;case "COLUMN_SIZE"->80;case "ORDINAL_POSITION","NULLABLE"->1;default->0;};
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        when(columns.next()).thenReturn(true,false);when(metadata.getColumns(null,"APP","ITEMS","%")).thenReturn(columns);
        var primaryKeys=empty();var importedKeys=empty();var indexes=empty();
        when(metadata.getPrimaryKeys(null,"APP","ITEMS")).thenReturn(primaryKeys);when(metadata.getImportedKeys(null,"APP","ITEMS")).thenReturn(importedKeys);
        when(metadata.getIndexInfo(null,"APP","ITEMS",false,true)).thenReturn(indexes);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation->{
            String sql=invocation.getArgument(0);var statement=mock(PreparedStatement.class);ResultSet rows=empty();
            if(sql.contains("FROM ALL_OBJECTS")){rows=mock(ResultSet.class);when(rows.next()).thenReturn(true,false);when(rows.getString(1)).thenReturn("TABLE");when(rows.getString(2)).thenReturn("ITEMS");}
            if(sql.contains("SEARCH_CONDITION")){rows=mock(ResultSet.class);when(rows.next()).thenReturn(true,false);when(rows.getString(1)).thenReturn("CK_NOTE");when(rows.getString(2)).thenReturn("NOTE IS NOT NULL").thenThrow(new SQLException("LONG read twice"));}
            when(statement.executeQuery()).thenReturn(rows);return statement;
        });
        var result=new SchemaMetadata(registry,oracle,mock(SybaseSource.class)).read(1,false,table->{});
        var table=result.tables().get("ITEMS");assertThat(table.columns().get(0).properties().get("Default")).isEqualTo(defaultValue==null?"":defaultValue);
        assertThat(table.constraints()).containsEntry("Check CK_NOTE","NOTE IS NOT NULL");
        verify(columns,times(1)).getString("COLUMN_DEF");assertThat(consumed).isTrue();
    }
    private ResultSet empty()throws SQLException{var rows=mock(ResultSet.class);when(rows.next()).thenReturn(false);return rows;}
}

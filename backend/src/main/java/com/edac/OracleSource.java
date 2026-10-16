package com.edac;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import static com.edac.Models.*;

@Service
public class OracleSource {
    @org.springframework.beans.factory.annotation.Value("${edac.oracle.mapping-query:classpath:sql/oracle-mapping.sql}")
    private org.springframework.core.io.Resource mappingQuery;
    private final OracleConnectionFactory connections;
    public OracleSource(OracleConnectionFactory connections) { this.connections = connections; }

    public TestResult test(ConnectionProfile profile, String password) {
        try (var connection = connections.open(profile, password);
             var statement = connection.prepareStatement("SELECT 1 FROM DUAL")) {
            statement.setQueryTimeout(15);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 1) throw new SQLException("Connection validation failed");
            }
            return new TestResult(true, "Connected to Oracle using the saved connection details.", List.of());
        } catch (SQLException e) { throw failure(e); }
    }

    public List<TableName> discover(ConnectionProfile profile, String password) {
        // Table inventory only. The user's field-extraction query will be integrated separately.
        try (var connection = connections.open(profile, password);
             var statement = connection.prepareStatement("SELECT OWNER, TABLE_NAME FROM ALL_TABLES WHERE OWNER = ? ORDER BY TABLE_NAME")) {
            statement.setString(1, profile.schema());
            statement.setQueryTimeout(30);
            statement.setMaxRows(10001);
            List<TableName> tables = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) tables.add(new TableName(rows.getString("OWNER"), rows.getString("TABLE_NAME")));
            }
            if (tables.size() > 10000) throw new IllegalArgumentException("More than 10,000 accessible tables. Upload a smaller table list.");
            if (tables.stream().anyMatch(t -> !t.name().matches("[A-Z_][A-Z0-9_$#]{0,127}")))
                throw new IllegalArgumentException("This schema contains quoted or mixed-case table names. Quoted identifiers are not supported yet; upload a list of unquoted tables instead.");
            return tables;
        } catch (SQLException e) { throw failure(e); }
    }

    public List<ColumnDetail> columns(ConnectionProfile profile, String password, TableName table) {
        try (var connection = connections.open(profile, password);
             var statement = connection.prepareStatement("SELECT COLUMN_ID,COLUMN_NAME,DATA_TYPE,DATA_LENGTH,CHAR_LENGTH,CHAR_USED,DATA_PRECISION,DATA_SCALE,NULLABLE FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID")) {
            statement.setString(1,table.schema()); statement.setString(2,table.name());
            statement.setQueryTimeout(30); statement.setMaxRows(5001);
            List<ColumnDetail> result = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(new ColumnDetail(rows.getInt("COLUMN_ID"),rows.getString("COLUMN_NAME"),
                    rows.getString("DATA_TYPE"),rows.getInt("DATA_LENGTH"),
                    rows.getString("CHAR_USED")==null?null:nullableInt(rows.getObject("CHAR_LENGTH")),
                    nullableInt(rows.getObject("DATA_PRECISION")),nullableInt(rows.getObject("DATA_SCALE")),
                    "Y".equals(rows.getString("NULLABLE")),true));
            }
            if(result.isEmpty()) throw new SourceTableUnavailableException("Table not found or no accessible columns in the selected Oracle connection.");
            if(result.size()>5000) throw new IllegalArgumentException("Table exceeds the supported limit of 5,000 columns.");
            return result;
        } catch (SQLException e) { throw failure(e); }
    }
    private static Integer nullableInt(Object value) { return value==null?null:((Number)value).intValue(); }

    public List<MappingField> mappingFields(ConnectionProfile profile,String password,TableName table) {
        var resource=mappingQuery==null?new org.springframework.core.io.ClassPathResource("sql/oracle-mapping.sql"):mappingQuery;
        String query=MappingMetadata.sql(resource);
        if(query.isBlank())return columns(profile,password,table).stream().map(c->new MappingField(c,null,null,null)).toList();
        try(var connection=connections.open(profile,password)){return MappingMetadata.read(connection,query,table);}
        catch(SQLException e){throw failure(e);}
    }
    private IllegalArgumentException failure(SQLException e) {
        return new IllegalArgumentException("Oracle connection or query failed (database error " + e.getErrorCode()
            + "). Check the saved host, port, service/SID, credentials, network access and schema permissions.");
    }
}

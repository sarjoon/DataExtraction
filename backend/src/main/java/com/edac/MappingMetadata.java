package com.edac;
import static com.edac.Models.*;
import java.sql.*;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
final class MappingMetadata {
    private MappingMetadata() {}
    static String sql(Resource resource) {
        try(var stream=resource.getInputStream()) {
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$","").trim();
        } catch(IOException e) {throw new IllegalStateException("Cannot read mapping metadata SQL.",e);}
    }
    static List<MappingField> read(Connection connection,String query,TableName table) throws SQLException {
        try(var statement=connection.prepareStatement(query)) {
            statement.setString(1,table.schema());statement.setString(2,table.name());statement.setQueryTimeout(30);statement.setMaxRows(5001);
            List<MappingField> result=new ArrayList<>();Set<String> names=new HashSet<>();
            try(var rows=statement.executeQuery()) {
                Set<String> labels=new HashSet<>();var metadata=rows.getMetaData();
                for(int i=1;i<=metadata.getColumnCount();i++)labels.add(metadata.getColumnLabel(i).toUpperCase(Locale.ROOT));
                while(rows.next()) {
                String name=rows.getString("COLUMN_NAME");
                if(name==null || name.isBlank() || !names.add(name))throw new IllegalArgumentException("Metadata query must return one row per named column.");
                String nullable=rows.getString("IS_NULLABLE");
                var column=new ColumnDetail(rows.getInt("COLUMN_ID"),name,rows.getString("DATA_TYPE"),
                    (Number)rows.getObject("DATA_LENGTH"),(Number)rows.getObject("CHAR_LENGTH"),
                    integer(rows.getObject("DATA_PRECISION")),integer(rows.getObject("DATA_SCALE")),
                    "YES".equalsIgnoreCase(nullable)||"Y".equalsIgnoreCase(nullable),true);
                var keys=new ArrayList<String>();
                if("PK".equalsIgnoreCase(rows.getString("PK_FLAG")))keys.add("PK");
                if("FK".equalsIgnoreCase(rows.getString("FK_FLAG")))keys.add("FK");
                // Presence and NULL are distinct: an explicit SQL NULL must stay blank.
                Map<String,Object> sourceMetadata=new HashMap<>();
                if(labels.contains("SOURCE_DATA_TYPE"))sourceMetadata.put("SOURCE_DATA_TYPE",rows.getString("SOURCE_DATA_TYPE"));
                if(labels.contains("SOURCE_LENGTH"))sourceMetadata.put("SOURCE_LENGTH",(Number)rows.getObject("SOURCE_LENGTH"));
                result.add(new MappingField(column,String.join(",",keys),rows.getString("FIELD_DESCRIPTION"),rows.getString("DEFAULT_VALUE"),nullable==null?null:column.nullable()?"O":"M",sourceMetadata));
            }}
            if(result.isEmpty())throw new SourceTableUnavailableException("Table missing, inaccessible, or metadata query returned no columns.");
            if(result.size()>5000)throw new IllegalArgumentException("Table exceeds the supported limit of 5,000 columns.");
            return result;
        }
    }
    private static Integer integer(Object value){return value==null?null:((Number)value).intValue();}
}

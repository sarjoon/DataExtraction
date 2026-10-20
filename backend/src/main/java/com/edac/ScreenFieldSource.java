package com.edac;

import java.sql.SQLException;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class ScreenFieldSource {
    private final OracleConnectionFactory connections;
    public ScreenFieldSource(OracleConnectionFactory connections){this.connections=connections;}
    public List<ReadableFieldService.FieldName> discover(Models.ConnectionProfile profile,String password,String screen) {
        String query=MappingMetadata.sql(new ClassPathResource("sql/oracle-screen-fields.sql"))
            .replace("${schema}",RegistryService.identifier(profile.schema()));
        try(var connection=connections.open(profile,password);var statement=connection.prepareStatement(query)) {
            statement.setString(1,screen);statement.setQueryTimeout(30);statement.setMaxRows(10001);
            var result=new ArrayList<ReadableFieldService.FieldName>();
            int count=0;
            try(var rows=statement.executeQuery()) {
                while(rows.next()) {
                    if(++count>10000)throw new IllegalArgumentException("Screen contains more than 10,000 fields. Use a smaller screen selection.");
                    String field=rows.getString("FIELD_NAME");if(field==null || field.isBlank())continue;
                    result.add(new ReadableFieldService.FieldName(rows.getString("TABLE_NAME"),field));
                }
            }
            return result;
        }catch(SQLException e){throw new IllegalArgumentException("Could not load fields for screen "+screen+" (database error "+e.getErrorCode()+"). Check the Oracle connection and dictionary permissions.");}
    }
}

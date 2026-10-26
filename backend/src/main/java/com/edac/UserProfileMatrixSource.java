package com.edac;

import java.sql.*;
import java.util.*;
import java.util.regex.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class UserProfileMatrixSource {
    public record GridPage(List<String> columns,List<List<String>> rows,int page,int size,boolean hasMore,String message) {}
    enum Query {
        MATRIX("user-profile-matrix"), REPORT("report-profile"), DATA("data-profile"),
        FUNCTION_SECURITY("function-security"), FORMAT("format-profile"), SCREEN("screen-profile"),
        FORMAT_ELEMENTS("format-elements"), SCREEN_PANELS("screen-panels");
        final String template;
        Query(String file){template=MappingMetadata.sql(new ClassPathResource("sql/reports/"+file+".sql"));}
    }
    private static final Pattern TABLE=Pattern.compile("\\$\\{([a-z_]+)}");
    private static final Set<String> PROFILE_TYPES=Set.of("REPORT","DATA","FUNCTION_SECURITY","FORMAT","SCREEN","MENU","EXTERNAL_SERVICE");
    private static final Logger log=LoggerFactory.getLogger(UserProfileMatrixSource.class);
    private final RegistryService registry;
    private final OracleConnectionFactory oracle;
    private final SybaseSource sybase;
    public UserProfileMatrixSource(RegistryService registry,OracleConnectionFactory oracle,SybaseSource sybase){
        this.registry=registry;this.oracle=oracle;this.sybase=sybase;
    }
    @FunctionalInterface private interface Work<T>{T read(Connection connection,Models.ConnectionProfile profile)throws SQLException;}
    private <T>T read(long id,Work<T> work){
        var profile=registry.connection(id);String password=registry.connectionPassword(id);
        try(var connection=switch(profile.databaseType()){
            case "ORACLE"->oracle.open(profile,password);
            case "SYBASE"->sybase.open(profile,password);
            case "H2"->DriverManager.getConnection(H2Source.url(profile.host(),profile.port(),profile.connectionType(),profile.databaseName()),profile.username(),password);
            default->throw new IllegalArgumentException("User profile matrix supports Oracle, Sybase ASE and H2.");
        }){
            return work.read(connection,profile);
        }catch(SQLException e){
            log.warn("User profile matrix query failed for connection {} (database error {})",id,e.getErrorCode(),e);
            throw new IllegalArgumentException("User profile matrix query failed (database error "+e.getErrorCode()+"). Check the saved schema/owner, access to the report tables, and database connection.",e);
        }
    }
    static String sql(Query query,DatabaseMetaData metadata,String databaseType,String schema)throws SQLException{
        if(schema==null||schema.isBlank())throw new IllegalArgumentException("Save a schema/owner on this connection before loading the report.");
        var matcher=TABLE.matcher(query.template);var result=new StringBuffer();
        while(matcher.find()){
            String table=matcher.group(1);
            if(!databaseType.equals("SYBASE")){
                if(metadata.storesUpperCaseIdentifiers())table=table.toUpperCase(Locale.ROOT);
                else if(metadata.storesLowerCaseIdentifiers())table=table.toLowerCase(Locale.ROOT);
            }
            matcher.appendReplacement(result,Matcher.quoteReplacement(quote(metadata,schema)+"."+quote(metadata,table)));
        }
        matcher.appendTail(result);return result.toString();
    }
    private static String quote(DatabaseMetaData metadata,String value)throws SQLException{
        String quote=Objects.toString(metadata.getIdentifierQuoteString(),"").trim();
        if(quote.isEmpty())throw new IllegalArgumentException("This JDBC driver does not report identifier quoting support.");
        return quote+value.replace(quote,quote+quote)+quote;
    }
    static void page(int page,int size){
        if(page<0||size<1||size>200||(long)page*size+size+1>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Choose a nonnegative page and between 1 and 200 rows per page.");
    }
    private static String value(String value,String label){
        if(value==null||value.isBlank()||value.length()>512||value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label+" must contain 1 to 512 characters without control characters.");
        return value;
    }
    private static String profileType(String type){
        String normalized=Objects.toString(type,"").trim().toUpperCase(Locale.ROOT);
        if(!PROFILE_TYPES.contains(normalized))throw new IllegalArgumentException("Unknown profile type.");
        return normalized;
    }
    private static String withoutOrder(String sql){
        int order=sql.lastIndexOf("\nORDER BY ");return order<0?sql:sql.substring(0,order);
    }
    private static void bind(PreparedStatement statement,List<String> values)throws SQLException{
        for(int i=0;i<values.size();i++)statement.setString(i+1,values.get(i));
    }
    private static boolean exists(Connection connection,String sql,List<String> values)throws SQLException{
        try(var statement=connection.prepareStatement(sql)){
            statement.setQueryTimeout(60);statement.setMaxRows(1);statement.setFetchSize(1);bind(statement,values);
            try(var rows=statement.executeQuery()){return rows.next();}
        }
    }
    private void requireProfile(Connection c,Models.ConnectionProfile profile,String user,String type,String code)throws SQLException{
        String matrix=sql(Query.MATRIX,c.getMetaData(),profile.databaseType(),profile.schema());
        if(!exists(c,"SELECT 1 FROM ("+withoutOrder(matrix)+") assigned_profiles WHERE PROFILE_TYPE=? AND PROFILE_CODE=?",List.of(user,type,code)))
            throw new IllegalArgumentException("This profile is not assigned to the entered user. Reload the matrix.");
    }
    private GridPage query(Connection c,String sql,List<String> values,int page,int size)throws SQLException{
        int offset=page*size,limit=offset+size+1;
        try(var statement=c.prepareStatement(sql)){
            statement.setQueryTimeout(60);statement.setMaxRows(limit);statement.setFetchSize(Math.min(200,limit));bind(statement,values);
            try(var result=statement.executeQuery()){
                var metadata=result.getMetaData();var columns=new ArrayList<String>();
                for(int i=1;i<=metadata.getColumnCount();i++)columns.add(metadata.getColumnLabel(i).toUpperCase(Locale.ROOT));
                var rows=new ArrayList<List<String>>();int position=0;
                while(result.next()){
                    if(position++<offset)continue;
                    if(rows.size()==size)return new GridPage(columns,rows,page,size,true,null);
                    var row=new ArrayList<String>();
                    // Read in column order so streamed dictionary values are consumed before later columns.
                    for(int i=1;i<=columns.size();i++)row.add(result.getString(i));
                    rows.add(row);
                }
                return new GridPage(columns,rows,page,size,false,null);
            }
        }
    }
    public GridPage matrix(long id,String user,int page,int size){
        page(page,size);
        return read(id,(c,p)->query(c,sql(Query.MATRIX,c.getMetaData(),p.databaseType(),p.schema()),List.of(user),page,size));
    }
    public GridPage details(long id,String user,String type,String code,int page,int size){
        page(page,size);String selectedType=profileType(type),selectedCode=value(code,"Profile code");
        return read(id,(c,p)->{
            requireProfile(c,p,user,selectedType,selectedCode);
            if(selectedType.equals("MENU")||selectedType.equals("EXTERNAL_SERVICE"))
                return new GridPage(List.of(),List.of(),page,size,false,"No detail query configured for "+selectedType+".");
            return query(c,sql(Query.valueOf(selectedType),c.getMetaData(),p.databaseType(),p.schema()),List.of(selectedCode),page,size);
        });
    }
    public GridPage children(long id,String user,String type,String code,String item,int page,int size){
        page(page,size);String selectedType=profileType(type),selectedCode=value(code,"Profile code"),selectedItem=value(item,"Format code or screen name");
        if(!Set.of("FORMAT","SCREEN").contains(selectedType))throw new IllegalArgumentException("Only FORMAT and SCREEN have another detail grid.");
        return read(id,(c,p)->{
            requireProfile(c,p,user,selectedType,selectedCode);
            var metadata=c.getMetaData();String parent=sql(Query.valueOf(selectedType),metadata,p.databaseType(),p.schema());
            String column=selectedType.equals("FORMAT")?"FORMAT":"SCREEN_NAME";
            if(!exists(c,"SELECT 1 FROM ("+withoutOrder(parent)+") profile_items WHERE "+quote(metadata,column)+"=?",List.of(selectedCode,selectedItem)))
                throw new IllegalArgumentException("This format or screen does not belong to the selected profile. Reload its details.");
            Query child=selectedType.equals("FORMAT")?Query.FORMAT_ELEMENTS:Query.SCREEN_PANELS;
            return query(c,sql(child,metadata,p.databaseType(),p.schema()),List.of(selectedItem),page,size);
        });
    }
}

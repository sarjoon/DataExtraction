package com.edac;

import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class SchemaMetadata {
    public record Column(String name,String nativeType,String logicalType,Map<String,String> properties) {}
    public record Table(String name,List<Column> columns,Map<String,String> constraints,Map<String,String> indexes,Long rowCount) {}
    public record Inventory(String databaseType,String schema,Map<String,Set<String>> objects,Set<String> unsupported,Map<String,Table> tables) {}
    static final List<String> TYPES=List.of("Tables","Views","Procedures","Triggers","Indexes");
    private final RegistryService registry;private final OracleConnectionFactory oracle;private final SybaseSource sybase;
    public SchemaMetadata(RegistryService registry,OracleConnectionFactory oracle,SybaseSource sybase){this.registry=registry;this.oracle=oracle;this.sybase=sybase;}
    Connection open(Models.ConnectionProfile p)throws SQLException{
        String password=registry.connectionPassword(p.id());
        return switch(p.databaseType()){
            case "ORACLE"->oracle.open(p,password);
            case "SYBASE"->sybase.open(p,password);
            case "H2"->DriverManager.getConnection(H2Source.url(p.host(),p.port(),p.connectionType(),p.databaseName()),p.username(),password);
            default->throw new IllegalArgumentException("Unsupported schema comparison database.");
        };
    }
    public Inventory read(long id,boolean countRows,Consumer<String> progress)throws SQLException{
        var profile=registry.connection(id);String type=profile.databaseType(),schema=profile.schema();
        try(var connection=open(profile)){
            var metadata=connection.getMetaData();String catalog=connection.getCatalog();
            if(schema==null||schema.isBlank())throw new IllegalArgumentException("Save a schema/owner on both connection profiles before comparing.");
            var objects=new LinkedHashMap<String,Set<String>>();TYPES.forEach(t->objects.put(t,new TreeSet<>()));var unsupported=new HashSet<String>();
            if(type.equals("ORACLE"))query(connection,"SELECT OBJECT_TYPE, OBJECT_NAME FROM ALL_OBJECTS WHERE OWNER=? AND OBJECT_TYPE IN ('TABLE','VIEW','PROCEDURE','TRIGGER','INDEX')",List.of(schema),r->{String category=switch(r.getString(1)){case "TABLE"->"Tables";case "VIEW"->"Views";case "PROCEDURE"->"Procedures";case "TRIGGER"->"Triggers";default->"Indexes";};objects.get(category).add(r.getString(2));});
            else if(type.equals("SYBASE"))query(connection,"SELECT o.type, o.name FROM sysobjects o JOIN sysusers u ON u.uid=o.uid WHERE u.name=? AND o.type IN ('U','V','P','TR')",List.of(schema),r->{String category=switch(r.getString(1).trim()){case "U"->"Tables";case "V"->"Views";case "P"->"Procedures";default->"Triggers";};objects.get(category).add(r.getString(2));});
            else{
                try(var r=metadata.getTables(catalog,SybaseSource.pattern(metadata,schema),"%",new String[]{"TABLE","BASE TABLE","VIEW"})){while(r.next()){if(!schema.equals(r.getString("TABLE_SCHEM")))continue;objects.get(r.getString("TABLE_TYPE").equals("VIEW")?"Views":"Tables").add(r.getString("TABLE_NAME"));}}
                query(connection,"SELECT TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA=?",List.of(schema),r->objects.get("Triggers").add(r.getString(1)));
                query(connection,"SELECT DISTINCT ROUTINE_NAME FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA=? AND ROUTINE_TYPE='PROCEDURE'",List.of(schema),r->objects.get("Procedures").add(r.getString(1)));
            }
            if(type.equals("ORACLE")){objects.get("Indexes").clear();query(connection,"SELECT TABLE_NAME, INDEX_NAME FROM ALL_INDEXES WHERE OWNER=?",List.of(schema),r->objects.get("Indexes").add(r.getString(1)+"."+r.getString(2)));}
            if(objects.get("Tables").size()>10000)throw new IllegalArgumentException("Schema comparison supports up to 10,000 tables per selected schema.");
            var tables=new LinkedHashMap<String,Table>();int columnCount=0;
            for(String name:objects.get("Tables")){
                progress.accept(name);var columns=new ArrayList<Column>();
                try(var rows=metadata.getColumns(catalog,SybaseSource.pattern(metadata,schema),SybaseSource.pattern(metadata,name),"%")){while(rows.next()){
                    if(!schema.equals(rows.getString("TABLE_SCHEM"))||!name.equals(rows.getString("TABLE_NAME")))continue;
                    String nativeType=rows.getString("TYPE_NAME");int jdbcType=rows.getInt("DATA_TYPE"),size=rows.getInt("COLUMN_SIZE"),scale=rows.getInt("DECIMAL_DIGITS");
                    var properties=new LinkedHashMap<String,String>();properties.put("Position",Integer.toString(rows.getInt("ORDINAL_POSITION")));
                    properties.put("Length",isSized(jdbcType)?Integer.toString(size):"");
                    properties.put("Precision",isNumber(jdbcType)?Integer.toString(numericPrecision(jdbcType,size)):"");
                    properties.put("Scale",type.equals("ORACLE")&&nativeType.equalsIgnoreCase("DATE")?"0":isNumber(jdbcType)||jdbcType==Types.TIMESTAMP||jdbcType==Types.TIMESTAMP_WITH_TIMEZONE?Integer.toString(scale):"");
                    int nullable=rows.getInt("NULLABLE");properties.put("Nullable",rows.wasNull()?"Unknown":nullable==DatabaseMetaData.columnNullable?"Yes":nullable==DatabaseMetaData.columnNoNulls?"No":"Unknown");
                    properties.put("Default",Objects.toString(rows.getString("COLUMN_DEF"),""));
                    String identity=optional(rows,"IS_AUTOINCREMENT"),generated=optional(rows,"IS_GENERATEDCOLUMN");properties.put("Identity",identity.isEmpty()?"Not reported":identity);properties.put("Generated",generated.isEmpty()?"Not reported":generated);
                    columns.add(new Column(rows.getString("COLUMN_NAME"),nativeType,logical(jdbcType,nativeType,type),properties));
                }}
                if(columns.isEmpty())throw new IllegalArgumentException("No visible columns for "+schema+"."+name+". Check metadata permissions.");
                columnCount+=columns.size();if(columnCount>500000)throw new IllegalArgumentException("Select a schema with fewer than 500,000 columns.");
                columns.sort(Comparator.comparingInt(c->Integer.parseInt(c.properties().get("Position"))));
                var constraints=new TreeMap<String,String>();var pk=new TreeMap<Integer,String>();var pkNames=new TreeSet<String>();
                try(var r=metadata.getPrimaryKeys(catalog,schema,name)){while(r.next()){pk.put(r.getInt("KEY_SEQ"),r.getString("COLUMN_NAME"));if(r.getString("PK_NAME")!=null)pkNames.add(r.getString("PK_NAME"));}}
                if(!pk.isEmpty()){constraints.put("Primary key",String.join(", ",pk.values()));constraints.put("Primary key name",pkNames.isEmpty()?"Not reported":String.join(", ",pkNames));}
                var foreign=new TreeMap<String,SortedMap<Integer,String>>();
                try(var r=metadata.getImportedKeys(catalog,schema,name)){while(r.next()){
                    String constraint=r.getString("FK_NAME");if(constraint==null)constraint="FK_"+r.getString("PKTABLE_NAME");
                    String targetSchema=r.getString("PKTABLE_SCHEM");String target=(schema.equals(targetSchema)?"":Objects.toString(targetSchema,"")+".")+r.getString("PKTABLE_NAME");
                    foreign.computeIfAbsent("Foreign key "+constraint,k->new TreeMap<>()).put(r.getInt("KEY_SEQ"),r.getString("FKCOLUMN_NAME")+" -> "+target+"."+r.getString("PKCOLUMN_NAME")+" [update="+rule(r.getShort("UPDATE_RULE"))+", delete="+rule(r.getShort("DELETE_RULE"))+"]");
                }}foreign.forEach((key,value)->constraints.put(key,String.join(", ",value.values())));
                checks(connection,type,schema,name,constraints);
                var indexRows=new TreeMap<String,SortedMap<Integer,String>>();
                try(var r=metadata.getIndexInfo(catalog,schema,name,false,true)){while(r.next()){
                    String index=r.getString("INDEX_NAME");if(index==null||r.getShort("TYPE")==DatabaseMetaData.tableIndexStatistic)continue;
                    String filter=optional(r,"FILTER_CONDITION");
                    indexRows.computeIfAbsent(index,k->new TreeMap<>()).put(r.getInt("ORDINAL_POSITION"),(r.getBoolean("NON_UNIQUE")?"Nonunique ":"Unique ")+Objects.toString(r.getString("COLUMN_NAME"),"<expression not reported>")+" "+Objects.toString(r.getString("ASC_OR_DESC"),"")+(filter.isEmpty()||filter.equals("Not reported")?"":" WHERE "+filter));
                }}
                if(type.equals("ORACLE"))query(connection,"SELECT INDEX_NAME, COLUMN_POSITION, COLUMN_EXPRESSION FROM ALL_IND_EXPRESSIONS WHERE TABLE_OWNER=? AND TABLE_NAME=?",List.of(schema,name),r->{var index=indexRows.get(r.getString(1));if(index!=null){int position=r.getInt(2);String previous=index.getOrDefault(position,"");index.put(position,(previous.startsWith("Nonunique")?"Nonunique ":"Unique ")+Objects.toString(r.getString(3),"<expression not reported>"));}});
                var indexes=new TreeMap<String,String>();indexRows.forEach((key,value)->indexes.put(key,String.join(", ",value.values())));
                uniqueConstraints(connection,type,schema,name,constraints,indexes);
                if(!type.equals("ORACLE"))for(String index:indexes.keySet())objects.get("Indexes").add(name+"."+index);
                Long rows=null;if(countRows)try(var statement=connection.createStatement()){
                    statement.setQueryTimeout(300);try(var result=statement.executeQuery("SELECT COUNT(*) FROM "+quote(metadata,schema)+"."+quote(metadata,name))){result.next();rows=result.getLong(1);}
                }
                tables.put(name,new Table(name,columns,constraints,indexes,rows));
            }
            return new Inventory(type,schema,objects,unsupported,tables);
        }
    }
    private void uniqueConstraints(Connection c,String type,String schema,String table,Map<String,String> result,Map<String,String> indexes)throws SQLException{
        if(type.equals("SYBASE")){query(c,"SELECT i.name FROM sysindexes i JOIN sysobjects o ON o.id=i.id JOIN sysusers u ON u.uid=o.uid WHERE u.name=? AND o.name=? AND i.indid BETWEEN 1 AND 254 AND (i.status2 & 2)=2 AND (i.status & 2048)=0",List.of(schema,table),r->result.put("Unique "+r.getString(1),indexes.getOrDefault(r.getString(1),"Not reported")));return;}
        String sql=type.equals("ORACLE")?"SELECT c.CONSTRAINT_NAME, k.COLUMN_NAME FROM ALL_CONSTRAINTS c JOIN ALL_CONS_COLUMNS k ON k.OWNER=c.OWNER AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME AND k.TABLE_NAME=c.TABLE_NAME WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE='U' ORDER BY c.CONSTRAINT_NAME, k.POSITION":"SELECT c.CONSTRAINT_NAME, k.COLUMN_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS c JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k ON k.CONSTRAINT_CATALOG=c.CONSTRAINT_CATALOG AND k.CONSTRAINT_SCHEMA=c.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME=c.CONSTRAINT_NAME WHERE c.TABLE_SCHEMA=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE='UNIQUE' ORDER BY c.CONSTRAINT_NAME,k.ORDINAL_POSITION";
        var unique=new LinkedHashMap<String,List<String>>();query(c,sql,List.of(schema,table),r->unique.computeIfAbsent(r.getString(1),k->new ArrayList<>()).add(r.getString(2)));unique.forEach((name,columns)->result.put("Unique "+name,String.join(", ",columns)));
    }
    private void checks(Connection c,String type,String schema,String table,Map<String,String> result)throws SQLException{
        if(type.equals("H2"))query(c,"SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_CATALOG=tc.CONSTRAINT_CATALOG AND cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=? AND tc.TABLE_NAME=?",List.of(schema,table),r->result.put("Check "+r.getString(1),r.getString(2)));
        else if(type.equals("ORACLE"))query(c,"SELECT CONSTRAINT_NAME, SEARCH_CONDITION FROM ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? AND CONSTRAINT_TYPE='C'",List.of(schema,table),r->result.put("Check "+r.getString(1),Objects.toString(r.getString(2),"Not reported")));
        else{
            var fragments=new LinkedHashMap<String,StringBuilder>();
            query(c,"SELECT o.name, sc.text FROM sysconstraints cs JOIN sysobjects t ON t.id=cs.tableid JOIN sysusers u ON u.uid=t.uid JOIN sysobjects o ON o.id=cs.constrid JOIN syscomments sc ON sc.id=cs.constrid WHERE u.name=? AND t.name=? AND (cs.status & 128)=128 AND o.type<>'TR' ORDER BY o.name, sc.colid",List.of(schema,table),r->fragments.computeIfAbsent(r.getString(1),k->new StringBuilder()).append(Objects.toString(r.getString(2),"Not reported")));
            fragments.forEach((name,text)->result.put("Check "+name,text.toString()));
        }
    }
    private static String quote(DatabaseMetaData m,String name)throws SQLException{String q=m.getIdentifierQuoteString().trim();if(q.isEmpty())throw new IllegalArgumentException("Driver does not report identifier quoting support.");return q+name.replace(q,q+q)+q;}
    private static String rule(short value){return switch(value){case DatabaseMetaData.importedKeyCascade->"CASCADE";case DatabaseMetaData.importedKeyRestrict->"RESTRICT";case DatabaseMetaData.importedKeySetNull->"SET NULL";case DatabaseMetaData.importedKeyNoAction->"NO ACTION";case DatabaseMetaData.importedKeySetDefault->"SET DEFAULT";default->"UNKNOWN";};}
    private static String optional(ResultSet r,String key){try{return Objects.toString(r.getString(key),"");}catch(SQLException e){return "Not reported";}}
    private static boolean isSized(int type){return Set.of(Types.CHAR,Types.VARCHAR,Types.NCHAR,Types.NVARCHAR,Types.BINARY,Types.VARBINARY).contains(type);}
    private static boolean isNumber(int type){return Set.of(Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL,Types.FLOAT,Types.REAL,Types.DOUBLE).contains(type);}
    private static int numericPrecision(int type,int size){return switch(type){case Types.TINYINT->3;case Types.SMALLINT->5;case Types.INTEGER->10;case Types.BIGINT->19;default->size;};}
    static String logical(int type,String nativeType,String database){
        String n=nativeType.toUpperCase(Locale.ROOT);
        if(n.equals("UNITEXT"))return "UNICODE_LOB";
        if(n.equals("UNICHAR"))return "NCHAR";
        if(n.equals("UNIVARCHAR"))return "NVARCHAR";
        if(database.equals("ORACLE")&&n.equals("DATE"))return "TIMESTAMP";
        if(isNumber(type)&&!Set.of(Types.FLOAT,Types.REAL,Types.DOUBLE).contains(type))return "DECIMAL";
        return switch(type){case Types.VARCHAR->"VARCHAR";case Types.NVARCHAR->"NVARCHAR";case Types.CHAR->"CHAR";case Types.NCHAR->"NCHAR";case Types.BINARY->"BINARY";case Types.VARBINARY->"VARBINARY";case Types.CLOB,Types.LONGVARCHAR->"TEXT_LOB";case Types.NCLOB,Types.LONGNVARCHAR->"UNICODE_LOB";case Types.BLOB,Types.LONGVARBINARY->"BINARY_LOB";case Types.TIMESTAMP->"TIMESTAMP";case Types.TIMESTAMP_WITH_TIMEZONE->"TIMESTAMP WITH TIME ZONE";case Types.DATE->"DATE";case Types.BIT,Types.BOOLEAN->"BOOLEAN";default->n;};
    }
    @FunctionalInterface private interface Row {void accept(ResultSet rows)throws SQLException;}
    private static void query(Connection c,String sql,List<String> parameters,Row consumer)throws SQLException{try(var statement=c.prepareStatement(sql)){
        for(int i=0;i<parameters.size();i++)statement.setString(i+1,parameters.get(i));statement.setQueryTimeout(60);statement.setFetchSize(200);
        try(var rows=statement.executeQuery()){while(rows.next())consumer.accept(rows);}
    }}
}

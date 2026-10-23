package com.edac;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class SchemaComparisonEngineTest {
    SchemaMetadata.Inventory inventory(String database,String nativeType,Set<String> views){
        var objects=new LinkedHashMap<String,Set<String>>();SchemaMetadata.TYPES.forEach(k->objects.put(k,new TreeSet<>()));objects.get("Tables").add("ITEMS");objects.put("Views",views);
        var col=new SchemaMetadata.Column("ID",nativeType,"DECIMAL",Map.of("Position","1","Precision","10","Scale","0","Nullable","No","Default",""));
        return new SchemaMetadata.Inventory(database,"dbo",objects,Set.of(),Map.of("ITEMS",new SchemaMetadata.Table("ITEMS",List.of(col),Map.of("Primary key","ID"),Map.of(),4L)));
    }
    @Test void equalCountsStillDetectDifferentNamesAndTypesCanNormalize(){
        var a=inventory("ORACLE","NUMBER",Set.of("OLD_VIEW"));var b=inventory("SYBASE","int",Set.of("NEW_VIEW"));
        var normalized=SchemaComparisonEngine.compare(a,b,true,true);assertThat(normalized.get("objectCounts")).anyMatch(r->r.get("objectType").equals("Views")&&r.get("status").equals("Names differ"));
        assertThat(normalized.get("objectDifferences")).hasSize(2);assertThat(normalized.get("structureDifferences")).isEmpty();
        assertThat(SchemaComparisonEngine.compare(a,b,false,false).get("structureDifferences")).anyMatch(r->r.get("property").equals("Data type"));
    }
    @Test void literalDefaultsDoNotLoseCaseOrInternalWhitespace(){
        assertThat(SchemaComparisonEngine.canonical(" 'John  Smith' ")).isEqualTo("'John  Smith'");
        assertThat(SchemaComparisonEngine.canonical("current_timestamp")).isEqualTo("CURRENT_TIMESTAMP");
        assertThat(SchemaComparisonEngine.canonical("'NULL'")).isNotEqualTo(SchemaComparisonEngine.canonical("NULL"));
    }
    @Test void unsupportedCategoriesDoNotTurnIntoFalseZeroCounts(){
        var a=inventory("ORACLE","NUMBER",Set.of());var raw=inventory("SYBASE","int",Set.of());
        var b=new SchemaMetadata.Inventory(raw.databaseType(),raw.schema(),raw.objects(),Set.of("Procedures"),raw.tables());
        assertThat(SchemaComparisonEngine.compare(a,b,true,false).get("objectCounts")).anyMatch(row->row.get("objectType").equals("Procedures")&&row.get("afterCount")==null&&row.get("status").equals("Not comparable"));
    }
    @Test void sybaseTimestampIsBinaryRowVersionRatherThanAnOracleTimestamp(){
        assertThat(SchemaMetadata.logical(java.sql.Types.BINARY,"timestamp","SYBASE")).isEqualTo("BINARY");
        assertThat(SchemaMetadata.logical(java.sql.Types.TIMESTAMP,"TIMESTAMP","ORACLE")).isEqualTo("TIMESTAMP");
        assertThat(SchemaMetadata.logical(java.sql.Types.VARCHAR,"univarchar","SYBASE")).isEqualTo("NVARCHAR");
    }
}

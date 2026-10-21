package com.edac;

import static org.assertj.core.api.Assertions.*;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotDatabaseTest {
    @Test void oracleMergeMatchesCompositeKeysAndUpdatesOnlyNonKeyColumns(){
        var table=new SnapshotDatabase.Table("APP","ITEMS",List.of(),List.of("ID","PART"));
        String sql=SnapshotDatabase.mergeSql(table,List.of("ID","PART","VALUE_N"),true);
        assertThat(sql).contains("FROM dual","t.\"ID\"=s.\"ID\" AND t.\"PART\"=s.\"PART\"","WHEN MATCHED THEN UPDATE SET t.\"VALUE_N\"=s.\"VALUE_N\"","WHEN NOT MATCHED THEN INSERT");
        assertThat(sql).doesNotContain("UPDATE SET t.\"ID\"","UPDATE SET t.\"PART\"");
        assertThat(SnapshotDatabase.mergeSql(table,List.of("ID","PART"),true)).doesNotContain("WHEN MATCHED");
    }
    @Test void destinationTypesPreservePrecisionTimeAndUnicode(){
        assertThat(SnapshotDatabase.ddlType(new SnapshotDatabase.Column("AMOUNT",Types.NUMERIC,"NUMBER",0,-127,true),false)).isEqualTo("DECFLOAT(40)");
        assertThat(SnapshotDatabase.ddlType(new SnapshotDatabase.Column("LABEL",Types.VARCHAR,"VARCHAR",100,0,true),true)).isEqualTo("VARCHAR2(100 CHAR)");
        assertThat(SnapshotDatabase.ddlType(new SnapshotDatabase.Column("WHEN_D",Types.DATE,"DATE",0,0,true),false)).isEqualTo("TIMESTAMP(9)");
        assertThatThrownBy(()->SnapshotDatabase.ddlType(new SnapshotDatabase.Column("UNKNOWN",Types.OTHER,"CUSTOM",0,0,true),true)).hasMessageContaining("Unsupported");
    }
}

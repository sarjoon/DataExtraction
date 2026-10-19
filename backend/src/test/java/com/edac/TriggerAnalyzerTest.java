package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TriggerAnalyzerTest {
    @Test void extractsUniqueSourceFieldsAndRemovesBraceBlocks() {
        assertThat(TriggerAnalyzer.extract("{ &ignored = hidden } &a = portfolio_id\n&b=op_currency_id.code\n&c=portfolio_id\n&d=123\n&e=12.5"))
            .isEqualTo("portfolio_id\nop_currency_id.code\n12.5");
        // Preserve the supplied algorithm: it excludes digit-only values, not decimal literals.
        assertThat(TriggerAnalyzer.extract("plain text {&a=removed}" )).isEmpty();
    }
    @Test void selectsNatureBeforeExtractingAndCombinesFragmentsByRank() throws Exception {
        String url="jdbc:h2:mem:trigger_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        var factory=mock(OracleConnectionFactory.class);
        var profile=new Models.ConnectionProfile(1,"test","ORACLE","host",1521,"SERVICE_NAME","service","ignored","sa","PUBLIC",true);
        when(factory.open(profile,"secret")).thenAnswer(call->DriverManager.getConnection(url));
        try(var fixture=DriverManager.getConnection(url);var sql=fixture.createStatement()) {
            sql.execute("CREATE TABLE dict_screen(dict_id INT,name VARCHAR)");
            sql.execute("CREATE TABLE dict_attribute(dict_id INT,sqlname_c VARCHAR)");
            sql.execute("CREATE TABLE script_definition(object_id INT,attribute_dict_id INT,nature_e INT,rank_n INT,definition_c CLOB)");
            sql.execute("INSERT INTO dict_screen VALUES(1,'Portfolio Screen'),(2,'Other Screen'),(3,'Empty Screen')");
            sql.execute("INSERT INTO dict_attribute VALUES(1,'FIELD_A'),(2,'FIELD_B'),(3,'FIELD_C'),(4,'FIELD_D')");
            sql.execute("INSERT INTO script_definition VALUES (1,1,1,1,'&a=wrong_one'),(1,1,2,1,'&a=wrong_two'),(1,1,10,2,'&b=second'),(1,1,10,1,'&a=first'),(1,1,10,3,'&c=first'),(1,2,1,1,'&a=wrong'),(1,2,2,1,'&a=chosen_two'),(1,3,1,1,'&a=chosen_one'),(1,4,3,1,'&a=ignored'),(2,1,10,1,'&a=other_screen')");
            var analyzer=new TriggerAnalyzer(factory);
            var fields=analyzer.analyze(profile,"secret","Portfolio Screen");
            assertThat(fields).containsExactly(new TriggerAnalyzer.TriggerField("FIELD_A","first\nsecond"),new TriggerAnalyzer.TriggerField("FIELD_B","chosen_two"),new TriggerAnalyzer.TriggerField("FIELD_C","chosen_one"));
            assertThat(analyzer.analyze(profile,"secret","Empty Screen")).isEmpty();
            assertThatThrownBy(()->analyzer.analyze(profile,"secret","Missing")).hasMessageContaining("Screen not found");
            sql.execute("SHUTDOWN");
        }
    }
}

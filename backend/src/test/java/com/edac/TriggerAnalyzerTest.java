package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TriggerAnalyzerTest {
    @Test void extractsUniqueSourceFieldsAndRemovesBraceBlocks() {
        assertThat(TriggerAnalyzer.extract("{ &ignored = hidden_id } &a = portfolio_id\n&b=op_currency_id.code\n&c=portfolio_id\n&d=123\n&e=12.5"))
            .isEqualTo("portfolio_id\nop_currency_id.code");
        assertThat(TriggerAnalyzer.extract("{&a=removed_id}" )).isEmpty();
    }
    @Test void extractsFieldsFromConditionalFunctionExpression() {
        assertThat(TriggerAnalyzer.extract("IF(protfolio_id <> NULL AND op_currency_id <> NULL, GET_OBJECT(\"deposit\", depoit_id.code),ud_spread","ud_spread"))
            .isEqualTo("protfolio_id\nop_currency_id\ndepoit_id.code\nud_spread");
    }
    @Test void deduplicatesDottedFieldsAcrossConditionAndResult() {
        assertThat(TriggerAnalyzer.extract("IF(portfolio_id <> NULL AND op_currency_id <> NULL AND deposit_id.ud_origional_load_purpose <> 0, deposit_id.ud_origional_load_purpose)"))
            .isEqualTo("portfolio_id\nop_currency_id\ndeposit_id.ud_origional_load_purpose");
    }
    @Test void acceptsAnyFieldSuffixAndPreservesFullDottedPaths() {
        assertThat(TriggerAnalyzer.extract("ud_spread portfolio_identity 123 12.5 NULL portfolio_id.code.description + portfolio_id.code.description + op_currency_id"))
            .isEqualTo("ud_spread\nportfolio_identity\nportfolio_id.code.description\nop_currency_id");
        assertThat(TriggerAnalyzer.extract("")).isEmpty();
    }
    @Test void extractsTenorDateAndCalendarFieldsFromNestedFunctions() {
        String script="""
            IF(ud_tenor_code_e <> 3,
              IF(IS_BUSINESSDATE(DATEADD(value_d,ud_tenor_code_e,ud_tenor_term_e),op_currency_id.calendar_id)=0,
                NEXT_BUSINESSDATE(DATEADD(value_d,ud_tenor_code_e,ud_tenor_term_e),1,op_currency_id.calendar_id),
                DATEADD(value_d,ud_tenor_code_e,ud_tenor_term_e)),
              IF(IS_BUSINESSDATE(DATEADD(value_d,YEAR,ud_tenor_term_e),op_currency_id.calendar_id)=0,
                NEXT_BUSINESSDATE(DATEADD(value_d,YEAR,ud_tenor_term_e),1,op_currency_id.calendar_id),
                DATEADD(value_d,YEAR,ud_tenor_term_e)))
            """;
        assertThat(TriggerAnalyzer.extract(script)).isEqualTo("ud_tenor_code_e\nvalue_d\nud_tenor_term_e\nop_currency_id.calendar_id");
    }
    @Test void ignoresQuotedStringsNumbersAndConstantsAndDeduplicatesCaseInsensitively() {
        assertThat(TriggerAnalyzer.extract("IF (amount > 1.2e-3 AND flag = TRUE, CUSTOM_FUNCTION('don''t use hidden_field', \"hidden_id\", currency_code), AMOUNT)"))
            .isEqualTo("amount\nflag\ncurrency_code");
        assertThat(TriggerAnalyzer.extract("IF(ud_spread <> NULL, other_field, UD_SPREAD)","ud_spread")).isEqualTo("ud_spread\nother_field");
        assertThat(TriggerAnalyzer.extract("DATEADD(value_d, month, 1)")).isEqualTo("value_d\nmonth");
    }
    @Test void includesConditionsFromEveryIfInTheRequestedRepaymentRule() {
        String rule="""
            IF(ud_prepymt_repymt_e=1 AND ud_waive_ind_f = 0 , ud_old_quantity_n+ud_int_to_date_m+ud_penalty_cost_m,
            IF(ud_prepymt_repymt_e=1 AND ud_waive_ind_f = 1 , ud_old_quantity_n+ud_int_to_date_m+ud_penalty_cost_m-ud_waived_amount_m,
            IF(ud_prepymt_repymt_e=2,ud_
            old_quantity_n+ud_int_to_date_m,NULL)))
            """;
        assertThat(TriggerAnalyzer.extract(rule)).isEqualTo("ud_prepymt_repymt_e\nud_waive_ind_f\nud_old_quantity_n\nud_int_to_date_m\nud_penalty_cost_m\nud_waived_amount_m");
    }
    @Test void includesBothConditionOperandsButExcludesNumbersAndQuotedValues() {
        assertThat(TriggerAnalyzer.extract("IF(left_field = right_field AND amount >= -1.25 AND code = \"fixed, ignored_field=1\" AND other_code <> 'text', result_field, NULL)"))
            .isEqualTo("left_field\nright_field\namount\ncode\nother_code\nresult_field");
    }
    @Test void respectsNestedCallCommasAndKeepsAssignmentsOutsideIfConditionsExcluded() {
        assertThat(TriggerAnalyzer.extract("target = IF(CHECK(left_field = right_field, another_field = 2) AND (flag = 1), branch_target = source_field, IF(other_flag = 0, second_target := second_source, 0))"))
            .isEqualTo("left_field\nright_field\nanother_field\nflag\nsource_field\nother_flag\nsecond_source");
        assertThat(TriggerAnalyzer.extract("IF(GET_OBJECT(\"kind\", &named_arg=lookup_field).id = comparison_field, result_field, NULL)"))
            .isEqualTo("lookup_field\ncomparison_field\nresult_field");
    }
    @Test void normalizesWhitespaceInDottedPaths() {
        assertThat(TriggerAnalyzer.extract("portfolio_id . code + portfolio_id.\ncode")).isEqualTo("portfolio_id.code");
    }
    @Test void extractsRequestedArgumentsAndSingleValueRules() {
        assertThat(TriggerAnalyzer.extract("IS_BUSINESSDATE(DATEADD(value_d,YEAR,ud_tenor_term_e),op_currency_id.calendar_id)"))
            .isEqualTo("value_d\nud_tenor_term_e\nop_currency_id.calendar_id");
        assertThat(TriggerAnalyzer.extract("ud_client_rate_n","ud_client_rate_n")).isEqualTo("ud_client_rate_n");
        assertThat(TriggerAnalyzer.extract("GET_BEST_OBJECT(GET_ENTITY(\"instrument\"), {ignore.this} &portfolio_id=portfolio_id AND &ref_curr_id.code=op_currency_id.code).id"))
            .isEqualTo("portfolio_id\nop_currency_id.code");
        assertThat(TriggerAnalyzer.extract("{commented_field}\n\"quoted_field\"\nIF(real_field <> NULL, GET_ENTITY(\"instrument\"), real_field)"))
            .isEqualTo("real_field");
    }
    @Test void preservesFullEnumRuleAndIgnoresCommentedOrQuotedEnums() {
        String rule="ENUM_LIST(0,1,4,6)";
        assertThat(TriggerAnalyzer.describe("nature_e",rule)).isEqualTo(new TriggerAnalyzer.TriggerField("nature_e","",rule,rule));
        String commented="{ENUM_LIST(0,1)} \"ENUM_LIST(2,3)\" ud_client_rate_n";
        assertThat(TriggerAnalyzer.describe("rate",commented)).isEqualTo(new TriggerAnalyzer.TriggerField("rate","ud_client_rate_n","",commented));
    }
    @Test void ignoresAssignmentTargetsAndConstantRightHandSides() {
        assertThat(TriggerAnalyzer.extract("&target=portfolio_id\n&target.code=op_currency_id.code\n&target=123\n&target=\"fixed value\""))
            .isEqualTo("portfolio_id\nop_currency_id.code");
        assertThat(TriggerAnalyzer.extract("target = DATEADD(value_d,YEAR,tenor_e)")).isEqualTo("value_d\ntenor_e");
        assertThat(TriggerAnalyzer.extract("target := nested(source_field)")).isEqualTo("source_field");
        assertThat(TriggerAnalyzer.extract("target = -1.25")).isEmpty();
        assertThat(TriggerAnalyzer.extract("&target {comment {nested} hidden} = DATEADD {ignored}(value_d,YEAR,tenor_e)"))
            .isEqualTo("value_d\ntenor_e");
        assertThat(TriggerAnalyzer.extract("left_a <> right_a AND left_b >= right_b AND left_c <= right_c AND left_d == right_d"))
            .isEqualTo("left_a\nright_a\nleft_b\nright_b\nleft_c\nright_c\nleft_d\nright_d");
    }
    @Test void flattensActualAndEscapedLineBreaksBeforeExtraction() {
        assertThat(TriggerAnalyzer.extract("ADD(first_field,\nsecond_field,\r\nthird_field,\\nfourth_field,\\r\\nfifth_field)"))
            .isEqualTo("first_field\nsecond_field\nthird_field\nfourth_field\nfifth_field");
    }
    @Test void concatenatesEntireRuleBeforeExtractionAndPreservesOriginalRuleText() {
        String rule="CALCULATE(instr_id,quantity_n,ud_client_rate_n,\nvalue_d,expiration_d,DATEDIFF(value_d,expiration_d,DAY),ud_client_\r\n    rate_n)";
        var result=TriggerAnalyzer.describe("output_field",rule);
        assertThat(result.completeCallTrigger()).isEqualTo("instr_id\nquantity_n\nud_client_rate_n\nvalue_d\nexpiration_d");
        assertThat(result.fullRule()).isEqualTo(rule);
        assertThat(TriggerAnalyzer.extract("ud_client_ra \r\n  te_n")).isEqualTo("ud_client_rate_n");
    }
    @Test void excludesAllUppercaseKeywordsAndRetainsLowercaseAndMixedCaseFields() {
        assertThat(TriggerAnalyzer.extract("DAY + YEAR + UPPER_FIELD_1 + _UPPER_FIELD + quantity_n + Mixed_Field"))
            .isEqualTo("quantity_n\nMixed_Field");
        assertThat(TriggerAnalyzer.extract("UD_CLIENT_RATE_N + ud_client_rate_n")).isEqualTo("ud_client_rate_n");
    }
    @Test void retainsEachDistinctFullDottedKeywordIncludingTheRequestedLoanPurposeField() {
        assertThat(TriggerAnalyzer.extract("IF(deposit_id <> NULL, deposit_id.ud_origional_loan_purpose_e + deposit_id.code + deposit_id.ud_origional_loan_purpose_\ne)"))
            .isEqualTo("deposit_id\ndeposit_id.ud_origional_loan_purpose_e\ndeposit_id.code");
        assertThat(TriggerAnalyzer.extract("&target.code = deposit_id.ud_origional_loan_purpose_e"))
            .isEqualTo("deposit_id.ud_origional_loan_purpose_e");
    }
    @Test void selectsNatureBeforeExtractingAndCombinesFragmentsByRank() throws Exception {
        String url="jdbc:h2:mem:trigger_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1";
        var factory=mock(OracleConnectionFactory.class);
        var readable=mock(ReadableFieldService.class);
        when(readable.names(1)).thenReturn(java.util.Set.of(
            new ReadableFieldService.FieldName("Portfolio Screen","FIELD_A"),new ReadableFieldService.FieldName("Portfolio Screen","FIELD_B"),new ReadableFieldService.FieldName("Portfolio Screen","FIELD_C")));
        var profile=new Models.ConnectionProfile(1,"test","ORACLE","host",1521,"SERVICE_NAME","service","ignored","sa","PUBLIC",true);
        when(factory.open(profile,"secret")).thenAnswer(call->DriverManager.getConnection(url));
        try(var fixture=DriverManager.getConnection(url);var sql=fixture.createStatement()) {
            sql.execute("CREATE TABLE dict_screen(dict_id INT,name VARCHAR)");
            sql.execute("CREATE TABLE dict_attribute(dict_id INT,sqlname_c VARCHAR)");
            sql.execute("CREATE TABLE script_definition(object_id INT,attribute_dict_id INT,nature_e INT,rank_n INT,definition_c CLOB)");
            sql.execute("INSERT INTO dict_screen VALUES(1,'Portfolio Screen'),(2,'Other Screen'),(3,'Empty Screen')");
            sql.execute("INSERT INTO dict_attribute VALUES(1,'FIELD_A'),(2,'FIELD_B'),(3,'FIELD_C'),(4,'FIELD_D'),(5,'NOT_CONFIGURED')");
            sql.execute("INSERT INTO script_definition VALUES (1,1,1,1,'&a=wrong_one_id'),(1,1,2,1,'&a=wrong_two_id'),(1,1,10,2,'&b=second_id + FIELD_A'),(1,1,10,1,'&a=first_id'),(1,1,10,3,'&c=first_id'),(1,2,1,1,'&a=wrong_id'),(1,2,2,1,'&a=chosen_two_id'),(1,3,1,1,'&a=chosen_one_id'),(1,4,3,1,'&a=ignored_id'),(2,1,10,1,'&a=other_screen_id')");
            sql.execute("INSERT INTO script_definition VALUES(1,5,10,1,'hidden_table_field')");
            var analyzer=new TriggerAnalyzer(factory,readable);
            var fields=analyzer.analyze(profile,"secret","Portfolio Screen");
            assertThat(fields).extracting(TriggerAnalyzer.TriggerField::attributeName,TriggerAnalyzer.TriggerField::completeCallTrigger)
                .containsExactly(tuple("FIELD_A","first_id\nsecond_id"),tuple("FIELD_B","chosen_two_id"),tuple("FIELD_C","chosen_one_id"));
            assertThat(fields).extracting(TriggerAnalyzer.TriggerField::screenName).containsOnly("Portfolio Screen");
            assertThat(fields.get(0).fullRule()).isEqualTo("&a=first_id\n&b=second_id + FIELD_A\n&c=first_id\n");
            assertThat(analyzer.analyze(profile,"secret","Empty Screen")).isEmpty();
            assertThat(analyzer.analyze(profile,"secret","Other Screen")).isEmpty();
            assertThatThrownBy(()->analyzer.analyze(profile,"secret","Missing")).hasMessageContaining("Screen not found");
            when(readable.names(1)).thenReturn(java.util.Set.of());
            assertThatThrownBy(()->analyzer.analyze(profile,"secret","Portfolio Screen")).hasMessageContaining("Configure readable");
            sql.execute("SHUTDOWN");
        }
    }
}

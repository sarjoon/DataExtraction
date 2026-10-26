package com.edac;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:profile_matrix_config;DB_CLOSE_DELAY=-1","spring.datasource.password="})
@ActiveProfiles("local") @AutoConfigureMockMvc
class UserProfileMatrixIntegrationTest {
    @Autowired RegistryService registry;
    @Autowired UserProfileMatrixService reports;
    @Autowired JdbcTemplate config;
    @Autowired MockMvc mvc;
    private Models.ConnectionProfile connection(){
        String name="matrix_"+UUID.randomUUID().toString().replace("-","");
        return registry.save(new Models.ConnectionInput(name,"H2",null,null,"MEMORY",name,"sa","REPORT_TEST","",true));
    }
    private String base(long id){return "/api/reports/user-profile-matrix/connections/"+id;}
    @Test void acceptsFreeTextInsteadOfPersistingUserCodesAndRejectsInvalidInput()throws Exception{
        var profile=connection();String path=base(profile.id());
        assertThat(config.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='REPORT_USER_CODE'",Integer.class)).isZero();
        for(String invalid:List.of(""," ","X".repeat(257),"USER\nCODE")){
            assertThatThrownBy(()->reports.matrix(profile.id(),invalid,0,100)).hasMessageContaining("USER_CODE");
            mvc.perform(get(path+"/matrix").param("userCode",invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(path+"/matrix")).andExpect(status().isBadRequest());
        mvc.perform(get(path+"/users")).andExpect(status().isNotFound());
        mvc.perform(get("/reports/user-profile-matrix")).andExpect(status().isOk());
    }
    @Test void executesAllQueriesForEnteredUserWithSchemaScopeMembershipChecksAndPaging()throws Exception{
        var profile=connection();
        try(var connection=DriverManager.getConnection(profile.jdbcUrl(),"sa","")){
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("sql/user-profile-matrix-fixture.sql"));
        }
        String user="USER001";
        var matrix=reports.matrix(profile.id(),"  "+user+"  ",0,100);
        assertThat(matrix.columns()).containsExactly("USER_CODE","ACCESS_SOURCE","CHANNEL_CODE","CHANNEL_PROFILE","PROFILE_TYPE","PROFILE_CODE");
        assertThat(matrix.rows()).hasSize(13);assertThat(matrix.hasMore()).isFalse();
        assertThat(matrix.rows()).allSatisfy(row->assertThat(row.get(0)).isEqualTo(user));
        assertThat(matrix.rows()).extracting(row->row.get(1)).contains("DIRECT_USER","CHANNEL_PROFILE");
        var first=reports.matrix(profile.id(),user,0,1);var second=reports.matrix(profile.id(),user,1,1);
        assertThat(first.hasMore()).isTrue();assertThat(first.rows()).hasSize(1);assertThat(second.rows().get(0)).isNotEqualTo(first.rows().get(0));
        var report=reports.details(profile.id(),user,"REPORT","RPT_OPER",0,100);
        assertThat(report.columns()).containsExactly("REPORT_PROFILE","REPORT_CODE","REPORT_DENOM");assertThat(report.rows()).hasSize(2);
        var data=reports.details(profile.id(),user,"DATA","DATA_OPER",0,100);
        assertThat(data.rows().get(0)).containsExactly("DATA_OPER","DATA_SEC_OPER","1","0");
        var security=reports.details(profile.id(),user,"FUNCTION_SECURITY","FSP_OPER",0,100);
        assertThat(security.columns()).hasSize(14);assertThat(security.rows().get(0)).contains("Draft","Validated");
        var formats=reports.details(profile.id(),user,"FORMAT","FMT_OPER",0,100);
        assertThat(formats.rows().get(0).get(1)).isEqualTo("F_DEPOSIT");
        var elements=reports.children(profile.id(),user,"FORMAT","FMT_OPER","F_DEPOSIT",0,100);
        assertThat(elements.columns()).hasSize(13);assertThat(elements.rows().get(0)).contains("quantity_n");
        var screens=reports.details(profile.id(),user,"SCREEN","SCR_OPER",0,100);
        assertThat(screens.rows().get(0).get(1)).isEqualTo("Deposit Entry");
        var panels=reports.children(profile.id(),user,"SCREEN","SCR_OPER","Deposit Entry",0,100);
        assertThat(panels.columns()).hasSize(10).doesNotHaveDuplicates().contains("PANEL_DENOM","PARENT_PANEL_DENOM");
        assertThat(panels.rows()).hasSize(2);
        assertThat(reports.details(profile.id(),user,"MENU","MENU_OPER",0,100).message()).contains("No detail query");
        assertThat(reports.details(profile.id(),user,"EXTERNAL_SERVICE","EXT_OPER",0,100).message()).contains("No detail query");
        assertThatThrownBy(()->reports.details(profile.id(),user,"REPORT","RPT_PRIVATE",0,100)).hasMessageContaining("not assigned");
        assertThatThrownBy(()->reports.details(profile.id(),user,"REPORT","RPT_OPER' OR '1'='1",0,100)).hasMessageContaining("not assigned");
        assertThatThrownBy(()->reports.children(profile.id(),user,"FORMAT","FMT_OPER","F_PRIVATE",0,100)).hasMessageContaining("does not belong");
        assertThatThrownBy(()->reports.children(profile.id(),user,"SCREEN","SCR_OPER","Hidden Screen",0,100)).hasMessageContaining("does not belong");
        assertThatThrownBy(()->reports.matrix(profile.id(),user,-1,100)).hasMessageContaining("nonnegative");
        for(String code:List.of("NO_SUCH_USER","X' OR '1'='1"))assertThat(reports.matrix(profile.id(),code,0,100).rows()).isEmpty();
        String path=base(profile.id());
        mvc.perform(get(path+"/matrix").param("userCode",user)).andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(13));
        mvc.perform(get(path+"/profiles/REPORT").param("userCode",user).param("profileCode","RPT_OPER")).andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(2));
        mvc.perform(get(path+"/profiles/FORMAT/items").param("userCode",user).param("profileCode","FMT_OPER").param("item","F_DEPOSIT")).andExpect(status().isOk()).andExpect(jsonPath("$.rows[0][1]").value("quantity_n"));
        mvc.perform(get(path+"/matrix").param("userCode",user).param("size","201")).andExpect(status().isBadRequest());
        mvc.perform(get(path+"/profiles/UNKNOWN").param("userCode",user).param("profileCode","anything")).andExpect(status().isBadRequest());
        try(var connection=DriverManager.getConnection(profile.jdbcUrl(),"sa","")){try(var statement=connection.createStatement()){statement.execute("SHUTDOWN");}}
    }
}

package com.edac;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:security_test;DB_CLOSE_DELAY=-1","spring.datasource.password=","edac.admin-password=testing-only-password-123"})
@AutoConfigureMockMvc @ActiveProfiles("production")
class ProductionSecurityTest {
    @Autowired MockMvc mvc;
    @Test void requiresAuthenticationAndDisablesConsole() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/status").with(httpBasic("edac-admin","testing-only-password-123"))).andExpect(status().isOk());
        mvc.perform(get("/h2-console/").with(httpBasic("edac-admin","testing-only-password-123"))).andExpect(status().isForbidden());
    }
}

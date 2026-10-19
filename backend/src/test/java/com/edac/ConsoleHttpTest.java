package com.edac;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.*;
import java.util.Map;

// Separate context: MockMvc's csrf() helper replaces the filter's repository for mock requests.
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:http_test;DB_CLOSE_DELAY=-1","spring.datasource.password="})
@ActiveProfiles("local")
class ConsoleHttpTest {
    @Autowired TestRestTemplate http;
    @Test void servesBundledFrontendAndDirectAngularRoutes() {
        for (String path : new String[]{"/", "/index.html", "/overview", "/connections", "/tables", "/mapping", "/generation", "/triggers"}) {
            var response = http.getForEntity(path, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("<edac-root>");
        }
        var index = http.getForObject("/index.html", String.class);
        var scripts = java.util.regex.Pattern.compile("src=\"([^\"]+\\.js)\"").matcher(index);
        assertThat(scripts.find()).isTrue();
        assertThat(http.getForEntity("/" + scripts.group(1), String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/sample-screens.csv",String.class).getBody()).contains("Screen Name");
    }
    @Test void realCookieProtectsWritesAndConsoleAllowsSameOriginFrames() {
        var csrf=http.getForEntity("/api/csrf",Map.class);
        assertThat(csrf.getStatusCode().is2xxSuccessful()).isTrue();
        String cookie=csrf.getHeaders().getFirst("Set-Cookie");
        assertThat(cookie).startsWith("XSRF-TOKEN=");
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        var input=new Models.ConnectionInput("HTTP test","ORACLE","oracle.example.com",1521,"SERVICE_NAME","ORCLPDB1","app_user","APP","test-secret");
        var denied=http.postForEntity("/api/connections",new HttpEntity<>(input,headers),String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        headers.set("Cookie",cookie.split(";")[0]);headers.set("X-XSRF-TOKEN",csrf.getBody().get("token").toString());
        var accepted=http.postForEntity("/api/connections",new HttpEntity<>(input,headers),String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        var console=http.getForEntity("/h2-console/",String.class);
        assertThat(console.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(console.getHeaders().getFirst("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(console.getBody()).containsIgnoringCase("H2");
    }
}

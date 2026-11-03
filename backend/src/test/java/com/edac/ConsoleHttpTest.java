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
    @Autowired RegistryService registry;
    @Test void acceptsBauWorkbookLargerThanOldFileAndRequestLimitsOverHttp() throws Exception {
        byte[] workbook;
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var output=new java.io.ByteArrayOutputStream()) {
            var spec=book.createSheet("File Spec");spec.createRow(0).createCell(0).setCellValue("File Name");spec.createRow(1).createCell(0).setCellValue("LEGACY_TABLE");
            book.createSheet("LEGACY_TABLE").createRow(0).createCell(0).setCellValue("Original BAU definition");
            for(int i=1;i<=60;i++) {
                String table="LEGACY_"+i;spec.createRow(i+1).createCell(0).setCellValue(table);
                book.createSheet(table).createRow(0).createCell(0).setCellValue("BAU definition "+i);
            }
            book.write(output);workbook=output.toByteArray();
        }
        // Incompressible package data makes the real uploaded XLSX larger than 6 MB.
        try(var input=new java.io.ByteArrayInputStream(workbook);var pkg=org.apache.poi.openxml4j.opc.OPCPackage.open(input);var output=new java.io.ByteArrayOutputStream()) {
            var part=pkg.createPart(org.apache.poi.openxml4j.opc.PackagingURIHelper.createPartName("/bau-test-data.bin"),"application/octet-stream");
            byte[] padding=new byte[6*1024*1024];new java.util.Random(42).nextBytes(padding);
            try(var stream=part.getOutputStream()){stream.write(padding);}
            pkg.save(output);workbook=output.toByteArray();
        }
        assertThat(workbook.length).isGreaterThan(6*1024*1024);
        String name="large_bau_"+java.util.UUID.randomUUID().toString().replace("-","");
        var profile=registry.save(new Models.ConnectionInput(name,"H2",null,null,"MEMORY",name,"sa","PUBLIC",""));
        registry.append(profile.id(),java.util.List.of("LEGACY_TABLE"),"MANUAL");
        registry.append(profile.id(),java.util.stream.IntStream.rangeClosed(1,60).mapToObj(i->"LEGACY_"+i).toList(),"MANUAL");
        var csrf=http.getForEntity("/api/csrf",Map.class);var headers=new HttpHeaders();headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Cookie",csrf.getHeaders().getFirst("Set-Cookie").split(";")[0]);headers.set("X-XSRF-TOKEN",csrf.getBody().get("token").toString());
        var body=new org.springframework.util.LinkedMultiValueMap<String,Object>();
        body.add("bauFile",new org.springframework.core.io.ByteArrayResource(workbook){@Override public String getFilename(){return "bau.xlsx";}});
        body.add("tableIds",registry.tables(profile.id()).stream().map(table->Long.toString(table.id())).collect(java.util.stream.Collectors.joining(",")));
        var response=http.exchange("/api/connections/"+profile.id()+"/mapping-workbook",HttpMethod.POST,new HttpEntity<>(body,headers),byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try(var result=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response.getBody()))) {
            assertThat(result.getSheet("LEGACY_TABLE").getRow(0).getCell(0).getStringCellValue()).isEqualTo("Original BAU definition");
            assertThat(result.getSheetAt(0).getRow(3).getCell(36).getStringCellValue()).isEqualTo("BAU");
        }
        assertThat(registry.tables(profile.id())).isEmpty();
    }
    @Test void servesBundledFrontendAndDirectAngularRoutes() {
        for (String path : new String[]{"/", "/index.html", "/overview", "/connections", "/tables", "/mapping", "/generation", "/triggers"}) {
            var response = http.getForEntity(path, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("<edac-root>");
        }
        var index = http.getForObject("/index.html", String.class);
        var scripts = java.util.regex.Pattern.compile("src=\"([^\"]+\\.js)\"").matcher(index);
        assertThat(scripts.find()).isTrue();
        var bundle=http.getForEntity("/" + scripts.group(1), String.class);
        assertThat(bundle.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bundle.getBody()).as("Packaged UI includes the BAU mapping upload")
            .contains("Is a BAU mapping workbook available?","bau-workbook","BAU mapping workbook (.xlsx)");
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

package com.edac;
import static com.edac.Models.*;
import java.io.IOException;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController
@RequestMapping("/api/connections/{connectionId}/mapping-workbook")
public class MappingWorkbookController {
    private final MappingWorkbookService service;
    private final BauMappingWorkbookService bauService;
    public MappingWorkbookController(MappingWorkbookService service,BauMappingWorkbookService bauService){this.service=service;this.bauService=bauService;}
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> generate(@PathVariable long connectionId,@Valid @RequestBody WorkbookRequest input) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=Extraction_Framework_Mapping.xlsx")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(service.generate(connectionId,input.tableIds()));
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> generateWithBau(@PathVariable long connectionId,
        @RequestParam(value="tableIds",required=false) java.util.List<Long> tableIds,@RequestParam("bauFile") MultipartFile bauFile) throws IOException {
        byte[] bytes=bauService.generate(connectionId,tableIds==null?java.util.List.of():tableIds,bauFile);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=Extraction_Framework_Mapping.xlsx")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(bytes);
    }
}

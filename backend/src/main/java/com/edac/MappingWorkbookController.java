package com.edac;
import static com.edac.Models.*;
import java.io.IOException;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/connections/{connectionId}/mapping-workbook")
public class MappingWorkbookController {
    private final MappingWorkbookService service;
    public MappingWorkbookController(MappingWorkbookService service){this.service=service;}
    @PostMapping
    public ResponseEntity<byte[]> generate(@PathVariable long connectionId,@Valid @RequestBody WorkbookRequest input) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=Extraction_Framework_Mapping.xlsx")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(service.generate(connectionId,input.tableIds()));
    }
}

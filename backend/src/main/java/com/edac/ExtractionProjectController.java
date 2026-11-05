package com.edac;
import java.io.IOException;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/connections/{connectionId}/extraction-project")
public class ExtractionProjectController {
    private final ExtractionProjectService service;
    public ExtractionProjectController(ExtractionProjectService service){this.service=service;}
    @GetMapping("/columns/{tableId}") public List<ExtractionProjectService.ColumnOption> columns(@PathVariable long connectionId,@PathVariable long tableId){return service.columns(connectionId,tableId);}
    @GetMapping("/configuration") public ExtractionProjectService.Configuration configuration(@PathVariable long connectionId) throws IOException {return service.configuration(connectionId);}
    @PostMapping("/configuration") public ExtractionProjectService.Configuration save(@PathVariable long connectionId,@Valid @RequestBody ExtractionProjectService.Request request) throws IOException {return service.save(connectionId,request);}
    @PostMapping public ResponseEntity<byte[]> generate(@PathVariable long connectionId,@Valid @RequestBody ExtractionProjectService.Request request) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=EDAC_Extraction_"+request.engine()+"_Project.zip").body(service.generate(connectionId,request));
    }
}

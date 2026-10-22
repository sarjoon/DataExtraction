package com.edac;
import java.io.IOException;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/connections/{connectionId}/extraction-scripts")
public class ExtractionScriptController {
    private final ExtractionScriptService service;
    public ExtractionScriptController(ExtractionScriptService service){this.service=service;}
    @GetMapping("/columns/{tableId}") public List<Models.ColumnDetail> columns(@PathVariable long connectionId,@PathVariable long tableId){return service.columns(connectionId,tableId);}
    @PostMapping public ResponseEntity<byte[]> generate(@PathVariable long connectionId,@Valid @RequestBody ExtractionScriptService.Request request) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CACHE_CONTROL,"no-store").header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=Oracle_Extraction_Scripts.zip").body(service.generate(connectionId,request));
    }
}

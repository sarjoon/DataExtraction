package com.edac;
import java.io.IOException;
import java.util.*;
import java.util.zip.ZipFile;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/snapshot/schema")
public class SchemaComparisonController {
    private final SchemaComparisonService service;private final SchemaComparisonReports reports;
    public SchemaComparisonController(SchemaComparisonService service,SchemaComparisonReports reports){this.service=service;this.reports=reports;}
    @PostMapping("/compare") public SchemaComparisonService.Job compare(@Valid @RequestBody SchemaComparisonService.Request request){return service.start(request);}
    @GetMapping("/jobs/{id}") public SchemaComparisonService.Job job(@PathVariable String id){return service.job(id);}
    @GetMapping("/history") public List<Map<String,Object>> history()throws IOException{return reports.history();}
    @GetMapping("/reports/{id}") public Map<String,Object> summary(@PathVariable String id)throws IOException{return reports.summary(id);}
    @GetMapping("/reports/{id}/{section}") public Map<String,Object> page(@PathVariable String id,@PathVariable String section,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="10")int size,@RequestParam(defaultValue="")String table)throws IOException{return reports.page(id,section,page,size,table);}
    @GetMapping("/reports/{id}/download") public ResponseEntity<StreamingResponseBody> excel(@PathVariable String id){var path=reports.zip(id);return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(id+".xlsx").build().toString()).body(output->{try(var zip=new ZipFile(path.toFile());var input=zip.getInputStream(zip.getEntry(id+".xlsx"))){input.transferTo(output);}});}
    @GetMapping("/reports/{id}/zip") public ResponseEntity<FileSystemResource> zip(@PathVariable String id){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(id+".zip").build().toString()).body(new FileSystemResource(reports.zip(id)));}
}

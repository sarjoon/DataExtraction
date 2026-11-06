package com.edac;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/connections/{connectionId}/extraction-project")
public class ExtractionProjectController {
    private static final Logger LOG=LoggerFactory.getLogger(ExtractionProjectController.class);
    private static final String REQUEST_ID="edac.extraction.requestId";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ExtractionProjectService service;
    public ExtractionProjectController(ExtractionProjectService service){this.service=service;}
    @GetMapping("/columns/{tableId}") public List<ExtractionProjectService.ColumnOption> columns(@PathVariable long connectionId,@PathVariable long tableId){return service.columns(connectionId,tableId);}
    @GetMapping("/configuration") public ExtractionProjectService.Configuration configuration(@PathVariable long connectionId) throws IOException {return service.configuration(connectionId);}
    @PostMapping("/configuration") public ExtractionProjectService.Configuration save(@PathVariable long connectionId,@Valid @RequestBody ExtractionProjectService.Request request) throws IOException {return service.save(connectionId,request);}
    @PostMapping public ResponseEntity<byte[]> generate(@PathVariable long connectionId,@Valid @RequestBody ExtractionProjectService.Request request,HttpServletRequest servlet) throws IOException {
        String id=requestId(servlet);
        LOG.info("Extraction project generation received: reference={}, connection={}, engine={}, tableCount={}",id,connectionId,request.engine(),request.tableIds().size());
        byte[] zip=service.generate(connectionId,request);
        LOG.info("Extraction project generation completed: reference={}, zipBytes={}",id,zip.length);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header("X-EDAC-Request-Id",id).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=EDAC_Extraction_"+request.engine()+"_Project.zip").body(zip);
    }
    private static String requestId(HttpServletRequest request) {
        Object existing=request.getAttribute(REQUEST_ID);if(existing!=null)return existing.toString();
        String id=UUID.randomUUID().toString();request.setAttribute(REQUEST_ID,id);return id;
    }
    // Explicit JSON bytes keep error details readable even for a binary ZIP download.
    @ExceptionHandler({IllegalArgumentException.class,IOException.class,MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,ResponseStatusException.class,RuntimeException.class})
    public ResponseEntity<byte[]> failure(Exception exception,HttpServletRequest request) throws IOException {
        String id=requestId(request);int status=500;String message="Extraction request failed. Check the server log using the reference.";
        if(exception instanceof MethodArgumentNotValidException invalid){status=400;message=invalid.getBindingResult().getFieldErrors().stream().map(f->f.getField()+": "+f.getDefaultMessage()).reduce((a,b)->a+"; "+b).orElse("Invalid extraction request.");}
        else if(exception instanceof HttpMessageNotReadableException){status=400;message="Cannot read extraction settings. The request must contain valid JSON matching the extraction configuration.";}
        else if(exception instanceof ResponseStatusException response){status=response.getStatusCode().value();message=response.getReason()==null?"Extraction request failed.":response.getReason();}
        else if(exception instanceof IllegalArgumentException){status=400;message=exception.getMessage()==null?"Invalid extraction settings.":exception.getMessage();}
        else if(exception instanceof IOException){message="Cannot create or read extraction project resources. Check the server log using the reference.";}
        LOG.warn("Extraction request failed: reference={}, method={}, path={}, status={}",id,request.getMethod(),request.getRequestURI(),status,exception);
        var details=new LinkedHashMap<String,Object>();details.put("message",message);details.put("requestId",id);details.put("path",request.getRequestURI());
        if(exception instanceof ExtractionProjectService.SourceTablesUnavailable missing){details.put("code","SOURCE_TABLE_UNAVAILABLE");details.put("tables",missing.tables());}
        byte[] body=JSON.writeValueAsBytes(details);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-EDAC-Request-Id",id).body(body);
    }
}

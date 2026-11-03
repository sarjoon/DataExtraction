package com.edac;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import java.io.IOException;
import jakarta.servlet.MultipartConfigElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    private final ObjectProvider<MultipartConfigElement> multipart;
    public ApiErrors(ObjectProvider<MultipartConfigElement> multipart){this.multipart=multipart;}
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e){return ResponseEntity.badRequest().body(Map.of("message",e.getBindingResult().getFieldErrors().stream().map(f->f.getField()+": "+f.getDefaultMessage()).reduce((a,b)->a+"; "+b).orElse("Invalid request.")));}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> size(MaxUploadSizeExceededException exception){
        String code="MULTIPART_REJECTED";
        String message="Server rejected the multipart upload. Check the rejection code and effective limits in this response.";
        Throwable root=exception;
        for(Throwable cause=exception;cause!=null;cause=cause.getCause()) {
            root=cause;
            String type=cause.getClass().getSimpleName();
            if(type.contains("FileCountLimit") || type.contains("PartCount")) {
                code="MULTIPART_PART_COUNT";message="Server rejected the number of upload fields. Refresh the application to use the updated BAU upload format.";
            }else if(type.contains("FileSizeLimit")) {
                code="MULTIPART_FILE_SIZE";message="The running server has a file-size limit. Check deployment configuration overrides.";
            }else if(type.contains("SizeLimitExceeded")) {
                code="MULTIPART_REQUEST_SIZE";message="The running server has a request-size limit. Check deployment configuration overrides.";
            }else if(cause.getMessage()!=null && cause.getMessage().toLowerCase(java.util.Locale.ROOT).contains("header")) {
                code="MULTIPART_HEADER_SIZE";message="Server rejected an upload header. Check the filename length and multipart header settings.";
            }
        }
        var config=multipart.getIfAvailable();
        long fileLimit=config==null?-2:config.getMaxFileSize(),requestLimit=config==null?-2:config.getMaxRequestSize();
        log.warn("Upload rejected: code={}, rootCause={}, maxFileBytes={}, maxRequestBytes={}",code,root.getClass().getName(),fileLimit,requestLimit,exception);
        return ResponseEntity.status(413).body(Map.of("message",message,"code",code,"rootCause",root.getClass().getSimpleName(),
            "maxFileBytes",fileLimit,"maxRequestBytes",requestLimit));
    }
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> missing(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"Request failed.":e.getReason()));}
    @ExceptionHandler({HttpMessageNotReadableException.class,IOException.class})
    ResponseEntity<?> malformed(){return ResponseEntity.badRequest().body(Map.of("message","Cannot read the request. Check the supplied file or fields."));}
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(){return ResponseEntity.internalServerError().body(Map.of("message","Could not save the data. Check backend database availability."));}
}

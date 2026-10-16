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

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e){return ResponseEntity.badRequest().body(Map.of("message",e.getBindingResult().getFieldErrors().stream().map(f->f.getField()+": "+f.getDefaultMessage()).reduce((a,b)->a+"; "+b).orElse("Invalid request.")));}
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> size(){return ResponseEntity.status(413).body(Map.of("message","Upload a file no larger than 5 MB."));}
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> missing(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"Request failed.":e.getReason()));}
    @ExceptionHandler({HttpMessageNotReadableException.class,IOException.class})
    ResponseEntity<?> malformed(){return ResponseEntity.badRequest().body(Map.of("message","Cannot read the request. Check the supplied file or fields."));}
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(){return ResponseEntity.internalServerError().body(Map.of("message","Could not save the data. Check backend database availability."));}
}

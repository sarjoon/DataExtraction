package com.edac;

import java.io.IOException;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/connections/{connectionId}/screens")
public class TriggerController {
    public record ScreenNames(@NotBlank @Size(max=256) String instrument,@NotEmpty @Size(max=10000) List<@NotBlank @Size(max=256) String> names) {}
    public record ScreenSelection(@NotEmpty @Size(max=200) List<@NotNull @Positive Long> screenIds) {}
    private final TriggerService service;
    private final TableListReader reader;
    public TriggerController(TriggerService service,TableListReader reader){this.service=service;this.reader=reader;}
    @GetMapping public List<TriggerService.ScreenEntry> screens(@PathVariable long connectionId,@RequestParam(required=false) String instrument){return service.screens(connectionId,instrument);}
    @PostMapping public Models.AppendResult append(@PathVariable long connectionId,@Valid @RequestBody ScreenNames input){return service.append(connectionId,input.instrument(),input.names(),"MANUAL");}
    @PostMapping("/import") public Models.AppendResult upload(@PathVariable long connectionId,@RequestParam(required=false) String instrument,@RequestParam("file") MultipartFile file) throws IOException {
        return service.register(connectionId,reader.readInstrumentScreens(file,instrument),"UPLOAD");
    }
    @PostMapping("/workbook") public ResponseEntity<byte[]> generate(@PathVariable long connectionId,@Valid @RequestBody ScreenSelection input) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=Complete_Call_Trigger.xlsx")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(service.generate(connectionId,input.screenIds()));
    }
}

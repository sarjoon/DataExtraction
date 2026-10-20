package com.edac;

import java.io.IOException;
import java.util.List;
import com.edac.ReadableFieldService.FieldName;
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
    public record ReadableFields(@NotEmpty @Size(max=10000) List<@NotNull @Valid FieldName> fields) {}
    public record ScreenFields(@NotNull @Size(max=10000) List<@NotNull @Valid FieldName> fields) {}
    private final TriggerService service;
    private final TableListReader reader;
    private final ReadableFieldService readable;
    public TriggerController(TriggerService service,TableListReader reader,ReadableFieldService readable){this.service=service;this.reader=reader;this.readable=readable;}
    @GetMapping("/readable-fields") public List<ReadableFieldService.Entry> readableFields(@PathVariable long connectionId){return readable.list(connectionId);}
    @PostMapping("/readable-fields") public Models.AppendResult addReadable(@PathVariable long connectionId,@Valid @RequestBody ReadableFields input){return readable.append(connectionId,input.fields());}
    @PostMapping("/readable-fields/import") public Models.AppendResult uploadReadable(@PathVariable long connectionId,@RequestParam("file") MultipartFile file) throws IOException {
        if(file.getOriginalFilename()==null || !file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT).endsWith(".csv"))throw new IllegalArgumentException("Upload a CSV with Screen Name and Field Name headers.");
        return readable.append(connectionId,reader.readReadableFields(file));
    }
    @GetMapping("/{screenId}/readable-fields") public List<ReadableFieldService.ScreenField> screenFields(@PathVariable long connectionId,@PathVariable long screenId){return readable.screenFields(connectionId,screenId);}
    @PostMapping("/{screenId}/readable-fields") public Models.AppendResult saveScreenFields(@PathVariable long connectionId,@PathVariable long screenId,@Valid @RequestBody ScreenFields input){return readable.saveScreenFields(connectionId,screenId,input.fields());}
    @PostMapping("/readable-fields/{fieldId}/remove") public void removeReadable(@PathVariable long connectionId,@PathVariable long fieldId){readable.remove(connectionId,fieldId);}
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

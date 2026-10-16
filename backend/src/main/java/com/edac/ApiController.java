package com.edac;

import static com.edac.Models.*;
import java.io.IOException;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.flywaydb.core.Flyway;

@RestController @RequestMapping("/api")
public class ApiController {
    private final RegistryService registry;
    private final TableListReader reader;
    private final Flyway flyway;
    private final Environment environment;
    public ApiController(RegistryService registry,TableListReader reader,Flyway flyway,Environment environment){this.registry=registry;this.reader=reader;this.flyway=flyway;this.environment=environment;}
    @GetMapping("/csrf") public Map<String,String> csrf(CsrfToken token){return Map.of("token",token.getToken());}
    @GetMapping("/status") public Map<String,Object> status(){
        var current=flyway.info().current();
        return Map.of("status","UP","database","H2","migrationVersion",current==null?"None":current.getVersion().toString(),"consoleEnabled",environment.acceptsProfiles(Profiles.of("local")));
    }
    @GetMapping("/connections") public List<ConnectionProfile> connections(){return registry.connections();}
    @PostMapping("/connections") public ConnectionProfile save(@Valid @RequestBody ConnectionInput input){return registry.save(input);}
    @PostMapping("/connections/{id}") public ConnectionProfile update(@PathVariable long id,@Valid @RequestBody ConnectionInput input){return registry.update(id,input);}
    @PostMapping("/connections/{id}/test") public TestResult test(@PathVariable long id){return registry.test(id);}
    @GetMapping("/connections/{id}/tables") public List<TableEntry> tables(@PathVariable long id){return registry.tables(id);}
    @PostMapping("/connections/{id}/tables") public AppendResult append(@PathVariable long id,@Valid @RequestBody AppendRequest input){return registry.append(id,input.names(),"MANUAL");}
    @PostMapping("/connections/{id}/tables/import") public AppendResult upload(@PathVariable long id,@RequestParam("file") MultipartFile file)throws IOException{return registry.append(id,reader.read(file),"UPLOAD");}
    @PostMapping("/connections/{id}/tables/discover") public AppendResult discover(@PathVariable long id){
        var result=registry.discover(id);
        if(result.isEmpty())return new AppendResult(0,0,registry.tables(id).size());
        return registry.append(id,result.stream().map(t->t.schema()+"."+t.name()).toList(),"DATABASE");
    }
}

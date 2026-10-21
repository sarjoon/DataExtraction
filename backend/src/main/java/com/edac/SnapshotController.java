package com.edac;

import java.util.*;
import java.sql.SQLException;
import java.io.IOException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/snapshot")
public class SnapshotController {
    private final SnapshotService snapshots;private final SnapshotReports reports;
    public SnapshotController(SnapshotService snapshots,SnapshotReports reports){this.snapshots=snapshots;this.reports=reports;}
    @GetMapping("/batches/{connection}") public List<SnapshotService.Batch> batches(@PathVariable long connection){return snapshots.batches(connection);}
    @PostMapping("/batches/{connection}") public SnapshotService.Batch saveBatch(@PathVariable long connection,@RequestBody SnapshotService.BatchInput input){return snapshots.saveBatch(connection,input);}
    @PostMapping("/batches/{connection}/{id}/remove") public void removeBatch(@PathVariable long connection,@PathVariable long id){snapshots.removeBatch(connection,id);}
    @GetMapping("/tables/{connection}") public List<String> tables(@PathVariable long connection){return snapshots.registeredTables(connection);}
    @GetMapping("/tables/{connection}/discover") public List<String> discover(@PathVariable long connection) throws SQLException{return snapshots.discoverTables(connection);}
    @PostMapping("/tables/{connection}") public List<String> register(@PathVariable long connection,@RequestBody List<String> tables) throws SQLException{return snapshots.registerTables(connection,tables);}
    @GetMapping("/configuration/{connection}") public List<SnapshotService.Config> config(@PathVariable long connection){return snapshots.config(connection);}
    @PostMapping("/configuration/{connection}") public List<SnapshotService.Config> configure(@PathVariable long connection,@RequestBody List<SnapshotService.Config> configs) throws SQLException{return snapshots.configure(connection,configs);}
    @GetMapping("/columns/{connection}/{table}") public Map<String,Object> columns(@PathVariable long connection,@PathVariable String table) throws SQLException{return snapshots.columns(connection,table);}
    @PostMapping("/inspect") public List<Map<String,Object>> inspect(@RequestBody SnapshotService.Request input) throws SQLException{return snapshots.inspect(input);}
    @PostMapping("/capture") public SnapshotService.Job capture(@RequestBody SnapshotService.Request input){return snapshots.start(input,false);}
    @PostMapping("/compare") public SnapshotService.Job compare(@RequestBody SnapshotService.Request input){return snapshots.start(input,true);}
    @GetMapping("/jobs/{id}") public SnapshotService.Job job(@PathVariable String id){return snapshots.job(id);}
    @GetMapping("/history") public List<Map<String,Object>> history() throws IOException{return reports.history();}
    @GetMapping("/reports/{id}") public Map<String,Object> report(@PathVariable String id) throws IOException{return reports.summary(id);}
    @GetMapping("/reports/{id}/{table}/{category}") public Map<String,Object> page(@PathVariable String id,@PathVariable String table,@PathVariable String category,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size) throws IOException{return reports.page(id,table,category,page,size);}
    @GetMapping("/reports/{id}/download") public ResponseEntity<FileSystemResource> download(@PathVariable String id){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(id+".zip").build().toString()).body(new FileSystemResource(reports.zip(id)));}
    @ExceptionHandler(SQLException.class) public ResponseEntity<?> database(SQLException e){return ResponseEntity.badRequest().body(Map.of("message","Snapshot database access failed (error "+e.getErrorCode()+"). Check connection settings and table permissions."));}
}

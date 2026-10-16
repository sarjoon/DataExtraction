package com.edac;

import static com.edac.Models.*;
import java.util.*;
import jakarta.validation.Valid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/connections/{connectionId}/tables/{tableId}/columns")
public class MappingController {
    private final RegistryService registry;
    private final JdbcTemplate db;
    public MappingController(RegistryService registry,JdbcTemplate db) { this.registry=registry;this.db=db; }
    @GetMapping
    public List<ColumnDetail> columns(@PathVariable long connectionId,@PathVariable long tableId) {
        var columns=registry.columns(connectionId,tableId);
        var excluded=new HashSet<>(db.queryForList("SELECT column_name FROM column_exclusion WHERE table_id=?",String.class,tableId));
        return columns.stream().map(c->c.withIncluded(!excluded.contains(c.name()))).toList();
    }
    @PostMapping("/selection")
    public ColumnSelection select(@PathVariable long connectionId,@PathVariable long tableId,@Valid @RequestBody ColumnSelection input) {
        registry.table(connectionId,tableId);
        if(input.included()) db.update("DELETE FROM column_exclusion WHERE table_id=? AND column_name=?",tableId,input.columnName());
        else db.update("MERGE INTO column_exclusion(table_id,column_name) KEY(table_id,column_name) VALUES (?,?)",tableId,input.columnName());
        return input;
    }
}

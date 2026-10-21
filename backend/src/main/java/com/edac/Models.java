package com.edac;

import java.util.List;
import jakarta.validation.constraints.*;

public final class Models {
    private Models() {}
    public record ConnectionInput(
        @NotBlank @Size(max=100) String name,
        @NotBlank @Pattern(regexp="ORACLE|H2") String databaseType,
        @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9.-]{0,252}") String host,
        @Min(1) @Max(65535) Integer port,
        @NotBlank @Pattern(regexp="SERVICE_NAME|SID|FILE|MEMORY|TCP") String connectionType,
        @NotBlank @Pattern(regexp="[A-Za-z0-9_][A-Za-z0-9_.$#-]{0,127}") String databaseName,
        @NotBlank @Size(max=100) String username,
        @Pattern(regexp="(?:[A-Za-z_][A-Za-z0-9_$#]{0,127})?") String schema,
        @Size(max=500) String password) {
        @Override public String toString() { return "ConnectionInput[password=REDACTED]"; }
    }
    public record ConnectionProfile(long id, String name, String databaseType, String host, int port,
        String connectionType, String databaseName, String jdbcUrl, String username, String schema,
        boolean passwordSaved) {}
    public record TableEntry(long id, long connectionId, String schema, String name, String source, String createdAt) {}
    public record ColumnDetail(int position, String name, String dataType, Number byteLength, Number characterLength,
        Integer precision, Integer scale, boolean nullable, boolean included) {
        public ColumnDetail withIncluded(boolean value) { return new ColumnDetail(position,name,dataType,byteLength,characterLength,precision,scale,nullable,value); }
    }
    public record MappingField(ColumnDetail column,String key,String description,String defaultValue,String mandatory,java.util.Map<String,Object> sourceMetadata) {
        public MappingField(ColumnDetail column,String key,String description,String defaultValue,String mandatory){this(column,key,description,defaultValue,mandatory,java.util.Collections.emptyMap());}
        public MappingField(ColumnDetail column,String key,String description,String defaultValue){this(column,key,description,defaultValue,column.nullable()?"O":"M");}
    }
    public record WorkbookRequest(@NotEmpty @Size(max=200) List<@NotNull @Positive Long> tableIds) {}
    public record ColumnSelection(@NotBlank @Size(max=128) String columnName, @NotNull Boolean included) {}
    public record TableName(String schema, String name) {}
    public record AppendRequest(@NotEmpty @Size(max=10000) List<@NotBlank String> names) {}
    public record AppendResult(int added, int skipped, int total) {}
    public record TestResult(boolean connected, String message, List<TableName> tables) {}
}

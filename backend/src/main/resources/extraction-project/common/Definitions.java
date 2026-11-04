package com.edac.extraction;

import java.util.List;

public record Definitions(String engine,List<Table> tables) {
    public record Column(String name,String type,boolean numeric,boolean lob) {}
    public record Delta(String mode,String column,String type,String lowerSource,String upperSource,
                        String initialValue,String flagValue,String lowerOperator,String upperOperator) {}
    public record Table(String id,String schema,String name,String group,List<Column> columns,
                        Delta delta,String controlSumColumn,String sqlResource) {}
    public record Window(List<String> values,String checkpointTo) {}
    public record Stats(long records,String controlSum,long bytes,String sha256) {}
}

package com.edac.extraction;

import java.nio.file.Path;
import java.time.LocalDate;
import com.edac.extraction.Definitions.*;

public interface Extractor {
    Stats extract(Table table,Window window,Path part,Path work,LocalDate businessDate) throws Exception;
    default java.util.List<java.util.List<String>> query(String sql,Path work) throws Exception {
        throw new UnsupportedOperationException("This extractor does not support chunk planning.");
    }
    default void cancel() {}
}

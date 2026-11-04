package com.edac.extraction;

import java.nio.file.Path;
import java.time.LocalDate;
import com.edac.extraction.Definitions.*;

public interface Extractor {
    Stats extract(Table table,Window window,Path part,Path work,LocalDate businessDate) throws Exception;
    default void cancel() {}
}

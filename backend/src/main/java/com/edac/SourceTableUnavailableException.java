package com.edac;

/** Metadata lookup completed but returned no visible columns for this table. */
final class SourceTableUnavailableException extends IllegalArgumentException {
    SourceTableUnavailableException(String message) { super(message); }
}

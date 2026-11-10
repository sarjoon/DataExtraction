# Extraction recovery and large-table chunks

These features are generated into both the JDBC and SQLcl source projects. Rebuild EDAC and generate a new source ZIP before building/deploying an extractor with these changes.

## Rerun failed or missing files

```sh
java -jar extraction.jar --extraction.output-directory=/data/edac --extraction.run-id=ALL-20261110 --extraction.rerun=failed-or-missing
```

Use the original output directory and run ID. The saved request restores the business date, group, delta bounds, output formatting and chunk rules. Continue supplying credentials and the SQLcl executable through runtime configuration. The generated definitions must match the original run. A new request ID starts a new extraction.

Completed source and delivery files are checked by size/checksum as applicable. Valid completed files are skipped. Missing/corrupt source files are regenerated; failed delivery reuses the validated source file. Failed/interrupted extraction resumes once the previous process has released its filesystem locks. Existing runs without request.properties can resume with their original launch arguments and settings, without the explicit rerun flag.

## Enable resumable numeric chunks per table

External application.properties example (t10 is the ID in extraction-definitions.json):

```properties
extraction.chunks.t10.column=ID
extraction.chunks.t10.range-size=1000000
extraction.chunks.t10.parallel-jobs=2
extraction.parallel-jobs=4
```

Chunking is optional. The selected included numeric column must lead a visible valid index. Prefer a NOT NULL column. range-size means a numeric value interval, not a guaranteed row count. Sparse keys can create empty ranges; nullable keys require an additional IS NULL chunk that may scan the table. Check the actual Oracle access plan before choosing production settings. Partition/ROWID chunk planning is not implemented.

Spring Boot obtains one Oracle SCN per table, saves its numeric range plan and runs disjoint ranges at that SCN. Duplicate keys stay together. Delta predicates/binds are retained in each query. Table snapshots are independent. Chunk concurrency and ordinary table queries share the extraction.parallel-jobs budget; the JDBC DataSource pool must also be sized appropriately. SQLcl planning and extraction use the configured external executable, with no JDBC dependency added to SQLcl projects.

Each completed chunk has its own output, row count, control sum, byte size and SHA-256. Retries validate completed chunks, then run only missing, failed or corrupt chunks. An interrupted chunk restarts at its beginning. Final assembly streams details into one BEL-delimited file (or the selected delimiter), with a single header and combined trailer. Assembly failures can retry from completed chunks without querying Oracle. Empty tables produce a valid header/trailer. Final publication/delivery is required before delta checkpoint advancement; an older retry does not move a newer checkpoint backwards.

The source account needs DBMS_FLASHBACK execution and applicable table SELECT/FLASHBACK privileges. Oracle undo/LOB retention must preserve the snapshot throughout extraction and retries. DDL or expired snapshots may require a completely new run ID; the application does not silently mix newer chunks with an old snapshot. See [Oracle Flashback documentation](https://docs.oracle.com/en/database/oracle/oracle-database/19/adfns/flashback.html).

Budget persistent disk space for completed chunks plus the final assembled file and active SQLcl CSV staging. Chunks are retained for recovery. Checksum verification and final assembly add filesystem I/O. No extraction-time performance guarantee has been established against the target Oracle database.

State replacement retries transient Windows access-denied rename failures. Permanent filesystem permissions or persistent locks still fail and must be corrected. Header writes are flushed immediately for better visibility.

## Validation status

Recovery tests cover failed-chunk resume, snapshot reuse, missing/corrupt chunks and final files, empty tables, duplicate/fractional/NULL keys, long Unicode fields, envelope/count/sum assembly, saved run settings, delta SQL preservation and the concurrency budget. SQLcl planning has a mocked subprocess test. The full backend Maven package build passes: 125 tests, zero failures/errors, one skipped. The 11 source-generator tests pass, including compiled generated Java for both methods and the recovery contracts. SQLcl subprocess tests use mocks, and chunk source queries use a simulated Oracle adapter. No live Oracle/SQLcl benchmark or integration verification has been performed for this change.

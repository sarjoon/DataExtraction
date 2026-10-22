ORACLE EXTRACTION SCRIPTS

Requirements: Bash 4+, Oracle SQLcl (the sql executable, not SQL*Plus), Python 3.8+.
No EDAC application runtime, JavaScript engine or third-party Python package is needed.

1. Unzip the package on the extraction server.
2. Configure extraction.config. Prefer an Oracle wallet connection alias in EDAG_CONNECT.
   For password authentication set EDAG_CONNECT securely on that server; do not enable shell tracing.
3. Set BUSINESS_DATE=YYYYMMDD to the required processing date. It is not inferred from the server date.
4. Execute: BUSINESS_DATE=20261022 bash run.sh

Each SQL file selects explicit columns from its source schema/table (or view).
Saved Mapping workbook column exclusions are respected. All table-list entries are selected by default
in the generator. A missing/unsupported table fails package generation; nothing is silently skipped.
SQLcl writes canonical CSV staging files into a private temporary folder. The formatter reads one row
at a time and removes the staging files after execution. SQLcl logs and final files are retained in a
unique run directory. Concurrency is bounded in groups of MAX_PARALLEL_JOBS, default set by the UI.
Failure of any worker produces a nonzero main exit status. No previous output is deleted.
Files are published individually only when their formatting completes; other successful entities may
still be delivered if an entity fails. Configure TRANSFER_DIR for optional atomic per-file delivery.

Delimited output: one configured delimiter, UTF-8, LF line endings. Values containing the delimiter
or double quotes are quoted; embedded quotes become two double quotes. NULL is unquoted \N;
the actual text \N is quoted. No escaping is applied to the record type H/D/T itself.
Fixed-length output: lengths are CHARACTERS from the mapping metadata, never editable overrides.
TIMESTAMP is 19, DATE is 10, CLOB/NCLOB/BLOB are 3000. Other fields use mapping/dictionary lengths.
UTF-8 byte sizes can be larger for multibyte characters. Text is space-padded on the right, numeric values are
zero-padded on the left after their sign. Decimals retain their decimal point. NULL is spaces.
Overflow fails the entity and removes its partial final file; no value is silently truncated.
Fixed-length H fields: system 20, country 20, business date 8, generation timestamp 14 characters.
Fixed-length T fields: record count 15, optional decimal control sum 40 characters, space-padded right.
Detail fields use extraction.json widths, in metadata order. With envelope off, only data is written.

Dates are YYYY-MM-DD, timestamps YYYY-MM-DD HH24:MI:SS (19 characters, no fractional seconds).
The session time zone is UTC (including TIMESTAMP WITH LOCAL TIME ZONE). Numeric formatting uses
a decimal point. CR and LF in character data are replaced by spaces to keep one physical record per
row. RAW is hexadecimal. CLOB and NCLOB are supported without substring conversion. SQLcl's
LOB display limit is set above the formatter's enforced limit of 16,777,216 characters per staging
CSV field (including its one-character internal marker). Larger fields fail the entity without
publishing a partial file. Fixed-length LOB values must fit the automatic 3000-character length.
BLOB values are hexadecimal text, generated in chunks using an inline PL/SQL function (Oracle 12c+).
Two hexadecimal characters represent one source byte; no binary data is silently discarded.
LONG, XML and other unsupported types require an extraction view that converts them to bounded
supported values; generation otherwise fails explicitly. Numeric
control sums use decimal arithmetic over exactly the records written.
Header: H<delimiter>system<delimiter>country<delimiter>business date<delimiter>generation timestamp
Detail: D<delimiter>value1<delimiter>value2...
Trailer: T<delimiter>record count[<delimiter>numeric control sum]

This package does not translate Sybase security expressions, join standard/UD/extended tables,
create Oracle views, migrate data or execute SQL from the application. Register the appropriate
Oracle extraction views in the table list when joins, transformations or security filters are needed.
No ORDER BY is added: row order is unspecified. Keep source data stable if cross-table consistency
is required; parallel entities do not share a common database snapshot.

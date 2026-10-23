# Extraction Framework

Extraction Framework uses Angular and Spring Boot to prepare mapping workbooks and extraction scripts, capture data snapshots and compare database schemas.

## Database architecture

- **EDAC’s H2 database stores configuration.** Flyway manages EDAC connection profiles and configured table lists in H2. Inspect these through the H2 console.
- **Oracle, H2 and Sybase ASE connection profiles are supported.** Sybase is supported by Schema Comparison, connection testing and basic dictionary discovery. Data snapshot capture/comparison remains Oracle/H2; extraction script generation remains Oracle. Connections use encrypted saved credentials.
- Source database tables are never created or migrated by Flyway.
- Schema Comparison reads database dictionary metadata. Extraction Scripts uses the Oracle mapping metadata and generates delimited or fixed-length extraction packages.

## Snapshot

Connections have a **Read-only connection** checkbox. Flyway V12 protects all existing profiles by default; newly created profiles also default to read-only. Keep source connections read-only. Explicitly uncheck and save the setting only on intended Snapshot destinations. Read-only connections are allowed for discovery, extraction, source reads and both comparison sides, but Snapshot rejects destination inspection/capture and all create, append/update and truncate/import modes with HTTP 403. The backend checks the setting before mutation and each copy batch, independently of the browser controls. This application setting does not change database account grants or restrict direct SQL tools.

Database connections are global. The **Snapshot** main menu groups **Table Definition**, **Batches**, **Configuration**, **Take snapshot**, **Compare**, and **History**. **EDAC Extraction** contains Table List, Mapping Workbook and Extraction Scripts; **Call Trigger** contains Complete Call Trigger. Flyway V9 adds Snapshot configuration and disk-backed working tables to EDAC's configuration database. It does not modify source tables.

### Schema Comparison and external Sybase driver

Open **Snapshot → Schema Comparison**. Choose Before and After connections; Oracle, H2 and Sybase ASE can be compared across database types, and either side may be read-only. Scope is all metadata visible to each login in its saved schema/owner. Object totals and names are compared for tables, views, standalone procedures, triggers and indexes. Equal counts with different names are flagged. Index identities include their table name so Sybase indexes with the same name on different tables are counted separately. Oracle package members/functions are not counted as standalone procedures; H2 uses its catalog's PROCEDURE classification.

Table structure comparison includes column existence/order, native/logical types, character/binary lengths, numeric precision/scale, nullability, defaults, identity/generated flags when reported, primary and foreign keys, unique/check constraints, and index column order/uniqueness/filters/Oracle expressions. Logical type normalization is enabled by default and may be disabled. It compares basic type families and declared capacities, not full vendor-specific storage/range semantics. Original before/after values remain in changes. Constraint and index names, including generated names, are compared literally. Permission failures stop comparison; unreported optional attributes are flagged for review instead of claiming complete equality. Source text of procedures/views/triggers, grants, physical storage and index statistics are outside scope.

Exact per-table row counts are optional and off by default because large tables may take time. No table data is loaded for structure comparison. Metadata/count queries have timeouts, and no source DDL/DML is executed. Keep schemas and source data stable during comparison; there is no shared point-in-time transaction across databases. Tables are matched case-insensitively; names differing only by case are rejected as ambiguous. Up to 10,000 tables / 500,000 columns per selected schema are supported, with no arbitrary three-table limit.

Jobs run in the background and show separate Before/After table progress. Results use six Excel sheets: Summary, ObjectCounts, ObjectDifferences, TableRowCounts, StructureSummary and StructureDifferences. Changed/mismatched rows are yellow, added rows green, removed rows pink, and unreported/not-comparable entries require review. Grids default to 10 rows per page, with per-table change drill-down. Workbook values are strings to preserve precision and avoid formula interpretation; exceptionally long Excel values carry a truncation marker while JSON retains the complete value.

Each completed run saves matching `Schema_<timestamp>_<suffix>.json` and `.zip` files under `<snapshot-base>/snapshot/schema/json` and `.../zip`. The ZIP contains the six-sheet XLSX. The base is `-Dedac.snapshot.directory=...` or the executable JAR directory, as with data snapshots. **Schema history** (inside Schema Comparison) renders saved JSON and downloads Excel or ZIP without reconnecting to source databases. Partial/failed runs are not published. Running jobs do not resume after application restart; completed reports remain available.

Flyway V13 adds the SYBASE connection type to EDAC's configuration constraints. It makes no changes to external databases. The SAP driver is NOT a Maven dependency, is NOT bundled in resources and is loaded lazily from the path supplied at startup:

```text
java -Dedac.sybase.driver-path=/opt/company/jdbc/jconn42.jar -jar edac-backend-0.1.0-schema-comparison.jar
```

Windows paths with spaces must quote the whole JVM argument, for example `"-Dedac.sybase.driver-path=D:\Company Drivers\jconn4.jar"`. Use single backslashes on the command line. Place the `-D` argument **before** `-jar`. EDAC can compile and start without that JAR; only opening a Sybase connection requires it. Restart EDAC after changing the path/JAR. The loader automatically supports both `jconn4.jar` (`com.sybase.jdbc4.jdbc.SybDriver`) and `jconn42.jar` (`com.sybase.jdbc42.jdbc.SybDriver`) through standard JDBC interfaces, and calls the driver's `connect` method directly to avoid class-loader filtering by DriverManager. No company driver needs to leave the company environment.

In Database connections select **Sybase ASE**, enter host, port (default 5000), database, username/password and schema/owner (usually case-sensitive `dbo`). Leaving owner blank asks the database for `USER_NAME()`. An explicit owner permits saving a profile before installing the driver. Ensure the login can read the required ASE system catalogs and JDBC metadata procedures. Connection/catalog access must be verified against your company ASE and jConnect versions; automated tests use a generated JDBC test driver, never an SAP binary.

1. Save Oracle or H2 connections for the source/before and destination/after databases. An omitted schema is resolved from the database's default schema when saving the connection; this requires a successful connection. An explicit schema can still be saved without connecting.
2. In Snapshot Table Definition, choose the source connection and click **Discover tables** to fetch physical tables from its schema using the Oracle/H2 data dictionary. Select tables and save; the three-table restriction has been removed. These choices are stored exclusively in H2 `snapshot_table_registry`, independently of EDAC `table_registry`. In Snapshot Configuration, select a registered table from the dropdown, load its columns and save audit or other columns to ignore. Every registered table needs a declared primary key; composite primary keys are supported and cannot be ignored. Removing a registration also removes its column-exclusion configuration and batch memberships. Flyway V10 imports existing Snapshot selections into the registry and preserves their exclusions; EDAC table selections are not imported. Exclusions affect comparison, not the data copied into a snapshot.
3. Under **Batches**, create a named batch for the source connection and assign registered tables. A table may belong to several batches. Batch names accept up to 80 letters, digits, spaces, underscores and hyphens, starting with a letter or digit. Tables must have comparison configuration before execution. Flyway V11 creates a **Default** batch from existing configured tables for each connection. In Take snapshot, choose source, destination and an execution batch (or choose tables manually), then inspect the destination. Missing tables are created with columns and the primary key. If data exists, choose **Append and update duplicate primary keys** or **Truncate existing data and import**. Truncate-and-import clears rows transactionally with DELETE, preserving the existing table definition and rolling back data changes if import fails. Newly created Oracle tables can remain empty after a failed import because Oracle commits DDL. Append leaves destination-only records in place. Successful capture copies table configuration and exclusions to the destination. For a named batch, it also creates or replaces the destination batch with the same name and selected tables, so the captured database can immediately be used as Before. Copied structures include columns and primary keys, not source triggers, indexes, foreign keys, generated expressions or grants.
4. In Compare, select Before and After connections and an execution batch, or select configured tables manually. A named batch always executes its complete saved table list, sequentially, with no three-table limit. Names must match; schemas may differ. Only primary keys are staged initially, in an indexed H2 working table. Records are fetched and compared in batches of 200. The application runs one capture/comparison job at a time and shows its progress. Keep compared tables stable during a run; the multi-query comparison does not lock both databases into one shared point-in-time transaction.
5. The completed dashboard includes a pie chart and a per-table result grid. Details provide Summary, Records Only in Before Modification, Records Only in After Modification and Differences. Grids start at 10 rows per page. Each differing primary key opens its changed columns with Before and After values side by side.

Comparison progress counts each matching record pair once. Before/After key scans and records found on only one side have separate counters and do not inflate the compared count. Progress totals cover all selected tables. Snapshot capture counts each copied source row once; this is a processing count, with the destination transaction committed after the complete capture.

Every completed comparison automatically writes a JSON report and an Excel ZIP, regardless of download. Each table has one workbook containing four sheets. Differences use one row per changed column, with primary-key columns followed by Column Name, Before and After; unchanged columns are omitted. Composite primary-key values repeat on each changed-column row. Numeric and primary-key values are exported as text to preserve precision. Binary values are Base64. JSON retains complete text; Excel values exceeding its 32,767-character cell limit carry an explicit truncation marker. Normal Excel row/column limits apply. Unquoted table and column names and common numeric, text, binary and date/time types are supported; unsupported types fail explicitly. Destination tables must permit explicit primary-key insertion and compatible values.

Storage defaults to the directory containing the executable JAR (the current working directory for development). Override the base directory with a JVM argument:

```powershell
java '-Dedac.snapshot.directory=D:\Snapshot Reports' -jar target/edac-backend-0.1.0-readonly.jar
```

Reports are stored under `<base>/snapshot/json` and `<base>/snapshot/zip`. Named runs use matching `BatchName_yyyyMMdd_HHmmss_SSS_<unique-id>.json` and `.zip` basenames; spaces in the batch filename prefix become underscores. Manual runs use a compact table-based prefix. Excel filenames contain the same timestamp. JSON retains the original batch name and executed table list, so renaming or deleting a batch does not change saved history. JSON and ZIP are published together only after export completes. **History → Render** reads saved JSON without querying either compared database; **Download** returns the saved ZIP. History survives restarts. Running jobs do not resume after a restart; incomplete working rows are discarded before the next job.

Backend integration tests exercise capture, upserts, composite-key comparisons, exclusions, pagination and JSON/Excel exports. The opt-in half-million-row test uses a file-backed H2 working database:

```powershell
mvn -f backend/pom.xml '-Dtest=SnapshotScaleTest' '-Dedac.snapshot.scale=true' '-DargLine=-Xmx768m' test
```

## Run the combined application

Prerequisites: Java 17+, Maven 3.9+, Node.js 22.12+ or 24+, npm for frontend builds.

Before generating Complete Call Trigger reports, configure **Readable fields** for the selected Oracle connection in either of two ways: upload a CSV with Screen Name + Field Name headers (sample provided), or choose a screen, click **Load screen fields**, check the required fields and click **Save screen field selection**. The screen dropdown lists each screen name once. The query in `backend/src/main/resources/sql/oracle-screen-fields.sql` selects distinct `da.sqlname_c` from `script_definition` joined to `dict_attribute`, filtered by `dict_screen.name = ?`. Its parameter is the screen name alone, preserving case and spaces, without the instrument. There is no `dict_entity` or table-name lookup. Configurations are keyed by connection + screen name + field name; field names are normalized to uppercase. Different screens have independent selections; the same screen name under multiple instruments shares its selection. Saving replaces only the selected screen's field list, and saved choices reload as checked. Java filters rules by screen name and field name before analysis. Reports retain screen tabs and include a Screen Name column.

Flyway V8 preserves the previous table-based configuration in `legacy_readable_trigger_field` and creates the active screen-based `readable_trigger_field`. Old table associations cannot be reliably converted into screens, so reselect fields or upload the new Screen Name + Field Name CSV after upgrading. Other configuration, instruments and screen registrations are retained.

For assignments using a bare `=` or `:=`, the left-hand target is excluded from Complete Call Trigger; only references on the right are extracted. Numeric and quoted literal values produce no keyword. Comparisons such as `<>`, `>=`, `<=`, and `==` retain their operands. Before extraction, the entire selected rule is concatenated into one line: actual and escaped line breaks and adjacent indentation are removed. This reconstructs identifiers split across rule fragments, such as ud_client_ followed by rate_n, before keyword extraction and deduplication. Fully uppercase keywords such as DAY, YEAR and UPPER_FIELD are excluded. Extracted keywords remain separated by actual newlines in the Excel output. Complete Rule retains the original text and line breaks. The Rule Image column and image generation have been removed.

Complete Call Trigger is available from the Call Trigger menu at `/triggers`. Select an Oracle connection and an Instrument to display its screens. Select all visible screens or individual screens to generate a report. Switching instruments clears the selection. To register screens, enter a new or existing Instrument name, upload a CSV/TXT/XLSX screen list (optional `Screen Name` header; Excel reads the first sheet), or enter one screen name per line. Uploads accept Instrument and Screen Name columns, with one instrument/screen pair per row and multiple instruments per file. File Instrument values take precedence over the page input. Screen-only uploads use the instrument entered on the page. Names retain their case and are saved separately from tables in the H2 `screen_registry`, scoped to the connection and instrument. Duplicate screens within an instrument are skipped; the same screen can belong to different instruments. Flyway V6 preserves existing registrations under `Unassigned`. A sample CSV is available on the page.

Select up to 200 screens to download `Complete_Call_Trigger.xlsx`. Each screen gets a tab with `TAP Field Name` (attribute `sqlname_c`), `Complete Call Trigger` (unique extracted keywords, one per line), `Drop Down Nature`, `Complete Rule`, and `Screen Name`. Analysis uses the selected connection's schema and saved credentials, prioritizes nature 10, then 2, then 1, combines definitions by rank, and removes brace blocks. Extraction recognizes identifiers with any suffix in conditions and nested function arguments. It skips condition keywords, quoted strings, numbers, commented brace blocks, function names and fully uppercase keywords. Dotted identifiers are retained in full, such as deposit_id.ud_origional_loan_purpose_e and op_currency_id.calendar_id. Lowercase and mixed-case single-value rules are retained, even when they match the TAP attribute. Complete keywords are deduplicated case-insensitively in first-occurrence order; different dotted paths remain separate. Rules containing an active ENUM_LIST call are copied in full to Drop Down Nature. The original selected rule is preserved as text in Complete Rule. `backend/error` is retained as the original reference; current extraction is in `TriggerAnalyzer`.

Excel tab names are sanitized, truncated to 31 characters, and given unique suffixes where needed; the original screen name is saved in the first header's comment. Existing screens with no applicable definitions produce a header-only tab. A missing screen or query failure stops the export with an error, so no partial workbook is downloaded. The export limit is 50,000 attributes; values exceeding Excel's cell limit are reported rather than silently truncated.

From `backend`:

```powershell
java -jar target/edac-backend-0.1.0.jar
```

Open <http://127.0.0.1:8081>. Angular static assets and APIs are served together. Start from the `backend` directory so `./data/edac` and `./secrets/credential.key` resolve consistently.

For separate development servers, run `mvn spring-boot:run` in `backend`, and `npm ci` followed by `npm start` in `frontend`. The development UI runs on port 4300 and proxies `/api` to 8081.

## Test locally with an H2 source

1. Start the application from the backend directory and open Database connections.
2. Choose database type **H2**, connection type **Local file**, database name **EDAC_SOURCE**, username **sa**, schema **PUBLIC**, and leave the password blank. Give the profile a unique connection name.
3. Save, then **Test connection**. This creates the separate source database if absent.
4. Open the H2 console and connect with JDBC URL **jdbc:h2:file:./data/sources/EDAC_SOURCE**, username **sa**, blank password.
5. Run the SQL in [samples/h2-source.sql](samples/h2-source.sql) to create CUSTOMER and ACCOUNT with sample data.
6. Return to EDAC, select this source, open Manage tables and choose **Discover & append**.
7. Open Mapping workbook, select one table and change its column checkboxes. Reload and select it again to verify saved exclusions.

**In-memory** mode uses a named database in the EDAC process and loses its source data when EDAC stops. Its saved JDBC URL includes DB_CLOSE_DELAY=-1 so separate requests share the data. **TCP server** mode connects to an existing H2 server using host, port and a simple database name relative to that server’s configured base directory; EDAC does not start a TCP server. Arbitrary JDBC settings and filesystem paths are not accepted. Local file sources are kept under backend/data/sources, separate from EDAC configuration. Database type cannot be changed on an existing profile; create a new profile instead. Blank passwords on edits retain the saved password.

Flyway V4 enables explicitly configured H2 source profiles. Legacy H2 profiles without a source connection mode remain hidden. H2 metadata comes from INFORMATION_SCHEMA and does not require Oracle. Numeric precision is reported as supplied by H2 (binary for integer types, decimal for DECIMAL); unknown byte lengths display a dash.

## Configure Oracle

On Database Connections, enter:

- Connection name
- Oracle host and port (default 1521)
- Connection type: Service name or SID
- Service name/SID
- Oracle username and schema/owner
- Oracle password

Save the profile, then use **Test connection**. Saving persists configuration without requiring an active Oracle connection. Testing uses `SELECT 1 FROM DUAL` against Oracle with the saved credentials. **Edit connection** allows updating the profile; leaving the password blank retains the current encrypted password.

Service-name URLs use `jdbc:oracle:thin:@//host:port/service`. SID URLs use `jdbc:oracle:thin:@host:port:SID`. The Oracle Thin JDBC driver is included in the JAR. Initial support is for ordinary host/port TCP connections using service name or SID; wallet/TNS/TCPS configuration is not yet included.

**Discover & append** reads `ALL_TABLES` filtered by the saved schema/owner using a bound parameter. Only tables visible to the Oracle account can be listed. Connection timeout is 10 seconds, read timeout 30 seconds, and statements have explicit query timeouts. Your field-extraction SQL will be integrated separately when supplied.

Connection profiles and table lists persist in H2. `V2__oracle_source_connections.sql` adds Oracle configuration columns and encrypted password storage. Old H2 source rows and their lists are preserved in H2 for inspection but excluded from the external-source UI.

## Credential encryption

Nonempty passwords are stored using AES-256-GCM with a new random IV per encryption. Explicitly empty H2 passwords use a versioned empty-password marker and do not require decryption. To replace an unreadable saved H2 credential with an empty password, edit the profile, select **Use blank password**, and save. An empty password field alone retains the existing credential. Unreadable nonempty credentials are never silently treated as blank. Passwords and ciphertext are not returned by the API, and password fields are redacted in Java request-object logging.

In local mode, a random encryption key is generated once in `backend/secrets/credential.key`. The key is outside H2 and excluded from source control. Retain it across restarts and protect/back it up separately from the database: losing it prevents decrypting saved passwords. Do not commit or share this key. `EDAC_CREDENTIAL_KEY_FILE` can select a different local key path.

For managed deployments set `EDAC_CREDENTIAL_KEY` to a Base64-encoded 32-byte key supplied by your secret manager. This overrides the local key file. Non-local mode requires this environment variable and will not generate a file key.

## Table lists

Upload CSV, TXT or XLSX, or append names manually. Existing records are retained; duplicate connection/schema/table combinations are skipped. Invalid input rejects the complete batch.

Simple CSV (uses the selected Oracle profile's schema):

```csv
Table Name
CUSTOMER
ACCOUNT
```

An optional `Schema` column can specify an owner per row. TXT accepts one name per line. Manual entry accepts newline/comma-separated names and `SCHEMA.TABLE_NAME`.

Excel uploads read `FIle Spec` if present, otherwise the first sheet. The approved workbook's row-3 `File Name` header is recognised; headers may occur within the first five rows. File size limit: 5 MB. Entry limit: 10,000. File column limit: 100.

Unquoted identifiers are normalised to uppercase and may contain letters, digits, underscores, `$` and `#` (128 characters maximum). Quoted/mixed-case source identifiers are not supported yet. Imports configure table names; they do not create Oracle tables.

## Mapping table and column selection

The Mapping workbook screen lists configured tables for the selected source connection, with individual and Select all checkboxes. Selecting one table reads its column structure from Oracle ALL_TAB_COLUMNS or H2 INFORMATION_SCHEMA.COLUMNS using bound owner and table parameters. Selecting multiple tables hides column details and displays an explanatory message.

Every column is included by default. Unchecking a column saves an exclusion in H2; checking it again deletes that exclusion. Flyway V3 creates COLUMN_EXCLUSION, keyed by the configured table ID and exact column name, keeping preferences separate by connection, schema and table. Preferences survive reloads and are applied when columns are fetched again. Table selections themselves are temporary. Source byte length and character length are shown separately; these are not fixed-length output definitions.

Use **Generate mapping workbook** to download an XLSX for the currently selected tables. The backend reloads saved exclusions and metadata at generation time. The packaged reference is an exact copy of outputs/edac-sample-20261008/EDAC_Mapping_Template.xlsx, stored in backend/src/main/resources/templates. The File Spec layout, common values (Daily/master/Full/Fixed length/Implicit/ASCII and country flags), yellow headings, borders, widths, Header and Trailer are preserved. Sample table descriptions and sample business/relationship keys are not copied. File names link to their table tabs; Excel-incompatible or long sheet names are made safe and duplicate names get unique suffixes. Same-named tables in different schemas use schema-qualified file names.

H2 extraction SQL: backend/src/main/resources/sql/h2-mapping.sql. This reads source columns, nullable flags, PK/FK membership, remarks and defaults. Character fields use their declared character length; integer fields use decimal digit capacities (3/5/10/19); NUMERIC/DECIMAL use precision and scale with the template’s implicit decimal convention. DATE uses YYYYMMDD (8), TIMESTAMP uses YYYYMMDDHHMMSS (14). Floating-point output widths and unsupported type mappings remain blank for review. Source metadata remains visible in the last three columns. No PII classification is guessed. Null/unavailable values become blank cells; numeric zero remains zero. Header date lengths retain their LEN formulas. No source data rows are exported.

Oracle query placeholder: backend/src/main/resources/sql/oracle-mapping.sql. Comments document the expected aliases and schema/table bind parameters. When this file contains only comments, export uses the existing ALL_TAB_COLUMNS metadata and leaves unavailable keys/descriptions/defaults blank; Oracle export is not blocked by the missing custom SQL. A source connection failure still produces an error rather than a partial workbook. Supply a custom SELECT by replacing the placeholder and rebuilding, or configure edac.oracle.mapping-query=file:/absolute/path/query.sql. This file is read at generation time. The source account must have read access to its metadata.

Workbook limits: 200 selected tables and 50,000 included columns. A table with every column excluded retains its Header, empty Detail section and Trailer.

Oracle Extraction Scripts

Open **EDAC Extraction → Extraction Scripts** (`/generation`) and select an Oracle connection. All tables in its existing EDAC table list are selected by default; select a subset if needed. Choose **Delimited** or **Fixed-Length**, optional H/D/T records, system/country codes, and 1–32 parallel jobs. Delimiters include BEL, space, tab, pipe, comma or a custom character. Table control sums allow an included numeric control-sum column. Fixed lengths come from the same mapping metadata and cannot be edited: TIMESTAMP 19, DATE 10, CLOB/NCLOB/BLOB 3000, other fields mapping/dictionary lengths. Mapping workbook column exclusions are respected. Settings are embedded in the generated package; the screen does not execute scripts or save a separate extraction configuration in H2.

Download the ZIP, configure its `extraction.config` on the server, set `BUSINESS_DATE=YYYYMMDD`, and run `bash run.sh`. Requirements are Bash 4+, Oracle SQLcl and Python 3.8+ (standard library only). The ZIP contains a main controller, entity worker, one explicit-column SQL query per table, JSON metadata, formatter and operating instructions. Saved passwords are never exported; configure a wallet alias or credentials securely on the execution server. Extraction does not need write privileges on the source. No Sybase SQL/view translation or automatic UD/security joins are performed; register prepared Oracle extraction views in Table list as needed.

Delimited values containing the delimiter or double quotes are quoted, and embedded quotes are doubled. NULL is unquoted `\N`; literal `\N` is quoted. Fixed widths are characters in UTF-8 output, with space-padded text and zero-padded numbers; overflow fails instead of truncating. CR/LF in text become spaces. Oracle dates use YYYY-MM-DD and timestamps use 19 characters without fractional seconds; session time zone is UTC. CLOB and NCLOB are supported without substring conversion; the formatter rejects staging fields exceeding 16,777,216 characters, including the internal marker, without publishing partial output. Fixed-length values must fit their automatic mapping length. BLOB is hexadecimal text using an inline PL/SQL function (Oracle 12c+); LONG, XML and unsupported types still require explicit conversions in an extraction view. Counts and decimal sums are calculated over written rows. The package README documents fixed header/trailer widths, numeric formatting, file naming and delivery behaviour.

Each execution uses a new output directory with per-entity logs, bounded parallel workers and private CSV staging. A failed entity is not published, and any worker failure makes the controller exit nonzero. Successful entities may still be delivered when another fails. Optional `TRANSFER_DIR` publishes completed files individually. No previous output is deleted. SQLcl queries are generated and tested structurally; validate the package against the target Oracle/SQLcl version before production use.

## H2 console and security

Open <http://127.0.0.1:8081/h2-console/>:

- JDBC URL: `jdbc:h2:file:./data/edac`
- User: `sa`
- Password: blank unless `EDAC_DB_PASSWORD` was configured

The console opens EDAC configuration by default; use a source profile’s JDBC URL to inspect its H2 sample data. Configuration tables: `CONNECTION_PROFILE`, `TABLE_REGISTRY`, `COLUMN_EXCLUSION`; Flyway history: `flyway_schema_history`.

The default `local` profile binds to 127.0.0.1, enables local H2 console access, and permits local APIs. Only console requests are exempted from CSRF and allowed SAMEORIGIN frames. API writes retain CSRF protection. Allowed development CORS origins are localhost:4300 and 127.0.0.1:4300. The combined application uses the same origin for UI/API requests.

Outside local mode, the console is disabled and APIs require HTTP Basic authentication as `edac-admin`. Set `EDAC_ADMIN_PASSWORD` to at least 16 characters and supply `EDAC_CREDENTIAL_KEY`. Enterprise SSO and shared deployment are not yet configured; use HTTPS and appropriate organisational authentication before shared deployment.

H2 configuration variables: `EDAC_DB_URL`, `EDAC_DB_USERNAME`, `EDAC_DB_PASSWORD`.

H2 source reference: [H2 connection modes](https://h2database.com/html/features.html).

## Build and verify

1. In `frontend`, run `npm ci` and `npm run build`.
2. Replace the generated files in `backend/src/main/resources/static` with the contents of `frontend/dist/edac/browser` (including CSS chunks).
3. In `backend`, run `mvn clean package`.

Backend tests cover Flyway, H2 registry persistence, encrypted credential round trips, credential updates, Oracle URL generation/driver loading, mocked Oracle JDBC execution, schema binding, imports, deduplication, CORS, CSRF, static assets and console access. No successful live Oracle connection is claimed without a supplied Oracle endpoint.

Browser tests use the packaged UI on port 8081 by default:

```powershell
cd frontend
npx playwright install chromium
npm run test:e2e
```

For isolation, start a separate packaged backend with port 8083 and an in-memory H2 URL (`--spring.datasource.url=jdbc:h2:mem:browser_test`). Set `EDAC_E2E_URL=http://127.0.0.1:8083` and `EDAC_E2E_H2_URL=jdbc:h2:mem:browser_test` in the test shell. Browser tests save dummy Oracle profiles pointed at closed loopback port 1, verify the connection failure is reported without falling back to H2, and exercise profile updates/table lists. Use an isolated database to avoid retaining these test profiles.

References: [Oracle JDBC connection URLs](https://docs.oracle.com/en/database/oracle/oracle-database/19/jjdbc/data-sources-and-URLs.html), [Spring Boot H2 console security](https://docs.spring.io/spring-boot/3.5/reference/data/sql.html). The UI uses a Tech Mahindra-inspired red, charcoal and white theme. Existing database paths and configuration variable names are retained for compatibility.

The H2 browser test uses Java and the cached H2 driver to seed a separate source file. Set EDAC_JAVA or EDAC_H2_JAR if they are not available on PATH / in the standard Maven cache. To package beside a running JAR on Windows, use mvn package -Dedac.artifact.name=edac-backend-0.1.0-h2 and run that new JAR after stopping the previous instance.

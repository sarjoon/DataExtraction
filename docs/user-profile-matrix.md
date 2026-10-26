# User profile matrix

Reports → User profile matrix uses saved Oracle, Sybase ASE or H2 connections and their saved schema/owner.

Enter USER_CODE in the free-text box and choose Load matrix (or press Enter). No local list, upload, or H2 user-code registry is needed. The code is trimmed and validated, then bound as a SQL parameter. Flyway V17 drops the former report_user_code table; V16 is retained as migration history for existing installations.

Selecting a main row loads REPORT, DATA, FUNCTION_SECURITY, FORMAT or SCREEN details below it. FORMAT drills down to format elements; SCREEN drills down to screen panels. MENU and EXTERNAL_SERVICE show “No detail query configured”. Changing the connection, entered user, parent row or parent page clears descendant grids. Delayed responses for earlier selections are ignored.

Grids have bounded horizontal/vertical scrolling and sticky headers. Large results have Previous/Next controls, with 100 rows per page. Pages use a forward-only cursor and bounded JDBC row limits rather than retaining the complete result in application memory.

Queries are in backend/src/main/resources/sql/reports/. Table placeholders are qualified with the selected schema/owner and quoted using JDBC identifier rules. Values are bound parameters. Detail requests verify that the profile belongs to the entered user; third-level requests also verify the format/screen belongs to that profile.

The FUNCTION_SECURITY semicolon before WHERE is removed. The second SCREEN denomination column is named PARENT_PANEL_DENOM, and FIXED_COULMN is corrected to FIXED_COLUMN.

Endpoints are under /api/reports/user-profile-matrix/connections/{connectionId}:

- GET /matrix?userCode=...
- GET /profiles/{type}?userCode=...&profileCode=...
- GET /profiles/{type}/items?userCode=...&profileCode=...&item=...

Result endpoints accept page (zero-based) and size (1–200, default 100). The selected database must contain the application's profile and dictionary tables; an empty H2 database is not a source fixture.

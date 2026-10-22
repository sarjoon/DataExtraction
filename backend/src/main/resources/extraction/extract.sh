#!/usr/bin/env bash
set -euo pipefail
entity=$1; work=$2; output=$3; transfer=$4
[[ "$entity" =~ ^t[0-9]+$ ]] || exit 1
[[ "$EDAG_CONNECT" != *$'\n'* && "$EDAG_CONNECT" != *$'\r'* ]] || { echo 'Connection string must be a single line' >&2; exit 1; }
# Connection credentials are sent on stdin, never embedded in the package or command arguments.
"$SQLCL" -L -S /nolog <<SQL
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT FAILURE
SET ECHO OFF
SET VERIFY OFF
CONNECT $EDAG_CONNECT
@"$ROOT/sql/$entity.sql" "$work/$entity.csv"
EXIT FAILURE
SQL
"$PYTHON" "$ROOT/format.py" extract "$ROOT/extraction.json" "$entity" "$work/$entity.csv" "$output" "$BUSINESS_DATE" "$transfer"

#!/usr/bin/env bash
# Loads one source of a compliance screening list (sanctions or PEP) from a
# CSV of names, replacing that source as a whole in one transaction:
# a reload of UN leaves OFAC-SDN and the local list untouched, and a failed
# load leaves the previous UN rows in place. Names are normalised here with
# scripts/ops/normalize-screening-name.py, the same rule the service matches
# with (NormalizedName); see that script for how the two are kept in step.
#
# Usage: load-screening-list.sh [--allow-shrink] <conninfo> <sanctions|pep> <names.csv> <list-source> <list-version>
#   conninfo        libpq keyword/value string or URI without a credential; the
#                   credential comes from PGPASSWORD, a pgpass file or a service
#                   file, never from the command line (it would be in the shell
#                   history and the process list).
#   names.csv       UTF-8, one column, header exactly "display_name".
#   list-source     publisher of the list, for example UN, OFAC-SDN, UAE-LOCAL (letters, digits, . _ -).
#   list-version    the publisher's version or date, for example 2026-10-17 or 1842 (letters, digits, . _ : -).
#   --allow-shrink  a reload normally may not shrink a source below 90% of its
#                   current rows (a truncated or partial file would silently
#                   drop names); pass this when the publisher really removed
#                   entries. The shrink is then logged.
#
# Run as the schema owner or a loader role with SELECT, INSERT, DELETE on
# sc_cmp_evidence.sanctions_list_entry and pep_register_entry (RUNBOOK,
# "Screening lists"). Needs psql and python3. Rehearsed in CI by
# scripts/ops/test/rehearse-screening-list-load.sh.
set -euo pipefail

usage() {
  echo "usage: $(basename "$0") [--allow-shrink] <conninfo> <sanctions|pep> <names.csv> <list-source> <list-version>" >&2
  exit 2
}

allow_shrink=false
positional=()
for arg in "$@"; do
  case "$arg" in
    --allow-shrink) allow_shrink=true ;;
    --*) echo "refused: unknown option '$arg'" >&2; usage ;;
    *) positional+=("$arg") ;;
  esac
done
[ "${#positional[@]}" -eq 5 ] || usage
conninfo="${positional[0]}"; list="${positional[1]}"; names="${positional[2]}"; source="${positional[3]}"; version="${positional[4]}"
here="$(cd "$(dirname "$0")" && pwd)"
schema="sc_cmp_evidence"

# No credential on the command line, in any conninfo form: the libpq credential
# keyword as key=value, the URI user-info form (user:secret@host) and the same
# keyword as a URI query parameter (after ? or &), in any letter case.
credential_keyword="pass""word"
if printf '%s' "$conninfo" | grep -qiE "(^|[[:space:]?&])${credential_keyword}[[:space:]]*="; then
  echo "refused: the conninfo carries a credential; use PGPASSWORD, a pgpass file or a service file" >&2
  exit 1
fi
if printf '%s' "$conninfo" | grep -qE '^[A-Za-z]+://[^/@]*:[^/@]*@'; then
  echo "refused: the conninfo URI carries a credential; use PGPASSWORD, a pgpass file or a service file" >&2
  exit 1
fi

case "$list" in
  sanctions) table="sanctions_list_entry" ;;
  pep)       table="pep_register_entry" ;;
  *) echo "refused: unknown list '$list' (sanctions or pep)" >&2; exit 1 ;;
esac
[[ "$source" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] && [ "${#source}" -le 64 ] \
  || { echo "refused: list-source must be letters, digits, . _ - (at most 64 characters)" >&2; exit 1; }
[[ "$version" =~ ^[A-Za-z0-9][A-Za-z0-9._:-]*$ ]] && [ "${#version}" -le 128 ] \
  || { echo "refused: list-version must be letters, digits, . _ : - (at most 128 characters)" >&2; exit 1; }
[ -f "$names" ] || { echo "refused: $names is not a file" >&2; exit 1; }
[ -s "$names" ] || { echo "refused: $names is empty" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
staged="$work/staged.csv"

# Normalise and validate every name before touching the database.
rows="$(python3 -I "$here/normalize-screening-name.py" "$names" "$staged")"

# One session, one transaction: the previous load of this source is deleted
# and the new one inserted together, so a failure anywhere leaves the old rows.
# The shrink guard runs inside it: a new load below 90% of the current rows of
# this source raises, which ends the session before COMMIT. \copy takes no
# psql variables, so the staged path (a mktemp path, no quotes in it) is
# written into the script by the shell; the identifiers and literals go
# through psql variables and set_config.
psql -X -q -v ON_ERROR_STOP=1 "$conninfo" \
  -v table="$table" -v source="$source" -v version="$version" -v allow_shrink="$allow_shrink" <<SQL
BEGIN;
CREATE TEMP TABLE screening_stage (display_name text NOT NULL, normalized_name text NOT NULL) ON COMMIT DROP;
\\copy screening_stage (display_name, normalized_name) FROM '$staged' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8')
SELECT set_config('loader.source', :'source', true) AS a,
       set_config('loader.table', :'table', true) AS b,
       set_config('loader.allow_shrink', :'allow_shrink', true) AS c \gset set_
DO \$\$
DECLARE
    current_rows bigint;
    new_rows bigint;
BEGIN
    EXECUTE format('SELECT count(*) FROM sc_cmp_evidence.%I WHERE list_source = %L',
                   current_setting('loader.table'), current_setting('loader.source')) INTO current_rows;
    SELECT count(*) INTO new_rows FROM screening_stage;
    IF current_rows > 0 AND new_rows * 10 < current_rows * 9 THEN
        IF current_setting('loader.allow_shrink') = 'true' THEN
            RAISE NOTICE 'allow-shrink: source % goes from % to % rows (below 90%% of the current load)',
                current_setting('loader.source'), current_rows, new_rows;
        ELSE
            RAISE EXCEPTION 'refused: the new load of source % has % rows, below 90%% of the current % rows; a truncated file? pass --allow-shrink to replace anyway',
                current_setting('loader.source'), new_rows, current_rows;
        END IF;
    END IF;
END
\$\$;
DELETE FROM sc_cmp_evidence.:"table" WHERE list_source = :'source';
INSERT INTO sc_cmp_evidence.:"table" (normalized_name, display_name, list_source, list_version, loaded_at)
SELECT normalized_name, display_name, :'source', :'version', now() FROM screening_stage;
COMMIT;
SQL

loaded="$(psql -X -At -v ON_ERROR_STOP=1 "$conninfo" \
  -c "SELECT count(*) FROM $schema.$table WHERE list_source = '$source' AND list_version = '$version'")"
if [ "$allow_shrink" = true ]; then
  echo "allow-shrink was passed for $source"
fi
echo "loaded $loaded of $rows names into $schema.$table as $source version $version"

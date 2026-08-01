#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: fetch-wikidata-data.sh [-q FILE | -o FILE | -e URL | -h | <long option>]

Runs docs/examples/wikidata/getData.sparql against the Wikidata Query Service
and writes an NQ-friendly JSON file beside this script.

Options:
  -q, --query FILE       SPARQL query file. Default: getData.sparql beside this script
  -o, --output FILE      Output JSON file. Default: wikidata-companies.json beside this script
  -e, --endpoint URL     SPARQL endpoint. Default: https://query.wikidata.org/sparql
      --raw-output FILE  Also save the raw SPARQL JSON response.
  -h, --help            Show this help.

Environment:
  WIKIDATA_SPARQL_ENDPOINT  Overrides the default endpoint.
  WIKIDATA_USER_AGENT       Overrides the HTTP user agent sent to Wikidata.
USAGE
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
query_file="$script_dir/getData.sparql"
endpoint="${WIKIDATA_SPARQL_ENDPOINT:-https://query.wikidata.org/sparql}"
output_file="$script_dir/wikidata-companies.json"
raw_output_file=""
user_agent="${WIKIDATA_USER_AGENT:-nq-test-data-fetch/1.0}"

while (($#)); do
  case "$1" in
    -q|--query)
      query_file="${2:-}"
      shift 2
      ;;
    -o|--output)
      output_file="${2:-}"
      shift 2
      ;;
    -e|--endpoint)
      endpoint="${2:-}"
      shift 2
      ;;
    --raw-output)
      raw_output_file="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ -z "$query_file" || -z "$output_file" || -z "$endpoint" ]]; then
  echo "Query file, output file, and endpoint are required." >&2
  exit 1
fi

if [[ ! -f "$query_file" ]]; then
  echo "SPARQL query file not found: $query_file" >&2
  exit 1
fi

require_command curl
require_command jq

output_dir="$(dirname "$output_file")"
mkdir -p "$output_dir"

raw_tmp="$(mktemp "$output_dir/.wikidata-raw.XXXXXX")"
json_tmp="$(mktemp "$output_dir/.wikidata-json.XXXXXX")"
trap 'rm -f "$raw_tmp" "$json_tmp"' EXIT

generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

curl \
  --fail \
  --show-error \
  --silent \
  --location \
  --connect-timeout 20 \
  --max-time 180 \
  --retry 2 \
  --retry-delay 2 \
  --header "Accept: application/sparql-results+json" \
  --header "User-Agent: $user_agent" \
  --data-urlencode "query@$query_file" \
  --data-urlencode "format=json" \
  "$endpoint" > "$raw_tmp"

jq \
  --arg endpoint "$endpoint" \
  --arg query_file "$(basename "$query_file")" \
  --arg generated_at "$generated_at" \
  '
  def binding_value($name): .[$name].value // null;
  def entity_id($name):
    (binding_value($name)) as $value
    | if $value == null then null
      else (try ($value | capture("/entity/(?<id>Q[0-9]+)$").id) catch null)
      end;
  def with_record_ids:
    to_entries | map({record_id: (.key + 1)} + .value);
  def company_record:
    {
      company_uri: binding_value("company"),
      company_id: entity_id("company"),
      company_label: binding_value("companyLabel"),
      country_uri: binding_value("country"),
      country_id: entity_id("country"),
      country_label: binding_value("countryLabel")
    };
  def company_industry_record:
    {
      company_id: entity_id("company"),
      industry_uri: binding_value("industry"),
      industry_id: entity_id("industry"),
      industry_label: binding_value("industryLabel")
    };
  def company_role_record:
    {
      company_id: entity_id("company"),
      person_uri: binding_value("person"),
      person_id: entity_id("person"),
      person_label: binding_value("personLabel"),
      role: binding_value("role"),
      role_start: binding_value("start"),
      role_end: binding_value("end")
    };

  if (.results.bindings | type) != "array" then
    error("SPARQL response did not contain results.bindings")
  else
    .results.bindings as $bindings
    | (
        $bindings
        | map(company_record)
        | map(select(.company_id != null))
        | sort_by(.company_id, .country_id // "")
        | unique_by(.company_id)
        | sort_by(.company_label // "", .company_id)
        | with_record_ids
      ) as $companies
    | (
        $bindings
        | map(company_industry_record)
        | map(select(.company_id != null and .industry_label != null))
        | unique_by(.company_id, .industry_id, .industry_label)
        | sort_by(.company_id, .industry_label)
        | with_record_ids
      ) as $company_industries
    | (
        $bindings
        | map(company_role_record)
        | map(select(.company_id != null and (.person_id != null or .role != null)))
        | unique_by(.company_id, .person_id, .role, .role_start, .role_end)
        | sort_by(.company_id, .role // "", .person_label // "")
        | with_record_ids
      ) as $company_roles
    | {
        wikidata: {
          source: "wikidata-query-service",
          endpoint: $endpoint,
          query_file: $query_file,
          generated_at: $generated_at,
          record_count: ($bindings | length),
          company_count: ($companies | length),
          company_industry_count: ($company_industries | length),
          company_role_count: ($company_roles | length),
          company: $companies,
          company_industries: $company_industries,
          company_roles: $company_roles
        }
      }
  end
  ' "$raw_tmp" > "$json_tmp"

mv "$json_tmp" "$output_file"
chmod 0644 "$output_file"

if [[ -n "$raw_output_file" ]]; then
  mkdir -p "$(dirname "$raw_output_file")"
  cp "$raw_tmp" "$raw_output_file"
  chmod 0644 "$raw_output_file"
fi

company_count="$(jq -r '.wikidata.company_count' "$output_file")"
company_industry_count="$(jq -r '.wikidata.company_industry_count' "$output_file")"
printf 'Wrote %s companies and %s company_industries to %s\n' \
  "$company_count" "$company_industry_count" "$output_file"

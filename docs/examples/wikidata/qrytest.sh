#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
nql_command="${NQL_COMMAND:-nql}"

"$nql_command" \
  "$script_dir/wikidata-keyed-companies.nql" \
  "$script_dir/wikidata-companies.json"

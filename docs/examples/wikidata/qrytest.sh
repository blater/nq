#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
nq_command="${NQ_COMMAND:-nq}"

"$nq_command" \
  "$script_dir/wikidata-keyed-companies.nq" \
  "$script_dir/wikidata-companies.json"

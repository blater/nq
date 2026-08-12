#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../.." && pwd)"
nq_command="${NQ_COMMAND:-nq}"

"$nq_command" \
  run \
  --script-file "$script_dir/wikidata-keyed-companies.nq" \
  --input-file "$script_dir/wikidata-companies.json" \
  --state-dir "$repo_root/target/wikidata-state"

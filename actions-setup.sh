#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_repository="${NQ_SOURCE_REPOSITORY:-blater/nq}"
homebrew_token_was_provided=false

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

command -v gh >/dev/null 2>&1 || die "gh is required but was not found on PATH."

cd "$script_dir"

if ! gh auth status --hostname github.com >/dev/null 2>&1; then
  printf 'GitHub CLI authentication is required.\n'
  gh auth login --hostname github.com
fi

if [[ -n "${HOMEBREW_TAP_TOKEN:-}" ]]; then
  homebrew_token_was_provided=true
else
  printf 'Homebrew tap fine-grained token: ' >&2
  IFS= read -r -s HOMEBREW_TAP_TOKEN
  printf '\n' >&2
  [[ -n "$HOMEBREW_TAP_TOKEN" ]] || die "A Homebrew tap token is required."
  export HOMEBREW_TAP_TOKEN
fi

cleanup() {
  if [[ "$homebrew_token_was_provided" == false ]]; then
    unset HOMEBREW_TAP_TOKEN
  fi
}
trap cleanup EXIT

./util/setup/github-actions.sh

if ! gh secret list --repo "$source_repository" --app actions |
  awk '$1 == "HOMEBREW_TAP_TOKEN" { found = 1 } END { exit !found }'; then
  die "HOMEBREW_TAP_TOKEN was not found in $source_repository Actions secrets."
fi

printf 'Verified HOMEBREW_TAP_TOKEN in %s Actions secrets.\n' "$source_repository"

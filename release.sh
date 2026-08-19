#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source_repository="${NQL_SOURCE_REPOSITORY:-blater/nql}"

usage() {
  printf 'Current release: %s\n\n' "$current_version"
  cat <<'USAGE'
Usage: ./release.sh X.Y.Z

Update pom.xml to a higher version, commit and push the change, then create
and push the matching vX.Y.Z release tag. Wait for GitHub Actions to publish
the native, JVM, Homebrew, and GitHub-hosted Chocolatey distributions, then verify the
GitHub release assets.
USAGE
}

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || \
    die "$1 is required but was not found on PATH."
}

require_actions_secret() {
  local secret_name="$1"
  if ! grep -q "^${secret_name}[[:space:]]" <<< "$actions_secrets"; then
    die "$secret_name is not configured for $source_repository. Run ./actions-setup.sh."
  fi
}

version_is_greater() {
  local candidate="$1"
  local current="$2"
  local candidate_parts
  local current_parts
  local index
  local candidate_part
  local current_part

  IFS=. read -r -a candidate_parts <<< "$candidate"
  IFS=. read -r -a current_parts <<< "$current"

  for index in 0 1 2; do
    candidate_part="$((10#${candidate_parts[$index]}))"
    current_part="$((10#${current_parts[$index]}))"

    if (( candidate_part > current_part )); then
      return 0
    fi
    if (( candidate_part < current_part )); then
      return 1
    fi
  done

  return 1
}

cd "$script_dir"

current_version="$(awk -F '[<>]' '/^[[:space:]]*<version>/ { print $3; exit }' pom.xml)"
[[ "$current_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
  die "Current pom.xml version [$current_version] is not in X.Y.Z numeric format."

case "$#" in
  0)
    usage
    exit 0
    ;;
  1)
    case "$1" in
      -h|--help)
        usage
        exit 0
        ;;
    esac
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac

version="$1"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
  die "Version must use X.Y.Z numeric format, for example 1.2.3."

require_command gh
require_command git

gh auth status --hostname github.com >/dev/null 2>&1 || \
  die "GitHub CLI authentication is required. Run: gh auth login --hostname github.com"

[[ -f .github/workflows/release.yml ]] || \
  die "The GitHub release workflow is missing."
[[ -f util/chocolatey/nql.nuspec ]] || \
  die "The Chocolatey package definition is missing."
[[ -f util/chocolatey/VERIFICATION.txt.template ]] || \
  die "The Chocolatey verification template is missing."
[[ -f util/chocolatey/install.ps1 ]] || \
  die "The GitHub-hosted Chocolatey installer is missing."
[[ -f util/install-linux.sh ]] || \
  die "The Linux installer is missing."

actions_secrets="$(
  gh secret list --repo "$source_repository" --app actions
)" || die "Could not read GitHub Actions secrets for $source_repository."
require_actions_secret HOMEBREW_TAP_TOKEN

version_is_greater "$version" "$current_version" || \
  die "Version $version must be greater than current version $current_version."

[[ -z "$(git status --porcelain --untracked-files=normal)" ]] || \
  die "The Git working tree is not clean. Commit or stash existing changes first."

branch="$(git branch --show-current)"
[[ -n "$branch" ]] || die "A release cannot be created from a detached HEAD."

tag="v$version"
if git rev-parse --verify --quiet "refs/tags/$tag" >/dev/null; then
  die "Tag $tag already exists locally."
fi

remote_tag="$(git ls-remote --tags origin "refs/tags/$tag")" || \
  die "Could not check whether tag $tag exists on origin."
[[ -z "$remote_tag" ]] || die "Tag $tag already exists on origin."

version_line="$(sed -n '/^[[:space:]]*<version>/{=;q;}' pom.xml)"
[[ -n "$version_line" ]] || die "Could not locate the project version in pom.xml."

temporary_pom="$(mktemp "${TMPDIR:-/tmp}/nql-pom.XXXXXX")"
trap 'rm -f "$temporary_pom"' EXIT
sed "${version_line}s|<version>${current_version}</version>|<version>${version}</version>|" \
  pom.xml > "$temporary_pom"
mv "$temporary_pom" pom.xml

updated_version="$(awk -F '[<>]' '/^[[:space:]]*<version>/ { print $3; exit }' pom.xml)"
[[ "$updated_version" == "$version" ]] || die "Failed to update pom.xml."

printf 'Updating nql from %s to %s\n' "$current_version" "$version"
git add pom.xml
git commit -m "Release $tag"
git push
git tag -a "$tag" -m "Release $tag"
git push origin "$tag"

release_commit="$(git rev-parse "${tag}^{commit}")"
printf 'Release tag %s pushed; waiting for the GitHub release workflow.\n' "$tag"

run_id=""
for _ in {1..30}; do
  run_id="$(
    gh api \
      --method GET \
      "repos/$source_repository/actions/workflows/release.yml/runs" \
      -f event=push \
      -f head_sha="$release_commit" \
      -f per_page=1 \
      --jq '.workflow_runs[0].id // empty'
  )" || die "Could not find the GitHub release workflow run."
  [[ -z "$run_id" ]] || break
  sleep 2
done
[[ -n "$run_id" ]] || \
  die "The GitHub release workflow did not appear for $tag."

gh run watch "$run_id" --repo "$source_repository" --exit-status || \
  die "The GitHub release workflow failed: https://github.com/$source_repository/actions/runs/$run_id"

release_assets="$(
  gh release view "$tag" \
    --repo "$source_repository" \
    --json assets \
    --jq '.assets[].name'
)" || die "Could not inspect GitHub release $tag."

required_assets=(
  "nql-${version}-darwin-arm64.tar.gz"
  "nql-${version}-linux-x64.tar.gz"
  "nql-${version}-windows-x64.zip"
  "nql-${version}-jvm.jar"
  "nql.${version}.nupkg"
  "SHA256SUMS"
)
for asset in "${required_assets[@]}"; do
  grep -Fxq "$asset" <<< "$release_assets" || \
    die "GitHub release $tag is missing $asset."
done

printf 'Release complete: https://github.com/%s/releases/tag/%s\n' \
  "$source_repository" "$tag"
printf 'Install on macOS: brew install blater/tap/nql\n'
printf 'Install on Windows: irm https://raw.githubusercontent.com/blater/nql/master/util/chocolatey/install.ps1 | iex\n'

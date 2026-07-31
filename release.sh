#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'USAGE'
Usage: ./release.sh X.Y.Z

Update pom.xml to a higher version, commit and push the change, then create
and push the matching vX.Y.Z release tag.
USAGE
}

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
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

case "$#" in
  1) ;;
  *)
    usage >&2
    exit 2
    ;;
esac

version="$1"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
  die "Version must use X.Y.Z numeric format, for example 1.2.3."

cd "$script_dir"

current_version="$(awk -F '[<>]' '/^[[:space:]]*<version>/ { print $3; exit }' pom.xml)"
[[ "$current_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || \
  die "Current pom.xml version [$current_version] is not in X.Y.Z numeric format."

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

temporary_pom="$(mktemp "${TMPDIR:-/tmp}/nq-pom.XXXXXX")"
trap 'rm -f "$temporary_pom"' EXIT
sed "${version_line}s|<version>${current_version}</version>|<version>${version}</version>|" \
  pom.xml > "$temporary_pom"
mv "$temporary_pom" pom.xml

updated_version="$(awk -F '[<>]' '/^[[:space:]]*<version>/ { print $3; exit }' pom.xml)"
[[ "$updated_version" == "$version" ]] || die "Failed to update pom.xml."

printf 'Updating nq from %s to %s\n' "$current_version" "$version"
git add pom.xml
git commit -m "Release $tag"
git push
git tag -a "$tag" -m "Release $tag"
git push origin "$tag"

printf 'Release tag %s pushed; the GitHub release workflow has started.\n' "$tag"

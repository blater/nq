#!/bin/sh
set -eu

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

[ "$(uname -s)" = "Linux" ] || die "This installer supports Linux only."
case "$(uname -m)" in
  x86_64|amd64) ;;
  *) die "NQ releases currently support Linux x64 only." ;;
esac

for command in curl tar awk; do
  command -v "$command" >/dev/null 2>&1 || die "$command is required."
done

version="${NQ_VERSION:-}"
if [ -z "$version" ]; then
  latest_url="$(curl -fsSL -o /dev/null -w '%{url_effective}' \
    https://github.com/blater/nq/releases/latest)"
  version="${latest_url##*/v}"
fi
version="${version#v}"

asset="nq-$version-linux-x64.tar.gz"
release="https://github.com/blater/nq/releases/download/v$version"
install_dir="${NQ_INSTALL_DIR:-$HOME/.local/bin}"
temp="$(mktemp -d "${TMPDIR:-/tmp}/nq-install.XXXXXX")"
trap 'rm -rf "$temp"' EXIT HUP INT TERM

curl -fsSL "$release/$asset" -o "$temp/$asset"
curl -fsSL "$release/SHA256SUMS" -o "$temp/SHA256SUMS"

expected="$(awk -v asset="$asset" '$2 == asset { print $1; exit }' "$temp/SHA256SUMS")"
[ -n "$expected" ] || die "$asset is missing from SHA256SUMS."
if command -v sha256sum >/dev/null 2>&1; then
  actual="$(sha256sum "$temp/$asset" | awk '{ print $1 }')"
elif command -v shasum >/dev/null 2>&1; then
  actual="$(shasum -a 256 "$temp/$asset" | awk '{ print $1 }')"
else
  die "sha256sum or shasum is required."
fi
[ "$actual" = "$expected" ] || die "Checksum verification failed for $asset."

tar -xzf "$temp/$asset" -C "$temp"
[ -f "$temp/nq" ] || die "$asset does not contain nq."
mkdir -p "$install_dir"
cp "$temp/nq" "$install_dir/nq"
chmod 755 "$install_dir/nq"

printf 'Installed nq %s at %s/nq\n' "$version" "$install_dir"
case ":${PATH:-}:" in
  *":$install_dir:"*) ;;
  *) printf 'Add %s to PATH to run nq directly.\n' "$install_dir" ;;
esac

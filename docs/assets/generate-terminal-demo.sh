#!/usr/bin/env bash
set -euo pipefail

asset_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$asset_dir/../.." && pwd)"
asset_tmp="$(mktemp -d)"
trap 'rm -rf "$asset_tmp"' EXIT

javac -d "$asset_tmp" "$repo_root/util/GenerateLaunchAssets.java"
java -Djava.awt.headless=true -cp "$asset_tmp" GenerateLaunchAssets "$asset_tmp"

ffmpeg -hide_banner -loglevel error -y \
  -loop 1 -t 6 -i "$asset_tmp/terminal-demo-frame-1.png" \
  -loop 1 -t 7 -i "$asset_tmp/terminal-demo-frame-2.png" \
  -loop 1 -t 5 -i "$asset_tmp/terminal-demo-frame-3.png" \
  -loop 1 -t 10 -i "$asset_tmp/terminal-demo-frame-4.png" \
  -filter_complex \
  "[0:v][1:v][2:v][3:v]concat=n=4:v=1:a=0,fps=6,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer" \
  -loop 0 "$asset_dir/nql-terminal-demo.gif"

cp "$asset_tmp/nql-social-preview.png" "$asset_dir/nql-social-preview.png"

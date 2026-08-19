$ErrorActionPreference = "Stop"

if (-not (Get-Command choco -ErrorAction SilentlyContinue)) {
  throw "Chocolatey is required. Install it from https://chocolatey.org/install first."
}

$release = Invoke-RestMethod `
  -Uri "https://api.github.com/repos/blater/nql/releases/latest"
$version = $release.tag_name.TrimStart("v")
$packageName = "nql.$version.nupkg"
$packageAsset = $release.assets | Where-Object { $_.name -eq $packageName }
$checksumsAsset = $release.assets | Where-Object { $_.name -eq "SHA256SUMS" }

if (-not $packageAsset -or -not $checksumsAsset) {
  throw "GitHub release $($release.tag_name) does not contain the Chocolatey package and checksums."
}

$temp = Join-Path ([IO.Path]::GetTempPath()) ("nql-chocolatey-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temp | Out-Null

try {
  $package = Join-Path $temp $packageName
  $checksums = Join-Path $temp "SHA256SUMS"
  Invoke-WebRequest $packageAsset.browser_download_url -OutFile $package
  Invoke-WebRequest $checksumsAsset.browser_download_url -OutFile $checksums

  $checksumLine = Get-Content $checksums |
    Where-Object { $_ -match "\s$([regex]::Escape($packageName))$" } |
    Select-Object -First 1
  if (-not $checksumLine) {
    throw "$packageName is missing from SHA256SUMS."
  }

  $expected = ($checksumLine -split "\s+")[0]
  $actual = (Get-FileHash $package -Algorithm SHA256).Hash
  if (-not $actual.Equals($expected, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Checksum verification failed for $packageName."
  }

  & choco upgrade nql `
    --version $version `
    --source $temp `
    --yes `
    --no-progress
  if ($LASTEXITCODE -notin 0, 2, 1641, 3010) {
    throw "Chocolatey failed with exit code $LASTEXITCODE."
  }
} finally {
  Remove-Item $temp -Recurse -Force -ErrorAction SilentlyContinue
}

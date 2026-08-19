# Chocolatey package

The Windows release job builds `nql.exe`, places it in a self-contained
Chocolatey package, installs that package in the clean runner, verifies
`nql --version`, and attaches the `.nupkg` to the GitHub release.

`install.ps1` downloads the latest `.nupkg` and `SHA256SUMS` from GitHub,
verifies the package, and installs or upgrades it from a temporary local
Chocolatey source. There is no Chocolatey Community Repository publication.

The supported release entry point is `./release.sh X.Y.Z`. It waits for the
workflow to finish and checks that the GitHub release contains the Windows ZIP,
the Chocolatey `.nupkg`, all other supported builds, and `SHA256SUMS`.

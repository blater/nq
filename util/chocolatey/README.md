# Chocolatey package

The Windows release job builds `nq.exe`, places it in a self-contained
Chocolatey package, installs that package in the clean runner, verifies
`nq --version`, and attaches the `.nupkg` to the GitHub release.

After the GitHub release is published, the workflow pushes the package to the
Chocolatey Community Repository. Configure the `CHOCOLATEY_API_KEY` Actions
secret with `./actions-setup.sh` before creating a release tag.

The supported release entry point is `./release.sh X.Y.Z`. It waits for the
workflow to finish and checks that the GitHub release contains the Windows ZIP,
the Chocolatey `.nupkg`, all other supported builds, and `SHA256SUMS`.

Chocolatey reviews the first package submission before it becomes available
from the community feed. Subsequent versions may also be held for automated or
human moderation.

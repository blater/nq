# Releases

GitHub Actions builds and publishes native NQ executables for macOS ARM64,
Linux x64 and Windows x64. The committed workflow is
[`release.yml`](../.github/workflows/release.yml); each executable is built on
its target operating system with GraalVM Native Image.

## One-time and repeatable setup

Create a fine-grained GitHub token with **Contents: Read and write** access to
`blater/homebrew-tap`, then run:

```bash
./actions-setup.sh
```

The setup script authenticates the GitHub CLI when necessary and securely
prompts for the token. It is idempotent: it checks both repositories and token
access, enables GitHub Actions, creates or replaces the encrypted
`HOMEBREW_TAP_TOKEN` repository secret, and verifies that the secret exists. It
does not write or print the token.

## Publishing a release

Run the release script with a version greater than the current `pom.xml`
version:

```bash
./release.sh 1.2.3
```

The script requires a clean Git working tree and an `X.Y.Z` numeric version.
It updates `pom.xml`, commits and pushes the change, then creates and pushes
the matching `v1.2.3` annotated tag. Pushing that tag starts the release
workflow.

The release workflow:

1. verifies that the tag matches `pom.xml`;
2. runs the complete test suite;
3. builds and smoke-tests each native executable;
4. publishes packaged executables and `SHA256SUMS` to the GitHub release; and
5. updates `blater/homebrew-tap` from the macOS ARM64 artifact.

The workflow is safe to rerun: existing release assets are replaced, and an
unchanged Homebrew formula is not committed again. It can also be started
manually from the GitHub Actions **Release** workflow by supplying an existing
tag.

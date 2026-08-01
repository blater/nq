# Automation and CI/CD

NQ — SQL for nested data is designed to participate in scripts without mixing
result data and diagnostics.

## Process contract

- Query/output data is written to stdout.
- Warnings, errors, and debug diagnostics are written to stderr.
- Successful commands return status `0`.
- Invalid options, script parsing errors, database connection errors, and SQL
  execution failures return a non-zero status.
- `--debug` adds diagnostics and may expose SQL or metadata; sanitize logs.

## Shell example

```bash
set -euo pipefail

nq transform.nq input.json --output json >result.json
jq -e . result.json >/dev/null
```

Redirect stderr separately when preserving diagnostics:

```bash
if ! nq transform.nq input.json >result.json 2>nq-error.log; then
  sed -n '1,120p' nq-error.log >&2
  exit 1
fi
```

## GitHub Actions example

Pin the NQ version and verify its archive before use:

```yaml
name: transform-data
on: [push]

jobs:
  nq:
    runs-on: ubuntu-22.04
    steps:
      - uses: actions/checkout@v4
      - name: Install NQ
        env:
          NQ_VERSION: 0.9.3
        run: |
          curl -fLO "https://github.com/blater/nq/releases/download/v${NQ_VERSION}/nq-${NQ_VERSION}-linux-x64.tar.gz"
          curl -fLO "https://github.com/blater/nq/releases/download/v${NQ_VERSION}/SHA256SUMS"
          grep "nq-${NQ_VERSION}-linux-x64.tar.gz" SHA256SUMS | sha256sum --check
          tar -xzf "nq-${NQ_VERSION}-linux-x64.tar.gz"
      - name: Transform and validate
        run: |
          ./nq transform.nq input.json >result.json
          jq -e . result.json >/dev/null
```

## Credentials

Store a database properties file in a protected temporary location populated
from the CI system’s secret store. Never commit it or pass a reusable password
directly on the command line. Database command lines and debug logs may be
visible to other processes or retained by the CI provider.

## Cache isolation

Use a job-specific directory and delete it according to the CI provider’s
retention policy:

```bash
nq --cache input.json --cache-dir "$RUNNER_TEMP/nq-cache"
nq report.nq
```

Persistent caches contain source data. Do not upload them as build artifacts
unless that data is safe and the retention policy is intentional.

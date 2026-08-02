# Troubleshooting

## Confirm the executable and version

```bash
nq --version
nq -h
```

If the shell cannot find `nq`, use the full path and then add its directory to
`PATH`. On Windows, try `.\nq.exe -h` from the extraction directory.

## Operating-system warning for an unsigned binary

Published native executables are not currently code-signed. Verify the archive
against the release’s `SHA256SUMS`, then use a narrowly scoped operating-system
exception if required. Do not disable Gatekeeper, SmartScreen, antivirus, or
execution policy globally. See [Installing NQ](install.md).

## “Table not found” or “column not found”

Inspect the schema NQ discovered:

```bash
nq catalog '*' --cache input.json
```

Named JSON/YAML/TOML collections normally become tables with their member names.
Top-level anonymous JSON arrays, JSON Lines, CSV, and TSV use `ITEM`. See
[Source relation names](user-manual.md#source-relation-names).

## A file changed but a cached query shows old data

Persistent caches are not synchronized automatically with source changes.
Query without `--cache` for a fresh temporary load or rebuild the cache:

```bash
nq --cache input.json
```

Inspect cache state with:

```bash
nq --list-caches
```

## No active cache or JDBC connection

Supply an input file with the query, activate a cache, or configure JDBC:

```bash
nq query.nq input.json
nq --cache input.json
nq query.nq
nq query.nq -p database.properties
```

## JDBC driver or connection failure

1. Check the [support matrix](install.md#support-matrix).
2. Run `nq --help connection`.
3. Verify hostname, port, database, username, TLS options, and network access
   using the database vendor’s client.
4. Remember that native executables cannot load arbitrary driver JARs.
5. Use a protected properties file instead of a command-line password.

Enable debug diagnostics only while investigating and review output before
sharing:

```bash
nq query.nq -p database.properties --debug
```

## Hierarchy-path failure

NQ paths are a neutral mapping syntax, not full XPath, JSONPath, or YAMLPath.
Core paths use dotted names such as `{message.person.id}`; XML attributes use
`@`, such as `{message.person.@id}`. See
[Paths](user-manual.md#paths).

## Unexpected duplicate or missing nested objects

NQ infers identity from database metadata and naming conventions. Inspect the
warning, then add explicit `structure {path} key (...)` declarations when the
database lacks suitable keys or the intended output grain differs from the
inferred one. See [`structure`](user-manual.md#structure).

## Parquet naming or type failure

Run `nq --help parquet` and review
[Parquet input](user-manual.md#parquet-input). Use `--parquet-root` and
`--parquet-record` when physical names are generic. Unsafe names are projected;
collisions are rejected rather than silently overwritten.

## Asking for help

Include:

- NQ version and installation method;
- operating system and architecture;
- sanitized minimal input;
- exact command or `.nq` script;
- expected output; and
- complete stdout and stderr with secrets removed.

Open a [Q&A discussion](https://github.com/blater/nq/discussions/categories/q-a)
for usage help or a bug issue for reproducible incorrect behaviour.

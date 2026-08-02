# Choosing NQ or an adjacent data CLI

NQ — SQL for nested data overlaps with several excellent command-line tools.
This page defines the practical boundaries so users can choose the smallest
tool that fits their job.

Capabilities and links were reviewed on 1 August 2026. Follow the linked
official documentation for current details.

| Tool | Expression model | Formats highlighted by the project | Relational database workflow | Deliberately map joined rows into an arbitrary nested output hierarchy | Strong fit |
|---|---|---|---|---|---|
| **NQ** | SQL plus hierarchy paths | JSON, YAML, TOML, XML, CSV, TSV, JSON Lines, Parquet | JDBC queries plus mapped insert/update/delete/procedure calls | Yes; `into {path}` plus inferred or explicit `structure` keys coalesce rows by identity | Moving between nested documents and relational data with familiar SQL |
| [jq](https://jqlang.org/manual/) | jq filters | JSON | Not a core jq workflow | JSON construction is expressive, using jq rather than relational row identity | Focused, composable JSON transformation |
| [yq](https://mikefarah.gitbook.io/yq/) | jq-like expressions | YAML, JSON, XML, CSV, TOML and others | Not a core yq workflow | Can construct/update documents, using yq expressions rather than SQL row mapping | Configuration-file reads and edits across formats |
| [Dasel](https://daseldocs.tomwright.me/) | One selector/expression language across formats | JSON, YAML, XML, TOML, CSV, HCL and INI | Not a core Dasel workflow | Can construct and modify documents, without relational row-identity mapping | Consistent querying, editing, and conversion across formats |
| [Miller](https://miller.readthedocs.io/) | Verbs and a DSL over records | CSV, TSV, JSON, JSON Lines, YAML and other record formats | Can post-process database input/output; direct mapped DML is not its core model | Focuses on record streams, flattening, and unflattening rather than multi-level row identity | Streaming record transformation, joins, statistics, and conversion |
| [Remarshal](https://pypi.org/project/remarshal/) | Conversion options plus optional Starlark | JSON, YAML, TOML, CBOR, MessagePack | No | Supports guarded transcoding and general Starlark transforms, not joined-row hierarchy mapping | Detecting lossy conversions and converting non-XML formats |
| [DuckDB](https://duckdb.org/docs/stable/data/overview) | Rich analytical SQL | CSV, JSON, Parquet and many local/cloud sources | Database access is available through extensions and integrations | SQL supports nested types and JSON output; NQ's explicit hierarchy-path identity model is different | Fast analytical SQL over tabular, nested, lake, and cloud data |
| [dsq](https://github.com/multiprocessio/dsq) / [trdsql](https://noborus.github.io/trdsql/) | SQL over decoded file records | CSV, JSON, YAML and other tabular or record formats; support varies | Primarily local file queries | Results are principally rowsets or ordinary format output rather than explicit identity-aware hierarchies | Quick SQL queries and conversions over local files |
| [OctoSQL](https://github.com/cube2222/octosql) | SQL across pluggable sources | CSV, JSON Lines, Parquet, databases, and plugins | Can query and join file and database sources | Supports nested values, but not NQ's output-path and repeated-object identity mapping | SQL joins and transformations across heterogeneous sources |

SQL over files is an established category. NQ's distinction is therefore not
the presence of SQL by itself: it is the combination of relation discovery,
identity-aware hierarchy output, and mapped document/database writes.

## Choose NQ when

- SQL is the clearest way to join or aggregate collections inside a nested
  document.
- Joined rows must coalesce into stable parent and child objects instead of
  duplicating them through join expansion.
- The output document must have a hierarchy that differs from the query's flat
  row shape.
- The same project must both extract hierarchical documents from databases and
  apply documents back to database tables.
- XML and YAML must participate in the same mapping model as JSON.

## Choose jq or yq when

- The task is a direct field selection, update, or structural edit.
- You already know jq expressions and do not need relational database I/O.
- Preserving format-specific details such as YAML style is more important than
  a format-neutral hierarchy.

## Choose Dasel or Remarshal when

- You want one selector and update language across several document or
  configuration formats; choose Dasel.
- The job is principally format conversion and you want potentially lossy
  conversions rejected or made explicit; choose Remarshal.
- HCL, INI, CBOR, or MessagePack matters more than SQL or JDBC mapping.

## Choose Miller when

- The data is naturally a stream of records.
- Unix-pipeline behaviour and bounded streaming transformations dominate the
  workload.
- You want established verbs such as sort, cut, join, or stats without SQL.

## Choose DuckDB when

- Analytical performance over large CSV, TSV, JSON, Parquet, lake, or cloud data is
  the primary requirement.
- You need a broad analytical SQL engine, extensions, or embedded language APIs.
- The result is primarily tabular or naturally represented with DuckDB nested
  types rather than NQ hierarchy paths.

## Choose another SQL-over-file tool when

- The main requirement is a quick SQL query over CSV, TSV, JSON, YAML, TOML, Parquet, or
  another supported record format.
- Direct joins across several files or heterogeneous query sources matter more
  than mapping one result into a deliberate JSON, YAML, or XML contract.
- You do not need document paths to drive database DML or returned values to be
  written back into an input hierarchy.

## A respectful comparison policy

NQ documentation does not claim to replace these tools and does not publish
performance comparisons without reproducible workloads. Comparison examples
must use each tool idiomatically, pin versions, publish inputs and commands, and
state which dimensions are not being measured.

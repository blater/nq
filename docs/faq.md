# Frequently asked questions

## What is NQ's distinctive job?

NQ maps between hierarchical documents and relational data. It can discover
relations inside a nested file, query them with SQL, and use identity-aware
paths to turn joined rows into a deliberate JSON, YAML, or XML hierarchy. The
same path model can drive JDBC inserts, updates, deletes, procedures, and
returned values. File querying and format selection remain useful on their
own; the distinction is the complete hierarchy-to-relations bridge.

## Why not jq or yq?

Use jq or yq when a direct document filter or edit is clearest in their
expression model. NQ is aimed at jobs that are naturally relational—joins,
aggregates, subqueries—or that cross a document/database boundary and need
explicit hierarchical output mapping. See [the comparison guide](comparison.md).

## Why not DuckDB?

DuckDB is usually the stronger choice for high-performance analytics over
large tabular, nested, lake, or cloud datasets. NQ’s focus is a neutral
hierarchy model spanning JSON, YAML, XML, CSV, JSON Lines, Parquet, and JDBC,
with `into {path}` mappings and mapped DML. The overlap is real; choose based on
the workflow rather than the brand.

## Why not another SQL-over-file tool?

Use DuckDB, dsq, trdsql, OctoSQL, or a similar tool when the main job is SQL
over supported files or direct joins across heterogeneous query sources. SQL
over files is not NQ's unique feature. Choose NQ when joined rows need explicit
repeated-object identity in a new hierarchy, or when document paths must also
drive JDBC writes and returned values. See [the comparison guide](comparison.md).

## Why does file querying use H2?

H2 provides a mature SQL engine, metadata, transactions, and a persistent local
cache without requiring a server. It also matches NQ’s JDBC execution model.
The trade-offs are documented in [Performance and limits](performance.md).

## Why Java and GraalVM?

Java provides mature JDBC and document-format libraries across platforms.
GraalVM Native Image produces standalone release executables so most users do
not need a JVM. The native build cannot load arbitrary driver JARs at runtime;
the JVM build can.

## How large a file can NQ handle?

There is no fixed advertised maximum. Practical size depends on input shape,
available memory, temporary/cache storage, format reader behaviour, and the SQL
operation. The current document readers may materialize substantial hierarchy
state before loading H2, so NQ is not yet presented as a bounded-memory stream
processor. Test a representative file and read [Performance and limits](performance.md).

## Does my data leave the machine?

No data or usage telemetry is sent to the NQ project. NQ connects to a network
only when you configure a network JDBC endpoint.

## What does AGPL mean for CLI users?

The project intends ordinary use of the unmodified CLI on your own data to be
permitted and does not claim your generated output. Modification,
redistribution, and network-accessible modified versions require reading the
actual [licence](../LICENSE.txt).

## Which SQL dialect is used?

File queries run in H2 and use its SQL dialect. Queries sent to an external
database use that database’s JDBC driver and dialect. NQ adds mapping clauses
such as `into {path}` and `structure`, then renders executable SQL for the
selected database.

## Which databases are supported?

Logical driver names are H2, MySQL, MariaDB, PostgreSQL, Oracle, SQL Server,
Db2, SAP HANA, and Informix. Published native executables contain the common
driver set; enterprise drivers require a corresponding source build. See the
[support matrix](install.md#support-matrix).

## Is Parquet experimental?

Parquet input is supported within its documented type, map, naming, and native
build limitations. It is not labelled experimental, but it does not imply
feature parity with a dedicated columnar analytical engine. See
[Parquet input](user-manual.md#parquet-input).

## Where should I ask for help?

Use [GitHub Discussions](https://github.com/blater/nq/discussions/categories/q-a)
for usage questions and sanitized examples. Use an issue for reproducible bugs.
Report vulnerabilities privately through
[GitHub's security advisory form](https://github.com/blater/nq/security/advisories/new).

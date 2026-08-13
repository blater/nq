# NQ User Manual

## Introduction

NQ is a SQL-like scripting language for moving data between relational databases and hierarchical documents. It lets you write ordinary SQL and add small mapping clauses that describe where values should be read from or written to in a neutral `Hierarchy` tree.

Typical uses are:

- Query a database and render hierarchical output.
- Convert XML, JSON, JSON Lines, YAML, TOML, CSV, TSV, or Parquet files or standard input directly to any supported output format.
- Query those input formats through temporary H2, or load and select persistent H2 caches explicitly.
- Load XML, JSON, JSON Lines, YAML, TOML, CSV, TSV, or Parquet input and apply `insert`, `update`, `delete`, or stored-procedure calls.
- Capture database rows into an in-memory temp rowset and use those rows in later DML.
- Write returned database values, such as generated keys, back into the input hierarchy.

The core mapping model is format-neutral. XML, JSON, JSON Lines, YAML, TOML, CSV, TSV, and Parquet are boundary adapters around the same `Hierarchy` structure.

## Quick Start

### Minimal Script

Every script contains one or more statements. End each statement with `;`.

```sql
select 1 into {result.value};
```

Run it with an operational configuration file:

```bash
nq script.nq --config database.properties
```

Example `database.properties`:

```properties
jdbc.class.name=org.h2.Driver
jdbc.database=jdbc:h2:mem:demo;MODE=MySQL;DB_CLOSE_DELAY=-1
jdbc.username=sa
jdbc.password=
```

### Convert Input

An input file supplied without a script is read into NQ's neutral hierarchy and
written directly as JSON by default. Use `--output` to select another format:

```bash
nq customers.xml
nq customers.json -o yaml
nq customers.csv -o markdown
```

Direct conversion does not materialize H2 tables, create or select a cache, or
perform relation and key inference. Supplying a script disables this automatic
conversion and writes only the hierarchy produced by the script.

Standard input defaults to JSON, so only non-JSON streams need `-t`:

```bash
echo '{"customer":{"id":7,"name":"Alice"}}' | nq -o yaml
```

### Query An Input Document

An input file supplied with a script is loaded into temporary H2 unless
`--cache` is explicit. No external JDBC properties are required.

Input file:

```json
{
  "data": {
    "customer": [
      { "id": "C1", "country": "GB" },
      { "id": "C2", "country": "US" }
    ]
  }
}
```

Script:

```sql
select
  c.id into {result.customer.id},
  c.country into {result.customer.country}
from customer c
where c.country = 'GB'
order by c.id asc
structure {result.customer} key (c.id);
```

Run:

```bash
nq customers.nq customers.json
```

Output:

```json
{"result":{"customer":{"id":"C1","country":"GB"}}}
```

The same query can read JSON from a pipeline or shell redirection:

```bash
cat customers.json | nq customers.nq
nq customers.nq < customers.json
```

### Query SQL To Hierarchical Output

```sql
select
  personid,
  firstname into {people.person.firstname},
  surname into {people.person.surname}
from person
order by personid asc
structure {people.person} key (personid);
```

`into {people.person.firstname}` maps the selected value into the output hierarchy. `structure {people.person} key (personid)` says that rows with the same person ID contribute to one `person` object.

Default query output is JSON:

```json
{"people":{"person":[{"firstname":"Alice","surname":"Adams"},{"firstname":"Bob","surname":"Baker"}]}}
```

### Use XML Input To Update A Database

Input file:

```xml
<message>
  <person id="7">
    <firstname>Fred</firstname>
  </person>
</message>
```

Script:

```sql
update person
set firstname = {message.person.firstname}
where personid = {message.person.@id};
```

Run:

```bash
nq update-person.nq input.xml --config database.properties
```

### Use JSON, YAML, TOML, CSV, TSV, Or Parquet Input

The input file extension selects the input reader when a mapped DML statement needs file input.

JSON:

```json
{
  "message": {
    "person": {
      "id": "7",
      "firstname": "Fred"
    }
  }
}
```

YAML:

```yaml
message:
  person:
    id: "7"
    firstname: Fred
```

Both use the same DML paths:

```sql
insert into person (personid, firstname)
values ({message.person.id}, {message.person.firstname});
```

TOML uses the same paths and hierarchical mapping:

```toml
[message.person]
id = "7"
firstname = "Fred"
```

CSV paths begin with the format root `csv`; each row is mapped automatically:

```csv
person.id,person.firstname
7,Fred
8,Wilma
```

Paths:

```sql
insert into person (personid, firstname)
values ({csv.person.id}, {csv.person.firstname});
```

TSV has exactly the same structure and behavior, with tabs as delimiters and a
`tsv` format root. Replace `.csv` with `.tsv` and `csv` with `tsv` in the
paths above.

Parquet uses domain names from the file stem and schema message name by
default. For `customers.parquet` with `message customer`, DML paths look like:

```sql
insert into customer_snapshot (id, risk)
values ({customers.customer.id}, {customers.customer.risk});
```

Use `--parquet-root` and `--parquet-record` when the physical file or message
name is generic.

### Use Parameters

Parameters can appear in SQL text, DML expressions, and supported string input values.

```sql
select firstname into {people.person.firstname}
from person
where region = '${region:EMEA}';
```

Run:

```bash
nq people.nq --config database.properties --param region=APAC
```

`${name}` is replaced by a runtime parameter or Java system property. `${name:default}` uses a default when no value is found.

### Capture Rows And Reuse Them

```sql
capture 'people'
select personid, firstname from person order by personid;

insert into audit_log from temp 'people' (personid, firstname)
values ({personid}, {firstname});
```

`capture` materializes query rows in memory. Later `from temp 'people'` DML reads fields from that temp rowset instead of an input file.

## Command Line Reference

### Usage

NQ uses positional operands for each command's primary inputs. Named options
provide disambiguation and optional modifications; they are not required for
the normal path.

```bash
nq [<script.nq> [<data>]]
nq [<data>]
nq run <script> [<data>] [options]
nq convert [<data>] [options]
nq catalog [<data>] [<pattern>] [options]
nq cache load [<data>] [<name>] [options]
nq cache use <name> [options]
nq cache list [options]
nq cache clear (<name> | olderthan <age> | all) [options]
nq capabilities [-r <format>]
```

Piped and redirected input is always data. It defaults to JSON unless `-t` or
`--input-format` says otherwise:

```bash
cat customers.json | nq customers.nq
nq customers.nq < customers.json
cat customers.json | nq convert
```

Bare `nq` prints brief usage; it does not consume stdin merely to perform the
default JSON-to-JSON identity conversion. Supply `convert`, a format option, or
a script to state the intended work.

A script plus data and no database selection uses a temporary H2 database that
is discarded after the command. With JDBC settings, the data is available to
mapped DML. `--cache [--name <name>]` runs against the active or named cache;
supplying data with that form does not automatically load it into the cache.
The script may still modify the selected database explicitly.

`catalog` with data is also temporary. Catalog and cache commands produce a
versioned operational report; `--report-format` controls its serialization and
defaults to Markdown.

`nq capabilities` (also available as the root flag `nq --capabilities`) prints
the versioned discovery contract used by agents and integrations. It defaults
to JSON, accepts only `--report-format`, and does not inspect stdin, files,
configuration, environment variables, caches, databases, or installed drivers.

Every operational report has the same versioned envelope:

```json
{"schema_version":1,"status":"ok","command":"cache.clear","details":{"cleared":2}}
```

The command-specific `details` fields are:

| `command` | `details` contract |
|---|---|
| `catalog` | `catalog.table[]`; every table has `name`, and a matched-pattern detail also has `columns.column[]` entries with `name`, `type`, and `nullable`. |
| `cache.load` | `source`, `cache_name`, `cache_path`, and boolean `active`. |
| `cache.use` | `cache_name`, `cache_path`, and boolean `active`. |
| `cache.list` | `cache_dir` and `caches[]`; every item has `name`, ISO-8601 `modified`, and boolean `active`. |
| `cache.clear` | integer `cleared`. |
| `capabilities` | `contract_version`, `nq_version`, commands, option applicability and aliases, formats, JDBC drivers, CLI/stdin/cache semantics, report schemas, and exit codes. |

Structured diagnostics use `schema_version`, `code`, `level`, `message`, and an
optional `usage`. JSON and JSONL emit one object per line; YAML emits one marked
document per event; XML uses one `diagnostics` root; TOML uses repeated
`[[diagnostic]]` tables; CSV, TSV, and Markdown emit one header followed by an
event row. Result data remains on stdout and diagnostics remain on stderr.

### Options

| Option                     | Meaning                                          |
|----------------------------|--------------------------------------------------|
| `-h`                       | Print brief usage help.                          |
| `--help`                   | Print the complete `nq(1)` manual page.      |
| `--capabilities`           | Root alias for `nq capabilities`; print its JSON discovery contract. |
| `help [topic]`             | List focused help topics or print one topic.     |
| `-f`, `--script-file path` | Run a script from an explicitly named file.      |
| `-e`, `--script-text text` | Run explicitly supplied inline script text.      |
| `-i`, `--input-file path`  | Read an explicitly named input file; use `-` for stdin. |
| `-t`, `--input-format type` | Name the format of stdin.                        |
| `--param name=value`       | Add or override a runtime template parameter.    |
| `--config path`            | Load whitelisted operational NQ/JDBC settings.   |
| `--params-file path`       | Load task parameters visible to scripts/readers. |
| `--db type`                | Select a supported logical database type and infer its driver class and JDBC URL. |
| `--database name`          | Set the simple-form database name, Oracle service name, or H2 URL suffix. |
| `--host host`              | Set the simple-form database host. Defaults to `localhost`; not valid for H2. |
| `--port port`              | Set the simple-form database port. Stable conventional ports are inferred when omitted. |
| `--user username`          | Set `jdbc.username`. |
| `--password password`      | Set `jdbc.password`. |
| `--jdbc-driver name`       | Set the exact `jdbc.driver` logical driver name. |
| `--jdbc-class-name class`  | Set the exact `jdbc.class.name` driver class. |
| `--jdbc-database url`      | Set the complete `jdbc.database` JDBC URL. |
| `--jdbc-username username` | Set the exact `jdbc.username` value. |
| `--jdbc-password password` | Set the exact `jdbc.password` value. |
| `--output type`, `-o type` | Write output as `xml`, `json`, `jsonl`, `csv`, `tsv`, `yaml`, `toml`, or `markdown`. |
| `-r`, `--report-format type` | Write catalog, cache, capability, or diagnostic reports in a supported output format; capability defaults to `json`, other operational reports to `markdown`. |
| `--debug`                  | Log each query's inferred output-path, relation, key, and parent relationship decisions to stderr. |
| `--no-key-inference`       | Disable automatic DQL keys and preserve row-first output for paths without explicit `structure` keys. |
| `--cache`                  | Select cache execution (active cache by default). |
| `--name name`              | Select a named cache without activating it.       |
| `--cache-dir path`         | Select the directory that directly holds cache files. |
| `--parquet-root name`      | Override the Parquet hierarchy root name.          |
| `--parquet-record name`    | Override the repeated Parquet record node name.    |

All option values use the consistent spaced form `--option value`. Equals forms,
attached short values, short-option bundles, and duplicate boolean flags are
rejected. Use `--` when a positional operand begins with `-`.

### Hermetic State

`--cache-dir` names the actual cache directory:

```text
<cache-dir>/.active
<cache-dir>/<logical-name>.mv.db
```

Resolution is `~/.nq/cache`, then `cache.dir` from `--config`, then
`NQ_CACHE_DIR`, then explicit `--cache-dir`. Command-line options override every
inherited value. Read-only operations do not create an absent directory.

`--config` and `--params-file` are deliberately distinct. The former accepts
only supported operational keys; the latter contains arbitrary task parameters.
Both currently use UTF-8 Java `.properties` syntax, and `--param name=value`
overrides a task parameter from a file.

### JDBC Parameters

The simple command-line form builds a JDBC URL and infers the driver class:

```bash
nq report.nq \
  --db postgresql \
  --database customer_data \
  --host db.internal.example \
  --port 5544 \
  --user report_user
```

The supported URL and default rules are:

| Database type | Generated JDBC URL | Default port | Username default |
|---------------|--------------------|--------------|------------------|
| `h2` | `jdbc:h2:<database>` | none | `sa` |
| `postgresql` | `jdbc:postgresql://<host>:<port>/<database>` | `5432` | current OS user |
| `mysql` | `jdbc:mysql://<host>:<port>/<database>` | `3306` | current OS user |
| `mariadb` | `jdbc:mariadb://<host>:<port>/<database>` | `3306` | current OS user |
| `oracle` | `jdbc:oracle:thin:@//<host>:<port>/<service>` | `1521` | absent |
| `sqlserver` | `jdbc:sqlserver://<host>:<port>;databaseName=<database>` | `1433` | absent |
| `db2` | `jdbc:db2://<host>:<port>/<database>` | `50000` | absent |
| `hana` | `jdbc:sap://<host>:<port>/?databaseName=<database>` | required | absent |
| `informix` | `jdbc:informix-sqli://<host>:<port>/<database>` | required | absent for trusted-user handling |

The host defaults to `localhost`. HANA and Informix require an explicit port because neither has one safe conventional default. The password is never inferred.

For a complete JDBC URL or custom JVM driver, use the exact form:

```bash
nq report.nq \
  --jdbc-database 'jdbc:postgresql://db.internal.example:5432/customer_data' \
  --jdbc-username report_user
```

The named exact options map directly to the existing properties:

| Command-line option | Runtime property |
|---------------------|------------------|
| `--jdbc-driver` | `jdbc.driver` |
| `--jdbc-class-name` | `jdbc.class.name` |
| `--jdbc-database` | `jdbc.database` |
| `--jdbc-username` | `jdbc.username` |
| `--jdbc-password` | `jdbc.password` |

The same values can be supplied in an operational configuration file:

```properties
jdbc.driver=postgresql
jdbc.database=jdbc:postgresql://localhost:5432/customer_data
jdbc.username=report_user
jdbc.password=change-me
```

Configuration files provide defaults and command-line values override them
regardless of argument position. This makes partial configuration predictable:

```properties
# database.properties
jdbc.username=report_user
jdbc.password=change-me
```

```bash
nq report.nq --config database.properties --db postgresql --database customer_data
nq report.nq --db postgresql --database customer_data --config database.properties
```

Both commands use the generated PostgreSQL URL and credentials from the config
file. A command-line `--user`, `--password`, or `--jdbc-*` replaces the
corresponding inherited setting. Simple database identity options cannot be
mixed with exact JDBC identity options. A complete `--jdbc-database` URL is
sufficient in normal use; `--jdbc-driver` and `--jdbc-class-name` are mutually
exclusive escape hatches for unusual drivers.

#### Supplying a JDBC Driver JAR

The JVM build can use a JDBC driver that is not bundled with NQ. Put both the NQ fat JAR and the driver JAR on the Java classpath, invoke the main class directly, and supply the driver's class name and complete JDBC URL:

```bash
java -cp '/path/to/nq.jar:/path/to/vendor-driver.jar' \
  blater.nq.Main report.nq \
  --jdbc-class-name com.vendor.jdbc.Driver \
  --jdbc-database 'jdbc:vendor://db.internal.example/customer_data' \
  --jdbc-username report_user
```

On Windows, separate classpath entries with `;` instead of `:`. Add any other JARs required by the driver to the classpath as well. Do not combine `--jdbc-class-name` with `--jdbc-driver`; they are mutually exclusive driver-selection forms.

Use `java -cp` and `blater.nq.Main`, not `java -jar`; Java's `-jar` launch mode does not add user-supplied classpath entries. The native `nq`, `nq-enterprise`, and `nq-all` executables cannot load JARs at runtime. A custom driver for a native executable must be added as a build dependency and included when the executable is rebuilt, with any GraalVM Native Image configuration the driver requires.

For predefined drivers, the selected JAR or native executable must contain the requested JDBC dependency. If it does not, driver class loading fails when the connection is opened; NQ does not maintain a separate build-profile validation registry.

Supplied values are used as written. NQ does not validate port ranges, encode
URL components, or reconcile inconsistent credentials; the JDBC driver or
database reports those errors. The simple form requires `--db` and `--database`
together and does not decompose an inherited `jdbc.database` URL.

`run --cache` cannot be combined with JDBC connection options because they name
conflicting execution targets. Without `--cache`, explicit JDBC settings use an
accompanying input file or typed standard input for mapped DML; without JDBC
settings, that input is queried through temporary H2. With no input source,
JDBC settings take precedence over the active-cache fallback. Command-line
passwords may be visible in shell history and process listings; prefer a
protected operational config file for reusable credentials.

### Input Type Selection

Input file type is selected by file extension:

| Extension       | Input reader |
|-----------------|--------------|
| `.xml`          | XML          |
| `.json`         | JSON         |
| `.jsonl`        | JSON Lines   |
| `.yaml`, `.yml` | YAML         |
| `.toml`         | TOML         |
| `.csv`          | CSV          |
| `.tsv`          | TSV          |
| `.parquet`      | Parquet      |

Extension matching is case-insensitive. Blank or missing input filenames preserve the historical empty XML-input behavior.

For pipelines and redirected input, specify the format explicitly with `-i` or
`--input`. NQ spools standard input only for the duration of the command and
uses its normal direct-conversion or query path. With `--cache`, NQ creates and
activates a fresh persistent cache from that stream.

```bash
cat customers.json | nq -i json "select id, name from customers;"
nq -i json "select id, name from customers;" < customers.json
cat customers.json | nq -i json --cache "select id, name from customers;"
```

### Source Relation Names

For JSON, YAML, and TOML, NQ names a materialized object-record relation from the
source member that declares it. The rule applies at every depth:

| Source shape | SQL relation |
|---|---|
| `[{"id":1}]` | `ITEM` |
| `{"customers":[{"id":1}]}` | `CUSTOMERS` |
| `{"data":{"customers":[{"id":1}]}}` | `CUSTOMERS` |
| `regions -> customers -> orders` | `REGIONS`, `CUSTOMERS`, `ORDERS` |
| JSON Lines object records | `ITEM` |
| CSV records | `ITEM` |
| TSV records | `ITEM` |

A non-empty object-record collection becomes a relation. Repeated scalar fields
use value relations named from their owner, such as `CUSTOMER_TAG`. If two
different parts of a document produce the same table name, their compatible
rows share that table; incompatible SQL identifier collisions fail clearly.

### Querying Input Documents: Temporary and Persistent H2

NQ creates the same SQL tables for both modes. The difference is how long the H2 database lives.

#### Temporary loading by default

Supplying a script and input file without `--cache` or JDBC settings loads the file into an in-memory H2 database:

```bash
nq 'select id from item where a in (select max(a) from item);' elements.json
```

Typed standard input uses the same temporary path:

```bash
cat elements.json | nq 'select id from item where a in (select max(a) from item);'
```

The database exists only for that command and is discarded when the command
finishes. A source file is read again, or standard input is consumed again, on
every invocation, so changes are visible immediately. Temporary loads create no
cache file, do not appear in `cache list`, do not change the active cache,
and do not persist inferred key metadata. `--cache-dir` is invalid for temporary
execution because no persistent cache is involved.

Temporary loading avoids cache files, stale results, H2 file locking, and active-cache side effects. Its costs are that parsing and table creation are repeated for every command, the database cannot be reused later, and the materialized data must fit comfortably in memory. It is therefore a good default for one-off queries and modest input files.

#### Persistent caches

Loading and querying are separate, explicit operations. Load a document with
the `cache load` command:

```bash
nq cache load customers.json
cat customers.json | nq cache load --name customers
```

The first form generates a logical name; the second creates `customers`. A
successful load creates a fresh file-backed H2 database and activates it. It
does not append, overwrite, or upsert an existing cache. The command emits a
versioned report in the selected `--report-format`.

Query the active cache without repeating the input source:

```bash
nq totals.nq
```

Use `--cache` to be explicit, or use `--name` to query an existing named cache
without changing the active selection:

```bash
nq totals.nq --cache
nq totals.nq --cache --name customers
```

When a data source accompanies a cache-targeted run, NQ does not automatically
load that data into the cache. It remains available to mapped DML, so a script
may insert/update/delete explicitly. This prevents invisible cache growth and
keeps persistence intentional.

To switch the active selection without running a query or loading the source
file, use:

```bash
nq cache use customers
```

`cache use` only activates an existing logical cache name. No cache is created
or rebuilt when the name is missing.

By default, cache files are stored under:

```text
~/.nq/cache
```

Use `--cache-dir path` or `NQ_CACHE_DIR` to choose another direct cache
directory.

The active selection is stored in:

```text
~/.nq/cache/.active
```

It records the logical cache name, so the directory remains relocatable. A
later command must select the same `--cache-dir` to read that state. Generated
names use two-word jnames such as `bright-otter`; `.mv.db` is an H2 storage
suffix, not part of the logical name.

Persistent cache creation and selection are always explicit. Use `cache load`
to create and activate a fresh cache, or `cache use` to activate an existing
one. A script supplied with data but without `--cache` always performs a fresh
temporary load, even when a persistent cache already exists.

File-backed persistent caches are not synchronized with source-file changes.
Run the query with its input file and without `--cache` for a fresh temporary
load, or run `cache load` again to create and activate a fresh persistent cache.

When a cache is built, nq creates input-structure tables. Object names become table names, direct scalar children become columns, and nested objects become related tables.

Inspect the active cache or configured database with a `catalog` statement:

```sql
catalog;           -- list user tables without column details
catalog customer;  -- show full details for one table
catalog audit*;    -- show full details for matching tables
catalog *;         -- show full details for every user table
```

Catalog patterns are case-insensitive and `*` matches any sequence of characters.

Input-structure table rules:

- A hierarchy object becomes a materialized table when it carries scalar data or contains child objects.
- The object name becomes the table name, such as `customer`, `country`, or `wallet`.
- Nodes with the same object name share one table.
- Direct scalar children and XML attributes become columns.
- If every row of a structure table has a scalar `id`, nq preserves and uses
  that column as the row identity.
- Otherwise nq adds `_nq_id` as the generated row identity and preserves any
  partially supplied `id` field as ordinary nullable data.
- The `_nq_` prefix is reserved for NQ-generated technical names; input names
  using that prefix are rejected.
- Nested objects are separate tables, not embedded columns.
- Child rows get a containment reference named `<parent>_id`, such as `wallet.customer_id`.
- If the input child already has that `<parent>_id` field, nq preserves the input value.
- Repeated direct scalar children become child tables named `<parent>_<field>`
  with `_nq_id`, `<parent>_id`, and `value` columns.
- NQ does not infer primary key constraints, foreign key constraints, uniqueness, or indexes from the input file.

Example input shape:

```json
{
  "data": {
    "customer": [
      {
        "id": "C1",
        "ccode": "90",
        "wallet": [
          { "symbol": "GBP", "balance": "1.93" },
          { "symbol": "AUD", "balance": "998.33" }
        ]
      },
      {
        "id": "C2",
        "ccode": "90",
        "wallet": [
          { "symbol": "GBP", "balance": "89933.00" }
        ]
      }
    ],
    "country": [
      { "ccode": "89", "name": "vietnam" },
      { "ccode": "90", "name": "vatican city" }
    ]
  }
}
```

Example query:

```sql
output json;

select cn.name into {result.countryName}
from customer cu
inner join country cn on cn.ccode = cu.ccode
where cu.id = 'C1';
```

Example containment query:

```sql
output json;

select w.balance into {result.balance}
from customer cu
inner join wallet w on w.customer_id = cu.id
where cu.id = 'C1'
  and w.symbol = 'AUD';
```

Example grouped query over the sample identity/customer files in `docs/examples`:

```sql
output json;

select
  a.country_code as country_key,
  a.country_code into {result.region.country},
  count(distinct c.id) into {result.region.customerCount}
from customer c
inner join address a on a.customer_id = c.id
inner join kyc k on k.customer_id = c.id
where a.kind = 'residential'
  and k.status <> 'not_started'
group by a.country_code
order by country_key asc
structure {result.region} key (country_key);
```

Run it against any of the equivalent example inputs:

```bash
nq docs/examples/identity-country-counts.nq docs/examples/identity-customers.json
nq docs/examples/identity-country-counts.nq docs/examples/identity-customers.yaml
nq docs/examples/identity-country-counts.nq docs/examples/identity-customers.xml
```

Output:

```json
{"result":{"region":[{"country":"GB","customerCount":"2"},{"country":"US","customerCount":"4"}]}}
```

Repeated scalar values are queryable through child tables rather than packed strings:

```json
{
  "data": {
    "customer": [
      { "id": "C1", "tag": ["vip", "active"] }
    ]
  }
}
```

This creates `customer` and `customer_tag`. Query the tags with:

```sql
select tag.value
from customer_tag tag
where tag.customer_id = 'C1';
```

Input-structure tables are loaded into the persistent cache. Secondary path/value indexes are deferred to a later `--index` enhancement.

### Cache Maintenance

List caches:

```bash
nq cache list
```

The report shows each logical cache name, modification time, and active state.

Switch the active cache without loading or rebuilding it:

```bash
nq cache use bright-otter
```

Clear all caches:

```bash
nq cache clear all
```

Clear one cache by logical name:

```bash
nq cache clear bright-otter
```

Logical cache names are resolved under the selected direct cache directory.

Clear caches not modified within a duration:

```bash
nq cache clear olderthan 30m
nq cache clear olderthan 6h
nq cache clear olderthan 7d
```

Supported duration units are minutes, hours, and days. Short forms such as `m`, `h`, and `d` are accepted.

Cache maintenance commands accept `--cache-dir`:

```bash
nq cache clear all --cache-dir /tmp/nq-cache
```

### Output Format

Output format is selected in this order:

1. Command-line `--output type` or `-o type`.
2. The first `output type;` directive in the script.
3. Markdown for the command-line `catalog` command.
4. JSON otherwise.

Accepted output types are `xml`, `json`, `jsonl`, `csv`, `tsv`, `yaml`, `toml`, and `markdown`, case-insensitively. Markdown output renders
the result as one space-padded table, using dotted columns for nested scalar values and rows for repeated objects.

With no script, the input hierarchy is written directly in the selected output
format. With a script, only the hierarchy produced by the script is written;
NQ never falls back to converting the input when the script produces no output
or fails.

JSON Lines input treats each nonblank line as one JSON value and produces the
same hierarchy as a JSON top-level array containing those values. JSON Lines
output writes each synthetic top-level array item on its own line. A named or
synthetic-object result is preserved as one complete JSON value on one line.

Examples:

```bash
nq people.xml
nq people.json -o yaml
nq people.csv -o markdown
nq people.tsv -o csv
```

```bash
nq people.nq --config database.properties
```

```bash
nq people.nq --config database.properties -o jsonl > people.jsonl
```

```sql
output yaml;

select firstname into {people.person.firstname}
from person;
```

Only the first script `output` directive is significant. Later `output` directives are parsed but ignored.

## Script Language Reference

### Statement Terminators

Statements end with `;`.

```sql
select 1 into {result.value};
```

Handlers are separate statements and therefore require their own semicolon.

### Output Directive

Use `output` to set the script's preferred output format:

```sql
output json;
```

Accepted formats are `xml`, `json`, `jsonl`, `csv`, `tsv`, `yaml`, `toml`, and `markdown`. The keyword and format are case-insensitive. If multiple `output` directives appear, only the first one is used. A command-line `--output` or `-o` flag overrides the script directive.

### Comments

Line comments:

```sql
-- comment
```

Block comments:

```sql
/* comment */
```

### Keywords And Identifiers

Keywords are case-insensitive.

Identifiers can be quoted with double quotes, square brackets, or backticks where the grammar accepts a name:

```sql
select "first name" into {people.person.firstname} from person;
```

### String Literals

String literals use single quotes. Escape a single quote by doubling it:

```sql
literal insert into audit(message) values ('it''s done');
```

### Templates

Template placeholders are expanded from runtime parameters, then Java system properties, then defaults:

| Syntax            | Meaning                                                               |
|-------------------|-----------------------------------------------------------------------|
| `${name}`         | Replace with parameter/system property named `name`; omit if missing. |
| `${name:default}` | Use `default` when no value is found.                                 |
| `\${name}`        | Render literal `${name}`.                                             |

Examples:

```sql
select personid into {people.person.id}
from person
where region = '${region:EMEA}';
```

```bash
nq people.nq --config database.properties --param region=APAC
```

### Paths

Script mapping paths are dot-separated:

```sql
into {people.person.firstname}
```

XML attributes use `@` on the terminal segment:

```sql
into {people.person.@id}
```

Only terminal attributes are supported. JSON, YAML, TOML, CSV, and TSV do not have attributes, so use ordinary property paths for those formats.

## Query Output Reference

### Plain SELECT

A plain `select` runs as SQL. If it has no `into {path}` mappings, it does not add hierarchy output.

```sql
select count(*) as total from person;
```

### SELECT With Output Mappings

Add `into {path}` to a select item:

```sql
select
  personid,
  firstname into {people.person.firstname}
from person
order by personid asc
structure {people.person} key (personid);
```

The mapping clause is removed from the SQL sent to the database. NQ creates generated column aliases internally. Key expressions are also projected internally when they are needed to resolve a `structure` path.

SQL aliases are allowed:

```sql
select name as personName into {person.name} from person;
```

### `structure`

Without a `structure` key, nq uses JDBC metadata to infer primary, unique, composite, conventional, and logical keys for mapped object paths. Required key columns are projected internally and never appear in output. Inference uses metadata only; it never samples query rows.

```sql
select
  id into {companies.company.id},
  name into {companies.company.name}
from company
order by id;
```

Rows with the same inferred company key contribute to one object. Add an explicit key to override inference for that exact hierarchy path:

```sql
structure {companies.company} key (id)
```

A directly mapped top-level object can also be inferred without an artificial container:

```sql
select name into {festival.name} from festival;
```

The final path segment is the field, the segment above it is the object that owns the field, and the next segment—when present—names the enclosing inferred collection. Higher segments remain ordinary containers:

| Mapping | JSON shape |
|---|---|
| no `into` mapping | `[{"name":"..."}]` |
| `{name}` | `[{"name":"..."}]` |
| `{festival.name}` | `[{"festival":{"name":"..."}}]` |
| `{res.festival.name}` | `{"res":[{"festival":{"name":"..."}}]}` |
| `{root.res.festival.name}` | `{"root":{"res":[{"festival":{"name":"..."}}]}}` |

Inferred collections remain arrays for zero, one, or many objects. XML introduces a `<result>` document element when the inferred collection has no ordinary named root.

NQ also recognizes one explicit collection/item naming convention. If a plural relation name matches the container and its singular form matches the item, the item path itself repeats. For example, a `customers` relation mapped to `{customers.customer.*}` produces a `customers` object containing repeated `customer` items. Other names retain the row-first shapes shown above.

The explicit tuple is complete and authoritative for `{companies.company}`. Inference cannot add to or replace it, but remains available for undeclared sibling and descendant paths, even when they use the same table or alias. Use `--no-key-inference` to disable inference for the entire query.

Keys are typed tuples. Use a comma-separated list for a composite identity:

```sql
structure {customers.customer.order} key (o.customer_id, o.order_id)
```

Nested keys coalesce each level independently:

```sql
structure
  {people.person} key (p.personid),
  {people.person.nickname} key (n.nicknameid)
```

Grouped entity paths are also inferred independently. A relation key is eligible only when every component is present in the `group by` set; additional grouped display columns do not become part of the identity. A path with a mapped aggregate can instead use the complete grouping tuple as summary identity when all its non-aggregate mappings are grouped. Unsupported grouping, `distinct`, ungrouped aggregates, and unclassified function calls preserve row-first output rather than adding a hidden key that could change query grain.

For a left-joined child, an all-null inferred identity means that the child is absent. The parent remains in the output without an empty or null child member.

Key declarations do not change SQL ordering. Ordinary `order by` controls the first-encounter order of output objects. Add normal SQL tie-breakers when deterministic order matters:

```sql
order by p.surname, p.personid, n.nicknameid
```

Independent repeated siblings can be mapped from one joined result without duplicate output nodes when both paths have keys:

```sql
structure
  {customers.customer} key (c.id),
  {customers.customer.phone} key (p.id),
  {customers.customer.email} key (e.id)
```

### Attributes

For XML output, map terminal values to attributes with `@`:

```sql
select personid into {people.person.@id}
from person
structure {people.person} key (personid);
```

JSON, YAML, TOML, CSV, and TSV output adapters approximate attributes as ordinary fields.

### Appending Values

`append(space)` combines multiple mapped values into the same output node with a space separator:

```sql
select
  firstname into {people.person.name:append(space)},
  surname into {people.person.name:append(space)}
from person
structure {people.person} key (personid);
```

Output:

```xml
<name>Alice Adams</name>
```

### Null Output

By default, SQL `NULL` is preserved in the hierarchy. XML renders it as:

```xml
<field nil="true" />
```

Use `absent on null` to omit a mapped output field when the SQL value is null:

```sql
case when discount > 0 then discount end
  into {order.line.discount} absent on null
```

### Complete Hierarchical Query Example

This query joins customers, orders, order lines, and products into a three-level output hierarchy. It combines
runtime parameters, mapped XML attributes, appended values, a conditional expression, null omission, and a
`structure` key for every repeated object path.

```sql
output xml;

select
  c.customer_id into {customers.customer.@id},
  c.first_name into {customers.customer.name:append(space)},
  c.last_name into {customers.customer.name:append(space)},
  o.order_id into {customers.customer.order.@id},
  p.sku into {customers.customer.order.line.@sku},
  case when l.discount_amount > 0 then l.discount_amount end
    into {customers.customer.order.line.discount} absent on null
from customer c
join orders o on o.customer_id = c.customer_id
join order_line l on l.order_id = o.order_id
join product p on p.product_id = l.product_id
where c.region = '${region:EMEA}'
  and c.account_status = 'ACTIVE'
  and o.order_date >= date '${fromDate:2026-01-01}'
structure
  {customers.customer} key (c.customer_id),
  {customers.customer.order} key (o.order_id),
  {customers.customer.order.line} key (l.line_id);
```

The three keys allow rows for the same customer, order, and line to contribute to existing objects even when those
rows are not consecutive in the SQL result. The first time each key is encountered determines that object's position
in the output.

Example output:

```xml
<customers>
  <customer id="100">
    <name>Alice Adams</name>
    <order id="9001">
      <line sku="SKU-1" />
      <line sku="SKU-2">
        <discount>5.00</discount>
      </line>
    </order>
    <order id="9002">
      <line sku="SKU-3" />
    </order>
  </customer>
  <customer id="201">
    <name>Brian Baker</name>
    <order id="9110">
      <line sku="SKU-9" />
    </order>
  </customer>
</customers>
```

### `hierarchy union`

`hierarchy union` combines multiple mapped select branches into one hierarchy. It is useful when separate branch queries are clearer or avoid a large joined row expansion. It is not required merely to map repeated siblings: keyed paths also work with one joined select.

```sql
select
  c.id into {customers.customer.id},
  p.id into {customers.customer.phone.id},
  p.number into {customers.customer.phone.number}
from customer c
join phone p on p.customer_id = c.id

hierarchy union

select
  c.id into {customers.customer.id},
  e.id into {customers.customer.email.id},
  e.address into {customers.customer.email.address}
from customer c
join email e on e.customer_id = c.id
structure
  {customers.customer} key (c.id),
  {customers.customer.phone} key (p.id),
  {customers.customer.email} key (e.id);
```

Branches must map under the same document wrapper. NQ combines them with SQL `union all`, so duplicate source rows are preserved and keys decide whether output objects coalesce.

`using` metadata is only valid on the first `hierarchy union` branch.

### `using` Metadata

Syntax:

```sql
select using schema 'people.xsd' xmlroot = people namespace = 'urn:people'
  firstname into {people.person.firstname}
from person p
structure {people.person} key (p.personid);
```

Current behavior:

- `namespace = '...'` is applied to XML root output.
- `schema` and `xmlroot` are parsed as metadata markers but do not currently validate or reshape output.
- `using` requires at least one hierarchy mapping.
- `using` is not allowed on non-first `hierarchy union` branches.

### SQL Constructs

SQL expressions are mostly passed through to the database. Examples known to parse include:

```sql
case when category = 'A' then firstname end into {people.person.staffName}
```

```sql
group by department, upper(surname)
having count(*) > 1
order by upper(surname) asc, 1 desc
```

The parser recognizes nq mapping markers at top level and otherwise leaves SQL text to the database.

## DML Input Reference

Mapped DML reads values from a loaded input hierarchy or a captured temp rowset and binds them as SQL parameters.

### Input File Formats

XML:

```xml
<message>
  <person id="7">
    <firstname>Fred</firstname>
  </person>
</message>
```

Paths:

```sql
{message.person.@id}
{message.person.firstname}
```

JSON, YAML, and TOML:

- Top-level keys are the first path segment.
- Multiple top-level keys are addressed directly; internal format roots such
  as `json`, `yaml`, and `toml` are not part of mapped DML paths.
- Named arrays become repeated rows without adding an `item` path segment.
- JSON and YAML null values become hierarchy null values; TOML has no null literal.

Both a sole top-level array and one collection among multiple top-level keys
use the same direct path. For example, fields in a top-level `users` array are
referenced as:

```sql
{users.id}
{users.active}
```

CSV and TSV:

- Root is `csv` or `tsv`, matching the input format.
- Each record is a repeated `item`.
- Headers become child paths under each item.
- Dotted headers create nested nodes.

Example:

```csv
person.id,person.firstname
7,Fred
```

Paths:

```sql
{csv.person.id}
{csv.person.firstname}
```

The equivalent TSV paths begin with `{tsv...}`. The explicit technical forms
`{csv.item...}` and `{tsv.item...}` are also accepted.

Parquet:

- The root defaults to the projected input file stem, such as `customers` from `customers.parquet`.
- The repeated record node defaults to the projected Parquet schema message name, such as `customer`.
- Use `--parquet-root` and `--parquet-record` when physical names are generic or unstable.
- Unsafe Parquet names are projected to path-safe names. For example, `event.time` becomes `event_time` and `1st_seen` becomes `_1st_seen`.
- Projection collisions fail clearly. For example, `user-id` and `user_id` both project to `user_id`.
- Single-valued scalar structs flatten into owner fields, such as `profile_score`.
- Repeated structs remain child records.
- Parquet map keys become fields or child nodes on the owning record. The map wrapper and technical `key` / `value` rows are not exposed.

Example DML paths for `customers.parquet` with `message customer`:

```sql
{customers.customer.id}
{customers.customer.risk}
{customers.customer.profile_score}
```

### INSERT

With an input file:

```sql
insert into person (personid, firstname)
values ({message.person.id}, {message.person.firstname});
```

Without a column list, table column order is used:

```sql
insert into audit_log
values ({message.person.id}, {message.person.firstname});
```

With literals and expressions:

```sql
insert into audit_log (personid, status, nickname)
values ({person.id}, 'NEW', coalesce({person.nickname}, 'none'));
```

A DML expression can contain one mapped source path. If it contains no mapped source, it is treated as a literal expression.

### UPDATE

```sql
update person
set firstname = upper({person.firstname}),
    lastupdated = coalesce({person.lastUpdated}, 'fallback')
where personid = {person.@id};
```

Values in the `where` clause are key fields for row matching.

### DELETE

```sql
delete from audit_log
where personid = {person.id};
```

`delete` does not support `returns`.

### RETURNS

`returns` writes database-assigned values back into the input hierarchy after an `insert` or `update`.

```sql
insert into person (firstname)
values ({person.firstname})
returns personid into {person.id};
```

Multiple returned values are allowed:

```sql
update person
set firstname = {person.firstname}
where personid = {person.id}
returns lastupdated into {person.lastUpdated},
        version into {person.version};
```

`returns` requires a mapped input source and is not supported on `delete` or `insert ... select`.

### Stored Procedures

```sql
execute procedure update_person(firstname = {person.firstname});
```

Procedure arguments are mapped similarly to DML columns.

### Repeated Input Rows

When input contains repeated nodes, nq infers row context from mapped paths.

XML:

```xml
<message>
  <person id="7">
    <nickname id="10">Ace</nickname>
    <nickname id="11">Al</nickname>
  </person>
</message>
```

Script:

```sql
insert into nickname (nicknameid, personid, nickname)
values ({message.person.nickname.@id}, {message.person.@id}, {message.person.nickname});
```

This inserts two rows.

Ambiguous independent repeated branches are reported as statement problems. Use `onWarning` to choose whether to fail or continue.

### Temp Rowsets

Capture query results:

```sql
capture 'people'
select personid, firstname from person order by personid;
```

Insert from captured rows:

```sql
insert into audit_log from temp 'people' (personid, firstname)
values ({personid}, {firstname});
```

Update from captured rows:

```sql
update audit_log from temp 'people'
set firstname = {firstname}
where personid = {personid};
```

Delete from captured rows:

```sql
delete from audit_log from temp 'people'
where personid = {personid};
```

Temp row field names are matched case-insensitively by lowercased column label.

## Statement Reference

### `autocommit`

```sql
autocommit on;

autocommit off;
```

Calls `Connection.setAutoCommit(...)`.

### `literal`

Use `literal` to run SQL exactly as written, with template expansion at execution time.

```sql
literal create table audit_log (message varchar(80));
```

Some SQL-like DML without mapped sources is also treated as literal SQL:

```sql
insert into audit_log (message) values (${MESSAGE});
```

### `capture`

```sql
capture 'rowset_name'
select id, name from source_table;
```

The SQL after `capture 'name'` is stored as the capture query.

### `include` And `submapping`

`include` and `submapping` are textual inclusion directives handled before parsing.

```sql
include 'setup.sql';
submapping 'person-summary.nq';
```

Included paths are resolved relative to the including file. Circular includes fail. Both directive names behave the same.

## Error Handling Reference

Each statement can be followed by handler statements.

```sql
update menu
set dishname = {message.dish.dishname}
where dishid = {message.dish.@id};
onError('bad update', abort, rollback);
onWarning('ambiguous input', rollback);
```

Handlers:

| Handler          | Applies to                                          |
|------------------|-----------------------------------------------------|
| `onError(...)`   | SQL execution errors.                               |
| `onWarning(...)` | Statement problems such as ambiguous input mapping. |

Flags:

| Flag       | Meaning                                                                    |
|------------|----------------------------------------------------------------------------|
| `abort`    | Fail the script. This is the default behavior if no handler is present.    |
| `rollback` | Roll back the active JDBC connection before applying the handler behavior. |

If a handler has no `abort`, behavior is best effort: log and continue.

## Format Reference

### XML Input

- Root element becomes the hierarchy root.
- Elements become nodes.
- Attributes become child nodes marked as attributes.
- Leaf element text and attribute values are template-expanded.
- Empty filename or empty file returns an empty hierarchy.
- Malformed XML fails.

### JSON Input

- One top-level property becomes the hierarchy root.
- Multiple top-level properties use synthetic root `json`.
- Object properties become child nodes.
- Arrays under named properties become repeated child nodes with that property name.
- Anonymous arrays use `item` nodes.
- String values are template-expanded.
- Numbers and booleans become string values.
- JSON null becomes a null node.

### YAML Input

YAML input follows the same structure rules as JSON:

- One top-level mapping key becomes the hierarchy root.
- Multiple top-level keys use synthetic root `yaml`.
- Mappings become child nodes.
- Sequences under named keys become repeated child nodes.
- Anonymous sequences use `item` nodes.
- String scalars are template-expanded.
- Numbers and booleans become string values.
- YAML null becomes a null node.

### TOML Input

TOML shares the parser-neutral hierarchy mapper used by YAML:

- One top-level key becomes the hierarchy root.
- Multiple top-level keys use synthetic root `toml`.
- Tables become nested child nodes.
- Arrays of tables become repeated child nodes.
- Scalar arrays become repeated scalar nodes.
- String values are template-expanded.
- Numbers, booleans, dates, and times become string values.
- TOML has no null literal.

### CSV and TSV Input

- Root is `csv` for CSV input and `tsv` for TSV input.
- Rows are repeated `item` nodes.
- Headers become child names.
- Dotted headers become nested paths.
- CSV uses commas and TSV uses tabs as delimiters.
- Quoted delimiters, quotes, and newlines are parsed identically in both formats.
- Missing trailing cells become empty strings.
- Empty filename or empty file returns an empty hierarchy.

Example:

```csv
person.id,person.firstname
7,Fred
```

Paths:

```text
/csv/item/person/id
/csv/item/person/firstname
```

For TSV, the equivalent paths start with `/tsv/item`.

### Parquet Input

Parquet input is domain-shaped rather than storage-shaped:

- The root node defaults to the file stem after `.parquet` is removed.
- Each Parquet row becomes a repeated node named from the schema message name.
- Primitive fields become scalar child fields.
- Single-valued structs whose children are scalar fields flatten into owner fields using `<struct>_<field>`.
- Repeated structs and non-scalar nested groups become child nodes.
- List and repeated scalar values become repeated child nodes.
- String and enum values are template-expanded; other scalar values become strings.
- Null optional scalar fields become null nodes.
- Binary or fixed values without a supported logical type become Base64 strings.

Naming:

- `--parquet-root customers` overrides the root.
- `--parquet-record customer` overrides the repeated record node.
- Use spaced option values: `--parquet-root customers` and `--parquet-record customer`.
- Unsafe names are projected to path-safe names without changing case. Examples: `event.time` to `event_time`, `user-id` to `user_id`, `1st_seen` to `_1st_seen`.
- Projected-name collisions fail instead of being silently renamed.

Map fields:

- Map keys become public domain fields on the owning record.
- Scalar map values become direct fields.
- Simple nested-record map values flatten using `<key>_<child>`.
- List map values become repeated nodes under the key.
- The map wrapper name and technical `key` / `value` rows are not public hierarchy or cache names.

For a Parquet `customer` record with `attributes = {risk: low, tier: gold}`,
the cache table is queried as:

```sql
select risk, tier
from customer
where id = 'C1';
```

### XML Output

- Root hierarchy node becomes the XML root element.
- Inferred synthetic roots use document element `result`.
- Attribute nodes render as XML attributes.
- Null nodes render with `nil="true"`.
- Namespace metadata from `using namespace = ...` is applied to the root.

### JSON Output Adapter

Writer: `JsonOutputWriter`.

- Root node becomes a single top-level JSON property.
- Inferred outer collections retain their array shape for zero, one, or many objects.
- A missing collection name produces an anonymous array whose items retain their mapped object name.
- Child containers become nested objects.
- Repeated sibling names become arrays.
- Value nodes become JSON strings.
- Null nodes become JSON `null`.
- Attributes become ordinary properties.
- Attribute/element name conflicts preserve both as an array.

### YAML Output Adapter

Writer: `YamlOutputWriter`.

- Root node becomes a top-level mapping key.
- Inferred outer collections retain their sequence shape for zero, one, or many objects.
- A missing collection name produces an anonymous sequence whose items retain their mapped object name.
- Child containers become nested mappings.
- Repeated sibling names become sequences.
- Value nodes become quoted string scalars.
- Null nodes become YAML null.
- Attributes become ordinary properties.
- Attribute/element name conflicts preserve both as a sequence.

### TOML Output Adapter

Writer: `TomlOutputWriter`.

TOML shares hierarchy-to-map/list/scalar conversion with YAML, then uses its
own syntax-aware renderer:

- Child containers become TOML tables.
- Repeated object nodes become arrays of tables.
- Repeated scalar nodes become TOML arrays.
- Scalar hierarchy values are quoted strings, matching YAML's type-neutral output behavior.
- Attributes become ordinary properties.
- Null nodes become empty strings because TOML has no null literal.
- Synthetic anonymous top-level arrays are emitted under `item`, because a TOML document must have a table-shaped root.

### CSV and TSV Output Adapters

Writers: `CsvOutputWriter` and `TsvOutputWriter`. They share the same flattening
implementation and differ only in delimiter and format-specific error text.

CSV and TSV are flat formats, so hierarchy is flattened:

- If the root contains only one repeated child group, those children become records.
- Keyed top-level records also become records, including a single record.
- Otherwise, the root itself is written as one record.
- Nested scalar nodes become dotted column names.
- Repeated scalar and nested object nodes expand into rows, with parent values repeated on each row.
- Independent repeated sibling groups produce their Cartesian product because neither format has a nested value type.
- Cells never contain JSON arrays as a hierarchy fallback.
- Attributes become ordinary columns.
- Null nodes become empty cells.

### Markdown Output Adapter

Writer: `MarkdownOutputWriter`.

Markdown renders one fixed-width, human-readable table:

- If the root contains only one repeated child group, those children become table rows.
- Keyed top-level records also become table rows, including a single record.
- Otherwise, the root itself is written as one row.
- Nested scalar nodes become dotted column names.
- Repeated nodes expand into rows, with parent values repeated on each row.
- Attributes become ordinary columns.
- Null nodes become empty cells.
- Every column is 35 characters wide; longer content is truncated and shorter content is space-padded.
- Cell content is written literally without Markdown or HTML escaping.

## Known Limitations

- Operational configuration and task parameter files currently use UTF-8 Java `.properties` syntax; structured config files are not supported.
- `schema` and `xmlroot` metadata are parsed but do not currently perform validation or reshape output.
- DML expressions currently support one mapped source path per SQL expression.
- Core path support is simple path traversal, not full XPath, JSONPath, or YAMLPath.
- CSV and TSV output approximate nested repeated objects because neither has a native hierarchy.
- `--cache` input-structure tables do not infer parent-child joins. Use scalar fields present in the input for natural joins.

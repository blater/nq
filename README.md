[![Latest release](https://img.shields.io/github/v/release/blater/nql)](https://github.com/blater/nql/releases/latest)

NQL is a command-line tool for querying and moving data between JSON, YAML, TOML, XML, CSV, TSV files and relational databases.
It provides a familiar SQL language for querying JSON, YAML, TOML, XML, CSV, TSV, JSONL, and Parquet files, extracting complex
data structures into these formats from databases, and generally working with and reshaping data.

Full documentation: [NQL user manual](docs/user-manual.md).

## Install

| Platform | instructions | 
| --- | --- | 
| macOS ARM64 | `brew install blater/tap/nql` |
| Windows x64 | Run from an administrator powershell<br>with Chocolatey installed. <br>`irm https://raw.githubusercontent.com/blater/nql/master/util/chocolatey/install.ps1` |
| Lunux x64 | This installs the latest NQL package directly<br>from GitHub Releases. Rerun the same command to upgrade.<br>`curl -fsSL https://raw.githubusercontent.com/blater/nql/master/util/install-linux.sh` |


## What does it do?

You can run SQL queries directly against data files. For example given a json file 'users.json'
```json
    {
        "users": [
          {"id": 1, "name": "Alice", "active": true},
          {"id": 2, "name": "Bob", "active": false},
          {"id": 3, "name": "Charlie", "active": true}
        ],
        "address": [
          {"user_id": 1, "city": "New York"},
          {"user_id": 2, "city": "New York"},
          {"user_id": 3, "city": "London"}
        ]
    }
  }
```

Get all the active users from the file:

`nql "select name from users where active = 'true' order by id;" users.json`

` [{"name":"Alice"},{"name":"Charlie"}] `

You can use very complex selects. 
This is because NQL treats arrays as tables and fields as columns so you can run SQL over them as if the file is a database.

If your file has ID fields it can match on, then nql will also infer key relationships, allowing joins and subqueries:
```
  nql "select u.id, u.name, u.active, a.city
      from users u 
      join address a on a.user_id = u.id 
      order by u.id;" users.json 
```

If it can't infer then you can still tell it how to match fields with its `structure` keyword.

You can insert into a database from the file - this example inserts it into the 'appusers' table (we're using a local H2 database):
```
nql "insert into appusers (id, username, active) values ({users.id}, {users.name}, {users.active});"  \
    users.json --db h2 --database file:./users-db`
```

This updates the database `appusers` table:
```
nql "update appusers set isactive = {users.active} where id = {users.id};" \
    users.json --db h2 --database file:./users-db
```

You can also conveniently convert from one format to another.
```
nql users.json -o csv
nql customers.json -o yaml
nql customers.xml -o json
```

All of the commands operate on JSON/JSONL/YAML/ToML/XML/CSV/TSV input files and 
can output to JSON, YAML, XML, TOML, JSONL, CSV, TSV, and Markdown.


## Query a local H2 database

This complete example queries a local database and outputs YAML. 
The output format can be controlled with "output <format>" in the script or by using `-o <format>` on the command line.

```bash
nql "output yaml; select id, name, city from customer order by id;" \
    --db h2 --database file:./customer-demo -o yaml
```

```yaml
-
  id: "1"
  name: "Alice"
  city: "London"
-
  id: "2"
  name: "Bob"
  city: "Bristol"
```

Use the ***into*** clause to name fields or place individual values into specific places in the output. Note the use of SQL functions count and min - most SQL & complex constructs are supported:

```sql
select
  count(*) into {summary.customerCount},
  min(name) into {summary.firstCustomer}
from customer;
```

```json
{"summary":{"customerCount":2,"firstCustomer":"Alice"}}
```

## Updating a database from a JSON, XML, YAML, TOML, JSONL, CSV, or TSV file

you can use all the usual SQL commands to insert/update/delete database data from a file or stream:

Insert:
```bash
echo "person:{firstname:Barney, lastname:Rubble, city:London}" | \
    nql "insert into person (firstname, lastname, city) values ({person.firstName}, {person.lastName}, {person.city});" \
      -t yaml
```
Update:
```bash
echo "person:{id: 1, city:London}" | nql \
  "update person set city = {person.city} where personid = {person.id};" \
  -t yaml --config mydb.properties
```
Delete:
```bash
echo "<person><id>1</id></person>" | nql \
  "delete from person where personid = {person.id};" \
  -t xml
```

## Running literal sql 

When running nql against a database you can also run literal sql commands with the `literal` keyword.
For instance, create a table...

```bash
nql "
   literal drop table if exists person;

   literal create table person (
     personid integer auto_increment primary key,
     firstname varchar(80),
     lastname varchar(80),
     city varchar(80)
   );
" --db h2 --database file:./customer-demo
```


## Insert rows and return generated keys

An insert can write database-assigned values back into its input hierarchy.
This XML insert maps the generated key into `{person.id}`:

```bash
nql "output xml;
   insert into person (firstname, lastname, city)
   values ({person.firstName}, {person.lastName}, {person.city})
   returns personid into {person.id};" \
  -t xml \
  --db h2 \
  --database file:./customer-demo <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<person>
  <firstName>Alice</firstName>
  <lastName>Adams</lastName>
  <city>London</city>
</person>
XML
```

The returned XML contains the generated ID:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<person>
  <firstName>Alice</firstName>
  <lastName>Adams</lastName>
  <city>London</city>
  <id>3</id>
</person>
```

The `returns` keyword is also valid on updates when the database calculates 
timestamps, versions, or other values.

Stored-procedure calls, repeated child records, transactions, error policies,
and captured query rows are covered in the
[DML reference](docs/user-manual.md#dml-input-reference).


## Build nested documents from joined rows

A non-trivial example. Here we have 4 tables showing a customer and their orders on a ecommerce site:

| Table | Column | Type | Relationship |
| --- | --- | --- | --- |
| `customer` | `id` | integer | Primary key |
|  | `name` | varchar(80) |  |
| `address` | `id` | integer | Primary key |
|  | `customer_id` | integer | Foreign key → `customer.id` |
|  | `addr1` | varchar(80) |  |
|  | `city` | varchar(30) |  |
|  | `primary_residence` | char |  |
| `customer_order` | `id` | integer | Primary key |
|  | `customer_id` | integer | Foreign key → `customer.id` |
|  | `ordered_on` | date |  |
| `order_item` | `id` | integer | Primary key |
|  | `order_id` | integer | Foreign key → `customer_order.id` |
|  | `sku` | varchar(40) |  |
|  | `quantity` | integer |  |

**Customers**
| ID   | NAME  |
| ---- | ----- |
| 1    | Alice |
| 2    | Bob   |
| 3    | Yuki  |

**Address**
| ID   | CUSTOMER_ID | ADDR1              |  CITY       | PRIMARY_RESIDENCE  |
| ---- | ----------- | ------------------ | ----------- | ------------------ |
| 1    | 1           | 21 acacia drive    | Tokyo       | Y                  |
| 2    | 1           | 11 Downing Street  | London      | N                  |
| 3    | 2           | 1 George Street    | Sydney      | Y                  |

**Customer Order**
| ID   | CUSTOMER_ID | ORDERED_ON  |
| ---- | ----------- | ----------- |
| 1001 | 1           | 2026-07-01  |
| 1002 | 1           | 2026-07-15  |
| 1003 | 2           | 2026-07-20  |

**Order Item**
| ID     | ORDER_ID  | SKU         | QUANTITY    |
| ------ | --------- | ----------- | ----------- |
| 1      | 1001      | TEA         | 2           |
| 2      | 1001      | CAKE        | 1           |
| 3      | 1002      | MUG         | 2           |
| 4      | 1003      | COFFEE      | 1           |


We want to pull the data out a list of all customers and details of any orders they might have made. NQL shows JSON by default.
We will create a SQL query that joins all the tables together and uses "into" to define the output structure:

```sql
select
  c.name       into {customers.customer.name},
  a.city       into {customers.customer.city} absent on null,
  o.id         into {customers.customer.orders.order.order_id},
  o.ordered_on into {customers.customer.orders.order.date},
  i.sku        into {customers.customer.orders.order.items.item.product},
  i.quantity   into {customers.customer.orders.order.items.item.qty}
from customer c
left join address a on a.customer_id = c.id and a.primary_residence = 'Y'
left join customer_order o on o.customer_id = c.id
left join order_item i on i.order_id = o.id
;
```
Note the use of "absent on null" to suppress fields when the value is null.
The result contains each customer once and nests orders and items beneath it:

```json
{
  "customers": [
    {
      "customer": {
        "name": "Alice", "city": "Tokyo",
        "orders": {
          "order": [
            {
              "order_id": 1001, "date": "2026-07-01",
              "items": {
                "item": [
                  { "product": "TEA", "qty": 2 },
                  { "product": "CAKE", "qty": 1 }
                ]
              }
            },
            {
              "order_id": 1002, "date": "2026-07-15",
              "items": {
                "item": { "product": "MUG", "qty": 2 }
              }
            }
          ]
        }
      }
    },
    {
      "customer": {
        "name": "Bob", "city": "Sydney",
        "orders": {
          "order": {
            "order_id": 1003, "date": "2026-07-20",
            "items": {
              "item": {
                "product": "COFFEE", "qty": 1
              }
            }
          }
        }
      }
    },
    {
      "customer": {
        "name": "Yuki",
        "orders": {}
      }
    }
  ]
}

```

## CLI usage

NQL uses positional operands for the main inputs and flags for optional changes
or explicit disambiguation:

```text
nql [run] <script> [data] [options]
nql <data-file> [options]
nql convert [data] [options]
nql catalog [data] [pattern] [options]
nql cache load [data] [name] [options]
nql cache use <name> [options]
nql cache list [options]
nql cache clear (<name> | olderthan <age> | all) [options]
nql capabilities [-r <format>]
```

Common flag groups:

- `--cache [--name <cache-name>]` selects persistent cache execution. With data
  but no script it loads and activates a new cache; with a script it selects the
  active or named cache without automatically loading the data into it.
- `--cache-dir <path>` selects the directory that directly contains cache state,
  making independent runs easy to isolate.
- `-o, --output <format>` selects result-data output;
  `-r, --report-format <format>` selects operational reports. Result data
  defaults to JSON, catalog/cache reports default to Markdown, and the
  capability contract defaults to JSON.
- `nql capabilities` (or `nql --capabilities`) prints a versioned, side-effect-free
  discovery contract for agents and integrations.
- `--config <file.properties>` supplies operational NQL/JDBC settings.
  `--params-file <file.properties>` supplies parameters visible to scripts, and
  repeatable `--param <name=value>` entries override individual parameter values.

Run `nql help <command>` for command-specific syntax and examples.


### Connecting to a database

You can supply all the parameters on the command line

```bash
nql myscript.nql \
  --db postgresql \
  --host db.example.com \
  --port 5432 \
  --database sales \
  --user report_user \
  --password secret

```
or supply an NQL configuration file containing the connection details
```bash
nql myscript.nql --config mydatabase.properties
```
The [JDBC guide](docs/user-manual.md#jdbc-parameters) covers this in detail.

Native release builds include drivers for H2, PostgreSQL, MySQL, and MariaDB.
Additional driver profiles are available when building from source. The JVM
build can also load a driver JAR at runtime.


## Local caches speed repeated queries on the same large file

A query and input file use a temporary local database for that command. Use
`cache load` explicitly only when imported data should remain available to
later NQL commands:

```bash
nql cache load customers.json
nql catalog '*'
nql "select id, name from customers order by id;"
```

The [cache reference](docs/user-manual.md#querying-input-documents-temporary-and-persistent-h2)
covers storage, selection, inspection, and cleanup. NQL does not upload source
data or send usage telemetry.


## Stdin, diagnostics, and exit status

Piped, redirected, and here-document stdin is always data, never NQL script text.
It defaults to JSON unless `-t, --input-format` says otherwise. An explicit data
file or literal input takes precedence; if piped input is immediately detectable,
NQL warns that stdin is being ignored.

Bare `nql` prints brief usage instead of performing an identity JSON-to-JSON
conversion. Use `nql convert`, an output option such as `nql -o yaml`, or a script
when consuming stdin.

Result data and successful command reports are written to stdout. Warnings,
errors, and debug output are written to stderr. `--report-format` produces
versioned, structured catalog/cache/capability reports and structured diagnostics in the
selected JSON, JSONL, YAML, TOML, XML, CSV, TSV, or Markdown format.

Exit statuses are stable: `0` for success, `1` for execution failure, `2` for
invalid usage or configuration, and `130` when interrupted. See the
[automation guide](docs/automation.md) for scripting and CI examples.


## Where NQL fits

NQL is intended for work that crosses document and relational boundaries:

- exporting joined database data into a deliberate JSON, YAML, or XML shape;
- applying JSON, YAML, TOML, XML, CSV, TSV, or Parquet data through database DML;
- joining or aggregating related collections inside structured files;
- replacing one-off data movement code with a checked-in SQL-like script.

Use a focused tool when the task stays inside a simpler boundary:

- jq for JSON-native filtering and editing;
- yq or Dasel for direct YAML or document edits;
- Remarshal for guarded format conversion;
- Miller for record-stream processing;
- DuckDB or another SQL-over-file tool for primarily analytical, tabular work.

The [comparison guide](docs/comparison.md) describes these boundaries in more
detail.



## Documentation

- [Installation](docs/install.md)
- [Task-oriented recipes](docs/recipes/README.md)
- [User manual](docs/user-manual.md)
- [Automation and CI/CD](docs/automation.md)
- [Troubleshooting](docs/troubleshooting.md)
- [Frequently asked questions](docs/faq.md)

NQL is licensed under the [GNU Affero General Public License v3.0](LICENSE.txt).

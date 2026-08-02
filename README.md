
NQ is a command-line tool for querying and moving data between JSON, YAML, TOML, XML, CSV, TSV files and relational databases.
It provides a familiar SQL language for querying JSON, YAML, TOML, XML, CSV, TSV, JSONL, and Parquet files, extracting complex
data structures into these formats from databases, and generally working with and reshaping data.

## Install

| Platform | instructions | 
| --- | --- | 
| macOS ARM64 | `brew install blater/tap/nq` |
| Windows x64 | Run from an administrator powershell<br>with Chocolatey installed. <br>`irm https://raw.githubusercontent.com/blater/nq/master/util/chocolatey/install.ps1` | 
| Lunux x64 | This installs the latest NQ package directly<br>from GitHub Releases. Rerun the same command to upgrade.<br>`curl -fsSL https://raw.githubusercontent.com/blater/nq/master/util/install-linux.sh` |

## Getting started

You can run SQL directly against JSON/XML/Yaml.  
This filters for active users...

```bash
echo '{
  "users": [
    {"id": 1, "name": "Alice", "active": true},
    {"id": 2, "name": "Bob", "active": false},
    {"id": 3, "name": "Charlie", "active": true}
  ]
}' | nq "select name from users where active = 'true' order by id;"
```

```json
[{"name":"Alice"},{"name":"Charlie"}]
```

You can use sql functions like count, and you can specify the output structure using "into {a.document.path}"

```bash
echo '{
  "users": [
    {"id": 1, "name": "Alice", "active": true},
    {"id": 2, "name": "Bob", "active": false},
    {"id": 3, "name": "Charlie", "active": true}
   ]
}' | nq "select count(*) into {summary.activeUsers} from users where active = 'true';" 
```

```json
{"summary":{"activeUsers":"2"}}
```

## Select data as Yaml/JSON/XML from a database

Queries can be run against databases as well as directly against data files. 
Use the --output or -o option to specify the output format. 
It supports markdown, csv, tsv, yaml, toml, xml, jsonl, and json. The default output format is json.

```bash
nq "select id, name, city
    from customer
    order by id;" \
  --db h2 \
  --database file:./target/nq-readme \
  --output yaml
```

```yaml
- id: "1"
  name: Alice
  city: London
- id: "2"
  name: Bob
  city: Bristol
```


Use the ***into*** clause to name fields or place individual values into specific places in the output:
```sql
select
  count(*) into {summary.customerCount},
  min(name) into {summary.firstCustomer}
from customer;
```

```json
{"summary":{"customerCount":"2","firstCustomer":"Alice"}}
```

## Updating a database from a JSON, XML, YAML, TOML, JSONL, CSV, or TSV file

you can use all the usual SQL commands to insert/update/delete database data from a file or stream:

Insert:
```bash
echo "person:{firstname:Barney, lastname:Rubble, city:London}" | \
    nq "insert into person (firstname, lastname, city) values ({person.firstName}, {person.lastName}, {person.city});" 
```
Update:
```bash
echo "person:{id: 1, city:London}" | nq "update person set city = {person.city} where personid = {person.id};" -p mydb.properties
```
Delete:
```bash
echo "<person><id>1</id></person>" | nq -i xml  "delete from person where personid = {person.id};" 
```


### Connecting to a database

You can supply all the parameters on the command line

```bash
nq myscript.nq \
  --db postgresql \
  --host db.example.com \
  --port 5432 \
  --database sales \
  --user report_user
  --password secret

```
or supply the name of a properties file containing the connection details
```bash
nq myscript.nq -p mydatabase.properties
```
The [JDBC guide](docs/user-manual.md#jdbc-parameters) covers this in detail.


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


We want to pull the data out a list of all customers and details of any orders they might have made. Nq shows JSON by default.
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
              "order_id": "1001", "date": "2026-07-01",
              "items": {
                "item": [
                  { "product": "TEA", "qty": "2" },
                  { "product": "CAKE", "qty": "1" }
                ]
              }
            },
            {
              "order_id": "1002", "date": "2026-07-15",
              "items": {
                "item": { "product": "MUG", "qty": "2" }
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
            "order_id": "1003", "date": "2026-07-20",
            "items": {
              "item": {
                "product": "COFFEE", "qty": "1"
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

## Insert rows and return generated keys

An insert can write database-assigned values back into its input hierarchy.
This XML insert maps the generated key into `{person.id}`:

```bash
nq --input xml \
  "output xml;

   insert into person (firstname, lastname, city)
   values ({person.firstName}, {person.lastName}, {person.city})
   returns personid into {person.id};" \
  --db h2 \
  --database file:./target/nq-readme <<'XML'
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

The exact ID depends on the current database sequence. It is `3` after running
the tutorial from its setup step. `returns` is also available on updates when
the database calculates timestamps, versions, or other values.

Stored-procedure calls, repeated child records, transactions, error policies,
and captured query rows are covered in the
[DML reference](docs/user-manual.md#dml-input-reference).

## Supported formats

NQ reads these input formats:

| Format | Extensions or selection |
| --- | --- |
| JSON | `.json` or `--input json` |
| JSON Lines | `.jsonl` or `--input jsonl` |
| YAML | `.yaml`, `.yml`, or `--input yaml` |
| TOML | `.toml` or `--input toml` |
| XML | `.xml` or `--input xml` |
| CSV | `.csv` or `--input csv` |
| TSV | `.tsv` or `--input tsv` |
| Parquet | `.parquet` or `--input parquet` |

Output is available as JSON, JSON Lines, YAML, TOML, XML, CSV, TSV, or Markdown. JSON is
the default.

A file supplied without SQL is converted directly and does not create a
database:

```bash
nq customers.xml --output json
nq customers.json --output yaml
nq customers.csv --output markdown
```

A query and input file use a temporary local database for that command. Use
`--cache` or `-c` explicitly only when the imported data should remain
available to later NQ commands:

```bash
nq --cache customers.json
nq catalog
nq "select id, name from customers order by id;"
```

The [cache reference](docs/user-manual.md#querying-input-documents-temporary-and-persistent-h2)
covers storage, selection, inspection, and cleanup. NQ does not upload source
data or send usage telemetry.

Native release builds include drivers for H2, PostgreSQL, MySQL, and MariaDB.
Additional driver profiles are available when building from source. The JVM
build can also load a driver JAR at runtime.

NQ writes result data to stdout and diagnostics to stderr. Successful commands
return status `0`; invalid options, parsing failures, database connection
failures, and SQL execution failures return a non-zero status. See the
[automation guide](docs/automation.md) for scripting and CI examples.

## Where NQ fits

NQ is intended for work that crosses document and relational boundaries:

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

NQ is licensed under the [GNU Affero General Public License v3.0](LICENSE.txt).

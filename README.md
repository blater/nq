# NQ

NQ is a CLI tool that lets you use SQL to query JSON YAML, XML, CSV files, and to load and extract them from databases.
You can get it on a mac with `brew install blater/tap/nq`

The simplest thing you can do with it is query a JSON file using SQL (this also works for any of the other file formats).

Given a file `customers.json`:
```json
{"customers":[
  {"id":1,"name":"Alice","city":"London"},{"id":2,"name":"Bob","city":"Paris"},
  {"id":3,"name":"Eva","city":"London"}]}
```

  Select the customers from London:
```sql
  nq "select id, name from customers where city = 'London';" customers.json
```
  Result:
```json
  [{"id":"1","name":"Alice"},{"id":"3","name":"Eva"}]
```


### more advanced queries

You can use SQL functions and subqueries for more advanced searches, for example find the elements with the highest values in `elements.json`
```json
[ 
  {"elementId":1, "value": 1}, {"elementId": 2, "value": 3}, {"elementId": 3, "value": 3}, {"elementId": 4, "value": 2} 
]
```

```bash
nq 'select elementId into {id}, value from item where value in (select max(value) from item);' elements.json
```
result:

```json
[{"id":"2", "value":"3"},{"id":"3", "value":"3"}]
```

### anonymous arrays 

JSON and YAML files can have anonymous arrays, consider if the customers file didn't lable the array as "customers":
```json
[
  {"id":1,"name":"Alice","city":"London"},{"id":2,"name":"Bob","city":"Paris"},
  {"id":3,"name":"Eva","city":"London"}]}
]
```
How can you query this in NQ when there is no name for the array of customers? 
In this case NQ will automatically call the anonymous array ***"item"***.  So in this case your query would be `select id, name from item where city='London';`
If there are more anonymous arrays they'll get assigned item_1, item_2, etc. See the full manual for all the options around managing this.

### Extract data as JSON/XML/YAML from a database

You can extract JSON & other formats from a database, you can also insert/update a database from data files.

This example extracts customers into an

__table customers__

| id | name  | status     |
|---:|-------|------------|
| 1  | Alice | active     |
| 2  | Bob   | inactive   |
| 3  | Eva   | active     |

```bash
nq "select id as {customerId}, name as {firstname} from customers where status = 'active';" -p mydb.properties
```

Result:

```json
[{"customerId":"1","firstname":"Alice"},{"customerId":"3","firstname":"Eva"}]
```

If you want this as YAML instead then use the "--output yaml" flag on the commend line:

```bash
nq "select id as {customerId}, name as {firstname} from customer where status = 'active';" -p mydb.properties --output yaml
```

The `--output` flag works for `json`, `yaml`, `xml`, `csv`, `jsonl`, and `markdown` output formats.


### A hierachical extract

We can extract abitrary hierarchies. Lets look at a customers/invoices/invoice_items database:

#### customers
| customer_id | name |
|------|-------|
|  1   | Alice |
|  2   | Bob   |

#### customer_address
| customer_id | city | primary | zip |
|-------------|------|---------|-----|
|  1          | London   | Y   | SW11AA |
|  2          | New York | N   | 12345  |
|  2          | Tokyo    | Y   | XZY123 |

#### invoices
| invoice_id | customer_id |  issued_on  |
|----------|---------------|-------------|
|   1001   |  1            |  2026-07-01 |
|   1002   |  1            |  2026-07-15 |
|   1003   |  2            |  2026-07-20 |

#### invoice_items
 item_id | invoice_id | sku  | quantity | unit_price |
|--------|------------|--------|------|-------|
| 1      |   1001     | TEA    |  2   | 3.50 |
| 2      |   1001     | CAKE   |  1   | 4.25 |
| 3      |   1002     | MUG    |  2   | 8.00 |
| 4      |   1003     | COFFEE |  1   | 7.50 |

Lets pull this data out into a JSON document, except that in our output we want to put the customers' primary city into the top level 
customer object. This shows we don't have to rigidly follow the same hierarchy as in the database, we can transform it:

```sql
 select
    c.customer_id into {customers.customer.id},
    c.name        into {customers.customer.name},
    a.city        into {customers.customer.city},

    i.invoice_id  into {customers.customer.invoices.id},
    i.issued_on   into {customers.customer.invoices.issuedOn},

    li.item_id    into {customers.customer.invoices.items.id},
    li.sku        into {customers.customer.invoices.items.sku},
    li.quantity   into {customers.customer.invoices.items.quantity},
    li.unit_price into {customers.customer.invoices.items.unitPrice}

  from customers c
  left join customer_address a on a.customer_id = c.customer_id and a.primary='Y'
  left join invoices i on i.customer_id = c.customer_id
  left join invoice_items li on li.invoice_id = i.invoice_id
```

Output:
```json
{
    "customers": {
      "customer": [
        {
          "id": "1", "name": "Alice", "city": "London",
          "invoices": [
            {
              "id": "1001", "issuedOn": "2026-07-01",
              "items": [
                { "id": "1", "sku": "TEA", "quantity": "2", "unitPrice": "3.50" },
                { "id": "2", "sku": "CAKE", "quantity": "1", "unitPrice": "4.25" }
              ]
            },
            {
              "id": "1002", "issuedOn": "2026-07-15",
              "items": [
                { "id": "3", "sku": "MUG", "quantity": "2", "unitPrice": "8.00" }
              ]
            }
          ]
        },
        {
          "id": "2", "name": "Bob", "city": "Tokyo",
          "invoices": [
            {
              "id": "1003", "issuedOn": "2026-07-20",
              "items": [
                { "id": "4", "sku": "COFFEE", "quantity": "1", "unitPrice": "7.50" }
              ]
            }
          ]
        }
      ]
    }
  }
```


You can extract to any depth of hierachy.


### Applying data from a file to a database

This example uses values from an incoming JSON document to update the corresponding database record.
Note that this also works for YAML, JSONL, CSV, XML, with experimental support for Parquet files as well.

Given `person.json`:

```json
{
  "message": {
    "person": {
      "id": 7,
      "first_name": "Fred"
    }
  }
}
```

The script `update-person.nq` reads values through mapping paths:

```sql
update person
set first_name = {message.person.first_name}
where person_id = {message.person.id};
```

Apply it as follows (see the usage section for more about how to configure a db properties file with your connection information)

```bash
nq update-person.nq person.json -p database.properties
```

The row whose `person_id` is `7` is updated to use the name `Fred`. Equivalent YAML and XML structures can use the same mapping paths; CSV and Parquet inputs are also supported.


#### Inference and `structure`

There is some magic happening behind the scenes in the example above. NQ must decide when multiple SQL rows describe the same customer or 
child object.  By default it infers object identity and parent-child relationships from database metadata and naming conventions,
using primary, unique and composite keys where available.

This works most of the time, but what happens if your database has no keys or unique indexes defined, so there's no metadata for NQ to 
use?  It'll try to use common sense to match the names of columns then e.g. customer_id exists on both orders and customers table, 
so it knows which customer object to attach their orders to.  
Most mapped queries therefore need no `structure` clause. However, if the DB has no keys and no easily recognised naming convention then 
you can provide explicit directions to NQ via the ***"structure"*** keyword.

In this example `structure` declares the identity of the repeated objects:

```sql
select
  c.id    into {customers.customer.id},
  c.name  into {customers.customer.name},
  o.id    into {customers.customer.order.id},
  o.total into {customers.customer.order.total}
from customer c
left join customer_order o on o.customer_id = c.id
order by c.id, o.id
structure
  {customers.customer} key (c.id),
  {customers.customer.order} key (c.id, o.id);
```

The structure declarations state that rows with the same customer ID contribute to one customer, while rows with the same customer and order IDs contribute to one order. An explicit key overrides inference for that output path; if no key is declared or inferred, NQ preserves row-first output for the path.

### Produce a summary from nested data

This example joins related customer, address and verification data from a nested file, then produces a country-level summary for reporting or downstream processing.

The complete [`identity-customers.json`](docs/examples/identity-customers.json) contains customers with nested addresses and KYC records. A reduced excerpt shows the relevant shape:

```json
{
  "identity_data": {
    "customer": [
      {
        "id": "C2001",
        "address": [ 
          { "id": "A4001", "kind": "residential", "country_code": "GB" } 
        ],
        "kyc": { "id": "K5001", "status": "approved" }
      }
    ]
  }
}
```

[`identity-country-counts.nq`](docs/examples/identity-country-counts.nq) joins the generated cache tables, filters the source rows, aggregates them and maps the result:

```sql
select
  a.country_code into {result.region.country},
  count(distinct c.id) into {result.region.customerCount}
from customer c
inner join address a on a.customer_id = c.id
inner join kyc k on k.customer_id = c.id
where a.kind = 'residential'
  and k.status <> 'not_started'
group by a.country_code
structure {result.region} key (country_key);
```

Run it against the complete example:

```bash
nq identity-country-counts.nq identity-customers.json
```

Result:

```json
{"result":{
  "region":[
     {"country":"GB","customerCount":"2"},{"country":"US","customerCount":"4"}
   ]
 }
}
```

See the [NQ user manual](docs/user-manual.md) for the complete language and command-line reference, and [`docs/examples`](docs/examples/) for further runnable examples.

## Usage

The principal command forms are:

```text
nq <script-file-or-text> [input-file] [name=value ...] [options]
nq <input-file> [cache-options]
nq catalog [table-pattern] [options]
nq --use-cache <input-file-or-cache-filename> [cache-options]
nq --list-caches [cache-options]
nq --clear-cache [input-file-or-cache-filename] [cache-options]
```

A script can be a `.nq` file or one quoted inline argument. Options and positional arguments can appear in any unambiguous order.

### Files and caches

Input formats are selected by extension:

- `.json`
- `.jsonl`
- `.yaml` or `.yml`
- `.xml`
- `.csv`
- `.parquet`

For JSON and YAML, a named object collection becomes a table with that member
name: `{"customers":[...]}` creates `CUSTOMERS`, including when the collection
is nested. A top-level anonymous JSON array, JSON Lines file, or CSV file uses
the compatibility table `ITEM`. Use repeatable
`--relation-alias '<source-path>=<table-name>'` options to resolve name
collisions or name anonymous relations. If several anonymous paths would share
`ITEM`, NQ warns and merges them by default; use
`--anonymous-collections error` to require aliases instead. See the
[user manual](docs/user-manual.md#source-relation-names-and-aliases) for paths,
collision rules, and cache variants.

Supplying an input file on its own loads it into a persistent local H2 cache and makes that cache active:

```bash
nq customers.json
nq first-query.nq
nq second-query.nq
```

The default cache directory is `~/.nq/cache`. Common cache operations are:

```bash
nq --list-caches
nq --use-cache customers.json
nq --clear-cache customers.json
nq --clear-cache-older-than 7d
```

Use `--cache-dir <path>` to select another cache directory. `--use-cache` and targeted `--clear-cache` also accept a bare `cache-*.mv.db` filename, resolved under the selected cache directory.

Inspect the active cache or configured database with:

```bash
nq catalog
nq catalog customer
nq catalog 'audit*'
```

### Database connections

Connection details can be kept in a database properties file:

```properties
jdbc.driver=postgresql
jdbc.database=jdbc:postgresql://localhost:5432/customer_data
jdbc.username=report_user
jdbc.password=change-me
```
The supported logical driver names are `h2`, `mysql`, `mariadb`, `postgresql`, `oracle`, `sqlserver`, `db2`, `hana` and `informix`. Exact JDBC settings are available through `--jdbc-driver`, `--jdbc-class-name`, `--jdbc-database`, `--jdbc-username` and `--jdbc-password`.

You can specify the database properties in the nq command using the "-p filename" flag. e.g.:

```bash
nq myscript.nq -p mydbprops.properties
```

You can also provide connection details directly (though it's not recommended to use --password outside of a dev environment):

```bash
nq report.nq \
  --db mysql \
  --database customer_data \
  --host localhost \
  --user report_user \
  --password=change-me
```

### Output and help

Output defaults to JSON. Select another format in the script like this:

```sql
output xml;
```

You can also specify the output format on the command line with the "--output" flag
```bash
nq report.nq -p database.properties --output yaml
```
Supported output formats are JSON, JSON Lines, YAML, XML, CSV and Markdown. Select
JSON Lines with `--output jsonl` or `output jsonl;`; redirect stdout to a `.jsonl`
file when file output is required.

Use the built-in help for the current command-line reference:

```bash
nq -h
nq --help
nq --help cache
nq --help connection
```

## How to build

NQ requires JDK 25 and Maven. A GraalVM JDK with Native Image is required only for native executables.

### JVM build

Run the test suite and create the executable fat JAR:

```bash
mvn test
mvn package
```

Run the packaged application with:

```bash
java -jar target/nq-*.jar -h
```

The default `jdbc-common` profile includes H2, MySQL, MariaDB and PostgreSQL. Alternative driver sets are:

```bash
mvn -Pjdbc-common package
mvn -Pjdbc-enterprise package
mvn -Pjdbc-all package
```

- `jdbc-common`: H2, MySQL, MariaDB and PostgreSQL.
- `jdbc-enterprise`: Oracle, SQL Server, Db2, SAP HANA and Informix, plus H2 for cache support.
- `jdbc-all`: all common and enterprise drivers.

### Native build

With a GraalVM JDK and `native-image` available:

```bash
mvn -Pjdbc-common,native -DskipTests package
mvn -Pjdbc-enterprise,native -DskipTests package
mvn -Pjdbc-all,native -DskipTests package
```

The resulting executable names are:

- `nq` for `jdbc-common`
- `nq-enterprise` for `jdbc-enterprise`
- `nq-all` for `jdbc-all`

GitHub Actions builds the release executables for macOS ARM64, Linux x64 and
Windows x64. See the [release guide](docs/releases.md) for setup and publishing.

### Custom JDBC drivers

The JVM build can use a user-supplied JDBC driver JAR: put the NQ fat JAR, the driver JAR, and any driver dependencies on the Java classpath, invoke `blater.nq.Main`, and provide `--jdbc-class-name` and `--jdbc-database`. Native executables cannot load JARs at runtime. See [Supplying a JDBC Driver JAR](docs/user-manual.md#supplying-a-jdbc-driver-jar) for the full command and limitations.

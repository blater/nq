# NQ

NQ is a tool that lets you use SQL to query JSON YAML, XML, CSV files, and to load and extract them from databases.

It lets you query JSON using SQL.  Given customers.json:
```json
  [{"id":1,"name":"Alice","city":"London"},
   {"id":2,"name":"Bob","city":"Paris"},
   {"id":3,"name":"Eva","city":"London"}]
```

  Select the customers from London:
```sql
  nq "select id, name from item where city = 'London' order by id;" customers.json
```
  Result:
```json
  [{"id":"1","name":"Alice"},{"id":"3","name":"Eva"}]
```

You can use the normal SQL functions, for example find the elements with the highest values in `elements.json`
```json
[ 
  {"id":1, "value": 1}, {"id": 2, "value": 3}, {"id": 3, "value": 3}, {"id": 4, "value": 2} 
]
```

```bash
nq 'select id, value from item where a in (select max(a) from item);' elements.json
```
result:

```json
[{"id":"2", "value":"3"},{"id":"3", "value":"3"}]
```

### Get JSON from a database

You can extract JSON from a database, you can also insert/update a database from JSON.
Here's a simple extract example:

__table customer__

| id | name  | status     |
|---:|-------|------------|
| 1  | Alice | active     |
| 2  | Bob   | inactive   |
| 3  | Eva   | active     |

```bash
nq "select id into {customerId}, name into {firstname} from customer where status = 'active' order by id;" -p mydb.properties
```

Result:

```json
[{"customerId":"1","firstname":"Alice"},{"customerId":"3","firstname":"Eva"}]
```

If you want this as YAML instead then use the "--output yaml" flag on the commend line:

```bash
nq "select id into {customerId}, name into {firstname} from customer where status = 'active' order by id;" -p mydb.properties --output yaml
```

The `--output` flag works for `json`, `yaml`, `xml`, `csv`, `jsonl`, and `markdown` output formats.


### A hierachical extract

This example produces a customer document with each customer's orders nested underneath, ready to save as JSON, XML or YAML or pass to another application.

Given two tables in a DB, customers and orders:

***customers***
| customer_id | customer_name |
|------------:|---------------|
| 1           | Alice         |
| 2           | Bob           |
| 3           | Eva           |

***orders***
| customer_id | order_id | item_sku   | amount |
|------------:|----------|-----------:|-------:|
| 1           | 10       | B0K12345XY |   4.50 |
| 1           | 10       | C0K32199ZZ |  20.00 |
| 1           | 11       | B01M12345X |  13.00 |
| 2           | 12       | C0K32199ZZ |  20.00 |
| 2           | 12       | C0K32199ZZ |  20.00 |
| 2           | 13       | B0K99999AA |   5.00 |

 we want a json file of each customer as an object with an array of orders and totals underneath.

```sql
  select
    c.customer_id into {customers.customer.id},
    c.customer_name into {customers.customer.name},
    o.order_id into {customers.customer.orders.id},
    sum(o.amount) into {customers.customer.orders.total}
  from customers c
  left join orders o on o.customer_id = c.customer_id
  group by c.customer_id, c.customer_name, o.order_id
  order by c.customer_id, o.order_id;
```

  Expected JSON:
```json
  {
    "customers": {
      "customer": [
        {
          "id": "1",
          "name": "Alice",
          "orders": [
            {"id": "10", "total": "24.50"},
            {"id": "11", "total": "13.00"}
          ]
        },
        {
          "id": "2",
          "name": "Bob",
          "orders": [
            {"id": "12", "total": "40.00"},
            {"id": "13", "total": "5.00"}
          ]
        },
        {
          "id": "3",
          "name": "Eva"
        }
      ]
    }
  }
```

You can extract to any depth of hierachy.


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

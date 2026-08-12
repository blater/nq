# Map database joins to nested JSON

Tested with NQ 0.9.3. This self-contained recipe creates an in-memory H2
database, inserts customers, orders, and items, then maps the joined rows into a
three-level JSON hierarchy.

From the repository root:

```bash
nq run --script-file docs/recipes/database-to-nested-json/database-to-nested-json.nq \
  --db h2 --database mem:nq_database_to_nested
```

Compare stdout with [`expected.json`](expected.json). The `structure` clause
states the identity of each repeated output object. Real database scripts omit
the setup `literal` statements and use `-p database.properties` or connection
options instead.

Change the boundary output without changing the query:

```bash
nq run --script-file docs/recipes/database-to-nested-json/database-to-nested-json.nq \
  --db h2 --database mem:nq_database_to_yaml --output yaml
```

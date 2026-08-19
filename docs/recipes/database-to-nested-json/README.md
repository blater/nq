# Map database joins to nested JSON

Tested with NQL 0.9.3. This self-contained recipe creates an in-memory H2
database, inserts customers, orders, and items, then maps the joined rows into a
three-level JSON hierarchy.

From the repository root:

```bash
nql docs/recipes/database-to-nested-json/database-to-nested-json.nql \
  --db h2 --database mem:nql_database_to_nested
```

Compare stdout with [`expected.json`](expected.json). The `structure` clause
states the identity of each repeated output object. Real database scripts omit
the setup `literal` statements and use `--config database.properties` or connection
options instead.

Change the boundary output without changing the query:

```bash
nql docs/recipes/database-to-nested-json/database-to-nested-json.nql \
  --db h2 --database mem:nql_database_to_yaml -o yaml
```

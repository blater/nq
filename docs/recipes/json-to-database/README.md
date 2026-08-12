# Apply JSON to a database

Tested with NQ 0.9.3. This recipe creates a local H2 table, inserts a row from
JSON hierarchy paths, and verifies the stored row with a second NQ query.

From the repository root:

```bash
nq run --script-file docs/recipes/json-to-database/json-to-database.nq \
  --input-file docs/recipes/json-to-database/person.json \
  --db h2 --database file:./target/nq-json-to-database

nq run --script-text "select id as person_key,
    id into {result.person.id},
    first_name into {result.person.firstName},
    city into {result.person.city}
  from person
  structure {result.person} key (person_key);" \
  --db h2 --database file:./target/nq-json-to-database
```

The first command echoes the input hierarchy after applying it. Compare the
second command’s stdout with [`expected.json`](expected.json). The setup begins
with `drop table if exists`, so the recipe is safe to rerun against its dedicated
local example database.

For a real database, remove the setup `literal create table` statement and use
a protected properties file:

```bash
nq run --script-file import-person.nq --input-file person.json --properties database.properties
```

Equivalent YAML and XML documents can use the same logical paths; XML
attributes use `@` in the path.

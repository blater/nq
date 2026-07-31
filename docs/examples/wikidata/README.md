# Wikidata Company Query Examples

These scripts query `wikidata-companies.json` through NQ cache mode. For
example:

```bash
nq --cache docs/data/wikidata-row-first.nq docs/data/wikidata-companies.json
```

The examples use the cache tables created from the dataset:

- `company`
- `company_industries`
- `company_roles`

| Script | Demonstrates |
|---|---|
| `wikidata-row-first.nq` | One output object per SQL result row without structure keys. |
| `wikidata-keyed-companies.nq` | A keyed company object with keyed industry children. |
| `wikidata-composite-nested-keys.nq` | Composite role keys nested below keyed companies. |
| `wikidata-sibling-keys.nq` | Keyed industry and role siblings from one join-expanded result. |
| `wikidata-hierarchy-union.nq` | Explicit `hierarchy union` branch composition. |

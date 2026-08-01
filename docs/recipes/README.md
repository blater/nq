# NQ recipes

These task-oriented recipes for NQ — SQL for nested data use checked-in,
sanitized fixtures and scripts. Commands are exercised by the test suite.

## Start here

| Recipe | Input/source | Result | Typical time |
|---|---|---|---|
| [Summarize nested identity data](nested-file-summary/README.md) | Equivalent JSON, YAML, or XML documents | Aggregated nested JSON | Under one minute |
| [Map database joins to nested JSON](database-to-nested-json/README.md) | Self-contained H2 tables | Customers → orders → items | Under one minute |
| [Apply JSON to a database](json-to-database/README.md) | JSON message plus self-contained H2 | Inserted row plus verification output | Under one minute |

## More examples

- [Format-equivalent fixtures](../examples/formats/README.md)
- [Festival hierarchy reports](../examples/festival/person-reference-reports.md)
- [Wikidata hierarchy and key examples](../examples/wikidata/README.md)
- [jq comparison fixture](../examples/jq/README.md)

## Contribute a recipe

Recipes are a first-class contribution; Java changes are not required. Open an
issue or pull request with invented or safely sanitized data, include exact
expected output, and add an end-to-end test for the checked-in command.

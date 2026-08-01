# DQL Key Inference Design

## Contract

NQ builds a query-independent database graph from JDBC metadata and caches it through the existing persistent H2 cache. At the parser boundary, each mapped SELECT branch also records immutable syntax facts for its relation sources, direct-column expressions, grouping, `DISTINCT`, and aggregate classification. Query planning binds those facts and hierarchy paths to the graph before execution. Required keys that are absent from the user projection are added as hidden result columns and never appear in output.

Inference uses schema metadata only. It does not sample result rows, retry a query, or change cached decisions in response to data. DML does not use this feature.

`--no-key-inference` disables automatic DQL keys and preserves row-first mapping for paths without explicit structure. `--debug` logs the inferred output-path-to-relation mappings, selected key evidence, and any parent metadata relationships actually used by each query to stderr.

## Explicit Structure Precedence

`structure {path} key (...)` is authoritative for its exact hierarchy path. Its tuple is used verbatim: inference cannot add, remove, reorder, replace, or validate its expressions. Metadata remains available for undeclared ancestors, descendants, and siblings, including paths that use the same alias or base table.

This is deliberately path-scoped. A key can span several aliases, and one table can occur several times with different semantic roles. Table-wide suppression would therefore make partial structure declarations unpredictable. Use `--no-key-inference` when inference must be disabled for the whole query.

## Inferred Graph

The graph records visible non-system tables and views, their columns and types, primary keys, composite primary keys, unique indexes, foreign keys, conventional ID/key columns, type-compatible naming relationships, and logical composite keys for association tables. Candidate precedence is primary key, unique index, conventional key, then logical association key.

The implementation is one parser-to-runtime pipeline:

| Class | Responsibility |
|---|---|
| `KeyInference` | Feature entry point and cache/refresh orchestration |
| `QueryShapeExtractor` | Conservative parser-owned extraction of top-level query facts |
| `DatabaseStructureInferrer` | Query-independent JDBC graph discovery |
| `SchemaNamingHeuristics` | Deterministic naming and logical-key rules |
| `IdentifierNaming` | Shared normalized singular/plural vocabulary |
| `DatabaseStructure` | Immutable cached graph |
| `KeyInferencePlanner` | Owner binding, per-path identity selection, branch compatibility, and hidden-key selection |
| `OutputPlacementPolicy` | Named-versus-anonymous inferred item placement |
| `SelectBlueprint` | Raw SQL rendering and hidden-expression compilation |
| `Hierarchy` | Execution of explicit keyed placement and runtime null/conflict policy |
| `CompiledSelect` | Executable SQL and effective mapping plan |

`PersistentCache` remains the single persistent-cache implementation and the sole owner of cache naming. It assigns each cache a two-word jname such as `bright-otter.mv.db`, retrying if that name already exists. Source identity is stored in cache metadata and determines reuse. File-cache data and its inferred graph share that database; JDBC targets receive metadata-only databases under the same cache root. Database target identity is derived entirely from JDBC configuration, so choosing a cache file never opens or inspects the source database.

## Ambiguity and Safety

An explicit path has no identity ambiguity: its supplied tuple wins. Relation ownership is nevertheless bound for explicit paths before undeclared identities are selected, so an explicitly keyed parent can still contribute relationship evidence to an inferred descendant. For undeclared paths, the planner considers mapped expression lineage, aliases, parent relationships, declared relationship strength, candidate-key strength, and stable qualified ordering. Equal-strength choices are deterministic and produce a warning identifying possible data loss.

An all-null inferred key represents an absent joined object. A partially null inferred key uses row-local identity rather than coalescing unrelated rows. If a complete inferred key coalesces rows with conflicting scalar values, NQ retains the first value and warns once per affected path that data may have been lost. Existing explicit-key conflict behaviour is unchanged.

Identity and repetition placement are separate validated values. An explicit key always repeats the named node at its declared path. For compatibility, an outer inferred key normally identifies an anonymous item beneath its parent collection: `{res.festival.name}` forms collection `res`, object `festival`, and field `name`; `{festival.name}` uses an anonymous root collection. Descendant inferred keys repeat their named object paths.

One narrow convention recognizes an explicitly named collection/item pair when the owning relation and container share the same normalized plural name and the item is its normalized singular. For example, relation `customers` mapped through `{customers.customer.*}` repeats named `customer` items under `customers`. Ambiguous names retain the compatibility placement. The hierarchy executes this stored placement; it does not derive shape from key origin or keyed ancestry. XML supplies a synthetic `result` document element when no ordinary document root exists.

## Query Shapes

- Ordinary joins use the owning relation's preferred key.
- A grouped entity path uses the highest-precedence relation key whose complete tuple is structurally present in the grouping set. Other grouped columns do not become part of that identity.
- The complete grouping tuple is used only for a positively identified summary path: it has a mapped aggregate, every mapped non-aggregate expression is grouped, and no relevant fact is unknown.
- An ungrouped aggregate or an unclassified function call preserves singleton/row-first behaviour.
- `DISTINCT` queries retain their projected grain; inference does not add columns that change distinctness.
- Unsupported grouping, derived sources, unresolved syntax, and incompatible hierarchy-union branches fall back at the affected path with no query retry.

Absence is distinct from unsupported analysis: no grouping, known grouping, and unsupported grouping are separate states, as are a genuinely relation-free query and an unsupported source. Identifiers retain their quoted parts until metadata binding, and structural comparison uses token facts and resolved columns rather than reconstructed SQL text.

## Cache and CLI

The default graph expiry is 24 hours. `--metadata-expiry-hours N` persists a target-specific expiry; zero refreshes every use. `--metadata-refresh` rebuilds the selected JDBC or active input-cache target transactionally and exits. Direct JDBC cache identities include the configured URL, driver and username but never passwords. Input-cache identities include the normalized source path, input type and materialization variant. Cache validation consumes parser-owned base-relation references and preserves the distinction between a known empty source list and unsupported sources.

## Test Strategy

Tests use small H2 schemas and semantic hierarchy assertions. Coverage includes declared/composite/unique/logical keys, naming relationships, persistent graph artifacts, hidden omitted keys, nested parent/child coalescing, explicit-parent binding with inferred descendants, opt-out, grouped entity and summary paths, collection/item placement, all-null joined children, unknown query facts, conflict warnings, metadata commands, and regression coverage. Parser tests cover whitespace/comments, aliases, quoted identifier boundaries, comma relations, composite grouping, derived sources, and unsupported grouping. Tests avoid full generated-SQL snapshots, exhaustive spelling matrices, sleeps, and repeating every scenario for every output writer.

# Performance and practical limits

NQ does not currently publish a universal input-size limit or comparative speed
claim. Workload shape, format, nesting, available memory, temporary storage,
cache mode, database driver, query plan, and output hierarchy all affect
behaviour.

## Current execution model

- Document readers construct a neutral hierarchy and discover relations.
- File-query relations are materialized into temporary or persistent H2 tables.
- SQL is executed by H2 for file queries or by the configured JDBC database.
- Output mapping may retain identity state for repeated hierarchy paths.

This model favours expressive relational queries and repeatable caching. It is
not presented as a bounded-memory streaming engine. For very large analytical
files, benchmark an idiomatic DuckDB or dedicated columnar workflow as well.

## Planning a representative test

Use production-shaped, sanitized data and record:

- NQ version and artifact type;
- OS, architecture, CPU, memory, filesystem, and JDK where applicable;
- input format, compressed/uncompressed byte size, record counts, maximum
  nesting, and number of discovered relations;
- temporary versus persistent cache and whether the cache is warm;
- exact query, output format, output byte size, and row/object counts;
- wall time, peak resident memory, and cache disk size; and
- correctness validation, not just elapsed time.

## Practical guidance

- Use a temporary query when the file changes often and is queried once.
- Use a persistent cache when the same unchanged input is queried repeatedly.
- Add explicit `order by` when deterministic output order matters.
- Reduce selected columns and rows before building a large output hierarchy.
- Protect cache storage like the source data and ensure sufficient free space.
- Test the complete read → query → map → write workflow; query time alone can be
  misleading.

## Benchmark policy

Any published comparison must pin all tool versions, use public fixtures,
publish every command/script, choose idiomatic approaches for each tool, run
multiple measured iterations after stated warm-ups, validate equivalent output,
and state unsupported or incomparable dimensions. Results must not be
generalized beyond the measured workloads.

# Glossary

## Active cache

The persistent input cache selected for scripts that do not name an input file
or JDBC connection.

## Boundary format

A file or output representation—JSON, YAML, XML, CSV, JSON Lines, Parquet, or
Markdown—adapted to or from NQ’s neutral hierarchy.

## Cache

A local H2 database materialized from an input document. A temporary cache
exists for one command; a persistent cache can be reused.

## Capture

An in-memory rowset produced by a query and consumed by later mapped DML in the
same script.

## Hierarchy

NQ’s format-neutral tree of named, valued, and repeated nodes.

## Hierarchy path

A dotted mapping location such as `{customers.customer.id}`. It describes
where a value is read from or written to in the neutral hierarchy.

## Inferred identity

The key tuple NQ selects from metadata, relationships, naming, and query shape
to decide when SQL rows contribute to the same repeated output object.

## Mapping

The association between a SQL expression and a hierarchy path. Query output
uses `expression into {path}`; mapped DML reads `{path}` as a value.

## Materialization

The process of discovering collections in an input hierarchy and loading them
as related H2 tables for SQL queries.

## Relation

A query-visible table discovered from a repeated or object-shaped source path.

## Relation alias

An explicit source-path-to-table-name assignment supplied with
`--relation-alias`, used to name anonymous relations or resolve collisions.

## Structure key

An explicit identity tuple declared with `structure {path} key (...)`. Rows
with the same tuple contribute to one object at that output path.

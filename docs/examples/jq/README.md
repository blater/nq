# jq/NQL capability comparisons

These examples compare expression styles for data-query tasks that both tools
can perform. They are executable examples, not benchmarks, and do not imply
that one tool is generally preferable.

## Select every row tied for the maximum

The fixture [`elements.json`](elements.json) contains four objects with fields
`a` and `id`. Two objects tie for the maximum.

With jq:

```bash
jq -f docs/examples/jq/maximal.jq docs/examples/jq/elements.json
```

With NQL:

```bash
nql docs/examples/jq/maximal.nql docs/examples/jq/elements.json
```

Expected NQL output:

```json
[{"id":2},{"id":3}]
```

## Group and aggregate

[`regional-summary.jq`](regional-summary.jq) uses `group_by`, `map`,
`length`, and `add`. [`regional-summary.nql`](regional-summary.nql)
expresses the same operation directly with `group by`, `count`, and `sum`.

```bash
jq -f docs/examples/jq/regional-summary.jq docs/examples/jq/sales.json
nql docs/examples/jq/regional-summary.nql docs/examples/jq/sales.json
```

## Rank within each group

[`ranked-sales.jq`](ranked-sales.jq) groups and sorts the input, converts each
group to indexed entries, projects the rank, and flattens the groups.
[`ranked-sales.nql`](ranked-sales.nql) uses one `row_number()` window
expression.

```bash
jq -f docs/examples/jq/ranked-sales.jq docs/examples/jq/sales.json
nql docs/examples/jq/ranked-sales.nql docs/examples/jq/sales.json
```

## Join sibling collections

[`customer-totals.jq`](customer-totals.jq) captures the root document and
searches the purchase array for each customer.
[`customer-totals.nql`](customer-totals.nql) queries the inferred `customer`
and `purchase` tables with a normal left join. It also retains customers with
no purchases.

```bash
jq -f docs/examples/jq/customer-totals.jq docs/examples/jq/customer-orders.json
nql docs/examples/jq/customer-totals.nql docs/examples/jq/customer-orders.json
```

The NQL sides of all four comparisons are exercised by
`JqCapabilityComparisonE2ETest`.

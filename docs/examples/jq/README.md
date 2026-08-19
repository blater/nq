# Small jq/NQL syntax comparison

The fixture [`elements.json`](elements.json) contains four objects with fields
`a` and `id`. The task is to select every object whose `a` equals the maximum
value. Two objects tie.

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

This fixture compares the expression styles only. It is not a benchmark and
does not imply that one tool is generally preferable.

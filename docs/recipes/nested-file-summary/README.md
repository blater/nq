# Summarize nested identity data

Tested with NQ 0.9.3.

This recipe joins customer, residential-address, and verification collections
inside a nested document, then counts eligible customers by country.

From the repository root:

```bash
nq docs/examples/identity-country-counts.nq docs/examples/identity-customers.json
```

Expected output:

```json
{"result":{"region":[{"country":"GB","customerCount":"2"},{"country":"US","customerCount":"4"}]}}
```

Run the same script against equivalent boundary formats:

```bash
nq docs/examples/identity-country-counts.nq docs/examples/identity-customers.yaml
nq docs/examples/identity-country-counts.nq docs/examples/identity-customers.xml
```

All three produce the same JSON result. Inspect the
[`identity-country-counts.nq`](../../examples/identity-country-counts.nq) query
and [example-domain notes](../../examples/README.md).

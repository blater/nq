# Example data and scripts

All paths below are relative to the repository root. Promoted examples are
covered by end-to-end tests.

## Identity/customer/KYC documents

The same sanitized domain is available in three equivalent formats:

- [`identity-customers.json`](identity-customers.json)
- [`identity-customers.yaml`](identity-customers.yaml)
- [`identity-customers.xml`](identity-customers.xml)

They contain customer, address, authentication, and KYC relations. Run the
country summary against any boundary format:

```bash
nq docs/examples/identity-country-counts.nq docs/examples/identity-customers.json
nq docs/examples/identity-country-counts.nq docs/examples/identity-customers.yaml
nq docs/examples/identity-country-counts.nq docs/examples/identity-customers.xml
```

## Festival and people hierarchy

[`festival/festival-dataset.json`](festival/festival-dataset.json) contains two
separately rooted domain hierarchies: organisations/festivals and people.
Objects refer to people by ID. Each festival assigns rigging and catering teams,
venues, sessions, managers, hosts, and performers.

Load and activate the cache:

```bash
nq cache load docs/examples/festival/festival-dataset.json
```

Then run the reports described in
[`festival/person-reference-reports.md`](festival/person-reference-reports.md):

- [`festival/person-resource-chart.nq`](festival/person-resource-chart.nq)
- [`festival/person-festival-summary.nq`](festival/person-festival-summary.nq)
- [`festival/person-work-chart.nq`](festival/person-work-chart.nq)

## Format scenarios

[`formats/README.md`](formats/README.md) describes small JSON, YAML, and XML
fixtures that exercise numeric and temporal scalar handling.

## Wikidata

[`wikidata/README.md`](wikidata/README.md) describes hierarchy mapping and key
examples over a generated company dataset.

## jq comparison

[`jq/README.md`](jq/README.md) contains a deliberately small task expressed in
both jq and NQ. It demonstrates syntax, not a performance claim.

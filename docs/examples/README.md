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
nql docs/examples/identity-country-counts.nql docs/examples/identity-customers.json
nql docs/examples/identity-country-counts.nql docs/examples/identity-customers.yaml
nql docs/examples/identity-country-counts.nql docs/examples/identity-customers.xml
```

## Festival and people hierarchy

[`festival/festival-dataset.json`](festival/festival-dataset.json) contains two
separately rooted domain hierarchies: organisations/festivals and people.
Objects refer to people by ID. Each festival assigns rigging and catering teams,
venues, sessions, managers, hosts, and performers.

Load and activate the cache:

```bash
nql cache load docs/examples/festival/festival-dataset.json
```

Then run the reports described in
[`festival/person-reference-reports.md`](festival/person-reference-reports.md):

- [`festival/person-resource-chart.nql`](festival/person-resource-chart.nql)
- [`festival/person-festival-summary.nql`](festival/person-festival-summary.nql)
- [`festival/person-work-chart.nql`](festival/person-work-chart.nql)

## Format scenarios

[`formats/README.md`](formats/README.md) describes small JSON, YAML, and XML
fixtures that exercise numeric and temporal scalar handling.

## Wikidata

[`wikidata/README.md`](wikidata/README.md) describes hierarchy mapping and key
examples over a generated company dataset.

## jq comparison

[`jq/README.md`](jq/README.md) contains a deliberately small task expressed in
both jq and NQL. It demonstrates syntax, not a performance claim.

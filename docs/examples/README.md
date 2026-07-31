# Example Data Files

These files provide the same sample identity/customer/KYC domain in JSON, YAML, and XML:

- `identity-customers.json`
- `identity-customers.yaml`
- `identity-customers.xml`

The examples are shaped for `--cache` query mode. Object names are singular so generated cache tables are easy to query:

- `identity_data`
- `auth`
- `auth_user`
- `login`
- `customer`
- `email_address`
- `address`
- `kyc`
- `status_event`

Most customers have an `auth_user_id` that joins to `auth_user.id`; one customer intentionally has no login. KYC records include a current `status` and a nested `status_event` history for flow-style queries.

`identity-country-counts.nq` queries these files in cache mode and counts customers by residential address country, excluding customers whose KYC status is `not_started`.

## Independent hierarchies with person references

`person-reference-hierarchies.json` contains two separately rooted domain hierarchies and a people hierarchy. Objects refer to people by ID, and each of the five festivals assigns one rigging team and one catering team from the organisation hierarchy. Every festival's event manager is also a department manager.

```text
root
|-- organisation
|   `-- department[] (manager_person_id -> people.person.id)
|       `-- team[] (lead_person_id/member_person_ids -> people.person.id)
|-- festival[] (event_manager_person_id -> people.person.id)
|   |-- event_team[] (team_id -> organisation.department.team.id)
|   `-- venue[] (contact_person_id -> people.person.id)
|       `-- session[] (host_person_id/performer_person_ids -> people.person.id)
`-- people
    `-- person[]
        `-- address[]
```

See `person-reference-reports.md` for three reports and runnable NQ queries over this cache:

- `person-resource-chart.nq`
- `person-festival-summary.nq`
- `person-work-chart.nq`

# Report ideas for the person-reference example

Load and activate the example cache once:

```bash
nq cache load --input-file docs/examples/festival/festival-dataset.json
```

The following reports can then be run without repeating the input path or `--cache`.

## Resource allocation chart

This is a team-centred workload view for operations planning. It shows each festival-facing rigging or catering team, its department manager, team lead, allocated festivals, total festival-assignment count, and the lead and members assigned to each festival.

Useful questions include:

- Which teams have the highest festival workload?
- Which staff members inherit those assignments through team membership?
- Are rigging and catering assignments reasonably balanced?

```bash
nq run --script-file docs/examples/festival/person-resource-chart.nq
```

Each `festival` entry contains a keyed `staffMember` list. The role distinguishes the team lead from ordinary members, and the keys prevent join expansion from duplicating either festivals or staff.

## Festival summary

This is an event briefing organised by festival. It includes the event manager, the one rigging and one catering team, team leads and members, venues and contacts, and sessions with hosts and performers.

Useful questions include:

- Who owns the festival and each operational workstream?
- Which people and teams need to be included in the event briefing?
- Who is responsible for each venue and session?

```bash
nq run --script-file docs/examples/festival/person-festival-summary.nq
```

The query demonstrates several independently repeated, keyed branches under each festival.

## Individual person work chart

This is a person-centred workload view. It shows one person's team roles, festivals inherited through those teams, directly managed festivals, and headline counts for all three.

Useful questions include:

- How many teams and festivals involve this person?
- Is the person acting as a lead or member of each team?
- Which festivals do they manage directly rather than inherit through a team?

The query defaults to `PER001`; pass another ID as a runtime parameter:

```bash
nq run --script-file docs/examples/festival/person-work-chart.nq
nq run --script-file docs/examples/festival/person-work-chart.nq --param personId=PER004
```

The team and managed-festival paths are keyed independently so a person with several of each is not duplicated.

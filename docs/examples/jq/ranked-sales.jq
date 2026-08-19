group_by(.region)
| map(
    sort_by(-.amount, .rep)
    | to_entries
    | map({
        region: .value.region,
        rep: .value.rep,
        amount: .value.amount,
        sales_rank: (.key + 1)
      })
  )
| add

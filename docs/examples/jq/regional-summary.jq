group_by(.region)
| map({
    region: .[0].region,
    sale_count: length,
    revenue: (map(.amount) | add)
  })

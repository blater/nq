#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

../target/nq-common /dev/stdin --cache --cache-dir data/cache data/wikidata-companies.json <<'EOF'
  output json;
  select id into {companies.company.id},
        company_label into {companies.company.label},
        company_id into {companies.company.compid},
        country_label into {companies.company.country}
  from company
  order by id createsnew {companies.company}
;
EOF

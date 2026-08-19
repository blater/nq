package blater.nql.runner.inference;

import blater.nql.domain.MappingPlan;

/** SQL and hierarchy plan prepared before the query is executed. */
public record CompiledSelect(String sql, MappingPlan plan) {
}

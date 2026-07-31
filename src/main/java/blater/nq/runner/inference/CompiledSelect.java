package blater.nq.runner.inference;

import blater.nq.domain.MappingPlan;

/** SQL and hierarchy plan prepared before the query is executed. */
public record CompiledSelect(String sql, MappingPlan plan) {
}

package blater.nql.execution;

import blater.nql.cli.ExecutionTarget;

import java.util.Objects;

/** Resolves the automatic temporary-database/active-cache execution target. */
public final class ExecutionTargetResolver {
  public ExecutionTarget resolve(ExecutionTarget target, MaterializedInput input) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(input, "input");
    if (target instanceof ExecutionTarget.InputOrActiveCache automatic) {
      return input instanceof MaterializedInput.Provided
          ? new ExecutionTarget.Temporary()
          : new ExecutionTarget.ActiveCache(automatic.cacheDirectory());
    }
    return target;
  }
}

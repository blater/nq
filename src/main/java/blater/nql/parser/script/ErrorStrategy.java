package blater.nql.parser.script;

import blater.nql.core.parser.HiQLParser;
import lombok.Getter;

import java.util.List;

import static blater.nql.parser.script.ErrorBehaviourType.BEST_EFFORT;
import static blater.nql.parser.script.ErrorBehaviourType.FAIL;
import static blater.nql.util.ValueUtil.has;

// Responsibility: Stores per-statement handling for row problems
// and SQL execution errors.
@Getter
public class ErrorStrategy {
  private ErrorBehaviourType onProblemBehaviour;
  private boolean onProblemRollback;
  private ErrorBehaviourType onErrorBehaviour;
  private boolean onErrorRollback;

  public ErrorStrategy( ErrorBehaviourType statementProblemBehaviour, ErrorBehaviourType onErrorBehaviour) {
    this.onProblemBehaviour = statementProblemBehaviour;
    this.onErrorBehaviour = onErrorBehaviour;
  }

  /* on problem behaviour */
  public void setOnProblem( ErrorBehaviourType behaviour, boolean rollback) {
    onProblemBehaviour = behaviour;
    onProblemRollback = rollback;
  }

  /* on error behaviour */
  public void setOnError( ErrorBehaviourType behaviour, boolean rollback) {
    onErrorBehaviour = behaviour;
    onErrorRollback = rollback;
  }

  public static ErrorStrategy from(List<HiQLParser.HandlerBlockContext> handlers)
  {
    ErrorStrategy handling = new ErrorStrategy(FAIL, FAIL);
    if (handlers.isEmpty())
      return handling;

    for (var handlerBlock : handlers) {
      boolean shouldRollback = handlerBlock.handlerFlag().stream().anyMatch(flag -> flag.K_ROLLBACK() != null);
      boolean shouldFail = handlerBlock.handlerFlag().stream().anyMatch(flag -> flag.K_ABORT() != null);
      var behaviour = shouldFail ? FAIL :BEST_EFFORT;

      if (has(handlerBlock.K_ONERROR())) {
        handling.setOnError(behaviour, shouldRollback);
      } else {
        handling.setOnProblem(behaviour, shouldRollback);
      }
    }
    return handling;
  }
}

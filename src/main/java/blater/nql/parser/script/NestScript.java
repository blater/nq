package blater.nql.parser.script;

import blater.nql.outputwriter.OutputType;
import lombok.Value;

import java.util.List;

/*
 * Responsibility: Holds the parsed statement list.
 */
@Value
public class NestScript {
  OutputType outputType;
  List<NestStatement> statements;

  public NestScript(List<NestStatement> statements) {
    this(null, statements);
  }

  public NestScript(OutputType outputType, List<NestStatement> statements) {
    this.outputType = outputType;
    this.statements = statements;
  }

  public OutputType outputType() {
    return outputType;
  }

  public List<NestStatement> statements() {
    return statements;
  }
}

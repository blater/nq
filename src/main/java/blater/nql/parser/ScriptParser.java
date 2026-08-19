package blater.nql.parser;

import blater.nql.core.parser.HiQLLexer;
import blater.nql.core.parser.HiQLParser;
import blater.nql.outputwriter.OutputType;
import blater.nql.parser.script.ErrorStrategy;
import blater.nql.parser.script.NestScript;
import blater.nql.parser.script.NestStatement;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.util.Objects;

import static blater.nql.util.ValueUtil.has;

/*
 * Responsibility: Parses nql text into NestScript and dispatches
 * statement bodies to the focused statement builders.
 */
public class ScriptParser {

  public static NestScript parse(String inputScript) {
    HiQLParser parser = init(inputScript);
    var script = parser.script();
    var outputType = script.scriptItem().stream()
        .map(HiQLParser.ScriptItemContext::outputDirective)
        .filter(Objects::nonNull)
        .findFirst()
        .map(ScriptParser::outputType)
        .orElse(null);
    var statements = script.scriptItem().stream()
            .map(HiQLParser.ScriptItemContext::statementBlock)
            .filter(Objects::nonNull)
            .map(ScriptParser::buildStatement)
            .toList();
    return new NestScript(outputType, statements);
  }

  private static HiQLParser init(String input) {
    HiQLLexer lexer = new HiQLLexer(CharStreams.fromString(input));
    SyntaxErrorListener errorListener = new SyntaxErrorListener();
    lexer.removeErrorListeners();
    lexer.addErrorListener(errorListener);
    HiQLParser parser = new HiQLParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    parser.addErrorListener(errorListener);
    return parser;
  }

  private static NestStatement buildStatement(HiQLParser.StatementBlockContext statementBlock) {
    var body = statementBlock.body();
    var stmt =
        has(body.autoCommit()) ? NestStatement.autocommit(Boolean.toString(has(body.autoCommit().K_ON())))
      : has(body.catalog()) ? buildCatalog(body.catalog())
      : has(body.selectStatement()) ? SelectBuilder.buildSelect(body.selectStatement())
      : has(body.dmlUpdate()) ? DmlBuilder.buildDmlUpdate(body.dmlUpdate())
      : has(body.dmlInsert()) ? DmlBuilder.buildDmlInsert(body.dmlInsert())
      : has(body.dmlDelete()) ? DmlBuilder.buildDmlDelete(body.dmlDelete())
      : has(body.execProc()) ? DmlBuilder.buildExecProc(body.execProc())
      : has(body.literalSql()) ? buildLiteralSql(body.literalSql())
      : has(body.capture()) ? buildCapture(body.capture())
      : NestStatement.noop();

    stmt.setErrorHandling(ErrorStrategy.from(statementBlock.handlerBlock()));
    return stmt;
  }

  private static NestStatement buildCatalog(HiQLParser.CatalogContext ctx) {
    String tablePattern = has(ctx.catalogPattern()) ? ctx.catalogPattern().getText() : null;
    return NestStatement.catalog(ParseUtils.unquoteIdentifier(tablePattern));
  }

  private static NestStatement buildCapture(HiQLParser.CaptureContext ctx) {
    return NestStatement.capture(
        ParseUtils.unquoteString(ctx.STRING().getText()),
        ParseUtils.textOf(ctx.rawSql()).trim());
  }

  private static NestStatement buildLiteralSql(HiQLParser.LiteralSqlContext ctx) {
    return NestStatement.literal(ParseUtils.textOf(ctx.rawSql()).trim());
  }

  private static OutputType outputType(HiQLParser.OutputDirectiveContext ctx) {
    return OutputType.fromName(ctx.outputFormat().getText());
  }
}

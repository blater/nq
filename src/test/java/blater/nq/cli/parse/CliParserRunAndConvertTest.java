package blater.nq.cli.parse;

import blater.nq.cli.ConvertInvocation;
import blater.nq.cli.DataInput;
import blater.nq.cli.DataSourceSpec;
import blater.nq.cli.ExecutionTarget;
import blater.nq.cli.InputSelection;
import blater.nq.cli.OutputSelection;
import blater.nq.cli.RunInvocation;
import blater.nq.cli.ScriptSource;
import blater.nq.inputreader.InputType;
import blater.nq.outputwriter.OutputType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CliParserRunAndConvertTest {
  private final CliParser parser = new CliParser();

  @Test
  void parsesExplicitAndImplicitRunIntoTheSameTypedInvocation() {
    var explicit = assertInstanceOf(
        RunInvocation.class, parser.parse("run", "report.nq", "customers.json"));
    var implicit = assertInstanceOf(
        RunInvocation.class, parser.parse("report.nq", "customers.json"));

    assertEquals(explicit, implicit);
    assertScriptFile(explicit, "report.nq");
    assertInputFile(explicit.input(), "customers.json", InputType.JSON);
    assertInstanceOf(ExecutionTarget.Temporary.class, explicit.target());
  }

  @Test
  void commandWordsAreCaseInsensitive() {
    var invocation = assertInstanceOf(
        RunInvocation.class, parser.parse("RUN", "report.nq", "customers.json"));

    assertScriptFile(invocation, "report.nq");
    assertInputFile(invocation.input(), "customers.json", InputType.JSON);
  }

  @Test
  void scriptWithoutDataRunsAgainstTheActiveCache() {
    var invocation = assertInstanceOf(RunInvocation.class, parser.parse("report.nq"));

    assertScriptFile(invocation, "report.nq");
    var automatic = assertInstanceOf(InputSelection.Automatic.class, invocation.input());
    assertEquals(InputType.JSON, automatic.format());
    assertInstanceOf(ExecutionTarget.InputOrActiveCache.class, invocation.target());
    assertEquals(new OutputSelection.ScriptOrDefault(), invocation.output());
  }

  @Test
  void bindsLiteralScriptAndLiteralJsonDataInCanonicalOrder() {
    var invocation = assertInstanceOf(
        RunInvocation.class, parser.parse("select id from customer;", "{\"customer\":[]}"));

    var script = assertInstanceOf(ScriptSource.Text.class, invocation.script());
    assertEquals("select id from customer;", script.value());
    assertInputText(invocation.input(), "{\"customer\":[]}", InputType.JSON);
    assertInstanceOf(ExecutionTarget.Temporary.class, invocation.target());
  }

  @Test
  void acceptsReverseScriptAndDataOrderWhenExtensionsIdentifyTheirTypes() {
    var invocation = assertInstanceOf(
        RunInvocation.class, parser.parse("customers.JSON", "report.NQ"));

    assertScriptFile(invocation, "report.NQ");
    assertInputFile(invocation.input(), "customers.JSON", InputType.JSON);
  }

  @Test
  void namedAndPositionalOperandsFillDifferentTypedSlots() {
    var namedScript = assertInstanceOf(
        RunInvocation.class, parser.parse("--script-file", "report.nq", "customers.json"));
    assertScriptFile(namedScript, "report.nq");
    assertInputFile(namedScript.input(), "customers.json", InputType.JSON);

    var namedInput = assertInstanceOf(
        RunInvocation.class,
        parser.parse("--input-file", "customers.json", "select id from customer;"));
    var script = assertInstanceOf(ScriptSource.Text.class, namedInput.script());
    assertEquals("select id from customer;", script.value());
    assertInputFile(namedInput.input(), "customers.json", InputType.JSON);
  }

  @Test
  void cacheSelectionTargetsActiveOrNamedCacheWithoutChangingOperandBinding() {
    var active = assertInstanceOf(
        RunInvocation.class, parser.parse("report.nq", "customers.json", "--cache"));
    assertInstanceOf(ExecutionTarget.ActiveCache.class, active.target());
    assertInputFile(active.input(), "customers.json", InputType.JSON);

    var named = assertInstanceOf(
        RunInvocation.class,
        parser.parse("--name", "Customer_Cache", "customers.json", "report.nq", "--cache"));
    var target = assertInstanceOf(ExecutionTarget.NamedCache.class, named.target());
    assertEquals("customer_cache", target.name().value());
    assertInputFile(named.input(), "customers.json", InputType.JSON);
  }

  @Test
  void parsesImplicitAndExplicitFileConversion() {
    var implicit = assertInstanceOf(ConvertInvocation.class, parser.parse("customers.yaml"));
    var explicit = assertInstanceOf(ConvertInvocation.class, parser.parse("convert", "customers.yaml"));

    assertEquals(explicit, implicit);
    assertDataFile(implicit.input(), "customers.yaml", InputType.YAML);
    assertEquals(OutputType.JSON, implicit.output());
  }

  @Test
  void explicitConvertMakesAnUnclassifiedOperandLiteralData() {
    var invocation = assertInstanceOf(
        ConvertInvocation.class, parser.parse("convert", "{\"customer\":[]}"));

    assertDataText(invocation.input(), "{\"customer\":[]}", InputType.JSON);
    assertEquals(OutputType.JSON, invocation.output());
  }

  @Test
  void inputTextWithoutACommandImpliesConversion() {
    var invocation = assertInstanceOf(
        ConvertInvocation.class, parser.parse("--input-text", "{\"id\":1}"));

    assertDataText(invocation.input(), "{\"id\":1}", InputType.JSON);
  }

  @Test
  void operandlessConvertSelectsStandardInput() {
    var invocation = assertInstanceOf(ConvertInvocation.class, parser.parse("convert"));

    assertInstanceOf(DataSourceSpec.StandardInput.class, invocation.input().source());
    assertEquals(InputType.JSON, invocation.input().format());
  }

  @Test
  void emptyArgumentsSelectImplicitStandardInputConversion() {
    var invocation = assertInstanceOf(ConvertInvocation.class, parser.parse());

    assertInstanceOf(DataSourceSpec.StandardInput.class, invocation.input().source());
    assertEquals(InputType.JSON, invocation.input().format());
  }

  @Test
  void acceptsSpacedLongAndShortOutputOptionsInterspersedWithOperands() {
    var longOption = assertInstanceOf(
        ConvertInvocation.class, parser.parse("convert", "--output", "yaml", "customers.json"));
    var shortOption = assertInstanceOf(
        ConvertInvocation.class, parser.parse("convert", "customers.json", "-o", "YAML"));

    assertEquals(OutputType.YAML, longOption.output());
    assertEquals(longOption, shortOption);
  }

  @Test
  void delimiterTreatsACommandWordAsLiteralScriptText() {
    var invocation = assertInstanceOf(RunInvocation.class, parser.parse("--", "cache"));

    var script = assertInstanceOf(ScriptSource.Text.class, invocation.script());
    assertEquals("cache", script.value());
    assertInstanceOf(ExecutionTarget.InputOrActiveCache.class, invocation.target());
  }

  @Test
  void delimiterDoesNotDisableFilenameClassification() {
    var invocation = assertInstanceOf(RunInvocation.class, parser.parse("--", "report.nq"));

    assertScriptFile(invocation, "report.nq");
  }

  @Test
  void delimiterAllowsDashPrefixedLiteralConversionData() {
    var invocation = assertInstanceOf(
        ConvertInvocation.class, parser.parse("convert", "--", "-literal-data"));

    assertDataText(invocation.input(), "-literal-data", InputType.JSON);
  }

  private static void assertScriptFile(RunInvocation invocation, String path) {
    var source = assertInstanceOf(ScriptSource.File.class, invocation.script());
    assertEquals(Path.of(path), source.path());
  }

  private static void assertInputFile(
      InputSelection selection, String path, InputType inputType) {
    var provided = assertInstanceOf(InputSelection.Provided.class, selection);
    assertDataFile(provided.input(), path, inputType);
  }

  private static void assertInputText(
      InputSelection selection, String value, InputType inputType) {
    var provided = assertInstanceOf(InputSelection.Provided.class, selection);
    assertDataText(provided.input(), value, inputType);
  }

  private static void assertDataFile(DataInput input, String path, InputType inputType) {
    var source = assertInstanceOf(DataSourceSpec.File.class, input.source());
    assertEquals(Path.of(path), source.path());
    assertEquals(inputType, input.format());
  }

  private static void assertDataText(DataInput input, String value, InputType inputType) {
    var source = assertInstanceOf(DataSourceSpec.Text.class, input.source());
    assertEquals(value, source.value());
    assertEquals(inputType, input.format());
  }
}

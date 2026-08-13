package blater.nq.cli;

import blater.nq.inputreader.InputType;
import blater.nq.outputwriter.OutputType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InvocationModelTest {
  @Test
  void cacheNamesArePortableNormalizedAndReserved() {
    assertEquals("customer_2026", new CacheName("Customer_2026").value());
    assertThrows(IllegalArgumentException.class, () -> new CacheName("../customer"));
    assertThrows(IllegalArgumentException.class, () -> new CacheName("all"));
    assertThrows(IllegalArgumentException.class, () -> new CacheName("OLDERTHAN"));
  }

  @Test
  void invocationOptionsDefensivelyCopyParameters() {
    var mutable = new java.util.LinkedHashMap<>(Map.of("region", "EMEA"));
    var options = new InvocationOptions(
        mutable, false, ParquetOverrides.NONE);

    mutable.put("region", "APAC");

    assertEquals("EMEA", options.parameters().get("region"));
    assertThrows(UnsupportedOperationException.class,
        () -> options.parameters().put("year", "2026"));
  }

  @Test
  void runPreservesWhetherOutputWasExplicitlyOverridden() {
    var active = new ExecutionTarget.ActiveCache(Path.of("cache"));
    var inherited = new RunInvocation(
        new ScriptSource.File(Path.of("report.nq")),
        new InputSelection.None(), active, new OutputSelection.ScriptOrDefault(),
        false, InvocationOptions.EMPTY);
    var overridden = new RunInvocation(
        new ScriptSource.File(Path.of("report.nq")),
        new InputSelection.None(), active, new OutputSelection.Explicit(OutputType.JSON),
        false, InvocationOptions.EMPTY);

    assertEquals(new OutputSelection.ScriptOrDefault(), inherited.output());
    assertEquals(new OutputSelection.Explicit(OutputType.JSON), overridden.output());
  }

  @Test
  void temporaryTargetsRequireInput() {
    assertThrows(IllegalArgumentException.class, () -> new RunInvocation(
        new ScriptSource.Text("select 1;"), new InputSelection.None(),
        new ExecutionTarget.Temporary(), new OutputSelection.ScriptOrDefault(),
        false, InvocationOptions.EMPTY));

    var data = new DataInput(new DataSourceSpec.Text("{}"), InputType.JSON);
    var run = new RunInvocation(
        new ScriptSource.Text("select 1;"), new InputSelection.Provided(data),
        new ExecutionTarget.Temporary(), new OutputSelection.ScriptOrDefault(),
        false, InvocationOptions.EMPTY);
    assertEquals(new InputSelection.Provided(data), run.input());
  }
}

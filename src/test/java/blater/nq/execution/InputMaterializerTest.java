package blater.nq.execution;

import blater.nq.cli.DataInput;
import blater.nq.cli.DataSourceSpec;
import blater.nq.cli.ExecutionTarget;
import blater.nq.cli.InputSelection;
import blater.nq.inputreader.InputType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputMaterializerTest {
  @TempDir Path temporaryDirectory;
  private final InputMaterializer materializer = new InputMaterializer();

  @Test
  void terminalAutomaticInputFallsBackWithoutReadingStdin() throws IOException {
    InputStream unreadable = new InputStream() {
      @Override public int read() { throw new AssertionError("stdin was read"); }
    };
    var input = materializer.materialize(
        new InputSelection.Automatic(InputType.JSON),
        environment(unreadable, StdinDisposition.TERMINAL));

    assertInstanceOf(MaterializedInput.None.class, input);
    var target = new ExecutionTargetResolver().resolve(
        new ExecutionTarget.InputOrActiveCache(temporaryDirectory), input);
    assertInstanceOf(ExecutionTarget.ActiveCache.class, target);
  }

  @Test
  void redirectedAutomaticInputIsStagedAndSelectsTemporaryTarget() throws IOException {
    try (var input = materializer.materialize(
        new InputSelection.Automatic(InputType.YAML),
        environment(new ByteArrayInputStream("id: 1".getBytes()), StdinDisposition.REDIRECTED))) {
      var provided = assertInstanceOf(MaterializedInput.Provided.class, input);
      assertTrue(provided.path().toString().endsWith(".yaml"));
      assertEquals("id: 1", Files.readString(provided.path()));
      assertInstanceOf(ExecutionTarget.Temporary.class, new ExecutionTargetResolver().resolve(
          new ExecutionTarget.InputOrActiveCache(temporaryDirectory), input));
    }
  }

  @Test
  void emptyImplicitStdinMeansNoDataButEmptyExplicitStdinRemainsData() throws IOException {
    var implicit = materializer.materialize(
        new InputSelection.Automatic(InputType.JSON),
        environment(InputStream.nullInputStream(), StdinDisposition.REDIRECTED));
    assertInstanceOf(MaterializedInput.None.class, implicit);

    try (var explicit = materializer.materialize(
        new InputSelection.Provided(new DataInput(
            new DataSourceSpec.StandardInput(), InputType.JSON)),
        environment(InputStream.nullInputStream(), StdinDisposition.REDIRECTED))) {
      var provided = assertInstanceOf(MaterializedInput.Provided.class, explicit);
      assertEquals(0, Files.size(provided.path()));
    }
  }

  @Test
  void literalInputIsUtf8StagedAndOwned() throws IOException {
    Path staged;
    try (var input = materializer.materialize(
        new InputSelection.Provided(new DataInput(
            new DataSourceSpec.Text("name: café"), InputType.YAML)),
        environment(InputStream.nullInputStream(), StdinDisposition.TERMINAL))) {
      var provided = assertInstanceOf(MaterializedInput.Provided.class, input);
      staged = provided.path();
      assertEquals("name: café", Files.readString(staged));
    }
    assertFalse(Files.exists(staged));
  }

  @Test
  void fileInputIsBorrowedAndNeverDeleted() throws IOException {
    Path file = temporaryDirectory.resolve("data.json");
    Files.writeString(file, "{}");
    try (var ignored = materializer.materialize(
        new InputSelection.Provided(new DataInput(new DataSourceSpec.File(file), InputType.JSON)),
        environment(InputStream.nullInputStream(), StdinDisposition.TERMINAL))) {
      assertTrue(Files.exists(file));
    }
    assertTrue(Files.exists(file));
  }

  @Test
  void unknownDispositionFailsWithoutReadingStdin() {
    assertThrows(InputEnvironmentException.class, () -> materializer.materialize(
        new InputSelection.Automatic(InputType.JSON),
        environment(InputStream.nullInputStream(), StdinDisposition.UNKNOWN)));
  }

  private static InputEnvironment environment(InputStream input, StdinDisposition disposition) {
    return new InputEnvironment() {
      @Override public InputStream stdin() { return input; }
      @Override public StdinDisposition stdinDisposition() { return disposition; }
      @Override public boolean hasImmediatelyAvailableInput() { return false; }
    };
  }
}

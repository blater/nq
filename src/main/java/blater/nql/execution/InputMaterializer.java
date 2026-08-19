package blater.nql.execution;

import blater.nql.cli.DataInput;
import blater.nql.cli.DataSourceSpec;
import blater.nql.cli.InputSelection;
import blater.nql.inputreader.InputType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Resolves ambient stdin and stages non-file data for the existing readers. */
public final class InputMaterializer {
  public MaterializedInput materialize(
      InputSelection selection,
      InputEnvironment environment) throws IOException {
    Objects.requireNonNull(selection, "selection");
    Objects.requireNonNull(environment, "environment");
    return switch (selection) {
      case InputSelection.None ignored -> new MaterializedInput.None();
      case InputSelection.Provided provided -> materializeProvided(provided.input(), environment);
      case InputSelection.Automatic automatic -> materializeAutomatic(automatic, environment);
    };
  }

  private MaterializedInput materializeProvided(
      DataInput input,
      InputEnvironment environment) throws IOException {
    return switch (input.source()) {
      case DataSourceSpec.File file -> new MaterializedInput.Provided(
          input, file.path(), MaterializedInput.Ownership.BORROWED);
      case DataSourceSpec.Text text -> stageText(input, text.value());
      case DataSourceSpec.StandardInput ignored -> stageStream(input, environment.stdin(), false);
    };
  }

  private MaterializedInput materializeAutomatic(
      InputSelection.Automatic automatic,
      InputEnvironment environment) throws IOException {
    return switch (environment.stdinDisposition()) {
      case TERMINAL -> new MaterializedInput.None();
      case UNKNOWN -> throw new InputEnvironmentException(
          "Cannot determine whether standard input is interactive; send EOF or use an explicit data source");
      case REDIRECTED -> stageStream(
          new DataInput(new DataSourceSpec.StandardInput(), automatic.format()),
          environment.stdin(), true);
    };
  }

  private MaterializedInput stageText(DataInput input, String text) throws IOException {
    Path path = temporaryPath(input.format());
    boolean completed = false;
    try {
      Files.writeString(path, text, StandardCharsets.UTF_8);
      completed = true;
      return new MaterializedInput.Provided(
          input, path, MaterializedInput.Ownership.TEMPORARY);
    } finally {
      if (!completed) Files.deleteIfExists(path);
    }
  }

  private MaterializedInput stageStream(
      DataInput input,
      InputStream stream,
      boolean emptyMeansNoData) throws IOException {
    Path path = temporaryPath(input.format());
    boolean completed = false;
    long bytes;
    try (OutputStream output = Files.newOutputStream(path)) {
      bytes = stream.transferTo(output);
      completed = true;
    } finally {
      if (!completed) Files.deleteIfExists(path);
    }
    if (emptyMeansNoData && bytes == 0) {
      Files.deleteIfExists(path);
      return new MaterializedInput.None();
    }
    return new MaterializedInput.Provided(input, path, MaterializedInput.Ownership.TEMPORARY);
  }

  private static Path temporaryPath(InputType format) throws IOException {
    return Files.createTempFile("nql-input-", format.fileExtension());
  }
}

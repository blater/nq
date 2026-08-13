package blater.nq.execution;

import blater.nq.domain.Hierarchy;
import blater.nq.inputreader.InputReader;
import blater.nq.inputreader.InputType;

import java.util.Map;
import java.util.Objects;

import static blater.nq.execution.EngineParameterNames.INPUT_FILENAME;
import static blater.nq.execution.EngineParameterNames.INPUT_TYPE;

/** Loads the engine input using the CLI-resolved format when one is present. */
public final class EngineInputLoader {
  private EngineInputLoader() {
  }

  public static Hierarchy load(Map<String, String> parameters) {
    Objects.requireNonNull(parameters, "parameters");
    String filename = parameters.get(INPUT_FILENAME);
    if (filename == null || filename.isBlank()) {
      throw new IllegalArgumentException("Input data is required");
    }
    InputType type = parameters.containsKey(INPUT_TYPE)
        ? InputType.fromName(parameters.get(INPUT_TYPE))
        : InputType.fromFilename(filename);
    return InputReader.of(type).load(filename, parameters);
  }
}

package blater.nql.cli;

import java.nio.file.Path;
import java.util.Objects;

/** Source of an NQL script. */
public sealed interface ScriptSource permits ScriptSource.File, ScriptSource.Text {
  record File(Path path) implements ScriptSource {
    public File {
      Objects.requireNonNull(path, "path");
    }
  }

  record Text(String value) implements ScriptSource {
    public Text {
      Objects.requireNonNull(value, "value");
    }
  }
}

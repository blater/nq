package blater.nql.cli.parse;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Strict UTF-8 Java-properties loading for CLI configuration and parameters. */
final class CliPropertyFiles {
  private CliPropertyFiles() {
  }

  static Map<String, String> read(Path path, String purpose) {
    if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".properties")) {
      throw new CliUsageException(purpose + " file must use the .properties format: " + path);
    }
    DuplicateRejectingProperties properties = new DuplicateRejectingProperties(path, purpose);
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException ex) {
      throw new CliUsageException("Cannot read " + purpose + " file " + path + ": " + ex.getMessage(), ex);
    }
    Map<String, String> values = new LinkedHashMap<>();
    properties.forEach((name, value) -> values.put(name.toString(), value.toString()));
    return Map.copyOf(values);
  }

  private static final class DuplicateRejectingProperties extends Properties {
    private final Path path;
    private final String purpose;

    private DuplicateRejectingProperties(Path path, String purpose) {
      this.path = path;
      this.purpose = purpose;
    }

    @Override
    public synchronized Object put(Object key, Object value) {
      if (containsKey(key)) {
        throw new CliUsageException(
            "Duplicate key in " + purpose + " file " + path + ": " + key);
      }
      return super.put(key, value);
    }
  }
}

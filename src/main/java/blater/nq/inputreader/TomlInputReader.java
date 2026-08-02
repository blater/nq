package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.util.Log;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blater.nq.util.ValueUtil.hasValue;

public class TomlInputReader implements InputReader {
  private static final String SYNTHETIC_ROOT = "toml";

  @Override
  public Hierarchy load(String filename, Map<String, String> parameters) {
    if (!hasValue(filename)) {
      return new Hierarchy();
    }

    Path path = Path.of(filename);
    try {
      if (Files.size(path) == 0) {
        return new Hierarchy();
      }
      try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
        CommentedConfig result = TomlFormat.newConfig(LinkedHashMap::new);
        new TomlParser().parse(reader, result, ParsingMode.REPLACE);
        return StructuredDataInputMapper.toHierarchy(
            deepValue(result),
            SYNTHETIC_ROOT,
            parameters);
      }
    } catch (NoSuchFileException | AccessDeniedException e) {
      return Log.fatal(IllegalStateException.class, "Could not open input file: " + filename, e);
    } catch (ParsingException e) {
      return Log.fatal(IllegalStateException.class, "Malformed TOML input file: " + filename, e);
    } catch (IOException e) {
      return Log.fatal(IllegalStateException.class, "Could not read input file: " + filename, e);
    }
  }

  private static Object deepValue(Object value) {
    if (value instanceof UnmodifiableConfig table) {
      Map<String, Object> converted = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : table.valueMap().entrySet()) {
        converted.put(entry.getKey(), deepValue(entry.getValue()));
      }
      return converted;
    }
    if (value instanceof List<?> array) {
      List<Object> converted = new ArrayList<>(array.size());
      for (Object item : array) {
        converted.add(deepValue(item));
      }
      return converted;
    }
    return value;
  }
}

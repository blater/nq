package blater.nql.inputreader;

import blater.nql.domain.Hierarchy;
import blater.nql.util.Log;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

import static blater.nql.util.ValueUtil.hasValue;

public class YamlInputReader implements InputReader {
  private static final String SYNTHETIC_ROOT = "yaml";

  @Override
  public Hierarchy load(String filename, Map<String, String> parameters) {
    Object inputYaml = loadYamlFile(filename);
    return StructuredDataInputMapper.toHierarchy(inputYaml, SYNTHETIC_ROOT, parameters);
  }

  private static Object loadYamlFile(String filename) {
    if (!hasValue(filename)) {
      return null;
    }

    Path path = Path.of(filename);
    try {
      if (Files.size(path) == 0) {
        return null;
      }
      Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
      return yaml.load(Files.readString(path, StandardCharsets.UTF_8));
    } catch (NoSuchFileException | AccessDeniedException e) {
      return Log.fatal(IllegalStateException.class, "Could not open input file: " + filename, e);
    } catch (IOException e) {
      return Log.fatal(IllegalStateException.class, "Could not read input file: " + filename, e);
    } catch (YAMLException e) {
      return Log.fatal(IllegalStateException.class, "Malformed YAML input file: " + filename, e);
    }
  }

}

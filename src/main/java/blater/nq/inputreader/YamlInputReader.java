package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.util.Log;
import blater.nq.util.Template;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static blater.nq.util.ValueUtil.hasValue;

public class YamlInputReader implements InputReader {
  private static final String SYNTHETIC_ROOT = "yaml";
  private static final String ARRAY_ITEM = "item";

  @Override
  public Hierarchy load(String filename, Map<String, String> parameters) {
    Object inputYaml = loadYamlFile(filename);
    return yamlToHierarchy(inputYaml, parameters);
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

  private static Hierarchy yamlToHierarchy(Object inputYaml, Map<String, String> parameters) {
    if (inputYaml == null) {
      return new Hierarchy();
    }

    if (inputYaml instanceof Map<?, ?> object && object.size() == 1) {
      Map.Entry<?, ?> root = object.entrySet().iterator().next();
      return new Hierarchy(toNode(root.getKey().toString(), root.getValue(), parameters));
    }

    return new Hierarchy(toNode(SYNTHETIC_ROOT, inputYaml, parameters));
  }

  private static Node toNode(String name, Object value, Map<String, String> parameters) {
    Node node = new Node(name);

    if (value == null) {
      node.setNullValue(true);
      return node;
    }

    if (value instanceof Map<?, ?> object) {
      for (Map.Entry<?, ?> entry : object.entrySet()) {
        for (Node child : toChildNodes(entry.getKey().toString(), entry.getValue(), parameters)) {
          node.addNode(child);
        }
      }
      return node;
    }

    if (value instanceof List<?> array) {
      for (Object item : array) {
        Node child = toNode(ARRAY_ITEM, item, parameters);
        child.setArrayItem(true);
        node.addNode(child);
      }
      return node;
    }

    if (value instanceof String stringValue) {
      node.setValue(Template.expand(stringValue, parameters));
    } else {
      node.setValue(value.toString());
    }
    return node;
  }

  private static List<Node> toChildNodes(String name, Object value, Map<String, String> parameters) {
    if (!(value instanceof List<?> array)) {
      return List.of(toNode(name, value, parameters));
    }
    List<Node> nodes = new ArrayList<>(array.size());
    for (Object item : array) {
      Node node = toNode(name, item, parameters);
      node.setArrayItem(true);
      nodes.add(node);
    }
    return nodes;
  }
}

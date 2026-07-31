package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.util.Log;
import blater.nq.util.Template;

import java.io.IOException;
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

public class JsonInputReader implements InputReader {
  private static final String SYNTHETIC_ROOT = "json";
  private static final String ARRAY_ITEM = "item";

  @Override
  public InputDocument read(String filename, Map<String, String> parameters) {
    Object inputJson = loadJsonFile(filename);
    return toDocument(inputJson, parameters);
  }

  static InputDocument toDocument(Object inputJson, Map<String, String> parameters) {
    Hierarchy hierarchy = toHierarchy(inputJson, parameters);
    return new InputDocument(
        hierarchy,
        SourceStructure.fromStructured(inputJson, hierarchy, SYNTHETIC_ROOT));
  }

  private static Object loadJsonFile(String filename) {
    if (!hasValue(filename)) {
      return null;
    }

    Path path = Path.of(filename);
    try {
      if (Files.size(path) == 0) {
        return null;
      }
      return parse(Files.readString(path, StandardCharsets.UTF_8));
    } catch (NoSuchFileException | AccessDeniedException e) {
      return Log.fatal(IllegalStateException.class, "Could not open input file: " + filename, e);
    } catch (IOException e) {
      return Log.fatal(IllegalStateException.class, "Could not read input file: " + filename, e);
    } catch (JsonParseException e) {
      return Log.fatal(IllegalStateException.class, "Malformed JSON input file: " + filename, e);
    }
  }

  static Object parse(String json) {
    return new Parser(json).parse();
  }

  static Hierarchy toHierarchy(Object inputJson, Map<String, String> parameters) {
    if (inputJson == null) {
      return new Hierarchy();
    }

    if (inputJson instanceof Map<?, ?> object && object.size() == 1) {
      Map.Entry<?, ?> root = object.entrySet().iterator().next();
      return new Hierarchy(toNode(root.getKey().toString(), root.getValue(), parameters));
    }

    return new Hierarchy(toNode(SYNTHETIC_ROOT, inputJson, parameters));
  }

  @SuppressWarnings("unchecked")
  private static Node toNode(String name, Object value, Map<String, String> parameters) {
    Node node = new Node(name);

    if (value instanceof JsonScalar scalar) {
      if (scalar.nullValue()) {
        node.setNullValue(true);
      } else {
        node.setValue(scalar.templateString()
            ? Template.expand(scalar.value(), parameters)
            : scalar.value());
      }
      return node;
    }

    if (value instanceof Map<?, ?> object) {
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) object).entrySet()) {
        for (Node child : toChildNodes(entry.getKey(), entry.getValue(), parameters)) {
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
    return Log.fatal(IllegalArgumentException.class, "Unsupported JSON value for node: " + name);
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

  private record JsonScalar(String value, boolean templateString, boolean nullValue) {
    static JsonScalar string(String value) {
      return new JsonScalar(value, true, false);
    }

    static JsonScalar literal(String value) {
      return new JsonScalar(value, false, false);
    }

    static JsonScalar jsonNull() {
      return new JsonScalar(null, false, true);
    }
  }

  private static final class Parser {
    private final String json;
    private int pos;

    Parser(String json) {
      this.json = json;
    }

    Object parse() {
      Object value = parseValue();
      skipWhitespace();
      if (!atEnd()) {
        throw error("Unexpected trailing content");
      }
      return value;
    }

    private Object parseValue() {
      skipWhitespace();
      if (atEnd()) {
        throw error("Expected JSON value");
      }

      char ch = json.charAt(pos);
      return switch (ch) {
        case '{' -> parseObject();
        case '[' -> parseArray();
        case '"' -> JsonScalar.string(parseString());
        case 't' -> {
          consumeLiteral("true");
          yield JsonScalar.literal("true");
        }
        case 'f' -> {
          consumeLiteral("false");
          yield JsonScalar.literal("false");
        }
        case 'n' -> {
          consumeLiteral("null");
          yield JsonScalar.jsonNull();
        }
        default -> {
          if (ch == '-' || Character.isDigit(ch)) {
            yield JsonScalar.literal(parseNumber());
          }
          throw error("Expected JSON value");
        }
      };
    }

    private Map<String, Object> parseObject() {
      expect('{');
      Map<String, Object> object = new LinkedHashMap<>();
      skipWhitespace();
      if (peek('}')) {
        pos++;
        return object;
      }

      while (true) {
        skipWhitespace();
        if (!peek('"')) {
          throw error("Expected object key");
        }
        String key = parseString();
        skipWhitespace();
        expect(':');
        object.put(key, parseValue());
        skipWhitespace();
        if (peek('}')) {
          pos++;
          return object;
        }
        expect(',');
      }
    }

    private List<Object> parseArray() {
      expect('[');
      List<Object> array = new ArrayList<>();
      skipWhitespace();
      if (peek(']')) {
        pos++;
        return array;
      }

      while (true) {
        array.add(parseValue());
        skipWhitespace();
        if (peek(']')) {
          pos++;
          return array;
        }
        expect(',');
      }
    }

    private String parseString() {
      expect('"');
      StringBuilder value = new StringBuilder();
      while (!atEnd()) {
        char ch = json.charAt(pos++);
        if (ch == '"') {
          return value.toString();
        }
        if (ch == '\\') {
          value.append(parseEscape());
        } else {
          if (ch < 0x20) {
            throw error("Unescaped control character in string");
          }
          value.append(ch);
        }
      }
      throw error("Unterminated string");
    }

    private char parseEscape() {
      if (atEnd()) {
        throw error("Unterminated string escape");
      }
      char escaped = json.charAt(pos++);
      return switch (escaped) {
        case '"' -> '"';
        case '\\' -> '\\';
        case '/' -> '/';
        case 'b' -> '\b';
        case 'f' -> '\f';
        case 'n' -> '\n';
        case 'r' -> '\r';
        case 't' -> '\t';
        case 'u' -> parseUnicodeEscape();
        default -> throw error("Unsupported string escape");
      };
    }

    private char parseUnicodeEscape() {
      if (pos + 4 > json.length()) {
        throw error("Incomplete unicode escape");
      }
      int codepoint = 0;
      for (int idx = 0; idx < 4; idx++) {
        int digit = Character.digit(json.charAt(pos++), 16);
        if (digit < 0) {
          throw error("Invalid unicode escape");
        }
        codepoint = (codepoint << 4) + digit;
      }
      return (char) codepoint;
    }

    private String parseNumber() {
      int start = pos;
      if (peek('-')) {
        pos++;
      }
      parseIntegerPart();
      if (peek('.')) {
        pos++;
        parseDigits("Expected fraction digits");
      }
      if (peek('e') || peek('E')) {
        pos++;
        if (peek('+') || peek('-')) {
          pos++;
        }
        parseDigits("Expected exponent digits");
      }
      return json.substring(start, pos);
    }

    private void parseIntegerPart() {
      if (peek('0')) {
        pos++;
        if (!atEnd() && Character.isDigit(json.charAt(pos))) {
          throw error("Leading zeroes are not valid in JSON numbers");
        }
        return;
      }
      parseDigits("Expected integer digits");
    }

    private void parseDigits(String message) {
      int start = pos;
      while (!atEnd() && Character.isDigit(json.charAt(pos))) {
        pos++;
      }
      if (pos == start) {
        throw error(message);
      }
    }

    private void consumeLiteral(String literal) {
      if (!json.startsWith(literal, pos)) {
        throw error("Expected " + literal);
      }
      pos += literal.length();
    }

    private void expect(char expected) {
      skipWhitespace();
      if (!peek(expected)) {
        throw error("Expected '" + expected + "'");
      }
      pos++;
    }

    private boolean peek(char expected) {
      return !atEnd() && json.charAt(pos) == expected;
    }

    private void skipWhitespace() {
      while (!atEnd()) {
        char ch = json.charAt(pos);
        if (ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') {
          return;
        }
        pos++;
      }
    }

    private boolean atEnd() {
      return pos >= json.length();
    }

    private JsonParseException error(String message) {
      return new JsonParseException(message + " at character " + pos);
    }
  }

  static final class JsonParseException extends RuntimeException {
    JsonParseException(String message) {
      super(message);
    }
  }
}

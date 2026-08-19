package blater.nql.runner;

import blater.nql.util.Template;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateTest {
  @Test
  void systemPropertyFallsBackToDefaultValue() {
    String previous = System.getProperty("systemOnly");
    System.setProperty("systemOnly", "system");
    try {
      assertEquals(
          "system fallback ",
          Template.expand("${systemOnly} ${missing:fallback} ${missing}", Map.of()));
    } finally {
      if (previous == null) System.clearProperty("systemOnly");
      else System.setProperty("systemOnly", previous);
    }
  }

  @Test
  void preservesBackslashesThatDoNotEscapePlaceholders() {
    assertEquals(
        "path = 'C:\\temp\\file.txt'",
        Template.expand("path = 'C:\\temp\\file.txt'", Map.of()));
  }

  @Test
  void backslashEscapesPlaceholderExpansion() {
    assertEquals(
        "literal ${name}",
        Template.expand("literal \\${name}", Map.of("name", "expanded")));
  }

  @Test
  void leavesUnclosedPlaceholderTextLiteral() {
    assertEquals(
        "literal ${name",
        Template.expand("literal ${name", Map.of("name", "expanded")));
  }
}

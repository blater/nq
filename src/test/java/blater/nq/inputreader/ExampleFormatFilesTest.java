package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleFormatFilesTest {
  private static final Path FORMAT_EXAMPLES = Path.of("docs", "examples", "formats");

  @Test
  void loadsJsonFormatScenarios() {
    assertFormatScenariosLoad("format-scenarios.json", new JsonInputReader());
  }

  @Test
  void loadsYamlFormatScenarios() {
    assertFormatScenariosLoad("format-scenarios.yaml", new YamlInputReader());
  }

  @Test
  void loadsXmlFormatScenarios() {
    assertFormatScenariosLoad("format-scenarios.xml", new XmlInputReader());
  }

  private void assertFormatScenariosLoad(String filename, InputReader reader) {
    Hierarchy hierarchy = reader.load(FORMAT_EXAMPLES.resolve(filename).toString(), Map.of());

    Node root = hierarchy.getRoot();
    assertEquals("format_samples", root.getName());

    List<Node> scenarios = children(root, "scenario");
    assertEquals(2, scenarios.size());

    assertBasicScenario(scenarios.getFirst());
    assertLowResolutionScenario(scenarios.get(1));
  }

  private void assertBasicScenario(Node scenario) {
    assertEquals("fmt-basic", child(scenario, "id").getValue());

    Node numberNode = child(scenario, "number");
    assertEquals("42", child(numberNode, "integer_value").getValue());
    assertEquals("9007199254740993", child(numberNode, "large_integer_value").getValue());
    assertEquals("1234.56", child(numberNode, "decimal_value").getValue());
    assertEquals("-17.25", child(numberNode, "signed_decimal_value").getValue());
    assertEquals("0", child(numberNode, "zero_value").getValue());

    Node dateNode = child(scenario, "date");
    assertEquals("2026-07-06", child(dateNode, "calendar_date").getValue());
    assertEquals("2026-07", child(dateNode, "month_date").getValue());
    assertEquals("2026-187", child(dateNode, "ordinal_date").getValue());

    Node dateTimeNodes = child(scenario, "datetime");
    assertEquals("2026-07-06T11:08", child(dateTimeNodes, "local_minutes").getValue());
    assertEquals("2026-07-06T11:08:30", child(dateTimeNodes, "local_seconds").getValue());
    assertEquals("2026-07-06T11:08:30.250", child(dateTimeNodes, "local_millis").getValue());
    assertEquals("2026-07-06T10:08:30Z", child(dateTimeNodes, "utc_seconds").getValue());
    assertEquals("2026-07-06T10:08:30.250Z", child(dateTimeNodes, "utc_millis").getValue());
    assertEquals("2026-07-06T12:08:30+02:00", child(dateTimeNodes, "offset_seconds").getValue());
    assertEquals("2026-07-06T12:08:30.250+02:00", child(dateTimeNodes, "offset_millis").getValue());

    Node timeNode = child(scenario, "time");
    assertEquals("11:08", child(timeNode, "local_minutes").getValue());
    assertEquals("11:08:30", child(timeNode, "local_seconds").getValue());
    assertEquals("11:08:30.250", child(timeNode, "local_millis").getValue());
    assertEquals("10:08:30Z", child(timeNode, "utc_seconds").getValue());
    assertEquals("12:08:30+02:00", child(timeNode, "offset_seconds").getValue());
    assertEquals("12:08:30.250+02:00", child(timeNode, "offset_millis").getValue());
  }

  private void assertLowResolutionScenario(Node scenario) {
    assertEquals("fmt-low-resolution", child(scenario, "id").getValue());

    Node number = child(scenario, "number");
    assertEquals("7", child(number, "integer_value").getValue());
    assertEquals("100.0", child(number, "decimal_value").getValue());
    assertEquals("-0.5", child(number, "signed_decimal_value").getValue());
    assertEquals("0", child(number, "zero_value").getValue());

    Node date = child(scenario, "date");
    assertEquals("2024-02-29", child(date, "calendar_date").getValue());

    Node datetime = child(scenario, "datetime");
    assertEquals("2024-02-29", child(datetime, "local_date_only_style").getValue());
    assertEquals("2024-02-29T09:05", child(datetime, "local_minutes").getValue());
    assertEquals("2024-02-29T09:05Z", child(datetime, "utc_minutes").getValue());
    assertEquals("2024-02-29T09:05-05:00", child(datetime, "offset_minutes").getValue());

    Node time = child(scenario, "time");
    assertEquals("09:05", child(time, "local_minutes").getValue());
    assertEquals("09:05Z", child(time, "utc_minutes").getValue());
    assertEquals("09:05-05:00", child(time, "offset_minutes").getValue());
  }

  private Node child(Node parent, String name) {
    List<Node> matches = children(parent, name);
    assertTrue(!matches.isEmpty(), "Missing child [" + name + "] below [" + parent.getName() + "]");
    return matches.getFirst();
  }

  private List<Node> children(Node parent, String name) {
    return parent.getChildren().stream()
        .filter(child -> child.getName().equals(name))
        .toList();
  }
}

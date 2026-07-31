package blater.nq.inputreader;

import blater.nq.util.Template;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateParametersAdapterTest {
  @Test
  void expandsInputDataReferencesAndDefaultValues() {
    assertEquals(
        "where firstname like 'A%' and surname = 'Smith'",
        Template.expand(
            "where firstname like '${root.namePrefix}%' and surname = '${root.surname:Smith}'",
            Map.of("root.namePrefix", "A")));
  }
}

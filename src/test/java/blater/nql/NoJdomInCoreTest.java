package blater.nql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoJdomInCoreTest {
  @Test
  void jdomImportsStayInXmlAdapters() throws IOException {
    Path root = Path.of("src/main/java/blater/nql");

    List<String> offenders = Files.walk(root)
        .filter(Files::isRegularFile)
        .filter(path -> path.toString().endsWith(".java"))
        .filter(path -> containsJdomImport(path) && !isAllowedXmlAdapter(path))
        .map(root::relativize)
        .map(Path::toString)
        .sorted()
        .toList();

    assertEquals(List.of(), offenders);
  }

  private static boolean containsJdomImport(Path path) {
    try {
      return Files.readString(path).contains("org.jdom2");
    } catch (IOException e) {
      throw new IllegalStateException("Could not read source file: " + path, e);
    }
  }

  private static boolean isAllowedXmlAdapter(Path path) {
    String normalized = path.toString().replace('\\', '/');
    return normalized.contains("/inputreader/xml/")
        || normalized.endsWith("/inputreader/XmlInputReader.java")
        || normalized.contains("/outputwriter/xml/")
        || normalized.endsWith("/outputwriter/XmlOutputWriter.java");
  }
}

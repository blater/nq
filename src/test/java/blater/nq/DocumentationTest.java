package blater.nq;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentationTest {
  @Test
  void markdownHasBalancedFencesAndValidTables() throws Exception {
    List<String> errors = new ArrayList<>();
    for (Path file : markdownFiles()) {
      List<String> lines = Files.readAllLines(file);
      long fenceCount = lines.stream().filter(line -> line.stripLeading().startsWith("```")).count();
      if (fenceCount % 2 != 0) {
        errors.add(file + ": unbalanced fenced code blocks");
      }

      for (int index = 0; index < lines.size(); index++) {
        String line = lines.get(index);
        if (isTableSeparator(line)) {
          if (index == 0 || !lines.get(index - 1).strip().startsWith("|")) {
            errors.add(file + ":" + (index + 1) + ": table separator has no header row");
          } else if (tableCellCount(line) != tableCellCount(lines.get(index - 1))) {
            errors.add(file + ":" + (index + 1) + ": table header/separator column mismatch");
          }
        }
      }
    }
    assertTrue(errors.isEmpty(), () -> String.join(System.lineSeparator(), errors));
  }

  private List<Path> markdownFiles() throws IOException {
    try (Stream<Path> paths = Files.walk(Path.of("."))) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".md"))
          .filter(path -> !path.startsWith(Path.of("./target")))
          .filter(path -> !path.startsWith(Path.of("./.git")))
          .sorted()
          .toList();
    }
  }

  private boolean isTableSeparator(String line) {
    String stripped = line.strip();
    return stripped.startsWith("|")
        && stripped.endsWith("|")
        && stripped.matches("\\|(?:\\s*:?-{3,}:?\\s*\\|)+");
  }

  private int tableCellCount(String line) {
    return (int) line.chars().filter(character -> character == '|').count() - 1;
  }

}

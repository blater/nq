package blater.nq;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentationTest {
  private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[[^]]*]\\(([^)]+)\\)");
  private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*\\s*$");
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

  @Test
  void relativeMarkdownLinksAndAnchorsResolve() throws Exception {
    List<String> errors = new ArrayList<>();
    Map<Path, Set<String>> anchorsByFile = new HashMap<>();
    for (Path file : markdownFiles()) {
      List<String> lines = Files.readAllLines(file);
      for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
        var matcher = MARKDOWN_LINK.matcher(lines.get(lineNumber));
        while (matcher.find()) {
          String destination = matcher.group(1).strip();
          if (destination.startsWith("http://")
              || destination.startsWith("https://")
              || destination.startsWith("mailto:")) {
            continue;
          }
          if (destination.startsWith("<") && destination.endsWith(">")) {
            destination = destination.substring(1, destination.length() - 1);
          }
          String[] parts = destination.split("#", 2);
          Path target = parts[0].isBlank()
              ? file
              : file.getParent().resolve(parts[0]).normalize();
          if (!Files.exists(target)) {
            errors.add(file + ":" + (lineNumber + 1) + ": missing link target " + destination);
            continue;
          }
          if (parts.length == 2 && !parts[1].isBlank() && Files.isRegularFile(target)) {
            Set<String> anchors = anchorsByFile.computeIfAbsent(target, this::anchors);
            if (!anchors.contains(parts[1].toLowerCase(Locale.ROOT))) {
              errors.add(file + ":" + (lineNumber + 1) + ": missing anchor " + destination);
            }
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

  private Set<String> anchors(Path file) {
    Set<String> anchors = new HashSet<>();
    Map<String, Integer> duplicates = new HashMap<>();
    try {
      for (String line : Files.readAllLines(file)) {
        var matcher = HEADING.matcher(line);
        if (!matcher.matches()) {
          continue;
        }
        String base = githubSlug(matcher.group(1));
        int duplicate = duplicates.merge(base, 1, Integer::sum) - 1;
        anchors.add(duplicate == 0 ? base : base + "-" + duplicate);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read Markdown anchors from " + file, exception);
    }
    return anchors;
  }

  private String githubSlug(String heading) {
    return heading
        .toLowerCase(Locale.ROOT)
        .replaceAll("<[^>]+>", "")
        .replaceAll("[`*_~]", "")
        .replaceAll("[^\\p{L}\\p{N} _-]", "")
        .strip()
        .replace(' ', '-');
  }
}

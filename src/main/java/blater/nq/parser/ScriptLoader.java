package blater.nq.parser;

import blater.nq.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

/*
 * Responsibility: Loads a HiQL script and expands include and
 * submapping directives as textual inclusion.
 */
public class ScriptLoader {
  private static final Pattern INCLUDE_DIRECTIVE = Pattern.compile(
      "(?i)\\b(include|submapping)\\s+'([^']+)'\\s*;");

  public static String load(String filename) throws IOException {
    return loadAndExpand(Paths.get(filename), new LinkedHashSet<>());
  }

  public static String loadText(String scriptText) {
    return expandText(scriptText, Paths.get(".").toAbsolutePath().normalize(), new LinkedHashSet<>());
  }

  private static String loadAndExpand(Path file, Set<Path> visiting) {
    String contents = null;
    Path filePath = file.toAbsolutePath().normalize();
    if (visiting.contains(filePath)) {
      Log.fatal(IllegalStateException.class, "Circular include detected " + filePath);
    }
    visiting.add(filePath);
    try {
      contents = expandText(Files.readString(filePath, UTF_8), filePath.getParent(), visiting);
    } catch (FileNotFoundException fe) {
      Log.fatal(IllegalStateException.class, "cannot find file " + filePath + ". " + fe.getMessage());
    } catch (IOException ie) {
      Log.fatal(IllegalStateException.class, "error opening file " + filePath + ". " + ie.getMessage());
    } finally {
      visiting.remove(filePath);
    }
    return contents;
  }

  private static String expandText(String fileContents, Path baseDir, Set<Path> visiting) {
    Matcher matcher = INCLUDE_DIRECTIVE.matcher(fileContents);
    StringBuilder out = new StringBuilder();
    int last = 0;
    while (matcher.find()) {
      out.append(fileContents, last, matcher.start());
      String included = matcher.group(2);
      Path target = baseDir != null ? baseDir.resolve(included) : Paths.get(included);
      out.append(loadAndExpand(target, visiting));
      last = matcher.end();
    }
    out.append(fileContents, last, fileContents.length());
    return out.toString();
  }
}

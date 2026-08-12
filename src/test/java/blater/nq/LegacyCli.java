package blater.nq;

import blater.nq.inputreader.InputType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Keeps pre-1.0 behavioral tests readable without exposing the ambiguous CLI in production. */
final class LegacyCli {
  private static final List<String> VALUE_OPTIONS = List.of(
      "-p", "-i", "--input", "-o", "--output", "--cache-dir",
      "--clear-cache-older-than", "--use-cache", "--parquet-root", "--parquet-record",
      "--db", "--database", "--host", "--port", "--user", "--password",
      "--jdbc-driver", "--jdbc-class-name", "--jdbc-database", "--jdbc-username",
      "--jdbc-password");

  private LegacyCli() {
  }

  static void main(String... args) throws Exception {
    Main.main(expand(args));
  }

  static Map<String, String> parse(String... args) {
    return ParameterParser.parse(expand(args));
  }

  private static String[] expand(String... args) {
    List<String> options = new ArrayList<>();
    List<String> positionals = new ArrayList<>();
    boolean cache = false;
    String stdinFormat = null;
    String clearTarget = null;
    String clearAge = null;
    String useCache = null;
    boolean listCaches = false;

    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      String name = optionName(argument);
      String attached = attachedValue(argument);
      if ("--cache".equals(name) || "-c".equals(name)) {
        cache = true;
      } else if ("--list-caches".equals(name)) {
        listCaches = true;
      } else if ("--clear-cache".equals(name)) {
        clearTarget = attached;
        if (clearTarget == null && index + 1 < args.length && !args[index + 1].startsWith("-")) {
          clearTarget = args[++index];
        }
      } else if ("--clear-cache-older-than".equals(name)) {
        clearAge = attached != null ? attached : args[++index];
      } else if ("--use-cache".equals(name)) {
        if (attached == null && index + 1 >= args.length) {
          throw new IllegalArgumentException("no cache name supplied");
        }
        useCache = attached != null ? attached : args[++index];
      } else if ("-i".equals(name) || "--input".equals(name)) {
        stdinFormat = attached != null ? attached : args[++index];
      } else if ("--cache-dir".equals(name)) {
        options.add("--state-dir");
        options.add(attached != null ? attached : args[++index]);
      } else if ("--output".equals(name) || "-o".equals(name)) {
        String value = attached != null ? attached : args[++index];
        options.add("--output");
        options.add(value);
      } else if (name.startsWith("-") && VALUE_OPTIONS.contains(name)) {
        if (attached != null) {
          options.add(name + "=" + attached);
        } else {
          if (index + 1 >= args.length) {
            throw new IllegalArgumentException("no value supplied for " + name);
          }
          options.add(name);
          options.add(args[++index]);
        }
      } else if (name.startsWith("-")) {
        options.add(argument);
      } else if (isAssignment(argument)) {
        options.add("--param");
        options.add(argument);
      } else {
        positionals.add(argument);
      }
    }

    if (listCaches) return combine(List.of("cache", "list"), reportOptions(options));
    if (useCache != null) {
      if (!positionals.isEmpty()) throw new IllegalArgumentException("Unexpected argument: " + positionals.getFirst());
      return combine(List.of("cache", "use", "--name", useCache), reportOptions(options));
    }
    if (clearAge != null) return combine(
        List.of("cache", "clear", "--older-than", clearAge), reportOptions(options));
    if (clearTarget != null || contains(args, "--clear-cache")) return combine(
        clearTarget == null
            ? List.of("cache", "clear", "--all")
            : List.of("cache", "clear", "--name", clearTarget),
        reportOptions(options));

    if (!positionals.isEmpty() && "catalog".equalsIgnoreCase(positionals.getFirst())) {
      List<String> command = new ArrayList<>(List.of("catalog"));
      positionals.removeFirst();
      String input = positionals.stream().filter(LegacyCli::isInput).findFirst().orElse(null);
      String pattern = positionals.stream().filter(value -> !value.equals(input)).findFirst().orElse(null);
      if (input != null) add(command, "--input-file", input);
      if (pattern != null) add(command, "--pattern", pattern);
      return combine(command, reportOptions(options));
    }

    if (positionals.size() > 2) {
      throw new IllegalArgumentException("Unexpected argument: " + positionals.get(2));
    }

    String input = positionals.stream().filter(LegacyCli::isInput).findFirst().orElse(null);
    String selectedInput = input;
    String script = positionals.stream().filter(value -> !value.equals(selectedInput)).findFirst().orElse(null);
    if (stdinFormat != null) input = "-";

    if (cache && script == null) {
      List<String> command = new ArrayList<>(List.of("cache", "load"));
      add(command, "--input-file", input);
      if (stdinFormat != null) add(command, "--input-format", stdinFormat);
      return combine(command, reportOptions(options));
    }
    if (script == null && input != null) {
      List<String> command = new ArrayList<>(List.of("convert", "--input-file", input));
      if (stdinFormat != null) add(command, "--input-format", stdinFormat);
      return combine(command, options);
    }

    if (script == null) {
      throw new IllegalArgumentException("No script filename supplied.");
    }

    List<String> command = new ArrayList<>(List.of("run"));
    add(command, Files.exists(Path.of(script)) ? "--script-file" : "--script-text", script);
    if (input != null) add(command, "--input-file", input);
    if (stdinFormat != null) add(command, "--input-format", stdinFormat);
    if (cache) command.add("--cache");
    return combine(command, options);
  }

  private static List<String> reportOptions(List<String> options) {
    List<String> converted = new ArrayList<>();
    for (int index = 0; index < options.size(); index++) {
      String option = options.get(index);
      if ("--output".equals(option)) {
        converted.add("--report-format");
      } else {
        converted.add(option);
      }
    }
    return converted;
  }

  private static String[] combine(List<String> command, List<String> options) {
    List<String> result = new ArrayList<>(command);
    result.addAll(options);
    return result.toArray(String[]::new);
  }

  private static void add(List<String> target, String key, String value) {
    if (value != null) {
      target.add(key);
      target.add(value);
    }
  }

  private static boolean isInput(String value) {
    return InputType.supportsFilename(value);
  }

  private static boolean isAssignment(String value) {
    int equals = value.indexOf('=');
    if (equals <= 0 || value.startsWith("--")) return false;
    String key = value.substring(0, equals);
    for (int index = 0; index < key.length(); index++) {
      char ch = key.charAt(index);
      if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.' && ch != '-') return false;
    }
    return true;
  }

  private static String optionName(String argument) {
    int equals = argument.startsWith("--") ? argument.indexOf('=') : -1;
    return equals < 0 ? argument : argument.substring(0, equals);
  }

  private static String attachedValue(String argument) {
    int equals = argument.startsWith("--") ? argument.indexOf('=') : -1;
    return equals < 0 ? null : argument.substring(equals + 1);
  }

  private static boolean contains(String[] args, String option) {
    for (String arg : args) if (option.equals(arg) || arg.startsWith(option + "=")) return true;
    return false;
  }
}

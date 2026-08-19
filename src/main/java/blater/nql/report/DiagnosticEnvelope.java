package blater.nql.report;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Stable outer envelope for one diagnostic event. */
public record DiagnosticEnvelope(
    String code,
    Level level,
    String message,
    Usage usage) {
  public static final int SCHEMA_VERSION = 1;

  public DiagnosticEnvelope {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(usage, "usage");
    if (!code.matches("NQL-[A-Z0-9]+(?:-[A-Z0-9]+)*")) {
      throw new IllegalArgumentException("Invalid diagnostic code: " + code);
    }
    if (message.isBlank()) throw new IllegalArgumentException("Diagnostic message is blank");
  }

  public Map<String, ?> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema_version", SCHEMA_VERSION);
    fields.put("code", code);
    fields.put("level", level.name().toLowerCase(Locale.ROOT));
    fields.put("message", message);
    if (usage instanceof Usage.Present present) fields.put("usage", present.value());
    return fields;
  }

  public enum Level { DEBUG, INFO, WARNING, ERROR }

  public sealed interface Usage permits Usage.None, Usage.Present {
    record None() implements Usage {
    }

    record Present(String value) implements Usage {
      public Present {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("Diagnostic usage is blank");
      }
    }
  }
}

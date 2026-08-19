package blater.nql.report;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/** Stable outer envelope for successful operational command reports. */
public record ReportEnvelope(String command, Map<String, ?> details) {
  public static final int SCHEMA_VERSION = 1;

  public ReportEnvelope {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(details, "details");
    if (!command.matches("[a-z]+(?:\\.[a-z]+)*")) {
      throw new IllegalArgumentException("Invalid report command identifier: " + command);
    }
    details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }

  public Map<String, ?> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema_version", SCHEMA_VERSION);
    fields.put("status", "ok");
    fields.put("command", command);
    fields.put("details", details);
    return fields;
  }
}

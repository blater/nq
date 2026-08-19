package blater.nql.domain;

import java.time.format.DateTimeFormatter;

/*
 * Responsibility: Centralizes date/time formatters shared by query
 * row rendering and value conversion.
 */
public final class DateFormats {
  public static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("d-MMM-yyyy HH:mm:ss.SSS");

  private DateFormats() {}
}

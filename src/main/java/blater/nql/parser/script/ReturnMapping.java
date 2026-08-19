package blater.nql.parser.script;

import lombok.AllArgsConstructor;
import lombok.Getter;

// Responsibility: Represents one database column value to write back into the input document.
@AllArgsConstructor
@Getter
public class ReturnMapping {
  private final String columnName;
  private final String xpath;
}

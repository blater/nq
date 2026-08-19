package blater.nql.outputwriter;

import blater.nql.domain.Hierarchy;
import blater.nql.util.Log;

/*
 * Responsibility: Renders a mapped hierarchy through a concrete output format.
 */
public interface OutputWriter {

  void write(Hierarchy result);
}

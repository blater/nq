package blater.nq.outputwriter;

import blater.nq.domain.Hierarchy;
import blater.nq.util.Log;

/*
 * Responsibility: Renders a mapped hierarchy through a concrete output format.
 */
public interface OutputWriter {

  void write(Hierarchy result);
}

package blater.nq.execution;

@FunctionalInterface
interface TerminalDetector {
  StdinDisposition detect();
}

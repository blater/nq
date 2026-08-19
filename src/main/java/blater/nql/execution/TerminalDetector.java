package blater.nql.execution;

@FunctionalInterface
interface TerminalDetector {
  StdinDisposition detect();
}

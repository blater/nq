package blater.nql;

import blater.nql.cli.parse.CliParser;
import blater.nql.cli.parse.CliUsageException;
import blater.nql.execution.InputEnvironment;
import blater.nql.execution.InvocationExecutor;
import blater.nql.report.DiagnosticEnvelope;
import blater.nql.report.DiagnosticWriter;
import blater.nql.report.ReportFormat;
import blater.nql.util.Log;

import java.io.PrintStream;
import java.util.Objects;

/** Testable argv-to-execution application boundary with stable exit categories. */
public final class NqlApplication {
  public static final int SUCCESS = 0;
  public static final int EXECUTION_FAILURE = 1;
  public static final int USAGE_FAILURE = 2;
  public static final int INTERRUPTED = 130;

  private final CliParser parser;
  private final InvocationExecutor executor;
  private final PrintStream diagnostics;

  public NqlApplication() {
    this(new CliParser(), new InvocationExecutor(), System.err);
  }

  public NqlApplication(
      CliParser parser,
      InvocationExecutor executor,
      PrintStream diagnostics) {
    this.parser = Objects.requireNonNull(parser, "parser");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
  }

  public int run(String[] arguments, InputEnvironment environment) {
    Objects.requireNonNull(arguments, "arguments");
    Objects.requireNonNull(environment, "environment");
    ReportFormat diagnosticFormat = requestedReportFormat(arguments);
    if (diagnosticFormat != null) {
      return runStructured(arguments, environment, diagnosticFormat);
    }
    try {
      executor.execute(parser.parse(arguments), environment);
      return SUCCESS;
    } catch (CliUsageException failure) {
      writeUsageFailure(failure, diagnosticFormat);
      return USAGE_FAILURE;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      writeDiagnostic(new DiagnosticEnvelope(
          "NQL-INTERRUPTED", DiagnosticEnvelope.Level.ERROR, "Operation interrupted",
          new DiagnosticEnvelope.Usage.None()));
      return INTERRUPTED;
    } catch (Exception failure) {
      writeDiagnostic(executionFailure(failure));
      return EXECUTION_FAILURE;
    }
  }

  private int runStructured(
      String[] arguments,
      InputEnvironment environment,
      ReportFormat format) {
    try (var writer = new DiagnosticWriter(format, diagnostics);
         Log.DiagnosticScope ignored = Log.withDiagnostics(writer::write)) {
      try {
        executor.execute(parser.parse(arguments), environment);
        return SUCCESS;
      } catch (CliUsageException failure) {
        writeUsageFailure(failure, writer);
        return USAGE_FAILURE;
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        writer.write(new DiagnosticEnvelope(
            "NQL-INTERRUPTED", DiagnosticEnvelope.Level.ERROR, "Operation interrupted",
            new DiagnosticEnvelope.Usage.None()));
        return INTERRUPTED;
      } catch (Exception failure) {
        writer.write(executionFailure(failure));
        return EXECUTION_FAILURE;
      }
    }
  }

  private void writeUsageFailure(CliUsageException failure, ReportFormat format) {
    DiagnosticEnvelope.Usage usage = failure.usage().isBlank()
        ? new DiagnosticEnvelope.Usage.None()
        : new DiagnosticEnvelope.Usage.Present(failure.usage());
    var diagnostic = new DiagnosticEnvelope(
        "NQL-CLI-USAGE", DiagnosticEnvelope.Level.ERROR, failure.getMessage(), usage);
    writeDiagnostic(diagnostic);
    if (format == null && usage instanceof DiagnosticEnvelope.Usage.Present present) {
      diagnostics.println(present.value());
    }
  }

  private static void writeUsageFailure(
      CliUsageException failure,
      DiagnosticWriter writer) {
    DiagnosticEnvelope.Usage usage = failure.usage().isBlank()
        ? new DiagnosticEnvelope.Usage.None()
        : new DiagnosticEnvelope.Usage.Present(failure.usage());
    writer.write(new DiagnosticEnvelope(
        "NQL-CLI-USAGE", DiagnosticEnvelope.Level.ERROR, failure.getMessage(), usage));
  }

  private void writeDiagnostic(DiagnosticEnvelope diagnostic) {
    diagnostics.println("ERROR [" + diagnostic.code() + "] " + diagnostic.message());
  }

  private static DiagnosticEnvelope executionFailure(Exception failure) {
    String message = failure.getMessage();
    return new DiagnosticEnvelope(
        "NQL-EXECUTION-FAILED", DiagnosticEnvelope.Level.ERROR,
        message == null || message.isBlank() ? failure.getClass().getSimpleName() : message,
        new DiagnosticEnvelope.Usage.None());
  }

  private static ReportFormat requestedReportFormat(String[] arguments) {
    for (int index = 0; index + 1 < arguments.length; index++) {
      if ("--report-format".equals(arguments[index]) || "-r".equals(arguments[index])) {
        try {
          return ReportFormat.fromName(arguments[index + 1]);
        } catch (IllegalArgumentException ignored) {
          return null;
        }
      }
    }
    return null;
  }
}

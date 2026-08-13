package blater.nq.execution;

import blater.nq.cli.CacheInvocation;
import blater.nq.cli.CapabilitiesInvocation;
import blater.nq.cli.CatalogInvocation;
import blater.nq.cli.ConvertInvocation;
import blater.nq.cli.Credentials;
import blater.nq.cli.DataInput;
import blater.nq.cli.DataSourceSpec;
import blater.nq.cli.DriverSelection;
import blater.nq.cli.ExecutionTarget;
import blater.nq.cli.HelpInvocation;
import blater.nq.cli.InputSelection;
import blater.nq.cli.InvocationOptions;
import blater.nq.cli.JdbcConnectionSpec;
import blater.nq.cli.NqInvocation;
import blater.nq.cli.OutputSelection;
import blater.nq.cli.ParquetOverrides;
import blater.nq.cli.RunInvocation;
import blater.nq.cli.VersionInvocation;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static blater.nq.execution.EngineParameterNames.*;

/** Builds the narrow mutable parameter map consumed by query and mapping internals. */
public final class EngineRuntimeParameters {
  private EngineRuntimeParameters() {
  }

  public static Map<String, String> from(NqInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    return adapt(invocation, new Materialization.None());
  }

  public static Map<String, String> from(
      NqInvocation invocation,
      MaterializedDataInput materializedInput) {
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(materializedInput, "materializedInput");
    return adapt(invocation, new Materialization.Provided(materializedInput));
  }

  private static Map<String, String> adapt(
      NqInvocation invocation,
      Materialization materialization) {
    Map<String, String> parameters = new LinkedHashMap<>();
    InputSelection input = switch (invocation) {
      case RunInvocation run -> {
        addOptions(parameters, run.options());
        addRunOptions(parameters, run);
        addTarget(parameters, run.target());
        yield run.input();
      }
      case ConvertInvocation convert -> {
        addOptions(parameters, convert.options());
        parameters.put(OUTPUT_TYPE, formatName(convert.output()));
        yield new InputSelection.Provided(convert.input());
      }
      case CatalogInvocation catalog -> {
        addOptions(parameters, catalog.options());
        addTarget(parameters, catalog.target());
        yield catalog.input();
      }
      case CacheInvocation.Load load -> {
        addOptions(parameters, load.options());
        yield new InputSelection.Provided(load.input());
      }
      case CacheInvocation.Use use -> {
        addDebug(parameters, use.debug());
        yield new InputSelection.None();
      }
      case CacheInvocation.ListCaches list -> {
        addDebug(parameters, list.debug());
        yield new InputSelection.None();
      }
      case CacheInvocation.Clear clear -> {
        addDebug(parameters, clear.debug());
        yield new InputSelection.None();
      }
      case HelpInvocation ignored -> new InputSelection.None();
      case VersionInvocation ignored -> new InputSelection.None();
      case CapabilitiesInvocation ignored -> new InputSelection.None();
    };
    addInput(parameters, input, materialization);
    return parameters;
  }

  private static void addRunOptions(Map<String, String> parameters, RunInvocation invocation) {
    if (invocation.noKeyInference()) parameters.put(NO_KEY_INFERENCE, Boolean.TRUE.toString());
    if (invocation.output() instanceof OutputSelection.Explicit explicit) {
      parameters.put(OUTPUT_TYPE, formatName(explicit.format()));
    }
  }

  private static void addOptions(Map<String, String> parameters, InvocationOptions options) {
    options.parameters().forEach((name, value) -> {
      if (isTaskParameter(name)) parameters.put(name, value);
    });
    addDebug(parameters, options.debug());
    addParquetOverrides(parameters, options.parquetOverrides());
  }

  private static boolean isTaskParameter(String name) {
    String normalized = name.toLowerCase(Locale.ROOT);
    return !normalized.startsWith("nq.")
        && !normalized.startsWith("jdbc.")
        && !normalized.startsWith("cache.")
        && !normalized.startsWith("nsql_")
        && !normalized.startsWith("nq_");
  }

  private static void addDebug(Map<String, String> parameters, boolean debug) {
    if (debug) parameters.put(DEBUG, Boolean.TRUE.toString());
  }

  private static void addParquetOverrides(
      Map<String, String> parameters,
      ParquetOverrides overrides) {
    if (overrides.root() instanceof ParquetOverrides.Value.Explicit explicit) {
      parameters.put(PARQUET_ROOT, explicit.value());
    }
    if (overrides.record() instanceof ParquetOverrides.Value.Explicit explicit) {
      parameters.put(PARQUET_RECORD, explicit.value());
    }
  }

  private static void addTarget(Map<String, String> parameters, ExecutionTarget target) {
    switch (target) {
      case ExecutionTarget.Temporary ignored -> {
      }
      case ExecutionTarget.InputOrActiveCache ignored -> throw new IllegalStateException(
          "Runtime parameters require automatic execution target resolution first");
      case ExecutionTarget.ActiveCache ignored -> {
      }
      case ExecutionTarget.NamedCache ignored -> {
      }
      case ExecutionTarget.Jdbc jdbc -> addJdbc(parameters, jdbc.connection());
    }
  }

  private static void addJdbc(Map<String, String> parameters, JdbcConnectionSpec connection) {
    parameters.put(JDBC_DATABASE, connection.url());
    switch (connection.driver()) {
      case DriverSelection.Automatic ignored -> {
      }
      case DriverSelection.Known known -> parameters.put(JDBC_DRIVER, known.value());
      case DriverSelection.ClassName className ->
          parameters.put(JDBC_CLASS_NAME, className.value());
    }
    addCredential(parameters, JDBC_USERNAME, connection.credentials().username());
    addCredential(parameters, JDBC_PASSWORD, connection.credentials().password());
  }

  private static void addCredential(
      Map<String, String> parameters,
      String name,
      Credentials.Value credential) {
    if (credential instanceof Credentials.Value.Specified specified) {
      parameters.put(name, specified.value());
    }
  }

  private static void addInput(
      Map<String, String> parameters,
      InputSelection input,
      Materialization materialization) {
    switch (input) {
      case InputSelection.None ignored -> {
        if (materialization instanceof Materialization.Provided) {
          throw new IllegalStateException("Materialized data supplied for an invocation without input");
        }
      }
      case InputSelection.Automatic ignored -> throw new IllegalStateException(
          "Runtime parameters require automatic stdin selection to be resolved first");
      case InputSelection.Provided provided -> {
        DataInput dataInput = provided.input();
        String filename = switch (materialization) {
          case Materialization.None noMaterialization -> switch (dataInput.source()) {
            case DataSourceSpec.File file -> file.path().toString();
            case DataSourceSpec.StandardInput standardInput -> STANDARD_INPUT;
            case DataSourceSpec.Text text -> throw new IllegalStateException(
                "Literal input data must be materialized first");
          };
          case Materialization.Provided staged -> {
            if (!staged.input().input().equals(dataInput)) {
              throw new IllegalStateException("Materialized data belongs to a different input source");
            }
            yield staged.input().path().toString();
          }
        };
        parameters.put(INPUT_FILENAME, filename);
        parameters.put(INPUT_TYPE, formatName(dataInput.format()));
      }
    }
  }

  private static String formatName(Enum<?> format) {
    return format.name().toLowerCase(Locale.ROOT);
  }

  private sealed interface Materialization permits Materialization.None, Materialization.Provided {
    record None() implements Materialization {
    }
    record Provided(MaterializedDataInput input) implements Materialization {
      public Provided {
        Objects.requireNonNull(input, "input");
      }
    }
  }
}

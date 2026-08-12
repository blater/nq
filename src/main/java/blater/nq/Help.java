package blater.nq;

/** Prints top-level and topic-specific command-line help. */
public final class Help {
  static final String USAGE = """
      Usage:
        nq run (--script-file <path> | --script-text <text>) [run-options]
        nq convert --input-file <path|-> [conversion-options]
        nq catalog [--pattern <pattern>] [source-options]
        nq cache load --input-file <path|-> [cache-options]
        nq cache use --name <cache-name> [cache-options]
        nq cache list [cache-options]
        nq cache clear (--all | --name <cache-name> | --older-than <age>) [cache-options]
        nq help [topic]
        nq -h | --help [topic]
        nq --version

      Explicit input and script options:
        --script-file <path>
        --script-text <text>
        --input-file <path|->
        --input-format <xml|json|jsonl|yaml|toml|csv|tsv|parquet>
        --param <name=value>

      Result and report options:
        -o, --output <xml|json|jsonl|csv|tsv|yaml|toml|markdown>
        --report-format <xml|json|jsonl|csv|tsv|yaml|toml|markdown>

      State options:
        --state-dir <path>     Store configuration and caches beneath this directory.
                               NQ_STATE_DIR supplies the same default.

      Run and connection options:
        -p, --properties <properties-file>
        --cache
        --db <type> --database <name> [--host <host>] [--port <port>]
        --user <username> [--password <password>]
        --jdbc-driver <name> --jdbc-class-name <class> --jdbc-database <url>
        --jdbc-username <username> --jdbc-password <password>
        --debug
        --no-key-inference
      """;

  static final String HELP_ON_HELP = """
      HELP
          nq help [topic]
          nq --help [topic]

      TOPICS
          run         Execute a script from an explicitly named file or text value.
          convert     Convert one explicitly named input source.
          catalog     Inspect a database, active cache, or input file.
          cache       Load, select, list, and clear persistent caches.
          connection  Configure JDBC connections.
          output      Choose result and report formats.
          state       Isolate configuration and cache files.
          parameters  Supply runtime template parameters.
          parquet     Override Parquet hierarchy names.
      """;

  static final String RUN_CMD = """
      RUN
          Execute an nq script.

      SYNOPSIS
          nq run --script-file <path> [--input-file <path|->] [options]
          nq run --script-text <text> [--input-file <path|->] [options]

      DESCRIPTION
          Exactly one script source is required. An input file is loaded into a
          temporary H2 database unless --cache is supplied. With no input or JDBC
          connection, run uses the active cache in --state-dir. Use --input-file -
          together with --input-format to read standard input.

      EXAMPLES
          nq run --script-file report.nq --properties database.properties
          nq run --script-text 'select id from item;' --input-file items.json
          nq run --script-file import.nq --input-file customers.json --param region=EMEA
      """;

  static final String CONVERT_CMD = """
      CONVERT
          Convert structured data without materializing it in H2.

      SYNOPSIS
          nq convert --input-file <path> [--output <format>]
          nq convert --input-file - --input-format <format> [--output <format>]

      EXAMPLES
          nq convert --input-file customers.xml --output json
          nq convert --input-file - --input-format yaml --output json < customers.yaml
      """;

  static final String CATALOG_CMD = """
      CATALOG
          Inspect database or input relations without changing persistent state.

      SYNOPSIS
          nq catalog [--pattern <pattern>] [connection-options]
          nq catalog --input-file <path|-> [--pattern <pattern>]

      DESCRIPTION
          Input files are inspected through an ephemeral in-memory database.
          Without an input or JDBC connection, catalog uses the active cache under
          --state-dir. Reports default to Markdown; --report-format selects another
          structured format.

      EXAMPLES
          nq catalog --input-file customers.json --pattern '*' --report-format json
          nq catalog --properties database.properties --pattern 'audit*'
      """;

  static final String CACHE_CMD = """
      CACHE
          Manage persistent local H2 caches.

      SYNOPSIS
          nq cache load --input-file <path|-> [--state-dir <path>]
          nq cache use --name <cache-name> [--state-dir <path>]
          nq cache list [--state-dir <path>]
          nq cache clear (--all | --name <cache-name> | --older-than <age>)

      DESCRIPTION
          All cache files and active-cache configuration live beneath --state-dir.
          NQ_STATE_DIR supplies the default; otherwise ~/.nq is used. Cache commands
          emit a report controlled by --report-format.
      """;

  static final String CONNECTION_CMD = """
      CONNECTION
          Use --properties for a JDBC properties file, or use --db and --database
          with optional --host, --port, --user, and --password. Exact JDBC options
          remain available as --jdbc-driver, --jdbc-class-name, --jdbc-database,
          --jdbc-username, and --jdbc-password.
      """;

  static final String OUTPUT_CMD = """
      OUTPUT
          --output controls run and convert result data.
          --report-format controls catalog, cache, and diagnostic reports.

          Both accept xml, json, jsonl, csv, tsv, yaml, toml, and markdown.
      """;

  static final String STATE_CMD = """
      STATE
          --state-dir <path> places config.properties and the cache directory under
          one explicit root. NQ_STATE_DIR supplies the same default. If neither is
          set, nq uses ~/.nq.
      """;

  static final String PARAMETERS_CMD = """
      PARAMETERS
          Supply each runtime template value explicitly:

          nq run --script-file report.nq --param region=EMEA --param year=2026
      """;

  static final String PARQUET_CMD = """
      PARQUET
          --parquet-root <name> and --parquet-record <name> override names inferred
          from a Parquet file and schema.
      """;

  static final String MAN_PAGE = """
      NQ(1)

      NAME
          nq - query and transform relational and hierarchical data

      SYNOPSIS
      """ + USAGE + """

      DESCRIPTION
          nq uses explicit commands and named operands. Result data is controlled by
          --output; operational reports are controlled independently by
          --report-format. Persistent state is hermetic beneath --state-dir.

      ENVIRONMENT
          NQ_STATE_DIR   Default state root when --state-dir is omitted.

      FILES
          <state-dir>/config.properties
          <state-dir>/cache/
      """;

  private Help() {
  }

  public static void printVersion() {
    String version = Help.class.getPackage().getImplementationVersion();
    System.out.println("nq " + (version == null || version.isBlank() ? "development" : version));
  }

  public static void printBriefHelp() {
    System.out.print(USAGE);
    System.out.println("Run 'nq help' for topics or 'nq --help' for the complete manual.");
  }

  public static void printCommandInfo(String command) {
    String normalized = command == null ? "" : command.strip().toLowerCase();
    String info = switch (normalized) {
      case "help" -> HELP_ON_HELP;
      case "run", "query" -> RUN_CMD;
      case "convert" -> CONVERT_CMD;
      case "catalog" -> CATALOG_CMD;
      case "cache", "load", "use", "list", "clear" -> CACHE_CMD;
      case "connection", "database", "db", "jdbc" -> CONNECTION_CMD;
      case "output", "report-format" -> OUTPUT_CMD;
      case "state", "state-dir" -> STATE_CMD;
      case "parameters", "parameter", "params" -> PARAMETERS_CMD;
      case "parquet" -> PARQUET_CMD;
      default -> "Unknown help topic: " + command + "\n\nRun 'nq help' to list available topics.\n";
    };
    System.out.print(info);
  }

  public static void printManPage() {
    System.out.print(MAN_PAGE);
  }
}

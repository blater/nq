package blater.nq;

/** Prints top-level and topic-specific command-line help. */
public final class Help {
  static final String USAGE = """
      Usage:
        nq [<script.nq> [<data>]]
        nq [<data>]
        nq run <script> [<data>] [options]
        nq convert [<data>] [options]
        nq catalog [<data>] [<pattern>] [options]
        nq cache load [<data>] [<name>] [options]
        nq cache use <name> [options]
        nq cache list [options]
        nq cache clear (<name> | olderthan <age> | all) [options]
        nq help [<command> [<subcommand>]]
        nq version

      Common source options:
        -f, --script-file <path>       Named equivalent for a script operand.
        -e, --script-text <text>       Supply literal NQ script text.
        -i, --input-file <path|->      Named equivalent for a data operand.
        --input-text <text>            Supply literal hierarchical data.
        -t, --input-format <format>    Override input format inference.

      Result and report options:
        -o, --output <format>          Run/convert result format (default: json).
        -r, --report-format <format>   Catalog/cache report format (default: markdown).

      Cache options:
        --cache                       Select persistent cache execution.
        --name <name>                 Named equivalent in a cache context.
        --cache-dir <path>            Actual directory containing cache state.

      Configuration and parameters:
        --config <file.properties>     NQ operational configuration.
        --params-file <file.properties>
        --param <name=value>           Repeat for distinct task parameter names.

      Other:
        --debug
        --no-key-inference
        -h                             Brief global help.
        --help                         Full or command-local help.
        --version                      Print the version.
      """;

  static final String HELP_ON_HELP = """
      HELP
          nq help [<command> [<subcommand>]]
          nq <command> --help

      COMMANDS
          run         Execute a script against data, a cache, or JDBC.
          convert     Convert hierarchical data directly.
          catalog     Inspect relations in data, a cache, or JDBC.
          cache       Load, select, list, and clear persistent caches.
          help        Show global or command-specific help.
          version     Print the NQ version.

      TOPICS
          connection, output, cache-dir, parameters, parquet
      """;

  static final String RUN_CMD = """
      RUN
          Execute one complete NQ script.

      SYNOPSIS
          nq <script.nq> [<data>]
          nq '<literal script>' ['<literal json>']
          nq run <script> [<data>] [options]

      DESCRIPTION
          Script and data operands may be files or literal values. Recognised
          extensions identify file roles. Script plus explicit data uses a
          temporary database unless --cache or JDBC is selected. With no explicit
          data, redirected stdin is data; terminal stdin falls back to the active
          cache. --cache [--name <name>] selects a persistent cache without
          automatically loading the supplied data into it.

      EXAMPLES
          nq report.nq customers.json
          nq 'select id from customer;' '{"customer":[{"id":1}]}'
          producer | nq report.nq
          nq report.nq --cache --name customers
      """;

  static final String CONVERT_CMD = """
      CONVERT
          Convert hierarchical data without creating a database.

      SYNOPSIS
          nq <data-file>
          nq convert [<data>] [-o <format>]

      DESCRIPTION
          The explicit convert command with no operand reads stdin. Input defaults
          to JSON unless inferred from a filename or overridden with -t. Output
          defaults to JSON. Bare nq prints brief help because JSON-to-JSON would
          be an identity operation.

      EXAMPLES
          nq customers.yaml
          nq customers.xml -o yaml
          producer | nq -o json
      """;

  static final String CATALOG_CMD = """
      CATALOG
          Inspect database relations without changing persistent state.

      SYNOPSIS
          nq catalog [<data>] [<pattern>] [options]

      DESCRIPTION
          Explicit data is inspected through a temporary database. Otherwise,
          redirected stdin is data and terminal stdin falls back to the active
          cache. Use --cache --name <name> for a non-activating named selection,
          or supply JDBC options. Reports default to Markdown.

      EXAMPLES
          nq catalog customers.json 'customer*'
          nq catalog 'audit*' --cache
          nq catalog --cache --name archive -r json
      """;

  static final String CACHE_CMD = """
      CACHE
          Manage persistent local caches.

      SYNOPSIS
          nq cache load [<data>] [<name>]
          nq cache use <name>
          nq cache list
          nq cache clear <name>
          nq cache clear olderthan <age>
          nq cache clear all

      DESCRIPTION
          --cache-dir is the actual cache directory. A successful load creates a
          fresh cache and activates it; it never appends, overwrites, or upserts.
          Named query selection does not change the active cache. Reports default
          to Markdown and can be made machine-readable with -r json.

      EXAMPLES
          nq cache load customers.json customers
          producer | nq cache load --name customers
          nq cache use customers
          nq cache --cache-dir ./cache clear olderthan 7d -r json
      """;

  static final String CONNECTION_CMD = """
      CONNECTION
          Simple form:
            --db <type> --database <name> [--host <host>] [--port <port>]

          Exact form:
            --jdbc-database <url>
            [--jdbc-driver <known-driver> | --jdbc-class-name <class>]

          Both forms accept --user and --password. A JDBC URL is sufficient in
          the normal case; driver hints are optional escape hatches.
      """;

  static final String OUTPUT_CMD = """
      OUTPUT
          -o, --output controls run and conversion result data.
          -r, --report-format controls catalog and cache reports.

          Formats are xml, json, jsonl, csv, tsv, yaml, toml, and markdown.
          Values are case-insensitive; md is an alias for markdown.
      """;

  static final String CACHE_DIR_CMD = """
      CACHE DIRECTORY
          Resolution order:
            ~/.nq/cache < config cache.dir < NQ_CACHE_DIR < --cache-dir

          The selected directory directly contains .active and cache database
          files. Read-only operations do not create an absent directory.
      """;

  static final String PARAMETERS_CMD = """
      PARAMETERS
          --config contains only whitelisted operational NQ settings.
          --params-file contains task parameters visible to scripts and readers.
          --param name=value overrides a file value and may repeat for distinct names.

      EXAMPLE
          nq report.nq --params-file report.properties --param region=EMEA
      """;

  static final String PARQUET_CMD = """
      PARQUET
          --parquet-root <name> and --parquet-record <name> override names inferred
          from Parquet input. They are invalid for non-Parquet input.
      """;

  static final String MAN_PAGE = """
      NQ(1)

      NAME
          nq - query and transform relational and hierarchical data

      SYNOPSIS
      """ + USAGE + """

      DESCRIPTION
          NQ uses convenient positional operands for each command's primary roles
          and named options for optional modifications or disambiguation. Result
          data and operational reports have separate format controls. Persistent
          cache state is hermetic beneath the selected cache directory.

      ENVIRONMENT
          NQ_CACHE_DIR   Default direct cache directory.

      FILES
          <cache-dir>/.active
          <cache-dir>/<logical-name>.mv.db
      """;

  private Help() {
  }

  public static void printVersion() {
    String version = Help.class.getPackage().getImplementationVersion();
    System.out.println("nq " + (version == null || version.isBlank() ? "development" : version));
  }

  public static void printBriefHelp() {
    System.out.print(USAGE);
    System.out.println("Run 'nq help' for commands or 'nq --help' for the complete manual.");
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
      case "cache-dir", "cache-directory" -> CACHE_DIR_CMD;
      case "parameters", "parameter", "params", "config" -> PARAMETERS_CMD;
      case "parquet" -> PARQUET_CMD;
      default -> "Unknown help topic: " + command
          + "\n\nRun 'nq help' to list available commands and topics.\n";
    };
    System.out.print(info);
  }

  public static void printManPage() {
    System.out.print(MAN_PAGE);
  }
}

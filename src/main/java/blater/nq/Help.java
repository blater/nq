package blater.nq;

/** Prints top-level and topic-specific command-line help. */
public final class Help {
  static final String USAGE = """
      Usage:
        nq <script-file-or-text> [input-file] [name=value ...] [options]
        nq catalog [table-pattern] [options]
        nq <input-file> [-o|--output <type>]
        nq -c|--cache <input-file> [cache-options]
        nq --use-cache <input-file-or-cache-filename> [cache-options]
        nq --list-caches [cache-options]
        nq --clear-cache [input-file-or-cache-filename] [cache-options]
        nq --clear-cache-older-than <age> [cache-options]
        nq -h | --help [topic]
        nq --version

      Connection options:
        -p <properties-file>
        --db <type> --database <name> [--host <host>] [--port <port>]
        --user <username> [--password <password>]
        --jdbc-driver <name>
        --jdbc-class-name <class>
        --jdbc-database <url>
        --jdbc-username <username>
        --jdbc-password <password>

      Output and query options:
        -o, --output <xml|json|jsonl|csv|yaml|markdown>
        --debug
        --no-key-inference

      Cache options:
        -c, --cache
        --cache-dir <path>
        --anonymous-collections <merge|error>
        --relation-alias <source-path>=<relation-name>  (repeatable)
        --metadata-refresh
        --metadata-expiry-hours <hours>

      Parquet options:
        --parquet-root <name>
        --parquet-record <name>

      Other:
        name=value
        -h
        --help [topic]
        --version
      """;

  public static void printVersion() {
    String version = Help.class.getPackage().getImplementationVersion();
    System.out.println("nq " + (version == null || version.isBlank() ? "development" : version));
  }

  static final String HELP_ON_HELP = """
      HELP
          nq -h
              Print brief usage help.

          nq --help
              Print the complete nq(1) manual page.

          nq --help help
              Print this list of available help topics.

          nq --help <topic>
              Print focused help for one topic.

      AVAILABLE HELP TOPICS
          query          Run a script against a database or input-file cache.
          catalog        List tables or show details for matching tables.
          cache          Load and reuse persistent input-file caches.
          use-cache      Switch the active cache without loading an input file.
          clear-cache    Remove all caches, one input's caches, or old caches.
          list-caches    List persistent input-file caches.
          connection     Configure JDBC connections from options or properties.
          output         Select XML, JSON, JSON Lines, YAML, CSV, or Markdown output.
          parameters     Supply runtime template parameters.
          parquet        Override Parquet hierarchy and record names.

      EXAMPLES
          nq --help query
          nq --help connection
          nq --help use-cache
          nq --help clear-cache
      """;

  static final String CATALOG_CMD = """
      CATALOG
          List database tables or show full details for matching tables.

      SYNOPSIS
          nq catalog [table-pattern] [connection/cache options]

      DESCRIPTION
          With no pattern, catalog lists user table names only. Supplying a
          table name or a pattern containing * includes table metadata and
          column details for every match. Matching is case-insensitive.

          Catalog uses an explicitly selected cache, otherwise a configured
          JDBC connection, otherwise the active cache. Quote patterns containing
          * so the shell does not expand them before nq receives them.
          Command-line catalog output defaults to Markdown; --output overrides it.

      EXAMPLES
          nq catalog
          nq catalog customer
          nq catalog 'audit*' --output json
          nq catalog '*' --cache customers.json
          nq catalog -p database.properties

      SEE ALSO
          nq --help cache
          nq --help connection
          nq --help output
      """;

  static final String QUERY_CMD = """
      QUERY
          Run an nq script against an external database or a cached input file.

      SYNOPSIS
          nq <script-file-or-text> [input-file] [name=value ...] [options]

      DESCRIPTION
          A script may be a filename or inline script text. Supply an input file
          without JDBC settings to query it through temporary in-memory H2.
          Add --cache to persist and reuse that H2 database. With JDBC settings,
          the input supplies mapped DML values. With no input or JDBC connection,
          the script queries the active cache. DQL structure keys are inferred
          from database metadata unless --no-key-inference is supplied. Named
          JSON/YAML collections use their member names as tables; anonymous
          record streams use ITEM. Resolve collisions or name anonymous paths
          with repeatable --relation-alias path=name options. Use
          --anonymous-collections error to reject ambiguous ITEM merging.

      EXAMPLES
          nq report.nq -p database.properties
          nq update.nq customers.json -p database.properties
          nq 'select id from item;' elements.json
          nq 'select id from customers;' customers.json
          nq query.nq data.json --relation-alias '/0=customers'
          nq query.nq customers.json --cache --output json
          nq 'output json; select 1 into {result.value};' -p database.properties

      SEE ALSO
          nq --help connection
          nq --help cache
          nq --help output
          nq --help parameters
      """;

  static final String CACHE_CMD = """
      CACHE
          Load and query a structured input file through persistent local H2.

      SYNOPSIS
          nq -c|--cache <input-file> [--cache-dir path]
          nq <script> <input-file> --cache [--cache-dir path] [--output type]
          nq <script> [--output type]

      DESCRIPTION
          Supported input types are XML, JSON, JSON Lines, YAML, CSV, and Parquet. --cache
          persists, reuses, and activates a file-backed H2 database under
          ~/.nq/cache. Persistent caching is always explicit; a lone input file
          instead converts directly to the selected output format. A script
          with no input or JDBC connection queries the active cache. Explicit
          --cache takes precedence over JDBC settings.

          JSON and YAML relation names come from collection member names.
          Anonymous JSON arrays, JSON Lines, and CSV use ITEM. Configure source
          paths with repeatable --relation-alias path=name options. Several
          unresolved anonymous paths merge into ITEM with a provenance-loss
          warning by default; --anonymous-collections error rejects the load.
          Materialization configuration is part of persistent cache identity.

      EXAMPLES
          nq --cache customers.json
          nq -c customers.json
          nq totals.nq
          nq totals.nq customers.json --cache --output json

      SEE ALSO
          nq --help query
          nq --help use-cache
          nq --help clear-cache
          nq --help list-caches
          nq --help parquet
      """;

  static final String USE_CACHE_CMD = """
      USE-CACHE
          Switch the active cache without loading or rebuilding it.

      SYNOPSIS
          nq --use-cache <input-file-or-cache-filename> [--cache-dir path]

      DESCRIPTION
          Selects an existing cache by its source path or by a bare cache
          filename. A bare cache filename is resolved in --cache-dir, or in
          ~/.nq/cache by default. The source file need not still exist. The
          command fails if no matching cache exists and does not create one. If
          several current variants exist, repeat their materialization options
          to select an exact variant; use --parquet-record for Parquet variants.
          Outdated layouts are listed but must be rebuilt from the source.

      EXAMPLES
          nq --use-cache customers.json
          nq --use-cache cache-0123456789abcdef.mv.db
          nq --use-cache customers.parquet --parquet-record customer
          nq --use-cache data.json --relation-alias '/0=customers'

      SEE ALSO
          nq --help cache
          nq --help list-caches
      """;

  static final String CLEAR_CACHE_CMD = """
      CLEAR-CACHE
          Remove persistent input-file caches.

      SYNOPSIS
          nq --clear-cache [--cache-dir path]
          nq --clear-cache <input-file-or-cache-filename> [--cache-dir path]
          nq --clear-cache=<input-file-or-cache-filename> [--cache-dir path]
          nq --clear-cache-older-than <age> [--cache-dir path]

      DESCRIPTION
          With no target, --clear-cache removes every cache. An input-file path
          removes every cache variant belonging to that source. A bare cache
          filename removes that file from --cache-dir, or ~/.nq/cache by
          default. Ages accept minutes, hours, or days, including 30m, 6h, and 7d.

      EXAMPLES
          nq --clear-cache
          nq --clear-cache customers.json
          nq --clear-cache cache-0123456789abcdef.mv.db
          nq --clear-cache-older-than 7d
      """;

  static final String LIST_CACHES_CMD = """
      LIST-CACHES
          List persistent input-file caches.

      SYNOPSIS
          nq --list-caches [--cache-dir path]

      DESCRIPTION
          Displays each cache's input type, creation time, materialization
          variant, and source path. The active cache is marked with an asterisk.
          Incompatible old input layouts are marked outdated and cannot be used.

      EXAMPLE
          nq --list-caches --cache-dir /tmp/nq-cache
      """;

  static final String CONNECTION_CMD = """
      CONNECTION
          Configure the JDBC connection used to execute a script.

      SYNOPSIS
          nq <script> -p <properties-file>
          nq <script> --db <type> --database <name> [connection-options]
          nq <script> --jdbc-database <url> [exact-jdbc-options]

      SIMPLE OPTIONS
          --db type             h2, postgresql, mysql, mariadb, oracle,
                                sqlserver, db2, hana, or informix
          --database name       Database, Oracle service, or H2 URL suffix
          --host host           Database host; defaults to localhost
          --port port           Database port
          --user username       JDBC username
          --password password   JDBC password; --password= supplies empty

      EXACT OPTIONS
          --jdbc-driver name
          --jdbc-class-name class
          --jdbc-database url
          --jdbc-username username
          --jdbc-password password

      EXAMPLES
          nq report.nq -p database.properties
          nq report.nq --db postgresql --database customers --user report
      """;

  static final String OUTPUT_CMD = """
      OUTPUT
          Select the document format written by nq.

      SYNOPSIS
          nq <input-file> --output <type>
          nq <input-file> -o <type>
          nq <script> [other arguments] --output <type>
          nq <script> [other arguments] -o <type>

      DESCRIPTION
          Accepted types are xml, json, jsonl, yaml, csv, and markdown,
          case-insensitively. A lone input file is read into NQ's neutral
          hierarchy and written directly in the selected format without H2
          materialization. When a script is supplied, its result replaces that
          direct conversion; the command-line option overrides the script's
          first 'output type;' directive. JSON is the default when neither is
          supplied. The command-line catalog command defaults to Markdown.

      EXAMPLES
          nq customers.xml --output json
          nq customers.json -o yaml
          nq customers.csv -o markdown
          nq report.nq -p database.properties --output json
          nq query.nq customers.csv --cache -o yaml
      """;

  static final String PARAMETERS_CMD = """
      PARAMETERS
          Supply values for ${name} and ${name:default} script templates.

      SYNOPSIS
          nq <script> [input-file] [name=value ...] [options]

      DESCRIPTION
          Runtime parameters may appear in any unambiguous argument position.
          Quote the complete name=value argument when its value contains spaces
          or shell-sensitive characters. Command-line values override properties.

      EXAMPLE
          nq report.nq -p database.properties region=EMEA 'name=Alice Smith'
      """;

  static final String PARQUET_CMD = """
      PARQUET
          Override names inferred from a Parquet filename and message schema.

      SYNOPSIS
          nq <script> <file.parquet> [--cache] [parquet-options]

      OPTIONS
          --parquet-root name     Override the hierarchy root inferred from the
                                  Parquet filename.
          --parquet-record name   Override the repeated record name inferred
                                  from the Parquet message type.

          Both options also accept --option=value.

      EXAMPLE
          nq query.nq data.parquet --cache \
            --parquet-root customers --parquet-record customer
      """;

  static final String MAN_PAGE = """
      NQ(1)

      NAME
          nq - query and transform relational and hierarchical data

      SYNOPSIS
          nq <script-file-or-text> [input-file] [name=value ...] [options]
          nq catalog [table-pattern] [connection/cache options]
          nq <input-file> [-o|--output type]
          nq -c|--cache <input-file> [--cache-dir path]
          nq --use-cache <input-file-or-cache-filename> [--cache-dir path]
          nq --list-caches [--cache-dir path]
          nq --clear-cache [input-file-or-cache-filename] [--cache-dir path]
          nq --clear-cache-older-than age [--cache-dir path]
          nq -h | --help | --help <topic>

      DESCRIPTION
          NQ is a SQL-like language for moving data between relational
          databases and XML, JSON, JSON Lines, YAML, CSV, or Parquet documents. It can run
          scripts against an external JDBC database, apply mapped DML from an
          input document, or query an input file through temporary or persistent
          H2.

          Arguments may appear in any unambiguous order. A script may be a file
          or inline text. The input file type is selected by its extension. An
          input file on its own is converted directly to JSON or the format
          selected by --output. Supplying a script disables direct conversion.
          Persistent caching requires -c or --cache. Named JSON/YAML collections
          become same-named relations; anonymous JSON, JSON Lines, and CSV record
          streams use ITEM.

      HELP
          -h
              Print brief usage help.

          --help
              Print this manual page.

          --help help
              List available focused help topics.

          --help <topic>
              Print focused help for a command or option group.

      COMMANDS
          catalog [table-pattern]
              List user table names. With a name or * pattern, include full
              table and column details for every matching table.

      OPTIONS
          -p file
              Load JDBC settings and runtime parameters from a properties file.

          --db type, --database name, --host host, --port port
              Construct a JDBC URL for a supported logical database type.

          --user username, --password password
              Set credentials for the simple JDBC connection form.

          --jdbc-driver name, --jdbc-class-name class, --jdbc-database url,
          --jdbc-username username, --jdbc-password password
              Set exact JDBC connection properties.

          --output type, -o type
              Write xml, json, jsonl, yaml, csv, or markdown output.

          --debug
              Log inference decisions and other diagnostic details to stderr.

          --no-key-inference
              Disable automatic DQL structure-key inference and preserve
              row-first output for paths without explicit structure keys.

          --metadata-refresh
              Rebuild cached database key and relationship metadata for the
              selected JDBC or input-cache target, then exit.

          --metadata-expiry-hours hours
              Persist the selected target's metadata expiry. Zero refreshes
              metadata on every use; the default is 24 hours.

          --cache, -c
              Persist, select, or query a file-backed local H2 cache. Without
              this option, a script and input file use temporary in-memory H2.
              An explicit cache takes precedence over JDBC settings.

          --use-cache input-file-or-cache-filename
              Make an existing input-file cache active without loading or
              rebuilding it.

          --cache-dir path
              Store caches somewhere other than ~/.nq/cache.

          --anonymous-collections merge|error
              Merge several unresolved anonymous paths into ITEM with a warning,
              or reject that ambiguous materialization. The default is merge.

          --relation-alias source-path=relation-name
              Assign a stable relation name to a source path. Repeat for more
              paths. This option and the anonymous policy select cache variants.

          --list-caches
              List persistent caches, variants, and source files. Incompatible
              old input layouts are marked outdated.

          --clear-cache [input-file-or-cache-filename]
              Clear all caches, every variant for one input file, or one named
              cache file.

          --clear-cache-older-than age
              Clear caches older than an age such as 30m, 6h, or 7d.

          --parquet-root name, --parquet-record name
              Override names inferred from a Parquet file and message schema.

          name=value
              Supply a runtime template parameter.

      FILES
          ~/.nq/config.properties
              Stores the active cache selection.

          ~/.nq/cache
              Default directory for persistent input-file caches.

      EXAMPLES
          nq report.nq -p database.properties
          nq update.nq customers.json -p database.properties region=EMEA
          nq customers.xml --output json
          nq --cache customers.json
          nq --use-cache customers.json
          nq catalog
          nq catalog 'customer*' --output json
          nq query.nq
          nq query.nq customers.json --cache --output json
          nq --list-caches
          nq --clear-cache-older-than 7d

      SEE ALSO
          nq --help help
          https://github.com/blater/nq
      """;

  public static void printBriefHelp() {
    System.out.print(USAGE);
    System.out.println("Run 'nq --help' for the complete manual or 'nq --help help' for topics.");
  }

  public static void printCommandInfo(String command) {
    String normalized = command == null ? "" : command.strip().toLowerCase();
    String info = switch (normalized) {
      case "help" -> HELP_ON_HELP;
      case "query", "run" -> QUERY_CMD;
      case "catalog" -> CATALOG_CMD;
      case "cache" -> CACHE_CMD;
      case "use-cache", "use" -> USE_CACHE_CMD;
      case "clear-cache", "clear" -> CLEAR_CACHE_CMD;
      case "list-caches", "list" -> LIST_CACHES_CMD;
      case "connection", "database", "db", "jdbc" -> CONNECTION_CMD;
      case "output" -> OUTPUT_CMD;
      case "parameters", "parameter", "params" -> PARAMETERS_CMD;
      case "parquet" -> PARQUET_CMD;
      default -> """
          Unknown help topic: %s

          Run 'nq --help help' to list available topics.
          """.formatted(command);
    };
    System.out.print(info);
  }

  public static void printManPage() {
    System.out.print(MAN_PAGE);
  }
}

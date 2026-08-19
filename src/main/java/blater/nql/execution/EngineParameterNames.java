package blater.nql.execution;

/** Internal map keys retained at the boundary with the existing query/mapping engine. */
public final class EngineParameterNames {
  public static final String INPUT_FILENAME = "NSQL_INPUTFILE";
  public static final String INPUT_TYPE = "NSQL_INPUT_TYPE";
  public static final String STANDARD_INPUT = "-";
  public static final String OUTPUT_TYPE = "NSQL_OUTPUT_TYPE";
  public static final String DEBUG = "NSQL_DEBUG";
  public static final String NO_KEY_INFERENCE = "NSQL_NO_KEY_INFERENCE";
  public static final String PARQUET_ROOT = "NSQL_PARQUET_ROOT";
  public static final String PARQUET_RECORD = "NSQL_PARQUET_RECORD";
  public static final String JDBC_DRIVER = "jdbc.driver";
  public static final String JDBC_CLASS_NAME = "jdbc.class.name";
  public static final String JDBC_DATABASE = "jdbc.database";
  public static final String JDBC_USERNAME = "jdbc.username";
  public static final String JDBC_PASSWORD = "jdbc.password";

  private EngineParameterNames() {
  }
}

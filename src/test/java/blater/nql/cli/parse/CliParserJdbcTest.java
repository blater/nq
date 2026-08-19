package blater.nql.cli.parse;

import blater.nql.cli.Credentials;
import blater.nql.cli.DriverSelection;
import blater.nql.cli.ExecutionTarget;
import blater.nql.cli.RunInvocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliParserJdbcTest {
  private final CliParser parser = new CliParser();

  @Test
  void exactJdbcUrlIsSufficientAndUsesAutomaticDriverDiscovery() {
    var invocation = assertInstanceOf(
        RunInvocation.class,
        parser.parse("report.nql", "--jdbc-database", "jdbc:postgresql://db/customers"));

    var target = assertInstanceOf(ExecutionTarget.Jdbc.class, invocation.target());
    assertEquals("jdbc:postgresql://db/customers", target.connection().url());
    assertInstanceOf(DriverSelection.Automatic.class, target.connection().driver());
    assertEquals(Credentials.UNSPECIFIED, target.connection().credentials());
  }

  @Test
  void exactJdbcFormPreservesExplicitlyEmptyCredentialsAndDriverHint() {
    var invocation = assertInstanceOf(
        RunInvocation.class,
        parser.parse(
            "report.nql", "--jdbc-database", "jdbc:h2:mem:test",
            "--jdbc-driver", "H2DB", "--user", "", "--password", ""));

    var connection = assertInstanceOf(ExecutionTarget.Jdbc.class, invocation.target()).connection();
    var driver = assertInstanceOf(DriverSelection.Known.class, connection.driver());
    assertEquals("h2", driver.value());
    assertEquals(new Credentials.Value.Specified(""), connection.credentials().username());
    assertEquals(new Credentials.Value.Specified(""), connection.credentials().password());
  }

  @Test
  void simpleJdbcFormBuildsTheExistingCanonicalUrl() {
    var invocation = assertInstanceOf(
        RunInvocation.class,
        parser.parse(
            "report.nql", "--db", "POSTGRES", "--database", "customers",
            "--host", "db.example", "--port", "6543", "--user", "agent"));

    var connection = assertInstanceOf(ExecutionTarget.Jdbc.class, invocation.target()).connection();
    assertEquals("jdbc:postgresql://db.example:6543/customers", connection.url());
    assertEquals(new DriverSelection.Known("postgresql"), connection.driver());
    assertEquals(new Credentials.Value.Specified("agent"), connection.credentials().username());
  }

  @Test
  void customDriverClassIsAnExplicitVariant() {
    var invocation = assertInstanceOf(
        RunInvocation.class,
        parser.parse(
            "report.nql", "--jdbc-database", "jdbc:vendor:customers",
            "--jdbc-class-name", "example.Driver"));

    var connection = assertInstanceOf(ExecutionTarget.Jdbc.class, invocation.target()).connection();
    assertEquals(new DriverSelection.ClassName("example.Driver"), connection.driver());
  }

  @Test
  void rejectsContradictoryConnectionFormsAndAliases() {
    assertUsage("report.nql", "--db", "h2", "--database", "mem:test",
        "--jdbc-database", "jdbc:h2:mem:test");
    assertUsage("report.nql", "--jdbc-database", "jdbc:h2:mem:test",
        "--jdbc-driver", "h2", "--jdbc-class-name", "org.h2.Driver");
    assertUsage("report.nql", "--jdbc-database", "jdbc:h2:mem:test",
        "--user", "one", "--jdbc-username", "two");
    assertUsage("report.nql", "--jdbc-database", "jdbc:h2:mem:test", "--cache");
  }

  @Test
  void rejectsIncompleteSimpleAndExactForms() {
    assertUsage("report.nql", "--db", "h2");
    assertUsage("report.nql", "--database", "mem:test");
    assertUsage("report.nql", "--host", "db.example");
    assertUsage("report.nql", "--jdbc-driver", "h2");
    assertUsage("report.nql", "--user", "agent");
  }

  private void assertUsage(String... arguments) {
    assertThrows(CliUsageException.class, () -> parser.parse(arguments));
  }
}

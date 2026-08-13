package blater.nq.cli.parse;

import blater.nq.cli.Credentials;
import blater.nq.cli.DriverSelection;
import blater.nq.cli.ExecutionTarget;
import blater.nq.cli.RunInvocation;
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
        parser.parse("report.nq", "--jdbc-database", "jdbc:postgresql://db/customers"));

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
            "report.nq", "--jdbc-database", "jdbc:h2:mem:test",
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
            "report.nq", "--db", "POSTGRES", "--database", "customers",
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
            "report.nq", "--jdbc-database", "jdbc:vendor:customers",
            "--jdbc-class-name", "example.Driver"));

    var connection = assertInstanceOf(ExecutionTarget.Jdbc.class, invocation.target()).connection();
    assertEquals(new DriverSelection.ClassName("example.Driver"), connection.driver());
  }

  @Test
  void rejectsContradictoryConnectionFormsAndAliases() {
    assertUsage("report.nq", "--db", "h2", "--database", "mem:test",
        "--jdbc-database", "jdbc:h2:mem:test");
    assertUsage("report.nq", "--jdbc-database", "jdbc:h2:mem:test",
        "--jdbc-driver", "h2", "--jdbc-class-name", "org.h2.Driver");
    assertUsage("report.nq", "--jdbc-database", "jdbc:h2:mem:test",
        "--user", "one", "--jdbc-username", "two");
    assertUsage("report.nq", "--jdbc-database", "jdbc:h2:mem:test", "--cache");
  }

  @Test
  void rejectsIncompleteSimpleAndExactForms() {
    assertUsage("report.nq", "--db", "h2");
    assertUsage("report.nq", "--database", "mem:test");
    assertUsage("report.nq", "--host", "db.example");
    assertUsage("report.nq", "--jdbc-driver", "h2");
    assertUsage("report.nq", "--user", "agent");
  }

  private void assertUsage(String... arguments) {
    assertThrows(CliUsageException.class, () -> parser.parse(arguments));
  }
}

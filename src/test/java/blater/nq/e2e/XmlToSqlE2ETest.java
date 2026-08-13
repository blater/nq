package blater.nq.e2e;

import blater.nq.testsupport.H2Database;
import blater.nq.testsupport.XmlTestHelpers;
import org.jdom2.Element;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static blater.nq.testsupport.XmlTestHelpers.runScriptString;

public class XmlToSqlE2ETest {
  @Test
  public void updatesMenuRowsFromXmlMessage() throws Exception {
    try (H2Database database = new H2Database()) {

      //
      // given a database with "Old Toast" as item 12 on the menu...
      //
      database.execute(
          "create table menu (" +
              "dishid integer primary key, " +
              "dishname varchar(80), " +
              "dishtype varchar(40), " +
              "price decimal(10,2))",
          "insert into menu (dishid, dishname, dishtype, price) values (12, 'Old Toast', 'OLD', 0.50)");

      //
      // When I have an update script
      //
      String script = """
          autocommit on
          ;
          update menu
          set dishname = {message.dish.dishname},
              dishtype = {message.dish.dishtype},
              price = {message.dish.prices.retail}
          where dishid = {message.dish.@id}
          ;
          """;
      //
      // from an xml file, which sets dish 12  to Dry Toast
      //
      String inputXml = "<message>" +
          "<dish id=\"12\">" +
          "<dishname>Dry Toast</dishname>" +
          "<dishtype>MAIN</dishtype>" +
          "<prices><retail>1.50</retail></prices>" +
          "</dish>" +
          "</message>";

      //
      // and I run the script to apply DB updates
      //
      runScriptString(database, script, inputXml);

      assertEquals("Dry Toast", database.queryString("select dishname from menu where dishid = 12"));
      assertEquals("MAIN", database.queryString("select dishtype from menu where dishid = 12"));
      assertEquals(new BigDecimal("1.50"), database.queryDecimal("select price from menu where dishid = 12"));
    }
  }

  @Test
  public void insertsPersonAndReturnsGeneratedKeyIntoXml() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer auto_increment primary key, " +
              "firstname varchar(80), " +
              "lastname varchar(80), " +
              "dob varchar(20))");

      String script = """
          autocommit on
          ;
          insert into person (firstname, lastname, dob)
          values ({person.forename}, {person.surname}, {person.dateOfBirth})
          returns personid into {person.id}
          ;
          """;
      String inputXml = "<person>" +
          "<forename>Fred</forename>" +
          "<surname>Flintstone</surname>" +
          "<dateOfBirth>1952-02-02</dateOfBirth>" +
          "</person>";

      Element rootElement = runScriptString(database, script, inputXml);

      assertEquals(1, database.queryInt("select count(*) from person"));
      assertEquals("Fred", database.queryString("select firstname from person"));
      assertEquals("Flintstone", database.queryString("select lastname from person"));
      assertEquals("1", rootElement.getChildTextTrim("id"));
    }
  }

  @Test
  public void insertsRepeatedChildRowsFromNestedXml() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table nickname (" +
              "nicknameid integer primary key, " +
              "personid integer, " +
              "nickname varchar(80))");

      String script = """
          autocommit on
          ;
          insert into nickname (nicknameid, personid, nickname)
          values ({message.person.nickname.@id}, {message.person.@id}, {message.person.nickname})
          ;
          """;
      String inputXml = "<message>" +
          "<person id=\"7\">" +
          "<nickname id=\"10\">Ace</nickname>" +
          "<nickname id=\"11\">Al</nickname>" +
          "</person>" +
          "</message>";

      Element rootElement = runScriptString(database, script, inputXml);

      assertEquals(2, database.queryInt("select count(*) from nickname"));
      assertEquals(7, database.queryInt("select personid from nickname where nicknameid = 10"));
      assertEquals("Ace", database.queryString("select nickname from nickname where nicknameid = 10"));
      assertEquals(7, database.queryInt("select personid from nickname where nicknameid = 11"));
      assertEquals("Al", database.queryString("select nickname from nickname where nicknameid = 11"));

      Element person = rootElement.getChild("person");
      List<Element> nicknames = person.getChildren("nickname");
      assertEquals(2, nicknames.size());
    }
  }

  @Test
  public void mappedInsertWithoutColumnListUsesTableColumnOrder() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table audit_log (personid integer, firstname varchar(80))");

      String script = """
          autocommit on
          ;
          insert into audit_log values ({message.person.id}, {message.person.firstname})
          ;
          """;
      String inputXml = """
          <message>
            <person>
              <id>7</id>
              <firstname>Fred</firstname>
            </person>
          </message>
          """;

      runScriptString(database, script, inputXml);

      assertEquals(1, database.queryInt("select count(*) from audit_log"));
      assertEquals(7, database.queryInt("select personid from audit_log"));
      assertEquals("Fred", database.queryString("select firstname from audit_log"));
    }
  }

  @Test
  public void updatesDbAssignedValueBackIntoInputXmlAfterDatabaseRefresh() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer primary key, " +
              "lastupdated varchar(80))",
          "insert into person (personid, lastupdated) values (7, 'old')");

      String script = """
          autocommit on
          ;
          update person
          set lastupdated = upper({person.lastUpdated})
          where personid = {person.@id}
          returns lastupdated into {person.lastUpdated}
          ;
          """;
      String inputXml = "<person id=\"7\"><lastUpdated>client</lastUpdated></person>";

      Element rootElement = runScriptString(database, script, inputXml);

      assertEquals("CLIENT", database.queryString("select lastupdated from person where personid = 7"));
      assertEquals("CLIENT", rootElement.getChildTextTrim("lastUpdated"));
    }
  }

  @Test
  public void updateWritesMultipleReturnedValuesBackIntoInputXml() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer primary key, " +
              "firstname varchar(80), " +
              "lastupdated varchar(80), " +
              "version integer)",
          "insert into person (personid, firstname, lastupdated, version) values (7, 'old', 'old', 3)");

      String script = """
          autocommit on
          ;
          update person
          set firstname = {person.firstname},
              lastupdated = upper({person.lastUpdated}),
              version = {person.version}
          where personid = {person.@id}
          returns lastupdated into {person.lastUpdated},
                  version into {person.version}
          ;
          """;
      String inputXml = "<person id=\"7\"><firstname>Fred</firstname><lastUpdated>client</lastUpdated><version>4</version></person>";

      Element rootElement = runScriptString(database, script, inputXml);

      assertEquals("Fred", database.queryString("select firstname from person where personid = 7"));
      assertEquals("CLIENT", rootElement.getChildTextTrim("lastUpdated"));
      assertEquals("4", rootElement.getChildTextTrim("version"));
    }
  }

  @Test
  public void updatesRowsUsingSqlLikeDmlExpressions() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer primary key, " +
              "firstname varchar(80), " +
              "lastname varchar(80), " +
              "lastupdated varchar(80))",
          "insert into person (personid, firstname, lastname, lastupdated) values (7, 'old', 'old', 'old')");

      String script = """
          autocommit on
          ;
          update person
          set firstname = upper({person.firstname}),
              lastname = coalesce({person.lastname}, 'Unknown'),
              lastupdated = coalesce({person.lastUpdated}, 'fallback')
          where personid = {person.@id}
          ;
          """;
      String inputXml = "<person id=\"7\"><firstname>fred</firstname></person>";

      runScriptString(database, script, inputXml);

      assertEquals("FRED", database.queryString("select firstname from person where personid = 7"));
      assertEquals("Unknown", database.queryString("select lastname from person where personid = 7"));
      assertEquals("fallback", database.queryString("select lastupdated from person where personid = 7"));
    }
  }

  @Test
  public void crossFlowCapturesQueryRowsAndPersistsThroughTemp() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (personid integer primary key, firstname varchar(80), surname varchar(80))",
          "insert into person (personid, firstname, surname) values (1, 'Alice', 'Adams')",
          "insert into person (personid, firstname, surname) values (2, 'Bob', 'Baker')",
          "create table audit_log (personid integer, firstname varchar(80))");

      String script =
          "autocommit on\n;\n" +
              "capture 'people'\n" +
              "select personid, firstname from person where personid < 10 order by personid;\n" +
              "insert into audit_log from temp 'people' (personid, firstname)\n" +
              "values ({personid}, {firstname})\n" +
              ";\n";

      XmlTestHelpers.runScriptString(database, script);

      assertEquals(2, database.queryInt("select count(*) from audit_log"));
      assertEquals("Alice", database.queryString("select firstname from audit_log where personid = 1"));
      assertEquals("Bob", database.queryString("select firstname from audit_log where personid = 2"));
    }
  }

  @Test
  public void fromTempUpdateUsesWherePredicateRows() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (personid integer primary key, firstname varchar(80))",
          "insert into person (personid, firstname) values (1, 'Alice')",
          "insert into person (personid, firstname) values (2, 'Bob')",
          "create table audit_log (personid integer primary key, firstname varchar(80))",
          "insert into audit_log (personid, firstname) values (1, 'old')",
          "insert into audit_log (personid, firstname) values (2, 'old')");

      String script =
          "autocommit on\n;\n" +
              "capture 'people'\n" +
              "select personid, firstname from person order by personid;\n" +
              "update audit_log from temp 'people'\n" +
              "set firstname = {firstname}\n" +
              "where personid = {personid}\n" +
              ";\n";

      XmlTestHelpers.runScriptString(database, script);

      assertEquals(2, database.queryInt("select count(*) from audit_log"));
      assertEquals("Alice", database.queryString("select firstname from audit_log where personid = 1"));
      assertEquals("Bob", database.queryString("select firstname from audit_log where personid = 2"));
      assertEquals(0, database.queryInt("select count(*) from audit_log where firstname = 'old'"));
    }
  }

  @Test
  public void fromTempDeleteUsesCapturedKeyRows() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (personid integer primary key, firstname varchar(80))",
          "insert into person (personid, firstname) values (1, 'Alice')",
          "insert into person (personid, firstname) values (2, 'Bob')",
          "create table audit_log (personid integer primary key, firstname varchar(80))",
          "insert into audit_log (personid, firstname) values (1, 'Alice')",
          "insert into audit_log (personid, firstname) values (2, 'Bob')",
          "insert into audit_log (personid, firstname) values (3, 'Charlie')");

      String script =
          """
              autocommit on
              ;
              capture 'people'
              select personid from person order by personid;
              delete from audit_log from temp 'people'
              where personid = {personid}
              ;
              """;

      XmlTestHelpers.runScriptString(database, script);

      assertEquals(1, database.queryInt("select count(*) from audit_log"));
      assertEquals("Charlie", database.queryString("select firstname from audit_log where personid = 3"));
    }
  }


}

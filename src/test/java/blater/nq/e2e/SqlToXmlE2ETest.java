package blater.nq.e2e;

import blater.nq.parser.ScriptLoader;
import blater.nq.parser.ScriptParser;
import blater.nq.parser.script.NestScript;
import blater.nq.testsupport.H2Database;
import org.jdom2.Element;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static blater.nq.testsupport.XmlTestHelpers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class SqlToXmlE2ETest {

  @Test
  public void rendersNoSchemaPersonListFromSqlSelect() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer primary key, " +
              "firstname varchar(80), " +
              "surname varchar(80))",
          "insert into person (personid, firstname, surname) values (1, 'Alice', 'Adams')",
          "insert into person (personid, firstname, surname) values (2, 'Bob', 'Baker')");

      NestScript script = ScriptParser.parse(
          """
              select
                personid,
                firstname into {people.person.firstname},
                surname into {people.person.surname}
              from person
              order by personid asc createsNew {people.person}\\G
              """);

      Element people = runScript(database, script);

      assertEquals("people", people.getName());
      List<Element> persons = children(people, "person");
      assertEquals(2, persons.size());
      assertChildText(persons.get(0), "firstname", "Alice");
      assertChildText(persons.get(0), "surname", "Adams");
      assertChildText(persons.get(1), "firstname", "Bob");
      assertChildText(child(people, "person"), "firstname", "Alice");
    }
  }

  @Test
  public void createsNestedNicknameElementsWithXmlUnionThrowNew() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer primary key, " +
              "firstname varchar(80), " +
              "surname varchar(80))",
          "create table nickname (" +
              "nicknameid integer primary key, " +
              "personid integer, " +
              "nickname varchar(80))",
          "insert into person (personid, firstname, surname) values (1, 'Alice', 'Adams')",
          "insert into person (personid, firstname, surname) values (2, 'Bob', 'Baker')",
          "insert into nickname (nicknameid, personid, nickname) values (10, 1, 'Ace')",
          "insert into nickname (nicknameid, personid, nickname) values (11, 1, 'Al')",
          "insert into nickname (nicknameid, personid, nickname) values (20, 2, 'Bee')");

      NestScript script = ScriptParser.parse(
          """
              select
                p.personid,
                p.firstname into {people.person.firstname},
                p.surname into {people.person.surname}
              from person p
              xmlunion
              select
                p.personid,
                n.nicknameid,
                n.nickname into {people.person.nickname}
              from person p, nickname n
              where p.personid = n.personid
              order by personid asc createsNew {people.person},
                nicknameid asc createsNew {people.person.nickname}\\G
              """);

      Element people = runScript(database, script);

      List<Element> persons = children(people, "person");
      assertEquals(2, persons.size());
      assertChildText(persons.get(0), "firstname", "Alice");
      assertEquals("Ace", persons.get(0).getChildren("nickname").get(0).getTextTrim());
      assertEquals("Al", persons.get(0).getChildren("nickname").get(1).getTextTrim());
      assertChildText(persons.get(1), "firstname", "Bob");
      assertEquals("Bee", persons.get(1).getChildren("nickname").get(0).getTextTrim());
    }
  }

  @Test
  public void createsIndependentSiblingCollectionsWithXmlUnion() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table customer (customerid integer primary key)",
          "create table phone (phoneid integer primary key, customerid integer, number varchar(80))",
          "create table email (emailid integer primary key, customerid integer, address varchar(80))",
          "insert into customer (customerid) values (1)",
          "insert into phone (phoneid, customerid, number) values (10, 1, '0207 000 0001')",
          "insert into phone (phoneid, customerid, number) values (11, 1, '0207 000 0002')",
          "insert into email (emailid, customerid, address) values (20, 1, 'one@example.com')",
          "insert into email (emailid, customerid, address) values (21, 1, 'two@example.com')");

      NestScript script = ScriptParser.parse(
          """
              select
                c.customerid,
                p.phoneid,
                c.customerid into {customers.customer.id},
                p.number into {customers.customer.phone.number}
              from customer c
              join phone p on p.customerid = c.customerid
              xmlunion
              select
                c.customerid,
                e.emailid,
                c.customerid into {customers.customer.id},
                e.address into {customers.customer.email.address}
              from customer c
              join email e on e.customerid = c.customerid
              order by
                customerid asc createsNew {customers.customer},
                phoneid asc createsNew {customers.customer.phone},
                emailid asc createsNew {customers.customer.email}\\G
              """);

      Element customers = runScript(database, script);

      List<Element> customer = children(customers, "customer");
      assertEquals(1, customer.size());
      assertChildText(customer.getFirst(), "id", "1");
      assertEquals(List.of("0207 000 0001", "0207 000 0002"),
          customer.getFirst().getChildren("phone").stream()
              .map(phone -> child(phone, "number").getTextTrim())
              .toList());
      assertEquals(List.of("one@example.com", "two@example.com"),
          customer.getFirst().getChildren("email").stream()
              .map(email -> child(email, "address").getTextTrim())
              .toList());
    }
  }

  @Test
  public void createsSiblingBranchesForOneOrderItemWithAmpersandCreatesNew() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer primary key, " +
              "firstname varchar(80), " +
              "surname varchar(80))",
          "insert into person (personid, firstname, surname) values (1, 'Alice', 'Adams')",
          "insert into person (personid, firstname, surname) values (2, 'Bob', 'Baker')");

      NestScript script = ScriptParser.parse(
          """
              select
                personid,
                firstname into {people.person.firstname},
                surname into {people.audit.surname}
              from person
              order by personid asc
                createsNew {people.person}
                & createsNew {people.audit}\\G
              """);

      Element people = runScript(database, script);

      List<Element> persons = children(people, "person");
      List<Element> audits = children(people, "audit");
      assertEquals(2, persons.size());
      assertEquals(2, audits.size());
      assertChildText(persons.get(0), "firstname", "Alice");
      assertChildText(audits.get(0), "surname", "Adams");
      assertChildText(persons.get(1), "firstname", "Bob");
      assertChildText(audits.get(1), "surname", "Baker");
    }
  }

  @Test
  public void appliesAppendCommandInNoSchemaOutput() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer primary key, " +
              "firstname varchar(80), " +
              "surname varchar(80))",
          "insert into person (personid, firstname, surname) values (1, 'Alice', 'Adams')");

      NestScript script = ScriptParser.parse(
          """
              select
                personid,
                firstname into {people.person.name:append(space)},
                surname into {people.person.name:append(space)}
              from person
              order by personid asc createsNew {people.person}\\G
              """);
      Element rootElement = runScript(database, script);
      List<Element> persons = children(rootElement, "person");

      assertEquals(1, persons.size());
      assertChildText(persons.getFirst(), "name", "Alice Adams");
    }
  }

  @Test
  public void mapsCaseProjectedValuesAndKeepsNullsByDefault() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer primary key, " +
              "name varchar(80), " +
              "category varchar(10), " +
              "age integer, " +
              "surname varchar(80), " +
              "code varchar(80), " +
              "label varchar(80), " +
              "path_value varchar(120))",
          "insert into person values (1, 'Alice', 'A', 20, 'Smithson', 'A_1', '50%off', '/users/7/profileA/edit')",
          "insert into person values (2, 'Bob', 'B', 70, 'Baker', 'TEMP9', 'plain', '/internal/7')");

      NestScript script = ScriptParser.parse(
          """
              select
                personid,
                case when category = 'A' then name end into {people.person.eq},
                case when category <> 'B' then name end into {people.person.neAngle},
                case when category != 'B' then name end into {people.person.neBang},
                case when age < 21 then name end into {people.person.lt},
                case when age <= 20 then name end into {people.person.le},
                case when age > 19 then name end into {people.person.gt},
                case when age >= 20 then name end into {people.person.ge},
                case when surname like 'Smith%' then name end into {people.person.like},
                case when path_value like '/users/%/profile_/%' then name end into {people.person.multiLike},
                case when code like 'A\\_%' then name end into {people.person.escapedLike},
                case when label like '50$%off' escape '$' then name end into {people.person.customEscapeLike},
                case when code not like 'TEMP%' then name end into {people.person.notLike},
                case when category = 'Z' then name end into {people.person.keptNull},
                case when category = 'Z' then name end into {people.person.absentNull} absent on null
              from person
              order by personid asc createsNew {people.person}\\G
              """);

      Element rootElement = runScript(database, script);

      List<Element> persons = children(rootElement, "person");
      assertEquals(2, persons.size());
      Element alice = persons.getFirst();
      assertChildText(alice, "eq", "Alice");
      assertChildText(alice, "neAngle", "Alice");
      assertChildText(alice, "neBang", "Alice");
      assertChildText(alice, "lt", "Alice");
      assertChildText(alice, "le", "Alice");
      assertChildText(alice, "gt", "Alice");
      assertChildText(alice, "ge", "Alice");
      assertChildText(alice, "like", "Alice");
      assertChildText(alice, "multiLike", "Alice");
      assertChildText(alice, "escapedLike", "Alice");
      assertChildText(alice, "customEscapeLike", "Alice");
      assertChildText(alice, "notLike", "Alice");
      assertEquals("true", child(alice, "keptNull").getAttributeValue("nil"));
      assertNull(alice.getChild("absentNull"));

      Element bob = persons.get(1);
      assertEquals("true", child(bob, "eq").getAttributeValue("nil"));
      assertEquals("true", child(bob, "notLike").getAttributeValue("nil"));
      assertEquals("true", child(bob, "keptNull").getAttributeValue("nil"));
      assertNull(bob.getChild("absentNull"));
    }
  }

  @Test
  public void schemaDeclarationDoesNotCreateParallelOutputMetadata() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer primary key, " +
              "firstname varchar(80), " +
              "surname varchar(80), " +
              "nickname varchar(80))",
          "insert into person (personid, firstname, surname, nickname) values (1, 'Alice', 'Adams', 'Ace')");

      Path schemaPath = Files.createTempFile("people", ".xsd");
      Files.writeString(schemaPath, """
          <?xml version="1.0" encoding="UTF-8"?>
          <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
            <xs:element name="people">
              <xs:complexType>
                <xs:sequence>
                  <xs:element name="person" maxOccurs="unbounded">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="firstname" type="xs:string"/>
                        <xs:element name="middlename" type="xs:string"/>
                        <xs:element name="surname" type="xs:string"/>
                        <xs:element name="nickname" type="xs:string" minOccurs="0"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:sequence>
              </xs:complexType>
            </xs:element>
          </xs:schema>
          """);

      NestScript script = ScriptParser.parse(
          "select using schema '" + schemaPath.toAbsolutePath() + "' xmlroot = people\n" +
              "  personid,\n" +
              "  surname into {people.person.surname},\n" +
              "  firstname into {people.person.firstname},\n" +
              "  nickname into {people.person.nickname}\n" +
              "from person\n" +
              "order by personid asc createsNew {people.person}\\G\n");

      Element rootElement = runScript(database, script);
      Element person = child(rootElement, "person");

      assertEquals(Arrays.asList("surname", "firstname", "nickname"), childNames(person));
      assertChildText(person, "firstname", "Alice");
      assertChildText(person, "surname", "Adams");
      assertChildText(person, "nickname", "Ace");
    }
  }

  @Test
  public void processesIncludeSubmappingParametersAndGoStatements() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer primary key, " +
              "firstname varchar(80), " +
              "surname varchar(80))",
          "insert into person (personid, firstname, surname) values (1, 'Alice', 'Adams')",
          "insert into person (personid, firstname, surname) values (2, 'Bob', 'Baker')");

      Path scriptDirectory = Files.createTempDirectory("hiql-scripts");
      Files.writeString(scriptDirectory.resolve("setup.sql"), """
          literal create table audit (message varchar(80))\\G
          insert into audit (message) values ('include-ran')\\G
          """);
      Files.writeString(scriptDirectory.resolve("summary.stx"), """
          select
            personid,
            firstname into {people.summary}
          from person
          where firstname like '${root.namePrefix}%'
          order by personid asc\\G
          """);
      Files.writeString(scriptDirectory.resolve("main.stx"), """
          autocommit on
          \\g
          include 'setup.sql'
          select
            personid,
            firstname into {people.person.firstname},
            surname into {people.person.surname}
          from person
          where firstname like '${root.namePrefix}%'
          order by personid asc createsNew {people.person}\\G
          insert into audit (message) values ('finish-ran')\\G
          submapping 'summary.stx'
          """);

      String text = ScriptLoader.load(scriptDirectory.resolve("main.stx").toString());
      NestScript script = ScriptParser.parse(text);
      Element rootElement = runScript(database, script, Map.of("root.namePrefix", "A"));

      List<Element> persons = children(rootElement, "person");
      assertEquals(1, persons.size());
      assertChildText(persons.getFirst(), "firstname", "Alice");
      assertChildText(persons.getFirst(), "surname", "Adams");
      assertChildText(rootElement, "summary", "Alice");
      assertEquals(2, database.queryInt("select count(*) from audit"));
    }
  }

  @Test
  public void mixesRawSqlAndSelectStatementInOneScript() throws Exception {
    try (H2Database database = new H2Database()) {
      NestScript script = ScriptParser.parse(
          """
              literal create table person (personid integer primary key, firstname varchar(80))\\G
              insert into person values (1, 'Alice')\\G
              insert into person values (2, 'Bob')\\G
              select personid, firstname into {people.person.name} from person
              order by personid asc createsNew {people.person}\\G
              """);

      Element rootElement = runScript(database, script);
      List<Element> persons = children(rootElement, "person");

      assertEquals(2, persons.size());
      assertChildText(persons.get(0), "name", "Alice");
      assertChildText(persons.get(1), "name", "Bob");
    }
  }
}

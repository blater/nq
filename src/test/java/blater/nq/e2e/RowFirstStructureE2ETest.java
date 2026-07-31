package blater.nq.e2e;

import blater.nq.parser.ScriptParser;
import blater.nq.parser.script.NestScript;
import blater.nq.testsupport.H2Database;
import org.jdom2.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blater.nq.testsupport.XmlTestHelpers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RowFirstStructureE2ETest {

  @Test
  void materializesOneObjectPerRowWithoutStructureKeys() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table company (id integer primary key, name varchar(80))",
          "insert into company values (1, 'Acme')",
          "insert into company values (2, 'Blair Ltd')");

      Element result = runScript(database, ScriptParser.parse(
          """
              select id into {companies.company.id}, name into {companies.company.name}
              from company order by id
              \\g
              """));

      assertEquals(2, result.getChildren("companies").size());
      assertChildText(child(result.getChildren("companies").get(0), "company"), "name", "Acme");
      assertChildText(child(result.getChildren("companies").get(1), "company"), "name", "Blair Ltd");
    }
  }

  @Test
  void hierarchyUnionPreservesBranchRowsAndSharesKeyedObjects() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table customer (id integer primary key)",
          "create table phone (id integer primary key, customer_id integer, number varchar(80))",
          "create table email (id integer primary key, customer_id integer, address varchar(80))",
          "insert into customer values (1)",
          "insert into phone values (10, 1, '0207 000 0001')",
          "insert into email values (20, 1, 'one@example.com')");

      Element customers = runScript(database, ScriptParser.parse(
          """
              select
                c.id into {customers.customer.id},
                p.id into {customers.customer.phone.id},
                p.number into {customers.customer.phone.number}
              from customer c join phone p on p.customer_id = c.id
              hierarchy union
              select
                c.id into {customers.customer.id},
                e.id into {customers.customer.email.id},
                e.address into {customers.customer.email.address}
              from customer c join email e on e.customer_id = c.id
              structure
                {customers.customer} key (c.id),
                {customers.customer.phone} key (p.id),
                {customers.customer.email} key (e.id)
              \\g
              """));

      Element customer = child(customers, "customer");
      assertEquals(1, customer.getChildren("phone").size());
      assertEquals(1, customer.getChildren("email").size());
      assertChildText(child(customer, "phone"), "number", "0207 000 0001");
      assertChildText(child(customer, "email"), "address", "one@example.com");
    }
  }

  @Test
  void coalescesIndependentRepeatedSiblingsFromOneJoinedRowset() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table customer (id integer primary key, name varchar(80))",
          "create table phone (id integer primary key, customer_id integer, number varchar(80))",
          "create table email (id integer primary key, customer_id integer, address varchar(80))",
          "insert into customer values (1, 'Acme')",
          "insert into phone values (10, 1, '0207 000 0001')",
          "insert into phone values (11, 1, '0207 000 0002')",
          "insert into email values (20, 1, 'one@example.com')",
          "insert into email values (21, 1, 'two@example.com')");

      NestScript script = ScriptParser.parse(
          """
              select
                c.id into {customers.customer.id},
                c.name into {customers.customer.name},
                p.id into {customers.customer.phone.id},
                p.number into {customers.customer.phone.number},
                e.id into {customers.customer.email.id},
                e.address into {customers.customer.email.address}
              from customer c
              join phone p on p.customer_id = c.id
              join email e on e.customer_id = c.id
              order by c.name, p.id, e.id
              structure
                {customers.customer} key (c.id),
                {customers.customer.phone} key (p.id),
                {customers.customer.email} key (e.id)
              \\g
              """);

      Element customers = runScript(database, script);
      Element customer = child(customers, "customer");
      assertChildText(customer, "id", "1");
      assertChildText(customer, "name", "Acme");
      assertEquals(List.of("0207 000 0001", "0207 000 0002"), customer.getChildren("phone").stream()
          .map(phone -> child(phone, "number").getTextTrim())
          .toList());
      assertEquals(List.of("one@example.com", "two@example.com"), customer.getChildren("email").stream()
          .map(email -> child(email, "address").getTextTrim())
          .toList());
    }
  }
}

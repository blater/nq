package blater.nql.outputwriter;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;
import blater.nql.util.Log;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

/*
 * Responsibility: Renders a tree as a JDOM XML document and applies the
 * XML-only namespace hint. Nodes flagged as attributes render as attributes
 * on their parent element.
 */
public class XmlOutputWriter implements OutputWriter {
  public void write(Hierarchy doc) {
    if (doc == null || doc.isEmpty()) {
      return;
    }
    Document xml = map(doc);
    XMLOutputter xmlout = new XMLOutputter();
    xmlout.setFormat(Format.getPrettyFormat());
    try {
      xmlout.output(xml, System.out);
    } catch (Exception ex) {
      Log.error("Exception writing the result XML", ex);
    }
  }

  public static Document map(Hierarchy hierarchy) {
    Node rootNode = hierarchy.getRoot();
    if (rootNode == null || rootNode.getName() == null)
      return new Document(); // empty doc

    Node contentNode = rootNode;
    String rootName = hierarchy.getRootKind() == Hierarchy.RootKind.NAMED
        ? rootNode.getName()
        : "result";
    var rootElement = new Element(rootName);
    if (hierarchy.hasNamespace())
      rootElement.setNamespace(Namespace.getNamespace(hierarchy.getNamespace()));

    if (contentNode.isCollection()) {
      writeAnonymousCollection(rootElement, contentNode);
    } else {
      writeChildren(rootElement, contentNode);
    }
    return new Document(rootElement);
  }

  public static String render(Hierarchy hierarchy) {
    return new XMLOutputter(Format.getPrettyFormat()).outputString(map(hierarchy));
  }

  private static void writeChildren(Element parent, Node node) {
    for (var child : node.getChildren()) {
      if (child.isCollection()) {
        writeNamedCollection(parent, child);
      } else if (child.isAttribute()) {
        writeAttribute(parent, child);
      } else {
        writeNode(parent, child);
      }
    }
  }

  private static void writeNamedCollection(Element parent, Node collection) {
    for (Node item : collection.getChildren()) {
      Element element = new Element(collection.getName());
      writeChildren(element, item);
      parent.addContent(element);
    }
  }

  private static void writeAnonymousCollection(Element parent, Node collection) {
    for (Node item : collection.getChildren()) {
      if (item.getChildren().size() == 1 && !item.getChildren().getFirst().isAttribute()) {
        writeNode(parent, item.getChildren().getFirst());
      } else {
        Element row = new Element("row");
        writeChildren(row, item);
        parent.addContent(row);
      }
    }
  }

  private static String nodeName(Node node) {
    return node.getName();
  }

  private static void writeAttribute(Element parent, Node node) {
    if (!node.hasValue()) {
      Log.fatal(IllegalArgumentException.class, "XML attribute path cannot point to a container node: " + node.getName());
    }
    parent.setAttribute(node.getName(), node.getValue());
  }

  private static void writeNode(Element parent, Node node) {
    var child = new Element(node.getName() == null || node.getName().isEmpty() ? "row" : nodeName(node));
    if (node.hasValue()) {
      if (node.isNull()) {
        child.setAttribute("nil", "true");
      } else {
        child.setText(node.getValue());
      }
    } else {
      writeChildren(child, node);
    }
    parent.addContent(child);
  }
}

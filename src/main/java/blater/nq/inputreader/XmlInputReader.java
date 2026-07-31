package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.util.Log;
import blater.nq.util.Template;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

import static blater.nq.util.ValueUtil.hasValue;

public class XmlInputReader implements InputReader {
  @Override
  public Hierarchy load(String filename, Map<String, String> parameters) {
    Document inputXml = loadXmlFile(filename);
    return xmlToHierarchy(inputXml, parameters);
  }

  private static Document loadXmlFile(String filename) {
    if (!hasValue(filename)) {
      return new Document();
    }

    Path path = Path.of(filename);
    try {
      if (Files.size(path) == 0) {
        return new Document();
      }
      return new SAXBuilder().build(path.toFile());
    } catch (NoSuchFileException | AccessDeniedException e) {
      return Log.fatal(IllegalStateException.class, "Could not open input file: " + filename, e);
    } catch (IOException e) {
      return Log.fatal(IllegalStateException.class, "Could not read input file: " + filename, e);
    } catch (JDOMException e) {
      return Log.fatal(IllegalStateException.class, "Malformed XML input file: " + filename, e);
    }
  }

  private static Hierarchy xmlToHierarchy(Document inputXml, Map<String, String> parameters) {
    if (inputXml == null || !inputXml.hasRootElement()) {
      return new Hierarchy();
    }
    return new Hierarchy(toNode(inputXml.getRootElement(), parameters));
  }

  private static Node toNode(Element element, Map<String, String> parameters) {
    Node node = new Node(element.getName());

    for (var attribute : element.getAttributes()) {
      Node attributeNode = new Node(attribute.getName());
      attributeNode.setValue(Template.expand(attribute.getValue(), parameters));
      attributeNode.setAttribute(true);
      node.addNode(attributeNode);
    }

    if (element.getChildren().isEmpty()) {
      node.setValue(Template.expand(element.getText(), parameters));
      return node;
    }

    for (Element child : element.getChildren()) {
      node.addNode(toNode(child, parameters));
    }

    return node;
  }
}

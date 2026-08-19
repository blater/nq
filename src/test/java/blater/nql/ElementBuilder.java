package blater.nql;

import org.jdom2.Element;

public class ElementBuilder extends Element {
    private Element root;
    private Element current;

    public ElementBuilder() {
        root = null;
        current = root;
    }

    public ElementBuilder node(String name) {
        if (root == null) {
            setRoot(name);
        } else {
            Element newEl = new Element(name);
            current.addContent(newEl);
            current = newEl;
        }
        return this;
    }

    public ElementBuilder sibling(String name) {
        if (root == null) {
            setRoot(name);
        } else {
            Element newEl = new Element(name);
            current.getParent().addContent(newEl);
            current = newEl;
        }
        return this;
    }

    private void setRoot(String name) {
        Element newEl = new Element(name);
        root = newEl;
        current = root;
    }

    public ElementBuilder value(String value) {
        current.addContent(value);
        return this;
    }

    public Element build() {
        return root;
    }
}

package gov.mot.netmon.capture;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Converts TShark PDML into a protocol tree like Wireshark's packet details pane.
 * The XML parser is hardened (no DTDs, no external entities) because PDML contains text decoded
 * from untrusted packets.
 */
public final class PdmlTree {

    static final int MAX_NODES = 5000;

    private PdmlTree() {
    }

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record TreeNode(String name, String label, List<TreeNode> children) {
    }

    public static List<TreeNode> parse(String pdml) {
        if (pdml == null || pdml.isBlank()) return List.of();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            Document doc = b.parse(new InputSource(new StringReader(stripDoctype(pdml))));
            Element packet = firstElement(doc.getDocumentElement(), "packet");
            if (packet == null) return List.of();
            int[] budget = {MAX_NODES};
            List<TreeNode> out = new ArrayList<>();
            for (Node n = packet.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && e.getTagName().equals("proto") && !"geninfo".equals(e.getAttribute("name"))) {
                    TreeNode t = node(e, budget);
                    if (t != null) out.add(t);
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read packet details: " + e.getMessage(), e);
        }
    }

    private static TreeNode node(Element e, int[] budget) {
        if (budget[0]-- <= 0) return null;
        if ("1".equals(e.getAttribute("hide"))) return null;
        String name = e.getAttribute("name");
        String label = e.getAttribute("showname");
        if (label.isEmpty()) label = e.getAttribute("show");
        if (label.isEmpty()) label = name;
        List<TreeNode> children = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && (c.getTagName().equals("field") || c.getTagName().equals("proto"))) {
                TreeNode t = node(c, budget);
                if (t != null) children.add(t);
            }
        }
        return new TreeNode(name, label.length() > 500 ? label.substring(0, 500) + "…" : label, children);
    }

    private static Element firstElement(Element root, String tag) {
        if (root.getTagName().equals(tag)) return root;
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) return e;
        }
        return null;
    }

    /** PDML starts with an xml-stylesheet processing instruction; any DOCTYPE would be rejected anyway. */
    private static String stripDoctype(String s) {
        return s.replaceAll("(?s)<!DOCTYPE[^>]*>", "");
    }
}

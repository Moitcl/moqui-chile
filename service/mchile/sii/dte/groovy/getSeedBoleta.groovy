import org.w3c.dom.*

import javax.xml.parsers.*
import org.xml.sax.SAXException;

semilla = "0";
try {
    // Turn the string into a URL object
    URL urlObject = new URL(urlSolicitud);
    // Open the stream (which returns an InputStream):
    InputStream inp = urlObject.openStream();

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = null;
    Document document = null;

    try {
        builder = factory.newDocumentBuilder();
    }
    catch (ParserConfigurationException pce) {
        pce.printStackTrace();
    }
    try {
        document = builder.parse(inp);
    } catch (SAXException sae) {
        sae.printStackTrace();
    }
    document.getDocumentElement().normalize();
    Element root = document.getDocumentElement();

    NodeList nList = document.getElementsByTagName("SII:RESP_BODY");

    for (int temp = 0; temp < nList.getLength(); temp++) {
        Node node = nList.item(temp);
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element element = (Element) node;
            semilla = element.getElementsByTagName("SEMILLA").item(0).getTextContent();
        }
    }
} catch(IOException ioe) {
    ioe.printStackTrace();
}
return
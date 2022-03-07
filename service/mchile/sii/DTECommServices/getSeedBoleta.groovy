import org.apache.http.protocol.*
import org.w3c.dom.*

import javax.xml.crypto.dsig.*
import javax.xml.crypto.dsig.dom.*
import javax.xml.crypto.dsig.keyinfo.*
import javax.xml.crypto.dsig.spec.*
import javax.xml.parsers.*
import java.io.*
import java.util.*
import java.io.InputStream
import org.xml.sax.SAXException;

seed = "0";
System.out.println("*******************************************************************");
System.out.println("Usando url solicitud semilla: " + urlSolicitud);
try
{
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

    System.out.println(root.getNodeName());

    NodeList nList = document.getElementsByTagName("SII:RESP_BODY");

    for (int temp = 0; temp < nList.getLength(); temp++)
    {
        Node node = nList.item(temp);
        System.out.println("");    //Just a separator
        if (node.getNodeType() == Node.ELEMENT_NODE)
        {
            Element eElement = (Element) node;
            System.out.println("Semilla: "    + eElement.getElementsByTagName("SEMILLA").item(0).getTextContent());
            seed = eElement.getElementsByTagName("SEMILLA").item(0).getTextContent();
        }

    }

}
catch(IOException ioe)
{
    ioe.printStackTrace();
}
return

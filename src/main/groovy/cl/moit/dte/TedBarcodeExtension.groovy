package cl.moit.dte

import org.krysalis.barcode4j.xalan.BarcodeExt
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.DocumentFragment
import org.w3c.dom.NodeList
import org.xml.sax.SAXException
import uk.org.okapibarcode.backend.Pdf417
import uk.org.okapibarcode.output.SvgRenderer

import javax.xml.transform.OutputKeys
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.awt.*

public class TedBarcodeExtension extends BarcodeExt {

    protected final static Logger logger = LoggerFactory.getLogger(TedBarcodeExtension.class);

    public DocumentFragment generate(NodeList tedxml) throws SAXException, IOException {
        try {
            String msg = null;
            msg = new String(getCleaned(tedxml.item(0)), "ISO-8859-1");
            // Nueva libreria PDF417
            Pdf417 barcode = new Pdf417();
            barcode.setPreferredEccLevel(5);
            //barcode.setEncodingMode(1);
            barcode.setContent(msg);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            SvgRenderer svg = new SvgRenderer(baos, 0.6, Color.WHITE, Color.BLACK, true);
            svg.render(barcode);

            int width = barcode.getWidth();
            int height = barcode.getHeight();

            Document tedDocument = (Document) cl.moit.dte.MoquiDTEUtils.parseDocument(baos.toByteArray());
            DocumentFragment tedFragment = tedDocument.createDocumentFragment();
            tedFragment.appendChild(tedDocument.getDocumentElement());

            return tedFragment;

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] getCleaned(org.w3c.dom.Node xml) {
        try {
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.transform(new DOMSource(xml), new StreamResult(baos));

            baos.close();
            byte[] out = baos.toByteArray();

            return new String(out, "ISO-8859-1").replaceAll(" xmlns=\"http://www.sii.cl/SiiDte\"", "").replaceAll("\n", "").replaceAll("\r", "").replaceAll(">\\s+<", "><").getBytes("ISO-8859-1");
        } catch (TransformerException e) {
            // Nunca debe invocarse
            e.printStackTrace();
            return null;
        } catch (IOException e) {
            // Nunca debe invocarse
            e.printStackTrace();
            return null;
        }
    }
}
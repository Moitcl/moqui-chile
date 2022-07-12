import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.impl.client.DefaultHttpClient
import org.w3c.dom.*
import javax.xml.parsers.*
import javax.xml.crypto.dsig.*
import javax.xml.crypto.dsig.dom.*
import javax.xml.crypto.dsig.spec.*
import javax.xml.crypto.dsig.keyinfo.*
import javax.xml.transform.TransformerFactory
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerException
import javax.xml.transform.dom.DOMSource
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.xml.sax.SAXException
import org.moqui.context.ExecutionContext
import java.nio.file.Paths
import java.nio.file.Files
import org.apache.http.util.EntityUtils

ExecutionContext ec = context.ec

ec.context.putAll(ec.service.sync().name("mchile.sii.DTEServices.load#DTEConfig").parameters([partyId:partyId]).call())

String now = "-"+System.nanoTime()

String returnedToken = "0"

System.out.println("Usando url token " + urlSolicitud)


//String semilla = getSemilla()
seed = ec.service.sync().name("mchile.sii.DTECommServices.get#SeedBoleta").parameters([isProduction:isProduction]).call().seed

System.out.println("Semilla: " + seed)

// Se arma XML para obtener token
DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance()
DocumentBuilder docBuilder = docFactory.newDocumentBuilder()
Document doc = docBuilder.newDocument()

Element rootElement = doc.createElement("getToken")
doc.appendChild(rootElement)

Element item = doc.createElement("item")
rootElement.appendChild(item)

// Se agrega semilla obtenida
Element semillaDoc = doc.createElement("Semilla")
semillaDoc.appendChild(doc.createTextNode(seed))
item.appendChild(semillaDoc)

// Firmar
XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM")
Reference ref = fac.newReference ("", fac.newDigestMethod(DigestMethod.SHA1, null), Collections.singletonList (fac.newTransform (Transform.ENVELOPED, (TransformParameterSpec) null)), null, null)

// Create the SignedInfo.
SignedInfo si = fac.newSignedInfo (fac.newCanonicalizationMethod (CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null), fac.newSignatureMethod(SignatureMethod.RSA_SHA1, null), Collections.singletonList(ref))

// Create the KeyInfo containing the X509Data.
KeyInfoFactory kif = fac.getKeyInfoFactory()
List x509Content = new ArrayList()
x509Content.add(certificate.getSubjectX500Principal().getName())
x509Content.add(certificate)
X509Data xd = kif.newX509Data(x509Content)
KeyInfo ki = kif.newKeyInfo(Collections.singletonList(xd))

DOMSignContext dsc = new DOMSignContext(pkey, doc.getDocumentElement())
XMLSignature signature = fac.newXMLSignature(si, ki)
signature.sign(dsc)

// Test
try {
    TransformerFactory transformerFactory =  TransformerFactory.newInstance()
    Transformer transformer = transformerFactory.newTransformer()
    DOMSource source = new DOMSource(doc)

    StreamResult result =  new StreamResult(new File("/tmp/token"+now+".xml"))
    transformer.transform(source, result)
} catch(TransformerException tfe){
    tfe.printStackTrace()
}

// Envio de XML para obtener token
String payload = new String(Files.readAllBytes(Paths.get("/tmp/token"+now+".xml")))
System.out.println("Abriendo URL "+urlSolicitud)
System.out.println("Enviando semilla: " + payload)


HttpClient httpClient = new DefaultHttpClient()
HttpPost httpPost = new HttpPost(urlSolicitud)

httpPost.setHeader("Content-type", "application/xml")
try {
    StringEntity stringEntity = new StringEntity(payload)
    httpPost.getRequestLine()
    httpPost.setEntity(stringEntity)

    HttpResponse response = httpClient.execute(httpPost)

    // Getting the status code.
    int statusCode = response.getStatusLine().getStatusCode()

    // Getting the response body.
    String responseBody = EntityUtils.toString(response.getEntity())

    System.out.println("Status code: " + statusCode)
    System.out.println("Response body: " + responseBody)

    // Conversion a XML para recuperar token

    // ***************************************************************
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
    DocumentBuilder builder = null
    Document document = null

    try {
        builder = factory.newDocumentBuilder()
    }
    catch (ParserConfigurationException pce) {
        pce.printStackTrace()
    }
    try {
        document = builder.parse(new ByteArrayInputStream(responseBody.getBytes()))
    } catch (SAXException sae) {
        sae.printStackTrace()
    }
    document.getDocumentElement().normalize()
    Element root = document.getDocumentElement()

    NodeList nList = document.getElementsByTagName("SII:RESP_BODY")

    for (int temp = 0; temp < nList.getLength(); temp++)
    {
        Node node = nList.item(temp)
        System.out.println("");    //Just a separator
        if (node.getNodeType() == Node.ELEMENT_NODE)
        {
            Element eElement = (Element) node
            returnedToken = eElement.getElementsByTagName("TOKEN").item(0).getTextContent()
        }

    }
    System.out.println("Token retornado: " + returnedToken)

} catch (Exception e) {
    throw new RuntimeException(e)
}

//token = EntityUtils.toString(resEntity)
token = returnedToken

return
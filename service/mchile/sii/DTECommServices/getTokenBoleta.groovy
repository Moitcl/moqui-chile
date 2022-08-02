import org.moqui.context.ExecutionContext
import groovy.xml.MarkupBuilder
import cl.moit.dte.MoquiDTEUtils
import org.moqui.util.RestClient
import org.moqui.util.RestClient.RestResponse
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.http.HttpField

ExecutionContext ec = context.ec

ec.context.putAll(ec.service.sync().name("mchile.sii.DTEServices.load#DTEConfig").parameters([partyId:partyId]).call())

if (tokenBoleta != null && tokenBoletaLastUsage != null && (ec.user.nowTimestamp.time - tokenBoletaLastUsage.time) < 50*60*1000) {
    token = tokenBoleta
    ec.service.sync().name("update#mchile.dte.PartyDteParams").parameters([partyId:partyId, tokenBoletaLastUsage:ec.user.nowTimestamp]).call()
    return
}

String now = "-"+System.nanoTime()

String returnedToken = "0"

//String semilla = getSemilla()
semilla = ec.service.sync().name("mchile.sii.DTECommServices.get#SeedBoleta").parameters([boletaIsProduction:boletaIsProduction]).call().semilla

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)
xmlBuilder.getToken(xmlns: 'http://www.sii.cl/SiiDte') {
    item() {
        Semilla(semilla)
    }
}
String xmlString = xmlWriter.toString()
xmlWriter.close()
org.w3c.dom.Document doc = MoquiDTEUtils.parseDocument(xmlString.getBytes())
byte[] signedXmlBytes = MoquiDTEUtils.sign(doc, "", pkey, certificate, "", "")
String signedXml = new String(signedXmlBytes)

URI uploadUrl = new URI(urlSolicitud)
RestClient restClient = new RestClient().uri(uploadUrl).method("POST")
restClient.getDefaultRequestFactory().getHttpClient().setUserAgentField(new HttpField(HttpHeader.USER_AGENT, "Mozilla/4.0 (compatible; PROG 1.0; Windows NT 5.0; YComp 5.0.2.4)"))
restClient.addHeader("Host", uploadUrl.getHost())
if (proxyHost != null && proxyPort != 0) {
    restClient.withRequestFactory(new cl.moit.net.ProxyRequestFactory(proxyHost, proxyPort))
}
restClient.acceptContentType("*/*").contentType("application/xml")
restClient.text(signedXml).encoding("ISO-8859-1")

RestResponse response = restClient.call()
xmlResponse = response.text()

dom = new groovy.xml.XmlParser(false, false).parse(new ByteArrayInputStream(xmlResponse.bytes))

token = dom.'SII:RESP_BODY'.TOKEN.text()
header = dom.'SII:RESP_HDR'
estado = header.ESTADO.text()
glosa = header.GLOSA.text()
//ec.logger.info("estado: ${estado}")
//ec.logger.info("glosa: ${glosa}")
ec.logger.info("Got new token from ${uploadUrl.host}: ${token}")
if (token != null && token.length() > 0)
    ec.service.sync().name("update#mchile.dte.PartyDteParams").parameters([partyId:partyId, tokenBoleta:token, tokenBoletaLastUsage:ec.user.nowTimestamp]).call()

return
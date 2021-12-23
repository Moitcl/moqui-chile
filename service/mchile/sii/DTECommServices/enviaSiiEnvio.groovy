import org.moqui.context.ExecutionContext
import org.moqui.util.RestClient
import org.moqui.util.RestClient.RestResponse
import org.eclipse.jetty.http.HttpField
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.client.util.ByteBufferContentProvider
import java.nio.ByteBuffer

ExecutionContext ec = context.ec

Boolean isProduction = ec.service.sync().name("mchile.sii.DTEServices.check#ProductionEnvironment").call().isProduction
URI uploadUrl
if (isProduction) {
    uploadUrl = new URI("https://palena.sii.cl/cgi_dte/UPL/DTEUpload")
} else {
    uploadUrl = new URI("https://maullin.sii.cl/cgi_dte/UPL/DTEUpload")
}

envio = ec.entity.find("mchile.dte.DteEnvio").condition("envioId", envioId).one()
rutEmisorEnvio = envio.rutEmisor
// Validación rut -->
ec.context.putAll(ec.service.sync().name("mchile.sii.DTEServices.load#DTEConfig").parameter("partyId", organizationPartyId).call())
if (rutEmisorEnvio != rutEmisor) {
    ec.message.addError("Rut Emisor del envío (${rutEmisorEnvio}) no coincide con Rut de organización que envía (${rutEmisor})")
    return
}

// Get token
String token = ec.service.sync().name("mchile.sii.DTECommServices.get#Token").parameter("isProduction", isProduction).parameter("partyId", organizationPartyId).call().token

locationReference = ec.resource.getLocationReference(envio.documentLocation)

// Prepare restClient
RestClient restClient = new RestClient().uri(uploadUrl).method("POST")
restClient.getDefaultRequestFactory().getHttpClient().setUserAgentField(new HttpField(HttpHeader.USER_AGENT, "Mozilla/4.0 (compatible; PROG 1.0; Windows NT 5.0; YComp 5.0.2.4)"))
restClient.addHeader("Host", uploadUrl.getHost())
restClient.addHeader("Cookie", "TOKEN=${token}").acceptContentType("*/*").contentType("multipart/form-data")

restClient.addFieldPart("rutSender", rutEnvia.substring(0, rutEnvia.length() - 2))
restClient.addFieldPart("dvSender", rutEnvia.substring(rutEnvia.length() - 1, rutEnvia.length()))
restClient.addFieldPart("rutCompany", rutEmisor.substring(0, rutEmisor.length() - 2))
restClient.addFieldPart("dvCompany", rutEmisor.substring(rutEmisor.length() - 1, rutEmisor.length()))
ByteBuffer fileByteBuffer = ByteBuffer.wrap(locationReference.openStream().readAllBytes())
ByteBufferContentProvider bbcp = new ByteBufferContentProvider("text/xml", fileByteBuffer)
restClient.addFilePart("archivo", locationReference.getFileName(), bbcp, null)

RestResponse response = restClient.call()
xmlDoc = new groovy.util.XmlParser(false, true).parseText(response.text())
status = xmlDoc.STATUS.text()

trackId = null
if (status == '0') {
    trackId = xmlDoc.TRACKID.text()
    ec.logger.warn("DTE Enviada correctamente con trackId " + trackId)
    ec.service.sync().name("update#mchile.dte.DteEnvio").parameters([envioId:envioId, trackId:trackId]).call()
} else {
    errorDescriptionMap = ['0':'Upload OK', '1':'El Sender no tiene permiso para enviar', '2':'Error en tamaño del archivo (muy grande o muy chico)', '3':'Archivo cortado (tamaño != al parámetro size)',
                           '5':'No está auten†icado', '6':'Empresa no autorizada a enviar archivos', '7':'Esquema Inválido', '8':'Firma del Documento', '9':'Sistema Bloqueado', '0':'Error Interno']
    ec.message.addMessage("Error "+ status + " al enviar DTE (${errorDescriptionMap[status]})", "danger")
    ec.logger.info("response: ${response.text()}")
}

return
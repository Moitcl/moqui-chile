import org.moqui.context.ExecutionContext
import org.moqui.util.RestClient
import org.moqui.util.RestClient.RestResponse
import org.eclipse.jetty.http.HttpField
import org.eclipse.jetty.http.HttpHeader
import org.moqui.util.StringUtilities
import org.xml.sax.SAXParseException

ExecutionContext ec = context.ec

Boolean isProduction = ec.service.sync().name("mchile.sii.DTEServices.check#ProductionEnvironment").call().isProduction
URI uploadUrl
if (isProduction) {
    uploadUrl = new URI("https://palena.sii.cl/cgi_dte/UPL/DTEUpload")
} else {
    uploadUrl = new URI("https://maullin.sii.cl/cgi_dte/UPL/DTEUpload")
}

envio = ec.entity.find("mchile.dte.DteEnvio").condition("envioId", envioId).one()
if (envio == null) {
    ec.message.addMessage("No se encuentra envío ${envioId}", "warning")
    return
}
rutEmisorEnvio = envio.rutEmisor
if (envio.rutReceptor != '60803000-K') {
    ec.message.addError("Envío ${envioId} tiene Rut de Receptor distinto al SII: ${envio.rutReceptor}, no se puede enviar")
    return
}
partyIdEmisor = ec.entity.find("mantle.party.PartyIdentification").condition([partyIdTypeEnumId:'PtidNationalTaxId', idValue:rutEmisorEnvio]).list().first?.partyId
// Validación rut -->
ec.context.putAll(ec.service.sync().name("mchile.sii.DTEServices.load#DTEConfig").parameter("partyId", partyIdEmisor).call())
if (rutEmisorEnvio != rutEmisor) {
    ec.message.addError("Rut Emisor del envío (${rutEmisorEnvio}) no coincide con Rut de organización que envía (${rutEmisor})")
    return
}

// Get token
String token = ec.service.sync().name("mchile.sii.DTECommServices.get#Token").parameter("isProduction", isProduction).parameter("partyId", partyIdEmisor).call().token

locationReference = ec.resource.getLocationReference(envio.documentLocation)

// Prepare restClient
ec.logger.info("Enviando directo, envío ${envioId} a uri ${uploadUrl}")
boundary = "MoitCl-${StringUtilities.getRandomString(10)}-${StringUtilities.getRandomString(10)}-${StringUtilities.getRandomString(10)}-DTE"

rutEnviaMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameter("rut", rutEnvia).call()
rutEmisorMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameter("rut", rutEmisor).call()
fileBytes = locationReference.openStream().readAllBytes()
fileName = locationReference.getFileName()
body = """--${boundary}\r
Content-Disposition: form-data; name="rutSender"\r
\r
${rutEnviaMap.rut}\r
--${boundary}\r
Content-Disposition: form-data; name="dvSender"\r
\r
${rutEnviaMap.dv}\r
--${boundary}\r
Content-Disposition: form-data; name="rutCompany"\r
\r
${rutEmisorMap.rut}\r
--${boundary}\r
Content-Disposition: form-data; name="dvCompany"\r
\r
${rutEmisorMap.dv}\r
--${boundary}\r
Content-Disposition: form-data; name="archivo"; filename="${fileName}"\r
\r
${new String(fileBytes, "ISO-8859-1")}\r
--${boundary}--\r
"""

RestClient restClient = new RestClient().uri(uploadUrl).method("POST")
restClient.getDefaultRequestFactory().getHttpClient().setUserAgentField(new HttpField(HttpHeader.USER_AGENT, "Mozilla/4.0 (compatible; PROG 1.0; Windows NT 5.0; YComp 5.0.2.4)"))
restClient.addHeader("Host", uploadUrl.getHost())
if (proxyHost != null && proxyPort != 0) {
    restClient.withRequestFactory(new cl.moit.net.ProxyRequestFactory(proxyHost, proxyPort))
}
restClient.addHeader("Cookie", "TOKEN=${token}").acceptContentType("*/*").contentType("multipart/form-data; boundary=${boundary}")
restClient.text(body).encoding("ISO-8859-1")

RestResponse response = restClient.call()
xmlResponse = response.text()

XmlParser parser = new groovy.util.XmlParser(false, true)
xmlDoc = null
status = null
try {
    xmlDoc = parser.parseText(xmlResponse)
    status = xmlDoc.STATUS.text()
    if (status == null || status == '')
        status = xmlDoc.'siid:STATUS'.text()
} catch (SAXParseException e) {
    ec.logger.warn("Error parsing response: ${e.toString()}")
}

trackId = null
if (status == '0') {
    trackId = xmlDoc.TRACKID.text()
    if (trackId == null || trackId == '')
        trackId = xmlDoc.'siid:TRACKID'.text()
    ec.logger.warn("DTE Enviada correctamente con trackId " + trackId)
    ec.service.sync().name("update#mchile.dte.DteEnvio").parameters([envioId:envioId, trackId:trackId, statusId:'Ftde-Sent']).call()
    ec.service.special().name("mchile.sii.DTECommServices.start#ValidaEnvioServiceJob").parameters([envioId: envioId, initialDelaySeconds:5, checkDelaySeconds:30, checkAttempts:4, minSecondsBetweenAttempts: 0]).registerOnCommit()
    envioFtdList = ec.entity.find("mchile.dte.DteEnvioFiscalTaxDocument").condition("envioId", envioId).list()
    if (envioFtdList)
        ec.service.sync().name("mchile.sii.DTECommServices.marcarEnviados#Documentos").parameters([trackId:trackId, documentIdList:envioFtdList.fiscalTaxDocumentId]).call()
} else {
    errorDescriptionMap = ['0':'Upload OK', '1':'El Sender no tiene permiso para enviar', '2':'Error en tamaño del archivo (muy grande o muy chico)', '3':'Archivo cortado (tamaño != al parámetro size)',
                           '5':'No está auten†icado', '6':'Empresa no autorizada a enviar archivos', '7':'Esquema Inválido', '8':'Firma del Documento', '9':'Sistema Bloqueado', '0':'Error Interno']
    ec.message.addMessage("Error "+ status + " al enviar DTE (${errorDescriptionMap[status]?:'Sin descripción'})", "danger")
    ec.logger.info("response: ${xmlResponse}")
}

return
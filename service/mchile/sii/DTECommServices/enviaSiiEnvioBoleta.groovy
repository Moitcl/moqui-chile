import org.moqui.context.ExecutionContext
import org.moqui.util.RestClient
import org.moqui.util.RestClient.RestResponse
import org.eclipse.jetty.http.HttpField
import org.eclipse.jetty.http.HttpHeader
import org.moqui.util.StringUtilities

ExecutionContext ec = context.ec

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

URI uploadUrl
if (boletaIsProduction) {
    uploadUrl = new URI("https://rahue.sii.cl/recursos/v1/boleta.electronica.envio")
} else {
    uploadUrl = new URI("https://pangal.sii.cl/recursos/v1/boleta.electronica.envio")
}

// Get token
String token = ec.service.sync().name("mchile.sii.DTECommServices.get#TokenBoleta").parameter("boletaIsProduction", boletaIsProduction).parameter("partyId", partyIdEmisor).call().token

locationReference = ec.resource.getLocationReference(envio.documentLocation)

// Prepare restClient
ec.logger.info("Subiendo envío ${envioId} a uri ${uploadUrl}")
boundary = "MoitCl-${StringUtilities.getRandomString(10)}-${StringUtilities.getRandomString(10)}-${StringUtilities.getRandomString(10)}-DTE"

rutEnviaMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameter("rut", rutEnviador).call()
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
Content-Type: application/octet-stream\r
Content-Disposition: form-data; name="archivo"; filename="archivo"\\r
\r
${new String(fileBytes, "ISO-8859-1")}\r
--${boundary}--\r
"""

RestClient restClient = new RestClient().uri(uploadUrl).method("POST")
RestClient.RequestFactory requestFactory = new cl.moit.net.ProxyRequestFactory("192.168.1.50", 9090)
requestFactory.getHttpClient().setUserAgentField(new HttpField(HttpHeader.USER_AGENT, "Mozilla/4.0 ( compatible; PROG 1.0; Windows NT)"))
restClient.withRequestFactory(requestFactory)
restClient.getDefaultRequestFactory().getHttpClient().setUserAgentField(new HttpField(HttpHeader.USER_AGENT, "Mozilla/4.0 ( compatible; PROG 1.0; Windows NT)"))
//ec.logger.info("Setting Host header to ${uploadUrl.getHost()}")
//restClient.addHeader("Host", uploadUrl.getHost())
if (proxyHost != null && proxyPort != 0) {
    restClient.withRequestFactory(new cl.moit.net.ProxyRequestFactory(proxyHost, proxyPort))
}
ec.logger.info("setting token cookie as ${token}")
restClient.addHeader("Cookie", "TOKEN=${token}").acceptContentType("application/json").contentType("multipart/form-data; boundary=${boundary}")
restClient.text(body).encoding("ISO-8859-1")

RestResponse response = restClient.call()
jsonResponse = response.text()
ec.logger.info("jsonResponse: ${jsonResponse}")
responseMap = new groovy.json.JsonSlurper().parseText(jsonResponse)

status = responseMap.estado

trackId = null
attemptCount = (envio.attemptCount?:0) + 1
if (status == 'REC') {
    trackId = responseMap.trackid
    ec.logger.warn("EnvioBoleta enviado correctamente con trackId " + trackId)
    ec.service.sync().name("update#mchile.dte.DteEnvio").parameters([envioId:envioId, trackId:trackId, statusId:'Ftde-Sent', attemptCount:attemptCount, lastAttempt:ec.user.nowTimestamp]).call()
    ec.service.special().name("mchile.sii.DTECommServices.start#ValidaEnvioServiceJob").parameters([envioId: envioId, initialDelaySeconds:5, checkDelaySeconds:30, checkAttempts:4, minSecondsBetweenAttempts: 0]).registerOnCommit()
    envioFtdList = ec.entity.find("mchile.dte.DteEnvioFiscalTaxDocument").condition("envioId", envioId).list()
    if (envioFtdList)
        ec.service.sync().name("mchile.sii.DTECommServices.marcarEnviados#Documentos").parameters([trackId:trackId, documentIdList:envioFtdList.fiscalTaxDocumentId]).call()
} else {
    ec.message.addMessage("Error "+ status + " al enviar DTE", "danger")
    if (attemptCount <= maxFail)
        statusId = envio.statusId
    else
        statusId = 'Ftde-Failed'
    ec.service.sync().name("update#mchile.dte.DteEnvio").requireNewTransaction(true).parameters([envioId:envioId, statusId:statusId, attemptCount:attemptCount, lastAttempt:ec.user.nowTimestamp]).call()
}

return
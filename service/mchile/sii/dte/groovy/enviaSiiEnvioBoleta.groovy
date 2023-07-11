import org.moqui.context.ExecutionContext
import org.moqui.util.RestClient
import org.moqui.util.StringUtilities
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.http.HttpField
import groovy.json.JsonSlurper

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
ec.context.putAll(ec.service.sync().name("mchile.sii.dte.DteInternalServices.load#DteConfig").parameters([partyId:envio.issuerPartyId]).call())
tokenMap = ec.service.sync().name("mchile.sii.dte.DteCommServices.get#TokenBoleta").parameters([boletaIsProduction:boletaIsProduction,  partyId:envio.issuerPartyId]).call()
token = tokenMap.token
urlSolicitud = boletaIsProduction? 'https://rahue.sii.cl/recursos/v1/boleta.electronica.envio' : 'https://pangal.sii.cl/recursos/v1/boleta.electronica.envio'
locationReference = ec.resource.getLocationReference(envio.documentLocation)

URI requestUrl = new URI(urlSolicitud)

boundary = "MoitCl-${StringUtilities.getRandomString(10)}-${StringUtilities.getRandomString(10)}-${StringUtilities.getRandomString(10)}-DTE"

rutEnviaMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameter("rut", rutEnviador).call()
rutEmisorMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameter("rut", rutOrganizacion).call()
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
Content-Type: text/xml
\r
${new String(fileBytes, "ISO-8859-1")}\r
--${boundary}--\r
"""

RestClient.RequestFactory requestFactory = new RestClient.SimpleRequestFactory(false, false)
requestFactory.getHttpClient().setUserAgentField(new HttpField(HttpHeader.USER_AGENT, "Mozilla/4.0 ( compatible; PROG 1.0; Windows NT)"))
RestClient restClient = new RestClient().uri(requestUrl).method("POST").withRequestFactory(requestFactory)
restClient.addHeader("Host", requestUrl.getHost()).addHeader("Cookie", "TOKEN=${token}").acceptContentType("application/json").contentType("multipart/form-data; boundary=${boundary}")
restClient.text(body).encoding("ISO-8859-1")

//proxyHost = "192.168.26.56"
//proxyPort = 9090
if (proxyHost != null && proxyPort != 0) {
    cl.moit.net.ProxyRequestFactory rf = new cl.moit.net.ProxyRequestFactory(proxyHost, proxyPort)
    rf.getHttpClient().setUserAgentField(new HttpField(HttpHeader.USER_AGENT, "Mozilla/4.0 ( compatible; PROG 1.0; Windows NT)"))
    restClient.withRequestFactory(rf)
}

RestClient.RestResponse response = restClient.call()
respSII = response.text()

def jsonSlurper = new JsonSlurper()
try {
    respuesta = jsonSlurper.parseText(respSII)
} catch (Exception e) {
    ec.message.addError("Error parsing response from SII: ${e.getMessage()}\nrespuesta SII: ${respSII}")
}
trackId = respuesta.trackid
estado = respuesta.estado

attemptCount = (envio.attemptCount ?: 0) + 1

if (estado == 'REC') {
    ec.logger.warn("DTE Enviada correctamente con trackId " + trackId)
    attemptCount
    ec.service.sync().name("update#mchile.dte.DteEnvio").parameters([envioId:envioId, trackId:trackId, statusId:'Ftde-Sent', attemptCount:attemptCount, lastAttempt:ec.user.nowTimestamp]).call()
    ec.service.special().name("mchile.sii.dte.DteCommServices.start#ValidaEnvioServiceJob").parameters([envioId: envioId, initialDelaySeconds:5, checkDelaySeconds:30, checkAttempts:4, minSecondsBetweenAttempts: 0]).registerOnCommit()
    envioFtdList = ec.entity.find("mchile.dte.DteEnvioFiscalTaxDocument").condition("envioId", envioId).list()
    if (envioFtdList)
        ec.service.sync().name("mchile.sii.dte.DteCommServices.marcarEnviados#Documentos").parameters([trackId:trackId, documentIdList:envioFtdList.fiscalTaxDocumentId]).call()

} else {
    ec.message.addMessage("Error "+ status + " al enviar DTE", "danger")
    ec.logger.info("response: ${respSII}")
    if (attemptCount <= maxFail)
        statusId = envio.statusId
    else
        statusId = 'Ftde-Failed'
    ec.service.sync().name("update#mchile.dte.DteEnvio").requireNewTransaction(true).parameters([envioId:envioId, statusId:statusId, attemptCount:attemptCount, lastAttempt:ec.user.nowTimestamp]).call()
}

return
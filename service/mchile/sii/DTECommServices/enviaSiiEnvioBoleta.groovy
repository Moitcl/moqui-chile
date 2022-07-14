import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.impl.client.DefaultHttpClient
import org.moqui.context.ExecutionContext
import org.apache.http.util.EntityUtils
import org.apache.http.client.methods.HttpPost
import org.apache.http.impl.cookie.BasicClientCookie
import org.apache.http.impl.client.BasicCookieStore
import org.apache.http.protocol.BasicHttpContext
import org.apache.http.protocol.HttpContext
import org.apache.http.message.BasicHeader
import org.apache.http.HttpEntity
import org.apache.http.client.protocol.ClientContext
import org.apache.http.entity.mime.MultipartEntityBuilder
import org.apache.http.entity.ContentType
import org.apache.http.entity.mime.content.StringBody

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

HttpClient client = new DefaultHttpClient()
useProxy = true
if (useProxy) {
    org.apache.http.HttpHost proxy = new org.apache.http.HttpHost("192.168.1.50", 9090)
    client.getParams().setParameter(org.apache.http.conn.params.ConnRoutePNames.DEFAULT_PROXY,proxy)
}
HttpPost post = new HttpPost(uploadUrl)

post.addHeader("Accept", "application/json")
post.addHeader(new BasicHeader("User-Agent", "Mozilla/4.0 ( compatible; PROG 1.0; Windows NT)"))

MultipartEntityBuilder builder = MultipartEntityBuilder.create()
//builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE)

rutEnviaMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameter("rut", rutEnviador).call()
rutEmisorMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameter("rut", rutEmisor).call()
builder.addPart("rutSender", new StringBody(rutEnviaMap.rut))
builder.addPart("dvSender", new StringBody(rutEnviaMap.dv as String))
builder.addPart("rutCompany", new StringBody(rutEmisorMap.rut))
builder.addPart("dvCompany", new StringBody(rutEmisorMap.dv as String))
builder.addBinaryBody("archivo", locationReference.openStream(), ContentType.DEFAULT_BINARY, "archivo")
builder.setBoundary(boundary)

HttpEntity entity = builder.build()

post.setEntity(entity)

BasicClientCookie cookie = new BasicClientCookie("TOKEN", token)
cookie.setPath("/")
cookie.setDomain(uploadUrl.getHost())
cookie.setSecure(true)
cookie.setVersion(1)

BasicCookieStore cookieStore = new BasicCookieStore()
cookieStore.addCookie(cookie)

//client.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.RFC_2109)
//post.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.BROWSER_COMPATIBILITY)

HttpContext localContext = new BasicHttpContext()
localContext.setAttribute(ClientContext.COOKIE_STORE, cookieStore)

HttpResponse response = client.execute(post, localContext)


HttpEntity resEntity = response.getEntity()
responseText = EntityUtils.toString(resEntity)

attemptCount = (envio.attemptCount?:0) + 1
if (response.getStatusLine().getStatusCode() == 200) {
    responseMap = new groovy.json.JsonSlurper().parseText(responseText)

    status = responseMap.estado

    trackId = null
    if (status == 'REC') {
        trackId = responseMap.trackid
        ec.logger.warn("EnvioBoleta enviado correctamente con trackId " + trackId)
        ec.service.sync().name("update#mchile.dte.DteEnvio").parameters([envioId:envioId, trackId:trackId, statusId:'Ftde-Sent', attemptCount:attemptCount, lastAttempt:ec.user.nowTimestamp]).call()
        ec.service.special().name("mchile.sii.DTECommServices.start#ValidaEnvioServiceJob").parameters([envioId: envioId, initialDelaySeconds:5, checkDelaySeconds:30, checkAttempts:4, minSecondsBetweenAttempts: 0]).registerOnCommit()
        envioFtdList = ec.entity.find("mchile.dte.DteEnvioFiscalTaxDocument").condition("envioId", envioId).list()
        if (envioFtdList)
            ec.service.sync().name("mchile.sii.DTECommServices.marcarEnviados#Documentos").parameters([trackId:trackId, documentIdList:envioFtdList.fiscalTaxDocumentId]).call()
        return
    }
}

ec.message.addMessage("Error " + response.getStatusLine() + " al enviar Boleta. response: ${responseText}", "danger")
if (attemptCount <= maxFail)
    statusId = envio.statusId
else
    statusId = 'Ftde-Failed'
ec.service.sync().name("update#mchile.dte.DteEnvio").requireNewTransaction(true).parameters([envioId: envioId, statusId: statusId, attemptCount: attemptCount, lastAttempt: ec.user.nowTimestamp]).call()

return
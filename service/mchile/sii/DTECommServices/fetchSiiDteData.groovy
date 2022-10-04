import org.moqui.context.ExecutionContext
import org.moqui.util.RestClient
import cl.moit.sii.SiiAuthenticator

ExecutionContext ec = context.ec

var a = []
var b = "0123456789abcdef"
Random random = new Random(ec.user.nowTimestamp.time)
for (int c = 0; 36 > c; c++)
    a[c] = b.charAt(random.nextInt(16));
a[14] = "4"
int start = 3 & (a[19] as int) | 8
a[19] = b.substring(start, start+1)
a[8] = a[13] = a[18] = a[23] = "-";
String transactionId = a.join("");

ec.logger.warn("transactionId: ${transactionId}")

dteconfig = ec.service.sync().name("mchile.sii.DTEServices.load#DTEConfig").parameters([partyId:organizationPartyId]).call()

SiiAuthenticator authenticator = new SiiAuthenticator()
authenticator.setRutOrganizacion(dteconfig.rutEmisor)
authenticator.setPortalMipyme(false)

if (dteconfig.certData != null && dteconfig.certData.length() > 0 && dteconfig.passCert != null && dteconfig.passCert.size() > 0) {
    authenticator.setCertData(dteconfig.certData)
    authenticator.setCertPass(dteconfig.passCert)
}

RestClient restClient = authenticator.createRestClient()

java.net.CookieStore cookieStore = authenticator.getRequestFactory().getHttpClient().getCookieStore()
cookieList = cookieStore.get(new URI("https://www4.sii.cl"))
String token = null
cookieList.each {
    if (it.name == 'TOKEN') {
        token = it.value
    }
}

restClient.uri(uri).contentType("application/json").acceptContentType("application/json, text/plain, */*")
metadata = [namespace: namespace, conversationId: token, transactionId: transactionId, page: null]
jsonMap = [metaData:metadata, data:data]
restClient.jsonObject(jsonMap)

response = restClient.call()

ec.logger.info("response: ${response.text()}")

result = response.jsonObject()

ec.logger.info("result: ${result}")
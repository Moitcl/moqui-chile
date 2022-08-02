import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

//ExecutionContext ec = context.ec

// Validación rut
if (rutReceptor) {
    ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameters([rut:rutCompany]).call()
}
String boundary = UUID.randomUUID().toString();
HttpClient httpclient = new DefaultHttpClient();

// Construccion de URL correcta
urlSolicitud = urlSolicitud + "/" +  rutCompany + "-" + trackId;
System.out.println("URL solicitud: " + urlSolicitud);

URI requestUrl = new URI(urlSolicitud + "/" +  rutCompany + "-" + trackId)

HttpGet httpget = new HttpGet(requestUrl);
httpget.addHeader("accept", "application/json");

BasicClientCookie cookie = new BasicClientCookie("TOKEN", token)
cookie.setPath("/")
cookie.setDomain(requestUrl.getHost())
cookie.setSecure(true)
//cookie.setVersion(1)

BasicCookieStore cookieStore = new BasicCookieStore();
cookieStore.addCookie(cookie);

//httpclient.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.RFC_2109);
//httpget.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.BROWSER_COMPATIBILITY);

HttpContext localContext = new BasicHttpContext();
localContext.setAttribute(HttpClientContext.COOKIE_STORE, cookieStore);
httpget.addHeader(new BasicHeader("User-Agent", "Mozilla/4.0 ( compatible; PROG 1.0; Windows NT)"));

HttpResponse response = httpclient.execute(httpget, localContext);
statusCode = response.getStatusLine().getStatusCode()
ec.logger.info("statusCode: ${statusCode}")

HttpEntity resEntity = response.getEntity();

respSII = EntityUtils.toString(resEntity);

return
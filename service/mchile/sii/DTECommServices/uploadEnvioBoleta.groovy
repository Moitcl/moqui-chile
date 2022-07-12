import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.moqui.context.ExecutionContext
import org.apache.http.util.EntityUtils;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.message.BasicHeader;
import org.apache.http.HttpEntity;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.ContentType;

ExecutionContext ec = context.ec

String token = ec.service.sync().name("mchile.sii.DTECommServices.get#TokenBoleta").parameter("isProduction", isProduction).parameter("partyId", partyIdEmisor).call().token

System.out.println("***************************************************************\n Enviando contenido, token: " + token + ", url: " + urlEnvio + " rut: "+rutCompania.substring(0, (rutCompania).length() - 2) + " Host envio: " + hostEnvio);
System.out.println("rutEnvia: "+rutEnvia);
System.out.println("***************************************************************\n");


String boundary = UUID.randomUUID().toString();
System.out.println("\nBoundary:" + boundary + "\n");

dteEnvioEv = ec.entity.find("mchile.dte.DteEnvio").condition("envioId", envioId).one()
if (dteEnvioEv.statusId != 'Ftde-Created') {
    ec.logger.error("Estado inválido para procesar envío ${envioId}: ${dteEnvioEv.statusId}")
    return
}

inputStream = ec.resource.getLocationReference(dteEnvioEv.documentLocation).openStream()

HttpClient client = new DefaultHttpClient();
HttpPost post = new HttpPost(urlEnvio);

post.addHeader("Accept", "application/json");
post.addHeader(new BasicHeader("User-Agent", "Mozilla/4.0 ( compatible; PROG 1.0; Windows NT)"));

MultipartEntityBuilder builder = MultipartEntityBuilder.create();
builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

builder.addPart("rutSender", new StringBody(rutEnvia.substring(0, rutEnvia.length() - 2)));
builder.addPart("dvSender", new StringBody(rutEnvia.substring(rutEnvia.length() - 1, rutEnvia.length())));
builder.addPart("rutCompany", new StringBody(rutCompania.substring(0, (rutCompania).length() - 2)));
builder.addPart("dvCompany", new StringBody(rutCompania.substring(rutCompania.length() - 1, rutCompania.length())));
builder.addBinaryBody("archivo", inputStream, ContentType.DEFAULT_BINARY, "archivo");
builder.setBoundary(boundary);

HttpEntity entity = builder.build();

post.setEntity(entity);

BasicClientCookie cookie = new BasicClientCookie("TOKEN", token);
cookie.setPath("/");
cookie.setDomain(hostEnvio);
cookie.setSecure(true);
cookie.setVersion(1);

CookieStore cookieStore = new BasicCookieStore();
cookieStore.addCookie(cookie);

client.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.RFC_2109);
post.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.BROWSER_COMPATIBILITY);

HttpContext localContext = new BasicHttpContext();
localContext.setAttribute(ClientContext.COOKIE_STORE, cookieStore);

HttpResponse response = client.execute(post, localContext);

if (response.getStatusLine().getStatusCode() != 200) {
    //String responseMsg = EntityUtils.toString(response.getEntity(), "UTF-8");
    respSII = EntityUtils.toString(response.getEntity(), "UTF-8");
    System.out.println("Error uploading Boleta: " + response.getStatusLine() + ", " +responseMsg);
    return null;
}

HttpEntity resEntity = response.getEntity();

respSII = EntityUtils.toString(resEntity);
System.out.println("Respuesta SII: " + respSII);

return
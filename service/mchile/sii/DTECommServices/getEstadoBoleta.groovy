import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.*;
import javax.xml.crypto.dsig.spec.*;
import javax.xml.crypto.dsig.keyinfo.*;
import java.util.*;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.xml.sax.SAXException;
import org.moqui.context.ExecutionContext
import java.nio.file.Paths;
import java.nio.file.Files;
import org.apache.http.util.EntityUtils;

ExecutionContext ec = context.ec


String boundary = UUID.randomUUID().toString();
HttpClient httpclient = new DefaultHttpClient();

// Rut y DV de receptor
String dvReceptor = rutReceptor.substring(rutReceptor.length() - 1, rutReceptor.length());
rutReceptor = rutReceptor.substring(0, rutReceptor.length() - 2);


// Construccion de URL correcta
urlSolicitud = urlSolicitud + "/" +  rutCompany + "-" + tipo + "-" + folio + "/estado?rut_receptor="+rutReceptor+"&dv_receptor="+dvReceptor+"&monto="+monto+"&fechaEmision="+fechaEmision;
System.out.println("URL solicitud: " + urlSolicitud);

HttpGet httpget = new HttpGet(urlSolicitud);
httpget.addHeader("accept", "application/json");

BasicClientCookie cookie = new BasicClientCookie("TOKEN", token);
cookie.setPath("/");
cookie.setDomain(hostEnvio);
cookie.setSecure(true);
cookie.setVersion(1);

CookieStore cookieStore = new BasicCookieStore();
cookieStore.addCookie(cookie);

httpclient.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.RFC_2109);
httpget.getParams().setParameter(ClientPNames.COOKIE_POLICY, CookiePolicy.BROWSER_COMPATIBILITY);

HttpContext localContext = new BasicHttpContext();
localContext.setAttribute(ClientContext.COOKIE_STORE, cookieStore);

httpget.addHeader(new BasicHeader("User-Agent", "Mozilla/4.0 ( compatible; PROG 1.0; Windows NT)"));

HttpResponse response = httpclient.execute(httpget, localContext);

HttpEntity resEntity = response.getEntity();

String resp = null;

String respSII = EntityUtils.toString(resEntity);
System.out.println("Respuesta SII: " + respSII);

// Armar JSON y devolverlo
JSONObject jsonObject = null;
try {
    Object obj = JSONValue.parse(respSII);
    jsonObject = (JSONObject) obj;
    //String estado = (String) jsonObject.get("estado");
    //String trackid = String.valueOf(jsonObject.get("trackid"));
    //System.out.println("Estado: " + estado);
    //System.out.println("Trackid: " + trackid);
} catch (Exception pe) {
    System.out.println("JSON parse error");
    System.out.println(pe);
}
return
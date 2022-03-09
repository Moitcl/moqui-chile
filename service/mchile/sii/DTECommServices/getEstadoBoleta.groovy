import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.*;
import javax.xml.crypto.dsig.*;
import javax.xml.crypto.dsig.dom.*;
import javax.xml.crypto.dsig.spec.*;
import javax.xml.crypto.dsig.keyinfo.*;
import java.util.*;
import org.moqui.context.ExecutionContext
import org.apache.http.util.EntityUtils;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.message.BasicHeader;
import org.apache.http.HttpEntity;
import org.apache.http.client.CookieStore;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.client.params.CookiePolicy;
import org.apache.http.client.protocol.ClientContext;

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

respSII = EntityUtils.toString(resEntity);
System.out.println("Respuesta SII: " + respSII);


return
package cl.moit.net;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.moqui.BaseException;
import org.moqui.util.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Base64;

public class ClientAuthRequestFactory implements RestClient.RequestFactory {
    private HttpClient httpClient;
    private static final Logger logger = LoggerFactory.getLogger(ClientAuthRequestFactory.class);

    public ClientAuthRequestFactory(String certData, String password) throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(Base64.getDecoder().decode(certData)), password.toCharArray());
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setKeyStore(ks);
        String alias = ks.aliases().nextElement();
        sslContextFactory.setCertAlias(alias);
        sslContextFactory.setNeedClientAuth(true);
        sslContextFactory.setWantClientAuth(true);
        sslContextFactory.setKeyStorePassword(password);
        sslContextFactory.setEndpointIdentificationAlgorithm("HTTPS");

        httpClient = new HttpClient(sslContextFactory);
        // use a default idle timeout of 15 seconds, should be lower than server idle timeouts which will vary by server but 30 seconds seems to be common
        httpClient.setIdleTimeout(15000);
        try { httpClient.start(); } catch (Exception e) { throw new BaseException("Error starting HTTP client", e); }
    }

    @Override public Request makeRequest(String uriString) {
        return httpClient.newRequest(uriString);
    }

    HttpClient getHttpClient() { return httpClient; }

    @Override public void destroy() {
        if (httpClient != null && httpClient.isRunning()) {
            try { httpClient.stop(); }
            catch (Exception e) { logger.error("Error stopping SimpleRequestFactory HttpClient", e); }
        }
    }
    @Override protected void finalize() throws Throwable {
        if (httpClient != null && httpClient.isRunning()) {
            logger.warn("SimpleRequestFactory finalize and httpClient still running, stopping");
            try { httpClient.stop(); } catch (Exception e) { logger.error("Error stopping SimpleRequestFactory HttpClient", e); }
        }
        super.finalize();
    }
}
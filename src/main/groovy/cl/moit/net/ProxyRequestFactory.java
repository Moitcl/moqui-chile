package cl.moit.net;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.moqui.BaseException;
import org.moqui.util.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyRequestFactory  implements RestClient.RequestFactory {

    private HttpClient httpClient;

    private static final Logger logger = LoggerFactory.getLogger(ProxyRequestFactory.class);

    public ProxyRequestFactory(String proxyHost, int proxyPort) {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(sslContextFactory);

        httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector));
        httpClient.getProxyConfiguration().getProxies().add(new HttpProxy(proxyHost, proxyPort));
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

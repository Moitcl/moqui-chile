package cl.moit.moqui.remote

class ProxyUrlStreamHandler extends URLStreamHandler {
    private Proxy proxy;
    ProxyUrlStreamHandler(Proxy proxy) {
        this.proxy = proxy
    }
    protected URLConnection openConnection(URL url) throws IOException {
        // The url is the parent of this stream handler, so must
        // create clone
        URL clone = new URL(url.toString());

        URLConnection connection = null;
        if (proxy.address().toString().equals("0.0.0.0/0.0.0.0:80")) {
            connection = clone.openConnection();
        } else
            connection = clone.openConnection(proxy);
        connection.setConnectTimeout(5 * 1000); // 5 sec
        connection.setReadTimeout(5 * 1000); // 5 sec
        return connection;
    }
}

package cl.moit.sii

import org.moqui.util.RestClient
import cl.moit.net.ClientAuthRequestFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SiiAuthenticator {

    protected final static Logger logger = LoggerFactory.getLogger(SiiAuthenticator.class)

    protected String rutOrganizacion
    protected String certData
    protected String certPass
    protected String username
    protected String password
    protected RestClient.RequestFactory requestFactory

    public SiiAuthenticator() {}

    public void setRutOrganizacion(String rutOrganizacion) { this.rutOrganizacion = rutOrganizacion }

    public void setCertData(String certData) { this.certData = certData }

    public void setCertPass(String certPass) { this.certPass = certPass }

    public void setUsername(String username) { this.username = username }

    public void setPassword(String password) { this.password = password }

    public RestClient createRestClient() {
        RestClient restClient = new RestClient()
        RestClient.RestResponse response
        String responseText = null
        if (certData != null && certData.size() > 0 && certPass != null && certPass.size() > 0) {
            requestFactory = new ClientAuthRequestFactory(certData, certPass)
            //requestFactory = new RestClient.SimpleRequestFactory(false, false)
            java.net.CookieStore cookieStore = requestFactory.getHttpClient().getCookieStore()
            restClient.withRequestFactory(requestFactory)
            restClient.uri("https://herculesr.sii.cl/cgi_AUT2000/CAutInicio.cgi?https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi?DESDE_DONDE_URL=OPCION%3D1%26TIPO%3D4").method("POST").acceptContentType("*/*")
            restClient.text("referencia=https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi?DESDE_DONDE_URL=OPCION%3D1%26TIPO%3D4")
            response = restClient.call()
            //logger.warn("Cookies after request:")
            //cookieStore.cookies.each { cookie -> logger.info("Cookie for ${cookie.commentURL}: ${cookie.name} = ${cookie.value}")}
            //logger.info("response: ${response.text()}")
            responseText = response.text()
        } else {
            // ToDo: autenticación con user/pass
            requestFactory = new RestClient.SimpleRequestFactory(false, false)
        }
        if (responseText =~ /Debido a que usted ha sido autorizado por otros contribuyentes\s+para que los represente electrónicamente en el sitio web del SII, esta página le permitirá decidir\s+si en esta oportunidad desea realizar trámites propios o representar electrónicamente a otro\s+contribuyente/) {
            logger.info("Selección de representar o continuar")
            restClient.uri("https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi?DESDE_DONDE_URL=OPCION=1&amp;TIPO=4")
            response = restClient.call() // Segundo llamado lleva a formulario
            responseText = new String(response.bytes(), "iso-8859-1")
            //logger.info("responseText: ${responseText}")
        }
        if (responseText =~ /location.replace\('https:\/\/www1.sii.cl\/cgi-bin\/Portal001\/mipeSelEmpresa.cgi\?DESDE_DONDE_URL=OPCION=1&TIPO=4'\)/) {
            logger.info("Redirección al origen")
            restClient.uri("https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi?DESDE_DONDE_URL=OPCION=1&amp;TIPO=4")
            response = restClient.call() // Segundo llamado lleva a formulario
            responseText = new String(response.bytes(), "iso-8859-1")
            //logger.info("responseText: ${responseText}")
        }
        if (responseText =~/Para identificar a la empresa con la que desea trabajar\s*en el Portal de Facturaci&oacute;n\s*Electr&oacute;nica del SII,\s*selecci&oacute;nelo de la lista de empresas que\s*lo han registrado como usuario autorizado/) {
            logger.info("Selección de empresa")
            restClient.uri("https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi").method("POST")
            restClient.contentType("application/x-www-form-urlencoded")
            restClient.addBodyParameters([RUT_EMP:rutOrganizacion, DESDE_DONDE_URL:"OPCION=1&TIPO=4"])
            restClient.text("RUT_EMP=${rutOrganizacion}&DESDE_DONDE_URL=OPCION%3D1%26TIPO%3D4")
            response = restClient.call()
            responseText = new String(response.bytes(), "iso-8859-1")
            //logger.info("responseText: ${responseText}")
        }

        return restClient
    }

}

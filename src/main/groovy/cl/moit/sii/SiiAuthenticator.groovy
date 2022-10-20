package cl.moit.sii

import org.eclipse.jetty.http.HttpField
import org.eclipse.jetty.http.HttpHeader
import org.moqui.util.RestClient
import cl.moit.net.ClientAuthRequestFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.regex.Pattern

class SiiAuthenticator {

    protected final static Logger logger = LoggerFactory.getLogger(SiiAuthenticator.class)

    protected String rutOrganizacion
    protected String rutRepresentado
    protected String certData
    protected String certPass
    protected String username
    protected String password
    protected String userAgent
    protected RestClient restClient
    protected RestClient.RequestFactory requestFactory
    protected String proxyHost = null
    protected int proxyPort = 0
    protected boolean portalMipyme = true
    protected boolean trustAll = false

    protected boolean debug = false

    public SiiAuthenticator() {}

    public void setRutOrganizacion(String rutOrganizacion) { this.rutOrganizacion = rutOrganizacion }

    public void setRutRepresentado(String rutRepresentado) { this.rutRepresentado = rutRepresentado }

    public void setCertData(String certData) { this.certData = certData }

    public void setCertPass(String certPass) { this.certPass = certPass }

    public void setUsername(String username) { this.username = username }

    public void setPassword(String password) { this.password = password }

    public void setDebug(boolean debug) { this.debug = debug }

    public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost }

    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort }

    public void setPortalMipyme(boolean portalMipyme) { this.portalMipyme = portalMipyme }

    public void setTrustAll(boolean trustAll) { this.trustAll = trustAll }

    public RestClient.RequestFactory getRequestFactory() {
        return requestFactory
    }

    public RestClient getRestClient() {
        if (restClient == null)
            restClient = createRestClient()
        return restClient
    }

    public RestClient createRestClient() {
        RestClient restClient = new RestClient()
        RestClient.RestResponse response
        String responseText = null
        if (certData != null && certData.size() > 0 && certPass != null && certPass.size() > 0) {
            requestFactory = new ClientAuthRequestFactory(certData, certPass, proxyHost, proxyPort)
            if (trustAll) {
                requestFactory.getHttpClient().sslContextFactory.setTrustAll(true)
                requestFactory.getHttpClient().sslContextFactory.setPkixCertPathChecker(null)
            }
            if (userAgent != null) {
                requestFactory.getHttpClient().setUserAgentField(new HttpField(HttpHeader.USER_AGENT, "Mozilla/4.0 ( compatible; PROG 1.0; Windows NT)"))
            }
            //requestFactory = new RestClient.SimpleRequestFactory(false, false)
            java.net.CookieStore cookieStore = requestFactory.getHttpClient().getCookieStore()
            restClient.withRequestFactory(requestFactory)
            restClient.uri("https://herculesr.sii.cl/cgi_AUT2000/CAutInicio.cgi?https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi?DESDE_DONDE_URL=OPCION%3D1%26TIPO%3D4").method("POST").acceptContentType("*/*")
            restClient.text("referencia=https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi?DESDE_DONDE_URL=OPCION%3D1%26TIPO%3D4")
            response = restClient.call()
            if (debug) {
                logger.warn("Cookies after request:")
                cookieStore.cookies.each { cookie -> logger.info("Cookie for ${cookie.commentURL}: ${cookie.name} = ${cookie.value}")}
                logger.info("response: ${response.text()}")
            }
            responseText = response.text()
        } else {
            logger.warn("Sending user/pass")
            requestFactory = new ClientAuthRequestFactory(null, null, proxyHost, proxyPort)
            restClient.withRequestFactory(requestFactory)
            restClient.uri("https://zeusr.sii.cl/cgi_AUT2000/CAutInicio.cgi").method("POST")
            restClient.contentType("application/x-www-form-urlencoded")
            int pos = username.indexOf('-')
            String rut = null
            String dv = null
            if (pos > 0 && pos < username.length()-1) {
                rut = username.substring(0,pos)
                dv = username.substring(pos+1)
                logger.warn("rut: ${rut}, dv: ${dv}")
            }
            restClient.text("rut=${rut}&dv=${dv}&referencia=https%3A%2F%2Fmisiir.sii.cl%2Fcgi_misii%2Fsiihome.cgi&411=&rutcntr=${username}&&clave=${password}")
            response = restClient.call()
            responseText = response.text()
        }
        if (responseText =~ /Debido a que usted ha sido autorizado por otros contribuyentes\s+para que los represente electrónicamente en el sitio web del SII, esta página le permitirá decidir\s+si en esta oportunidad desea realizar trámites propios o representar electrónicamente a otro\s+contribuyente/) {
            logger.info("Selección de representar o continuar")
            if (rutRepresentado) {
                // Cambiar a Representar
                logger.warn("Cambiando a representar")
                restClient.uri('https://zeusr.sii.cl/cgi_AUT2000/admRPDOBuild.cgi')
                response = restClient.call()
                responseText = new String(response.bytes(), "iso-8859-1")
                // javascript:sendMethodPost('/cgi_AUT2000/admRepresentar.cgi?RUT_RPDO=76514104&APPLS=RPETC&NOMBRE=MOIT%20SPA&APPLSDES=RPETC%20Consulta%20Registro%20Transferencia%20Credito%27);
                //"rPDOsTo":
                Pattern pattern = Pattern.compile('\\{"rutRpteRpdo":"([0-9]+)","dvRpteRpdo":"(.)","codAppl":"([^"]+)","descripcion":"([^"]+)","nameRpte":"([^"]+)","nameRpdo":"([^"]+)","marca":([^"]+)}', Pattern.DOTALL)
                Object matcher = responseText =~ pattern
                Collection results = matcher.findAll()
                Map representeeMap
                results.each { match ->
                    String rutCandidato = "${match[1]}-${match[2]}"
                    if (rutRepresentado == rutCandidato) {
                        representeeMap = [RUT_RPDO:match[1], APPLS:match[3], NOMBRE:match[7], APPLSDES:match[4]]
                    }
                }
                if (representeeMap == null) {
                    logger.error("No se pudo encontrar RUT representado")
                    return null;
                }
                if (certData != null && certData.size() > 0 && certPass != null && certPass.size() > 0) {
                    restClient.uri('https://herculesr.sii.cl/cgi_AUT2000/admRepresentar.cgi')
                } else {
                    restClient.uri('https://zeusr.sii.cl/cgi_AUT2000/admRepresentar.cgi')
                }
                restClient.contentType("application/x-www-form-urlencoded")
                representeeMap.each { key, value ->
                    restClient.addFieldPart(key as String, value as String)
                }
                restClient.text("")
                response = restClient.call()
                responseText = response.text()
                if (debug)
                    logger.info("responseText for representación: ${responseText}")
            }
        }
        if (portalMipyme) {
            restClient.uri("https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi?DESDE_DONDE_URL=OPCION=1&TIPO=4")
            response = restClient.call() // Segundo llamado lleva a formulario
            responseText = new String(response.bytes(), "iso-8859-1")
            if (debug)
                logger.info("responseText: ${responseText}")

            if (responseText =~ /location.replace\('https:\/\/www1.sii.cl\/cgi-bin\/Portal001\/mipeSelEmpresa.cgi\?DESDE_DONDE_URL=OPCION=1&TIPO=4'\)/) {
                logger.info("Redirección al origen")
                restClient.uri("https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi?DESDE_DONDE_URL=OPCION=1&amp;TIPO=4")
                response = restClient.call() // Segundo llamado lleva a formulario
                responseText = new String(response.bytes(), "iso-8859-1")
                if (debug)
                    logger.info("responseText: ${responseText}")
            }
            if (responseText =~/Para identificar a la empresa con la que desea trabajar\s*en el Portal de Facturaci&oacute;n\s*Electr&oacute;nica del SII,\s*selecci&oacute;nelo de la lista de empresas que\s*lo han registrado como usuario autorizado/) {
                logger.info("Selección de empresa")
                if (responseText =~/<option value="${rutOrganizacion}">/) {
                    restClient.uri("https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi").method("POST")
                    restClient.contentType("application/x-www-form-urlencoded")
                    restClient.addBodyParameters([RUT_EMP: rutOrganizacion, DESDE_DONDE_URL: "OPCION=1&TIPO=4"])
                    restClient.text("RUT_EMP=${rutOrganizacion}&DESDE_DONDE_URL=OPCION%3D1%26TIPO%3D4")
                    response = restClient.call()
                    logger.info("After call")
                    responseText = new String(response.bytes(), "iso-8859-1")
                    if (debug)
                        logger.info("responseText: ${responseText}")
                } else {
                    throw new RuntimeException("Empresa ${rutOrganizacion} no aparece entre las opciones de selección en el SII")
                }
            }

        }

        restClient.removeAllBodyParameters()
        return restClient
    }

}

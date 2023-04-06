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

    public SiiAuthenticator setRutOrganizacion(String rutOrganizacion) { this.rutOrganizacion = rutOrganizacion; return this }

    public SiiAuthenticator setRutRepresentado(String rutRepresentado) { this.rutRepresentado = rutRepresentado; return this }

    public SiiAuthenticator setCertData(String certData) { this.certData = certData; return this }

    public SiiAuthenticator setCertPass(String certPass) { this.certPass = certPass; return this }

    public SiiAuthenticator setUsername(String username) { this.username = username; return this }

    public SiiAuthenticator setPassword(String password) { this.password = password; return this }

    public SiiAuthenticator setDebug(boolean debug) { this.debug = debug; return this }

    public SiiAuthenticator setProxyHost(String proxyHost) { this.proxyHost = proxyHost; return this }

    public SiiAuthenticator setProxyPort(int proxyPort) { this.proxyPort = proxyPort; return this }

    public SiiAuthenticator setPortalMipyme(boolean portalMipyme) { this.portalMipyme = portalMipyme; return this }

    public SiiAuthenticator setTrustAll(boolean trustAll) { this.trustAll = trustAll; return this }

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
            try {
                response = restClient.call()
            } catch (Exception e) {
                ec.logger.error("Calling sii CautInicio certificate", e)
                ec.message.addError("Error de comunicación con SII en autenticación con certificado")
                return
            }
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
            try {
                response = restClient.call()
            } catch (Exception e) {
                ec.logger.error("Calling sii CautInicio user/pass", e)
                ec.message.addError("Error de comunicación con SII en autenticación con usuario/clave")
                return
            }
            responseText = response.text()
        }
        if (responseText =~ /Debido a que usted ha sido autorizado por otros contribuyentes\s+para que los represente electrónicamente en el sitio web del SII, esta página le permitirá decidir\s+si en esta oportunidad desea realizar trámites propios o representar electrónicamente a otro\s+contribuyente/) {
            if (debug) logger.info("Selección de representar o continuar")
            if (rutRepresentado) {
                // Cambiar a Representar
                if (debug) logger.warn("Cambiando a representar")
                restClient.uri('https://zeusr.sii.cl/cgi_AUT2000/admRPDOBuild.cgi')
                try {
                    response = restClient.call()
                } catch (Exception e) {
                    ec.logger.error("Calling sii obteniendo RUTs a representar", e)
                    ec.message.addError("Error de comunicación con SII al obtener RUTs para representar")
                    return
                }
                responseText = new String(response.bytes(), "iso-8859-1")
                // javascript:sendMethodPost('/cgi_AUT2000/admRepresentar.cgi?RUT_RPDO=76514104&APPLS=RPETC&NOMBRE=MOIT%20SPA&APPLSDES=RPETC%20Consulta%20Registro%20Transferencia%20Credito%27);
                // javascript:sendMethodPost('/cgi_AUT2000/admRepresentar.cgi?RUT_RPDO=76222457&amp;APPLS=RPETC,FIS10&amp;NOMBRE=INVERSIONES CJ LIMITADA&amp;APPLSDES=RPETC Consulta Registro Transferencia Credito, FIS10 Acceso a opciones de Usuarios Relacionados de BBRR');">76.222.457-7 </a>
                //"rPDOsTo":
                Pattern pattern = Pattern.compile('\\{"rutRpteRpdo":"([0-9]+)","dvRpteRpdo":"(.)","codAppl":"([^"]+)","descripcion":"([^"]+)","nameRpte":"([^"]+)","nameRpdo":"([^"]+)","marca":([^"]+)}[,\\]]', Pattern.DOTALL)
                Object matcher = responseText =~ pattern
                Collection results = matcher.findAll()
                Map representeeMap
                results.each { match ->
                    String rutCandidato = "${match[1]}-${match[2]}"
                    if (rutRepresentado == rutCandidato) {
                        representeeMap = [RUT_RPDO:match[1], APPLS:match[3], NOMBRE:match[6], APPLSDES:match[4]]
                    }
                }
                if (representeeMap == null) {
                    ec.message.addError("No se pudo encontrar RUT representado")
                    return null
                }
                boolean hasAllFields = true
                ['RUT_RPDO', 'APPLS', 'NOMBRE', 'APPLSDES'].each {
                    if (representeeMap[it] == null || representeeMap[it] == 'null') {
                        logger.error("Parsing representee data did not find key ${it}")
                        hasAllFields = false
                    }
                }
                if (debug) logger.warn("representeeMap: ${representeeMap}")
                if (!hasAllFields) {
                    logger.error("Received responseText: ${responseText}")
                    return null
                }
                if (certData != null && certData.size() > 0 && certPass != null && certPass.size() > 0) {
                    restClient.uri('https://herculesr.sii.cl/cgi_AUT2000/admRepresentar.cgi')
                } else {
                    restClient.uri('https://zeusr.sii.cl/cgi_AUT2000/admRepresentar.cgi')
                }
                restClient.contentType("application/x-www-form-urlencoded")
                restClient.addBodyParameters(representeeMap)
                org.eclipse.jetty.util.Fields fields = new org.eclipse.jetty.util.Fields()
                representeeMap.each { key, value ->
                    fields.add(key, value)
                }
                String bodyText = org.eclipse.jetty.client.util.FormRequestContent.convert(fields)
                restClient.text(bodyText)
                try {
                    response = restClient.call()
                } catch (Exception e) {
                    ec.logger.error("Calling sii admRepresentar", e)
                    ec.message.addError("Error de comunicación con SII en autenticación al activar representación")
                    return
                }
                responseText = response.text()
                if (responseText.contains("En este momento no lo podemos atender, pues hemos detectado un error")) {
                    logger.error("Error: ${responseText}")
                    return null;
                }
                if (debug)
                    logger.info("responseText for representación: ${responseText}")
            }
        }
        if (portalMipyme) {
            restClient.uri("https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi?DESDE_DONDE_URL=OPCION=1&TIPO=4")
            try {
                response = restClient.call() // Segundo llamado lleva a formulario
            } catch (Exception e) {
                ec.logger.error("Calling portalMipyme step 1", e)
                ec.message.addError("Error de comunicación con SII autenticando portalMipyme (paso 1)")
                return
            }
            responseText = new String(response.bytes(), "iso-8859-1")
            if (debug)
                logger.info("responseText: ${responseText}")

            if (responseText =~ /location.replace\('https:\/\/www1.sii.cl\/cgi-bin\/Portal001\/mipeSelEmpresa.cgi\?DESDE_DONDE_URL=OPCION=1&TIPO=4'\)/) {
                logger.info("Redirección al origen")
                restClient.uri("https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi?DESDE_DONDE_URL=OPCION=1&amp;TIPO=4")
                try {
                    response = restClient.call() // Segundo llamado lleva a formulario
                } catch (Exception e) {
                    ec.logger.error("Calling portalMipyme step 2", e)
                    ec.message.addError("Error de comunicación con SII autenticando portalMipyme (paso 2)")
                    return
                }
                responseText = new String(response.bytes(), "iso-8859-1")
                if (debug)
                    logger.info("responseText: ${responseText}")
            }
            if (responseText =~/Para identificar a la empresa con la que desea trabajar\s*en el Portal de Facturaci&oacute;n\s*Electr&oacute;nica del SII,\s*selecci&oacute;nelo de la lista de empresas que\s*lo han registrado como usuario autorizado/) {
                if (debug) logger.info("Selección de empresa")
                if (responseText =~/<option value="${rutOrganizacion}">/) {
                    restClient.uri("https://www1.sii.cl/cgi-bin/Portal001/mipeSelEmpresa.cgi").method("POST")
                    restClient.contentType("application/x-www-form-urlencoded")
                    restClient.addBodyParameters([RUT_EMP: rutOrganizacion, DESDE_DONDE_URL: "OPCION=1&TIPO=4"])
                    restClient.text("RUT_EMP=${rutOrganizacion}&DESDE_DONDE_URL=OPCION%3D1%26TIPO%3D4")
                    try {
                        response = restClient.call()
                    } catch (Exception e) {
                        ec.logger.error("Calling portalMipyme step 3", e)
                        ec.message.addError("Error de comunicación con SII autenticando portalMipyme (paso 3)")
                        return
                    }
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

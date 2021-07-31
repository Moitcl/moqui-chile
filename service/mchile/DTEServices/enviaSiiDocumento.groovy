import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

import cl.nic.dte.net.ConexionSii
import cl.nic.dte.util.Utilities
import cl.sii.siiDte.RECEPCIONDTEDocument

ExecutionContext ec

// Validación rut -->
ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameter("rut", enviadorS).call()
context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameter("partyId", activeOrgId).call())
passS = passCert

ConexionSii con = new ConexionSii()
// leo certificado y llave privada del archivo pkcs12
KeyStore ks = KeyStore.getInstance("PKCS12")
ks.load(certData.getBinaryStream(), passS.toCharArray())
String alias = ks.aliases().nextElement()
ec.logger.warn("Usando certificado " + alias + " del archivo PKCS12: " + certS)

X509Certificate x509 = (X509Certificate) ks.getCertificate(alias)
PrivateKey pKey = (PrivateKey) ks.getKey(alias, passS.toCharArray())

String token = con.getToken(pKey, x509)

String enviadorS = Utilities.getRutFromCertificate(x509)

ec.logger.warn("Archivo enviado: " + documentoS)

// Cambiar en produccion
RECEPCIONDTEDocument recp = con.uploadEnvioCertificacion(enviadorS, compaS, new File(documentoS), token)
ec.logger.warn("-----------------")
ec.logger.warn(recp.xmlText())

// Se verifica si el status es 0

String statusXML = recp.xmlText()
int inicio = statusXML.indexOf("&lt;siid:STATUS&gt;")
int fin = statusXML.indexOf("&lt;/siid:STATUS&gt;")

statusXML = statusXML.substring(inicio+1,fin)
statusXML = statusXML.replaceAll("siid:STATUS&gt;","")
ec.logger.warn("STATUS: " + statusXML)

if(statusXML.equals("0")) {
    trackId = recp.xmlText()
    inicio = trackId.indexOf("&lt;siid:TRACKID&gt;")
    fin = trackId.indexOf("&lt;/siid:TRACKID&gt;")
    trackId = trackId.substring(inicio+1,fin)
    trackId = trackId.replaceAll("siid:TRACKID&gt;","")
    ec.logger.warn("DTE Enviada correctamente con trackId " + trackId)
} else {
    ec.logger.warn("Error "+ statusXML + " al enviar DTE")
}

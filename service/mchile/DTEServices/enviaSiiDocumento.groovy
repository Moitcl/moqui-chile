import cl.nic.dte.net.ConexionSii
import cl.sii.siiDte.RECEPCIONDTEDocument

import org.moqui.context.ExecutionContext

ExecutionContext ec = context.ec

// Validación rut -->
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameter("partyId", organizationPartyId).call())

ConexionSii con = new ConexionSii()

ec.logger.warn("Archivo enviado: " + documentLocation)

locationDataSource = ec.resource.getLocationDataSource(documentLocation)
locationReference = ec.resource.getLocationReference(documentLocation)

java.io.File tempFile = File.createTempFile("envioSii", ".xml");
org.moqui.resource.ResourceReference tmpRr = ec.resource.getLocationReference(tempFile.getAbsolutePath())
tmpRr.putStream(locationReference.openStream())

RECEPCIONDTEDocument recp
ec.logger.warn("Enviando con rutEnvia ${rutEnvia}, rutEmisor ${rutEmisor}")
if (dteSystemIsProduction) {
    String token = con.getToken(pkey, certificate)
    recp = con.uploadDataSourceEnvioProduccion(rutEnvia, rutEmisor, tempFile, token)
} else {
    String token = con.getTokenCert(pkey, certificate)
    ec.logger.warn("token: ${token}")
    recp = con.uploadEnvioCertificacion(rutEnvia, rutEmisor, tempFile, token)
}
tempFile.delete()
ec.logger.warn("-----------------")
ec.logger.warn(recp.xmlText())

// Se verifica si el status es 0

String statusXML = recp.xmlText()
int inicio = statusXML.indexOf("<siid:STATUS>")
int fin = statusXML.indexOf("</siid:STATUS>")

statusXML = statusXML.substring(inicio+1,fin)
statusXML = statusXML.replaceAll("siid:STATUS>","")
ec.logger.warn("STATUS: " + statusXML)

if(statusXML.equals("0")) {
    trackId = recp.xmlText()
    inicio = trackId.indexOf("<siid:TRACKID>")
    fin = trackId.indexOf("</siid:TRACKID>")
    trackId = trackId.substring(inicio+1,fin)
    trackId = trackId.replaceAll("siid:TRACKID>","")
    ec.logger.warn("DTE Enviada correctamente con trackId " + trackId)
} else {
    ec.message.addMessage("Error "+ statusXML + " al enviar DTE", "danger")
}

import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpression
import javax.xml.xpath.XPathFactory
import groovy.xml.MarkupBuilder
import groovy.json.JsonSlurper

import org.w3c.dom.Document
import cl.moit.dte.MoquiDTEUtils

Integer estadoRecepEnv = 0
dteEnvioEv = ec.entity.find("mchile.dte.DteEnvio").condition("envioId", envioId).one()
if (dteEnvioEv.statusId != 'Ftde-Received') {
    ec.logger.error("Estado inválido para procesar envío ${envioId}: ${dteEnvioEv.statusId}")
    return
}

inputStream = ec.resource.getLocationReference(dteEnvioEv.documentLocation).openStream()
Map processingParameters = new JsonSlurper().parseText(dteEnvioEv.processingParameters)

Boolean createUnknownIssuer = processingParameters.createUnknownIssuer ?: true
Boolean requireIssuerInternalOrg = processingParameters.requireIssuerInternalOrg ?: false
Boolean createUnknownReceiver = processingParameters.createUnknownReceiver ?: true
Boolean requireReceiverInternalOrg = processingParameters.requireReceiverInternalOrg ?: true

Document doc
try {
    doc = MoquiDTEUtils.parseDocument(inputStream)
} catch (Exception e) {
    ec.logger.error("Parsing document: ${e.toString()}")
    estadoRecepEnv = 91
}

if (estadoRecepEnv == 0 && !MoquiDTEUtils.verifySignature(doc, "/sii:EnvioDTE/sii:SetDTE", "./sii:Caratula/sii:TmstFirmaEnv/text()")) {
    ec.logger.error("Firma del envío inválida")
    estadoRecepEnv = 2
}

processedItems = 0

groovy.util.Node envioDte = MoquiDTEUtils.dom2GroovyNode(doc)
setDte = envioDte.SetDTE

groovy.util.NodeList dteList = setDte.DTE

if (dteList.size() < 1) {
    ec.logger.error("Documento no contiene DTEs")
    estadoRecepEnv = 2
    return
}

// Caratula
caratula = setDte.Caratula
String issuerPartyId = null
String issuerTaxName = null

rutEmisorCaratula = caratula.RutEmisor.text()
rutReceptorCaratula = caratula.RutReceptor.text()
fechaFirmaEnvio = ec.l10n.parseTimestamp(caratula.TmstFirmaEnv.text(), "yyyy-MM-dd'T'HH:mm:ss")
emisor = setDte.DTE[0].Documento.Encabezado.Emisor
if (rutEmisorCaratula != emisor.RUTEmisor.text()) {
    ec.logger.error("Rut emisor de carátula (${rutEmisorCaratula} y DTE (${emisor.RUTEmisor.text()}) no coinciden")
    estadoRecepEnv = 2
}
issuerPartyId = ec.service.sync().name("mchile.sii.DTECommServices.get#PartyIdByRut").parameters([idValue:rutEmisorCaratula, createUnknown:createUnknownIssuer, razonSocial:emisor.RznSoc.text(), roleTypeId:'Supplier',
        giro:emisor.GiroEmis.text(), direccion:emisor.DirOrigen.text(), comuna:emisor.CmnaOrigen.text(), ciudad:emisor.CiudadOrigen.text()]).call().partyId
receptor = setDte.DTE[0].Documento.Encabezado.Receptor
if (rutReceptorCaratula != receptor.RUTRecep.text()) {
    ec.logger.error("Rut receptor de carátula (${rutReceptorCaratula} y DTE (${receptor.RUTRecep.text()}) no coinciden")
    estadoRecepEnv = 2
}
receiverPartyId = ec.service.sync().name("mchile.sii.DTECommServices.get#PartyIdByRut").parameters([idValue:rutReceptorCaratula, createUnknown:createUnknownReceiver, razonSocial:receptor.RznSocRecep.text(), roleTypeId:'Customer',
                                                                                              giro:emisor.GiroRecep.text(), direccion:emisor.DirRecep.text(), comuna:emisor.CmnaRecep.text(), ciudad:emisor.CiudadRecep.text()]).call().partyId

envioRespuestaId = ec.service.sync().name("create#mchile.dte.DteEnvio").parameters([envioTypeEnumId:'Ftde-RespuestaDte', statusId:'Ftde-Created', rutEmisor:rutReceptorCaratula, rutReceptor:rutEmisorCaratula, fechaEnvio:ec.user.nowTimestamp, internalId:idRecepcionDte]).call().envioId

EntityValue issuer = ec.entity.find("mantle.party.PartyDetail").condition("partyId", issuerPartyId).one()
issuerTaxName = issuer.taxOrganizationName
if (issuerTaxName == null || issuerTaxName.size() == 0)
    issuerTaxName = ec.resource.expand("PartyNameOnlyTemplate", null, issuer)

ec.logger.warn("Emisor según carátula: ${rutEmisorCaratula}, issuerTaxName ${issuerTaxName}")

digestValue = envioDte.Signature.SignedInfo.DigestValue.text()

/*
glosaEstadoRecepcionMap = [0:'Envío Recibido Conforme.', 1:'Envío Rechazado – Error de Schema', 2:'Envío Rechazado - Error de Firma', 3:'Envío Rechazado - RUT Receptor No Corresponde',
                           90:'Envío Rechazado - Archivo Repetido', 91:'Envío Rechazado - Archivo Ilegible', 99:'Envío Rechazado - Otros']
 */

XPath xpath = XPathFactory.newInstance().newXPath()
xpath.setNamespaceContext(new MoquiDTEUtils.DefaultNamespaceContext().addNamespace("sii", "http://www.sii.cl/SiiDte"))

XPathExpression expression = xpath.compile("/sii:EnvioDTE/sii:SetDTE/sii:DTE")
org.w3c.dom.NodeList dteNodeList = (org.w3c.dom.NodeList) expression.evaluate(doc.getDocumentElement(), XPathConstants.NODESET)

totalItems = dteNodeList.length
recepcionList = []
dteNodeList.each { org.w3c.dom.Node domNode ->
    recepcion = ec.service.sync().name("mchile.sii.DTEServices.load#DteFromDom").parameters(context+[domNode:domNode]).call()
    recepcionList.add(recepcion)
    ec.message.clearErrors()
    if (recepcion.internalErrors == null || recepcion.internalErrors.size() == 0)
        processedItems++
}

estadoGlosaMap = [0:'Envio Recibido Conforme', 1:'Envio Rechazado - Error de Schema', 2:'Envio Rechazado - Error de Firma', 3:'Envio Rechazado - RUT Receptor No Corresponde', 90:'Envio Rechazado - Archivo Repetido',
                  91:'Envio Rechazado - Archivo Ilegible', 99:'Envio Rechazado - Otros']
idAcuseRecibo = "EnvAcuseRecibo-" + envioRespuestaId
StringWriter writer = new StringWriter()
MarkupBuilder acuseRecibo = new MarkupBuilder(writer)
String tmstFirmaResp = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")
String schemaLocation = 'http://www.sii.cl/SiiDte RespuestaEnvioDTE_v10.xsd'
acuseRecibo.RespuestaDTE('xmlns': 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version:'1.0', 'xsi:schemaLocation': schemaLocation) {
    Resultado(ID:idAcuseRecibo) {
        Caratula(version:"1.0") {
            RutResponde(rutReceptorCaratula)
            RutRecibe(rutEmisorCaratula)
            IdRespuesta(envioRespuestaId)
            NroDetalles(processedItems)
            //NmbContacto("")
            //FonoContacto("")
            //MailContacto("")
            TmstFirmaResp(tmstFirmaResp)
        }
        RecepcionEnvio {
            NmbEnvio(dteEnvioEv.fileName)
            FchRecep(ec.l10n.format(dteEnvioEv.registerDate, "yyyy-MM-dd'T'HH:mm:ss"))
            CodEnvio(envioRespuestaId)
            EnvioDTEID(dteEnvioEv.internalId)
            Digest(digestValue)
            RutEmisor(rutEmisorCaratula)
            RutReceptor(rutReceptorCaratula)
            EstadoRecepEnv(estadoRecepEnv)
            RecepEnvGlosa(estadoGlosaMap[estadoRecepEnv])
            NroDTE(processedItems)
            recepcionList.each { Map recepcion ->
                RecepcionDTE {
                    TipoDTE(recepcion.tipoDte)
                    Folio(recepcion.folioDte)
                    FchEmis(recepcion.fchEmis)
                    RUTEmisor(recepcion.rutEmisor)
                    RUTRecep(recepcion.rutRecep)
                    MntTotal(recepcion.mntTotal)
                    EstadoRecepDTE(recepcion.estadoRecepDte)
                    RecepDTEGlosa(recepcion.recepDteGlosa)
                }
            }
        }
    }
}

ec.context.putAll(ec.service.sync().name("mchile.sii.DTEServices.load#DTEConfig").parameters([partyId:receiverPartyId]).call())
xml = writer.toString()
Document doc2 = MoquiDTEUtils.parseDocument(xml.getBytes())
byte[] salida = MoquiDTEUtils.sign(doc2, "#" + idAcuseRecibo, pkey, certificate, "#" + idAcuseRecibo,"Resultado")

try {
    MoquiDTEUtils.validateDocumentSii(ec, salida, schemaLocation)
} catch (Exception e) {
    ec.message.addError("Failed validation: " + e.getMessage())
}

doc2 = MoquiDTEUtils.parseDocument(salida)

if (MoquiDTEUtils.verifySignature(doc2, "/sii:RespuestaDTE/sii:Resultado", "./sii:Caratula/sii:TmstFirmaResp/text()")) {
    xmlContentLocation = "dbresource://moit/erp/dte/RespuestaDte/${rutReceptorCaratula}/${idAcuseRecibo}.xml"
    ec.logger.warn("Envio generado OK")
} else {
    xmlContentLocation = "dbresource://moit/erp/dte/RespuestaDte/${rutEmisor}/${idAcuseRecibo}-mala.xml"
    ec.logger.warn("Error al generar envio")
}
xmlContentRr = ec.resource.getLocationReference(xmlContentLocation)
xmlContentRr.putBytes(salida)
ec.service.sync().name("update#mchile.dte.DteEnvio").parameters([envioId:envioRespuestaId, documentLocation:xmlContentLocation, internalId:idAcuseRecibo, fileName:xmlContentRr.fileName]).call()

processed = (processedItems == totalItems)

return
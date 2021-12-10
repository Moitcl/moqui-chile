import org.moqui.entity.EntityCondition

import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpression
import javax.xml.xpath.XPathFactory
import groovy.xml.MarkupBuilder

import org.moqui.context.ExecutionContext
import org.w3c.dom.Document
import cl.moit.dte.MoquiDTEUtils

import java.text.SimpleDateFormat

ExecutionContext ec = context.ec

// Check signatures
Document doc
try {
    doc = MoquiDTEUtils.parseDocument(xml)
} catch (Exception e) {
    ec.message.addError("Parsing document: ${e.toString()}")
}

if (!MoquiDTEUtils.verifySignature(doc, "/sii:EnvioDTE/sii:SetDTE", "./sii:Caratula/sii:TmstFirmaEnv/text()"))
    ec.message.addError("Firma del envío inválida")

if (ec.message.hasError())
    return

groovy.util.Node envioDte = MoquiDTEUtils.dom2GroovyNode(doc)
setDte = envioDte.SetDTE

groovy.util.NodeList dteList = setDte.DTE

if (dteList.size() < 1) {
    ec.message.addError("Documento no contiene DTEs")
    return
}

recepcionEnvio = []
resultado = [recepcionEnvio:recepcionEnvio]

// Caratula
caratula = setDte.Caratula
String issuerPartyId = null
String issuerTaxName = null

rutEmisorCaratula = caratula.RutEmisor.text()
rutReceptorCaratula = caratula.RutReceptor.text()
caratulaResultado = [rutRecibe:rutEmisorCaratula, rutResponde:rutReceptorCaratula, nroDetalles:dteList.size()]
resultado.caratula = caratulaResultado
issuerPartyIdentificationList = ec.entity.find("mantle.party.PartyIdentification").condition([idValue:rutEmisorCaratula, partyIdTypeEnumId:'PtidNationalTaxId']).list()
if (issuerPartyIdentificationList.size() < 1) {
    if (createUnknownIssuer) {
        emisor = setDte.DTE[0].Documento.Encabezado.Emisor
        mapOut = ec.service.sync().name("mantle.party.PartyServices.create#Organization").parameters([organizationName:emisor.RznSoc.text(), taxOrganizationName:emisor.RznSoc.text(), roleTypeId:'Supplier']).call()
        issuerPartyId = mapOut.partyId
        ec.service.sync().name("create#mantle.party.PartyIdentification").parameters([partyId:issuerPartyId, partyIdTypeEnumId:'PtidNationalTaxId', idValue:rutEmisorCaratula]).call()
        ec.service.sync().name("create#mchile.dte.PartyGiro").parameters([partyId:issuerPartyId, description:emisor.GiroEmis.text(), isPrimary:'Y']).call()
        comunaList = ec.entity.find("moqui.basic.GeoAssocAndToDetail").condition("geoId", "CHL").condition(ec.entity.conditionFactory.makeCondition("geoName", EntityCondition.EQUALS, emisor.CmnaOrigen.text()).ignoreCase()).list()
        comunaId = comunaList? comunaList.first.geoId : null
        ec.service.sync().name("mantle.party.ContactServices.store#PartyContactInfo").parameters([partyId:issuerPartyId, address1:emisor.DirOrigen.text(),
                                                                                                  postalContactMechPurposeId:'PostalTax', stateProvinceGeoId:comunaId, countryGeoId:"CHL", city:emisor.CiudadOrigen.text()]).call()
    } else {
        ec.message.addError("No existe organización con RUT ${rutReceptorCaratula} (emisor) definida en el sistema")
    }
} else if (issuerPartyIdentificationList.size() == 1) {
    issuerPartyId = issuerPartyIdentificationList.first.partyId
} else {
    ec.message.addError("Más de un sujeto con mismo rut de emisor (${rutEmisorCaratula}: partyIds ${issuerPartyIdentificationList.partyId}")
}

fechaFirmaEnvio = ec.l10n.parseTimestamp(caratula.TmstFirmaEnv.text(), "yyyy-MM-dd'T'HH:mm:ss")
emisor = setDte.DTE[0].Documento.Encabezado.Emisor
if (rutEmisorCaratula != emisor.RUTEmisor.text())
    ec.message.addError("Rut emisor de carátula (${rutEmisorCaratula} y DTE (${emisor.RUTEmisor.text()}) no coinciden")
issuerPartyId = ec.service.sync().name("mchile.DTECommServices.get#PartyIdByRut").parameters([idValue:rutEmisorCaratula, createUnknown:true, razonSocial:emisor.RznSoc.text(), roleTypeId:'Supplier',
        giro:emisor.GiroEmis.text(), direccion:emisor.DirOrigen.text(), comuna:emisor.CmnaOrigen.text(), ciudad:emisor.CiudadOrigen.text()]).call().partyId
receptor = setDte.DTE[0].Documento.Encabezado.Receptor
if (rutReceptorCaratula != receptor.RUTRecep.text())
    ec.message.addError("Rut receptor de carátula (${rutReceptorCaratula} y DTE (${receptor.RUTRecep.text()}) no coinciden")
receiverPartyId = ec.service.sync().name("mchile.DTECommServices.get#PartyIdByRut").parameters([idValue:rutReceptorCaratula, createUnknown:false, razonSocial:receptor.RznSocRecep.text(), roleTypeId:'Customer',
                                                                                              giro:emisor.GiroRecep.text(), direccion:emisor.DirRecep.text(), comuna:emisor.CmnaRecep.text(), ciudad:emisor.CiudadRecep.text()]).call().partyId

envioDteId = ec.service.sync().name("create#mchile.dte.DteEnvio").parameters([envioTypeEnumId:'Ftde-EnvioDte', rutEmisor:rutEmisorCaratula, rutReceptor:rutReceptorCaratula, fechaEnvio:fechaFirmaEnvio, fechaRegistro:ec.user.nowTimestamp]).call().envioId
documentLocation ="dbresource://moit/erp/dte/EnvioDTE/${rutEmisorCaratula}/${rutEmisorCaratula}-${envioDteId}.xml"
ec.resource.getLocationReference(documentLocation).putBytes(xml)
ec.service.sync().name("update#mchile.dte.DteEnvio").parameters([envioId:envioDteId, documentLocation:documentLocation, receivedFileName:xmlFileName]).call().envioId
envioRespuestaId = ec.service.sync().name("create#mchile.dte.DteEnvio").parameters([envioTypeEnumId:'Ftde-RespuestaDte', rutEmisor:rutReceptorCaratula, rutReceptor:rutEmisorCaratula, fechaEnvio:ec.user.nowTimestamp]).call().envioId

EntityValue issuer = ec.entity.find("mantle.party.PartyDetail").condition("partyId", issuerPartyId).one()
issuerTaxName = issuer.taxOrganizationName
if (issuerTaxName == null || issuerTaxName.size() == 0)
    issuerTaxName = ec.resource.expand("PartyNameOnlyTemplate", null, issuer)

ec.logger.warn("Emisor según carátula: ${rutEmisorCaratula}, issuerTaxName ${issuerTaxName}")

/*
glosaEstadoRecepcionMap = [0:'Envío Recibido Conforme.', 1:'Envío Rechazado – Error de Schema', 2:'Envío Rechazado - Error de Firma', 3:'Envío Rechazado - RUT Receptor No Corresponde',
                           90:'Envío Rechazado - Archivo Repetido', 91:'Envío Rechazado - Archivo Ilegigle', 99:'Envío Rechazado - Otros']
recepcionEnvioDetalle = [nombreArchivo:xmlFilename, fechaRecepcion:ec.l10n.format(ec.user.nowTimestamp,"yyyy-MM-dd'T'HH:mm:ss"),
                rutEmisorEnvio:rutReceptorCaratula, rutReceptorEnvio:rutEmisorCaratula, estadoRecepcion:0]
recepcionEnvioDetalle.glosaEstadoRecepcion = glosaEstadoRecepcionMap[recepcionEnvioDetalle.estadoRecepcion]
 */

XPath xpath = XPathFactory.newInstance().newXPath()
xpath.setNamespaceContext(new MoquiDTEUtils.DefaultNamespaceContext().addNamespace("sii", "http://www.sii.cl/SiiDte"))

processedDocuments = 0
XPathExpression expression = xpath.compile("/sii:EnvioDTE/sii:SetDTE/sii:DTE")
org.w3c.dom.NodeList dteNodeList = (org.w3c.dom.NodeList) expression.evaluate(doc.getDocumentElement(), XPathConstants.NODESET)

recepcionList = []
dteNodeList.each { org.w3c.dom.Node domNode ->
    recepcion = ec.service.sync().name("mchile.DTEServices.load#DteFromDom").parameters(context+[requireReceiverInternalOrg:true, domNode:domNode]).call()
    recepcionList.add(recepcion)
    ec.message.clearErrors()
    processedDocuments++
}

SimpleDateFormat ft = new SimpleDateFormat("yyyyMMddhhmmssMs")
String datetime = ft.format(new Date())
idS = "EnvRecibo" + datetime
def StringWriter writer = new StringWriter()
def MarkupBuilder recepcionDte = new MarkupBuilder(writer)
SimpleDateFormat fts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
tmstFirmaResp = fts.format(new Date())
recepcionDte.'sii:RespuestaDTE'('xmlns:sii': 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version:'1.0', 'xsi:schemaLocation': 'http://www.sii.cl/SiiDte RespuestaEnvioDTE_v10.xsd') {
    'sii:Resultado'(ID:idS) {
        'sii:Caratula'(version:"1.0") {
            'sii:RutResponde'(rutReceptorCaratula)
            'sii:RutRecibe'(rutEmisorCaratula)
            'sii:IdRespuesta'(envioRespuestaId)
            'sii:NroDetalles'(processedDocuments)
            //'sii:NmbContacto'("")
            //'sii:FonoContacto'("")
            //'sii:MailContacto'("")
            'sii:TmstFirmaResp'(tmstFirmaResp)
        }
        recepcionList.each { Map recepcion ->
            'sii:ResultadoDTE' {
                'sii:TipoDTE'(recepcion.tipoDte)
                'sii:Folio'(recepcion.folioDte)
                'sii:RUTEmisor'(recepcion.rutEmisor)
                'sii:RUTRecep'(recepcion.rutRecep)
                'sii:MntTotal'(recepcion.mntTotal)
                'sii:EstadoDTE'(recepcion.estadoRecepDte)
                'sii:EstadoDTEGlosa'(recepcion.recepDteGlosa)
            }
        }
    }
}

ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameters([partyId:receiverPartyId]).call())
xml = writer.toString()
ec.logger.info("xml: ${xml}")
Document doc2 = MoquiDTEUtils.parseDocument(xml.getBytes())
byte[] salida = MoquiDTEUtils.sign(doc2, "#" + idS, pkey, certificate, "#" + idS,"sii:Resultado")

doc2 = MoquiDTEUtils.parseDocument(salida)

if (MoquiDTEUtils.verifySignature(doc2, "/sii:RespuestaDTE/sii:Resultado", "./sii:Caratula/sii:TmstFirmaResp/text()")) {
    xmlContentLocation = "dbresource://moit/erp/dte/RespuestaDTE/${rutReceptorCaratula}/${idS}.xml"
    ec.logger.warn("Envio generado OK")
} else {
    xmlContentLocation = "dbresource://moit/erp/dte/RespuestaDTE/${rutEmisor}/${idS}-mala.xml"
    ec.logger.warn("Error al generar envio")
}
ec.resource.getLocationReference(xmlContentLocation).putBytes(salida)
ec.service.sync().name("update#mchile.dte.DteEnvio").parameters([envioId:envioRespuestaId, documentLocation:xmlContentLocation]).call()
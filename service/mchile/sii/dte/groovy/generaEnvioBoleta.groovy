import org.moqui.context.ExecutionContext
import org.moqui.resource.ResourceReference
import org.w3c.dom.Document
import groovy.xml.MarkupBuilder

import cl.moit.dte.MoquiDTEUtils

ExecutionContext ec = context.ec

// Recuperacion de parametros de la organizacion
ec.context.putAll(ec.service.sync().name("mchile.sii.dte.DteInternalServices.load#DteConfig").parameters([partyId:organizationPartyId]).call())

ResourceReference[] DTEList = new ResourceReference[documentIdList.size()]

docNumberByType = [:]
dteEvList = ec.entity.find("mchile.dte.FiscalTaxDocument").condition("fiscalTaxDocumentId", "in", documentIdList).list()
dteList = []
issuerPartyId = null
receiverPartyId = (rutReceptor == '60803000-K' ? 'CL_SII' : null)
dteEvList.each { dte ->
    if (issuerPartyId != null && issuerPartyId != dte.issuerPartyId)
        ec.message.addError("No se pueden incluir documentos de diferentes emisores (${issuerPartyId} y ${dte.issuerPartyId})")
    else
        issuerPartyId = dte.issuerPartyId
    tipoDte = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#SiiCode").parameter("fiscalTaxDocumentTypeEnumId", dte.fiscalTaxDocumentTypeEnumId).call().siiCode
    contentLocation = ec.entity.find("mchile.dte.FiscalTaxDocumentContent").condition([fiscalTaxDocumentId:dte.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:"Ftdct-Xml"]).one()?.contentLocation
    if (contentLocation == null) {
        ec.message.addError("Did not find XML content for FiscalTaxDocument with id ${dte.fiscalTaxDocumentId}")
        return
    }
    docNumberByType[tipoDte] = (docNumberByType[tipoDte]?:0) + 1
    InputStream is = null
    try {
        is = ec.resource.getLocationReference(contentLocation).openStream()
        xmlString = new String(is.readAllBytes(), "ISO-8859-1").replaceAll("<\\?xml[^>]*\\?>\n*","")
        dteList.add(xmlString)
    } catch (IOException e) {
        ec.message.addError("Could not read DTE content: ${e.toString()}")
    } finally {
        is?.close()
    }
    ec.logger.warn("Agregado: " + dte.fiscalTaxDocumentId)
}

if (ec.message.hasError())
    return

// Validación rut
if (rutReceptor) {
    ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameters([rut:rutReceptor]).call()
}

idEnvio = "EnvBol-" + ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
String tmstFirmaResp = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)
fechaResolucionSii = "2014-10-20"
numeroResolucionSii = "80"

String schemaLocation = 'http://www.sii.cl/SiiDte EnvioBOLETA_v11.xsd'
xmlBuilder.EnvioBOLETA(xmlns: 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version: '1.0', 'xsi:schemaLocation': schemaLocation) {
    SetDTE(ID: idEnvio) {
        Caratula(version: '1.0') {
            RutEmisor(rutOrganizacion)
            RutEnvia(rutEnviador)
            //RutReceptor(rutReceptor)
            RutReceptor("60803000-K")
            FchResol(fechaResolucionSii)
            NroResol(numeroResolucionSii)
            TmstFirmaEnv(tmstFirmaResp)
            docNumberByType.each { key, value ->
                SubTotDTE {
                    TpoDTE(key)
                    NroDTE(value)
                }
            }
        }
        dteList.each { dte ->
            xmlBuilder.getMkp().yieldUnescaped("\n"+dte)
        }
    }
}

xml = xmlWriter.toString()

if (saveSinFirma) {
    ResourceReference xmlContentReference = ec.resource.getLocationReference("dbresource://moit/erp/dte/EnvioDte-sinfirma/${rutOrganizacion}/${idEnvio}-sinfirma.xml")
    //envioBoletaDocument.save(xmlContentReference.outputStream, opts)
}
Document doc = MoquiDTEUtils.parseDocument(xmlWriter.toString().getBytes())

byte[] salida = MoquiDTEUtils.sign(doc, "#" + idEnvio, pkey, certificate, "#" + idEnvio, "SetDTE")
doc = MoquiDTEUtils.parseDocument(salida)

try {
    MoquiDTEUtils.validateDocumentSii(ec, salida, schemaLocation)
} catch (Exception e) {
    ec.message.addError("Failed validation: " + e.getMessage())
}

ts = ec.user.nowTimestamp
if (MoquiDTEUtils.verifySignature(doc, "/sii:EnvioBOLETA/sii:SetDTE", "./sii:Caratula/sii:TmstFirmaEnv/text()")) {
    xmlContentLocation = "dbresource://moit/erp/dte/EnvioDte/${rutOrganizacion}/${idEnvio}.xml"
    envioRr = ec.resource.getLocationReference(xmlContentLocation)
    envioRr.putBytes(salida)
    fileName = envioRr.fileName
    ec.logger.warn("Envio generado OK")
} else {
    xmlContentLocation = "dbresource://moit/erp/dte/${rutOrganizacion}/${idEnvio}-mala.xml"
    envioRr = ec.resource.getLocationReference(xmlContentLocation)
    envioRr.putBytes(salida)
    fileName = envioRr.fileName
    ec.logger.warn("Error al generar envio")
}

envioId = ec.service.sync().name("create#mchile.dte.DteEnvio").parameters([envioTypeEnumId:'Ftde-EnvioBoleta', statusId:'Ftde-Created', internalId:idEnvio, rutEmisor:rutOrganizacion, issuerPartyId:issuerPartyId,
                                                                           rutReceptor:rutReceptor, receiverPartyId:'CL_SII', registerDate:ec.user.nowTimestamp, documentLocation:xmlContentLocation, fileName:fileName]).call().envioId
documentIdList.each { documentId ->
    ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters([fiscalTaxDocumentId:documentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Envio', contentLocation:xmlContentLocation, contentDate:ts]).call()
    ec.service.sync().name("create#mchile.dte.DteEnvioFiscalTaxDocument").parameters([fiscalTaxDocumentId:documentId, envioId:envioId]).call()
}

return
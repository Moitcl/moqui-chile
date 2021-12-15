import org.moqui.context.ExecutionContext
import org.moqui.resource.ResourceReference
import org.w3c.dom.Document
import groovy.xml.MarkupBuilder

import cl.moit.dte.MoquiDTEUtils

ExecutionContext ec = context.ec

// Recuperacion de parametros de la organizacion
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameters([partyId:organizationPartyId]).call())

ResourceReference[] DTEList = new ResourceReference[documentIdList.size()]

docNumberByType = [:]
dteEvList = ec.entity.find("mchile.dte.FiscalTaxDocument").condition("fiscalTaxDocumentId", "in", documentIdList).list()
dteList = []
dteEvList.each { dte ->
    tipoDte = ec.service.sync().name("mchile.DTEServices.get#SIICode").parameter("fiscalTaxDocumentTypeEnumId", dte.fiscalTaxDocumentTypeEnumId).call().siiCode
    contentLocation = ec.entity.find("mchile.dte.FiscalTaxDocumentContent").condition([fiscalTaxDocumentId:dte.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:"Ftdct-Xml"]).one()?.contentLocation
    if (contentLocation == null) {
        ec.message.addError("Did not find XML content for FiscalTaxDocument with id ${fiscalTaxDocumentId}")
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

// Validación rut
if (rutReceptor) {
    ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameters([rut:rutReceptor]).call()
}

idEnvio = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
String tmstFirmaResp = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)

xmlBuilder.'EnvioDTE'(xmlns: 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version: '1.0', 'xsi:schemaLocation': 'http://www.sii.cl/SiiDte RespuestaEnvioDTE_v10.xsd') {
    'SetDTE'(ID: idEnvio) {
        'Caratula'(version: '1.0') {
            'RutEmisor'(rutEmisor)
            'RutReceptor'(rutReceptor)
            'RutEnvia'(rutEnvia)
            'FchResol'(fchResol)
            'NroResol'(nroResol)
            'TmstFirmaEnv'(tmstFirmaResp)
            docNumberByType.each { key, value ->
                'SubTotDTE' {
                    'TipoDTE'(key)
                    'NroDTE'(value)
                }
            }
        }
        dteList.each { dte ->
            xmlBuilder.getMkp().yieldUnescaped(dte)
        }
    }
}

xml = xmlWriter.toString()

if (saveSinFirma) {
    ResourceReference xmlContentReference = ec.resource.getLocationReference("dbresource://moit/erp/dte/EnvioDte-sinfirma/${rutEmisor}/EnvDte-${idEnvio}-sinfirma.xml")
    //envioBoletaDocument.save(xmlContentReference.outputStream, opts)
}
Document doc = MoquiDTEUtils.parseDocument(("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + xmlWriter.toString()).getBytes("ISO-8859-1"))

byte[] salida = MoquiDTEUtils.sign(doc, "#" + idEnvio, pkey, certificate, "#" + idEnvio, "SetDTE")
doc = MoquiDTEUtils.parseDocument(salida)

ts = ec.user.nowTimestamp
if (MoquiDTEUtils.verifySignature(doc, "/sii:EnvioDTE/sii:SetDTE", "./sii:Caratula/sii:TmstFirmaEnv/text()")) {
    xmlContentLocation = "dbresource://moit/erp/dte/EnvioDte/${rutEmisor}/EnvDte-${idEnvio}.xml"
    envioRr = ec.resource.getLocationReference(xmlContentLocation)
    envioRr.putBytes(salida)
    fileName = envioRr.fileName
    ec.logger.warn("Envio generado OK")
    xmlContentLocation = "file:///Users/jhp/EnvioDte.xml"
    envioRr = ec.resource.getLocationReference(xmlContentLocation)
    envioRr.putBytes(salida)
} else {
    xmlContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/ENV-${idEnvio}-mala.xml"
    envioRr = ec.resource.getLocationReference(xmlContentLocation)
    envioRr.putBytes(salida)
    fileName = envioRr.fileName
    ec.logger.warn("Error al generar envio")
}

envioId = ec.service.sync().name("create#mchile.dte.DteEnvio").parameters([envioTypeEnumId:'Ftde-EnvioDte', statusId:'Ftde-Created', internalId:idEnvio, rutEmisor:rutEmisor, rutReceptor:rutReceptor,
                                                                 registerDate:ec.user.nowTimestamp, documentLocation:xmlContentLocation, fileName:fileName]).call().envioId
documentIdList.each { documentId ->
    ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters([fiscalTaxDocumentId:documentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Misc', contentLocation:xmlContentLocation, contentDate:ts]).call()
    ec.service.sync().name("create#mchile.dte.DteEnvioFiscalTaxDocument").parameters([fiscalTaxDocumentId:documentId, envioId:envioId]).call()
}

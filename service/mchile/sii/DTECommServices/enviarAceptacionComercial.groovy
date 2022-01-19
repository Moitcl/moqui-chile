import org.moqui.context.ExecutionContext
import org.w3c.dom.Document
import groovy.xml.MarkupBuilder
import cl.moit.dte.MoquiDTEUtils

ExecutionContext ec = context.ec

// Recuperación de datos para emitir aceptación -->
dteList = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId: fiscalTaxDocumentTypeEnumId, fiscalTaxDocumentNumber:fiscalTaxDocumentNumber]).forUpdate(true).list()
if (dteList)
    dte = dteList.first
else {
    ec.message.addError("No se encuentra DTE con los parámetros entregados")
    return
}

if (!(fiscalTaxDocumentTypeEnumId in ['Ftdt-33', 'Ftdt-34', 'Ftdt-43', 'Ftdt-46', 'Ftdt-52']))
    ec.message.addError("Aceptación no soportada para DTEs de tipo ${fiscalTaxDocumentTypeEnumId}")
codeOut = ec.service.sync().name("mchile.sii.DTEServices.get#SIICode").parameters([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId]).call()
Integer tipoDoc = codeOut.siiCode

if (dte.sentRecStatusId == 'Ftd-ReceiverAccept') {
    ec.message.addError("Ya está aceptado el DTE")
} else if (dte.sentRecStatusId != null && ! (dte.sentRecStatusId in ['Ftd-ReceiverAck', 'Ftd-SentRec'])) {
    ec.message.addError("Estado inválido para aceptación: ${dte.sentRecStatusId}")
}

// No se envían aceptaciones por boletas
if ((fiscalTaxDocumentTypeEnumId == 'Ftdt-39') || (fiscalTaxDocumentTypeEnumId == 'Ftdt-41') || (fiscalTaxDocumentTypeEnumId == 'PvtBoleta')) {
    ec.message.addError("Boletas no requieren envío de aceptación")
}
rutResponde = dte.receiverPartyIdValue

if (dte.invoiceId == null)
    ec.message.addError("No se encuentra invoice para el DTE especificado")

EntityValue invoice = ec.entity.find("mantle.account.invoice.Invoice").condition("invoiceId", dte.invoiceId).forUpdate(true).one()
if (invoice == null)
    ec.message.addError("No se encuentra invoice para el DTE especificado invoiceId: ${invoiceId}")

if (!(invoice.statusId in ['InvoiceIncoming', 'InvoiceReceived']))
    ec.message.addError("Estado inválido para nota de cobro: ${invoice.statusId}")
invoice.statusId = 'InvoiceApproved'
invoice.update()

// Recuperacion de parametros de la organizacion
ec.context.putAll(ec.service.sync().name("mchile.sii.DTEServices.load#DTEConfig").parameter("partyId", dte.receiverPartyId).call())

if (ec.message.hasError())
    return

declaracion = 'El acuse de recibo que se declara en este acto, de acuerdo a lo dispuesto en la letra b) del Art. 4, y la letra c) del Art. 5 de la Ley 19.983, acredita que la entrega de mercaderias o servicio(s) prestado(s) ha(n) sido recibido(s).'
dteContentEv = ec.entity.find("mchile.dte.FiscalTaxDocumentContent").condition([fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:"Ftdct-Xml"]).selectField("contentLocation").one()
String dteXmlLocation = dteContentEv.contentLocation

envioReciboId = ec.service.sync().name("create#mchile.dte.DteEnvio").parameters([envioTypeEnumId:'Ftde-EnvioRecibos', statusId:'Ftde-Created', rutEmisor:dte.receiverPartyIdValue, rutReceptor:dte.issuerPartyIdValue, fechaEnvio:ec.user.nowTimestamp]).call().envioId

idRecibo = "Recibo-" + dte.fiscalTaxDocumentId
StringWriter writer = new StringWriter()
MarkupBuilder reciboBuilder = new MarkupBuilder(writer)
String tmstFirmaRecibo = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")
reciboBuilder.Recibo(xmlns:"http://www.sii.cl/SiiDte", version:"1.0") {
    DocumentoRecibo(ID: idRecibo) {
        TipoDoc(tipoDoc)
        Folio(dte.fiscalTaxDocumentNumber)
        FchEmis(ec.l10n.format(dte.date, 'yyyy-MM-dd'))
        RUTEmisor(dte.issuerPartyIdValue)
        RUTRecep(dte.receiverPartyIdValue)
        MntTotal(invoice.invoiceTotal)
        Recinto()
        RutFirma(rutEnvia)
        Declaracion(declaracion)
        TmstFirmaRecibo(tmstFirmaRecibo)
    }
}
xmlRecibo = writer.toString()
writer.close()
Document doc = MoquiDTEUtils.parseDocument(xmlRecibo.getBytes())
byte[] reciboFirmado = MoquiDTEUtils.sign(doc, "#" + idRecibo, pkey, certificate, "#" + idRecibo,"DocumentoRecibo", false)

if (MoquiDTEUtils.verifySignature(doc, "/sii:Recibo/sii:DocumentoRecibo", "./sii:TmstFirmaRecibo/text()")) {
    ec.logger.info("Firma OK recibo")
} else {
    ec.message.addError("Error en firma de Recibo")
}

idEnvioRecibo = "EnvRecibo-" + envioReciboId
writer = new StringWriter()
MarkupBuilder envioRecibo = new MarkupBuilder(writer)
String tmstFirmaEnv = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")
String schemaLocation = 'http://www.sii.cl/SiiDte EnvioRecibos_v10.xsd'
String reciboFirmadoXml = new String(reciboFirmado, "ISO-8859-1")
//ec.logger.info("Recibo: ${reciboFirmadoXml}")
envioRecibo.EnvioRecibos('xmlns': 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version:'1.0', 'xsi:schemaLocation': schemaLocation) {
    SetRecibos(ID: idEnvioRecibo) {
        Caratula (version:"1.0"){
            RutResponde(dte.receiverPartyIdValue)
            RutRecibe(dte.issuerPartyIdValue)
            //NmbContacto()
            //FonoContacto()
            //MailContacto()
            TmstFirmaEnv(tmstFirmaEnv)
        }
        envioRecibo.getMkp().yieldUnescaped("\n"+reciboFirmadoXml)
    }
}

xmlEnvioRecibo = writer.toString()
writer.close()
doc = MoquiDTEUtils.parseDocument(xmlEnvioRecibo.getBytes())
byte[] envioReciboFirmado = MoquiDTEUtils.sign(doc, "#" + idEnvioRecibo, pkey, certificate, "#" + idEnvioRecibo,"SetRecibos")

if (MoquiDTEUtils.verifySignature(doc, "/sii:EnvioRecibos/sii:SetRecibos", "./sii:Caratula/sii:TmstFirmaEnv/text()")) {
    xmlContentLocation = "dbresource://moit/erp/dte/EnvioRecibo/${dte.receiverPartyIdValue}/${idEnvioRecibo}.xml"
    envioRr = ec.resource.getLocationReference(xmlContentLocation)
    envioRr.putBytes(envioReciboFirmado)
    fileName = envioRr.fileName
    ec.logger.warn("Envio generado OK")
} else {
    xmlContentLocation = "dbresource://moit/erp/dte/${dte.receiverPartyIdValue}/${idEnvioRecibo}-mala.xml"
    envioRr = ec.resource.getLocationReference(xmlContentLocation)
    envioRr.putBytes(envioReciboFirmado)
    fileName = envioRr.fileName
    ec.logger.warn("Error al generar envio")
}

try {
    MoquiDTEUtils.validateDocumentSii(ec, envioReciboFirmado, schemaLocation)
} catch (Exception e) {
    ec.message.addError("Failed validation: " + e.getMessage())
}

ec.service.sync().name("update#mchile.dte.DteEnvio").parameters([envioId:envioReciboId, documentLocation:xmlContentLocation, fileName:fileName]).call()
ec.service.sync().name("create#mchile.dte.DteEnvioFiscalTaxDocument").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, envioId:envioReciboId]).call()
ec.service.sync().name("update#mchile.dte.FiscalTaxDocument").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, sentRecStatusId:'Ftd-ReceiverAccept']).call()

return
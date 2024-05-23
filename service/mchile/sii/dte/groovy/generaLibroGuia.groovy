import groovy.xml.MarkupBuilder
import org.moqui.entity.EntityCondition
import org.w3c.dom.Document
import cl.moit.dte.MoquiDTEUtils
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityValue

ExecutionContext ec = ec

if (tipoEnvio == null)
    ec.message.addError("Se debe especificar el tipo")
if (tipoEnvio != 'TOTAL' && tipoEnvio != 'AJUSTE') // tipo PARCIAL (y correspondiente FINAL) aún no soportado
    ec.message.addError("tipoEnvio debe ser 'TOTAL' o 'AJUSTE', recibido: '${tipoEnvio}'")

if (ec.message.hasError())
    return

Map dteConfig = ec.service.sync().name("mchile.sii.dte.DteInternalServices.load#DteConfig").parameter("partyId", organizationPartyId).call()
String periodoTributario = ec.l10n.format(periodo, 'yyyy-MM')
Calendar cal = Calendar.instance
cal.setTimeInMillis(periodo.time)
cal.set(Calendar.DAY_OF_MONTH, 1)
cal.set(Calendar.HOUR_OF_DAY, 0)
cal.set(Calendar.MINUTE, 0)
cal.set(Calendar.SECOND, 0)
cal.set(Calendar.MILLISECOND, 0)
fromDate = new Timestamp(cal.timeInMillis)
cal.add(Calendar.MONTH, 1)
thruDate = new Timestamp(cal.timeInMillis)

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)

Map totalesPeriodo = [:]
List documentList = []

EntityFind entityFind = ec.entity.find("mchile.dte.GuiaDespachoDetails").condition("fiscalTaxDocumentTypeEnumId", EntityCondition.IN, ['Ftdt-50', 'Ftdt-52'])
        .condition("date", EntityCondition.GREATER_THAN_EQUAL_TO, fromDate).condition("date", EntityCondition.LESS_THAN, thruDate)
        .condition("statusId", EntityCondition.IN, ['Ftd-Issued', 'Ftd-Cancelled'])
if (fiscalTaxDocumentIdList)
    entityFind.condition("fiscalTaxDocumentId", EntityCondition.IN, fiscalTaxDocumentIdList)
if (!includeUnsentDtes)
    entityFind.condition("sentAuthStatusId", EntityCondition.IN, ['Ftd-SentAuthAccepted', 'Ftd-SentAuthAcceptedWithDiscrepancies', 'Ftd-SentAuthUnverified'])

documentEvList = entityFind.list()

Integer numeroFoliosAnulados = 0
Integer numeroGuiasAnuladas = 0
Integer numeroGuiasDeVenta = 0
Integer montoTotalGuiasVenta = 0
Map<String,Map<String,Integer>> totalesTraslados = [:]
documentEvList.each { EntityValue guiaEv ->
    fechaDoc = ec.l10n.format(guiaEv.date, 'yyyy-MM-dd')
    receiver = ec.entity.find("mantle.party.PartyDetail").condition("partyId", guiaEv.receiverPartyId).one()
    String razonSocial = receiver.taxOrganizationName?:ec.resource.expand('PartyNameOnlyTemplate', null, receiver)
    if (razonSocial.length() > 50)
        razonSocial = razonSocial.substring(0, 50)
    // operacion: 1 (Agrega); 2 (Elimina)
    indTraslado = ec.entity.find("moqui.basic.Enumeration").condition("enumId", guiaEv.indTrasladoEnumId).one()
    guia = [folio:guiaEv.fiscalTaxDocumentNumber, operacion:'1', tipoOperacion:indTraslado.enumCode, fechaDoc:fechaDoc, rutDoc:guiaEv.receiverPartyIdValue,
            razonSocial:razonSocial]
    if (guiaEv.montoNeto) {
        guia.montoNeto = guiaEv.montoNeto
        guia.tasaImpuestoIva = tasaImpuestoIva
        guia.montoIva = (guiaEv.montoIVARecuperable ?:0) + (guiaEv.montoIVANoRecuperable ?: 0) + (guiaEv.montoIVAUsoComun ?: 0) + (guiaEv.montoIVAActivoFijo ?: 0)
    }
    guia.montoTotal = (guia.montoNeto ?: 0) + (guia.montoExento ?: 0) + (guia.montoIva ?: 0)
    if (guiaEv.statusId == 'Ftd-Cancelled') {
        numeroFoliosAnulados++
        guia.anulado = 1
    } else if (guiaEv.anulacionEnumId) {
        anulado = ec.entity.find("moqui.basic.Enumeration").condition("enumId", guiaEv.anulacionEnumId).one()
        guia.anulado = anulado.enumCode
        if (anulado.enumId == 'AgdtAnuladoPreEnvioSii')
            numeroFoliosAnulados++
        else
            numeroGuiasAnuladas++
    } else {
        // Solamente se contabiliza en totales si no está anulada

        if (guiaEv.indTrasladoEnumId == 'IndTraslado-1') {
            numeroGuiasDeVenta++
            montoTotalGuiasVenta += guia.montoTotal
        } else {
            totTras = totalesTraslados[indTraslado.enumCode]
            if (totTras == null) {
                totTras = [cantidadGuias:0, montoTotalGuias:0]
                totalesTraslados[indTraslado.enumCode] = totTras
            }
            totTras.cantidadGuias++
            totTras.montoTotalGuias += guia.montoTotal
        }
    }

}

vatTaxRate = ec.service.sync().name("mchile.TaxServices.get#VatTaxRate").call().taxRate as BigDecimal
tasaImpuestoIva = (vatTaxRate * 100) as String

idLibro = "LibroGuias-" + ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
String schemaLocation = 'http://www.sii.cl/SiiDte LibroGuia_v10.xsd'
String tmstFirma = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")
xmlBuilder.LibroGuia(xmlns: 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version: '1.0', 'xsi:schemaLocation': schemaLocation) {
    EnvioLibro(ID: idLibro) {
        Caratula {
            RutEmisorLibro(dteConfig.rutOrganizacion)
            RutEnvia(dteConfig.rutEnviador)
            PeriodoTributario(periodoTributario)
            FchResol(dteConfig.fechaResolucionSii)
            NroResol(dteConfig.numeroResolucionSii)
            TipoLibro('ESPECIAL')
            TipoEnvio(tipoEnvio)
            if (folioNotificacion)
                FolioNotificacion(folioNotificacion)
        }
        ResumenPeriodo {
           if (numeroFoliosAnulados > 0)
               TotFolAnulado(numeroFoliosAnulados)
           if (numeroGuiasAnuladas > 0)
               TotGuiaAnulada(numeroGuiasAnuladas)
            TotGuiaVenta(numeroGuiasDeVenta)
            TotMntGuiaVta(montoTotalGuiasVenta)
            if (montoTotalModificados > 0)
                TotMntModificado(montoTotalModificados)
            totalesTraslados.each { String tipoTraslado, Map<String,Integer>totales ->
                TotTraslado {
                    TpoTraslado(tipoTraslado)
                    CantGuia(totales.cantidadGuias)
                    if (totales.montoTotalGuias > 0)
                        MntGuia(totales.montoTotalGuias)
                }
            }
        }
        documentList.each { guia ->
            Detalle {
                Folio(guia.folio)
                if (guia.anulado)
                    Anulado(guia.anulado)
                if (guia.tipoEnvio == 'AJUSTE')
                    Operacion(guia.operacion)
                if (guia.tipoOperacion)
                    TpoOper(guia.tipoOperacion)
                if (guia.fechaDoc)
                    FchDoc(guia.fechaDoc)
                if (guia.rutDoc)
                    RUTDoc(guia.rutDoc)
                if (guia.razonSocial)
                    RznSoc(guia.razonSocial)
                if (guia.montoNeto)
                    MntNeto(guia.montoNeto)
                if (guia.tasaImpuesto)
                    TasaImp(guia.tasaImpuesto)
                if (guia.montoIva)
                    IVA(guia.montoIva)
                if (guia.montoTotal)
                    MntTotal(guia.montoTotal)
                if (guia.montoModificacion)
                    MntModificado(guia.montoModificacion)
                if (guia.tipoDocumentoReferencia)
                    TpoDocRef(guia.tipoDocumentoReferencia)
                if (guia.folioDocumentoReferencia)
                    FolioDocRef(guia.folioDocumentoReferencia)
                if (guia.fechaDocumentoReferencia)
                    FchDocRef(guia.fechaDocumentoReferencia)
            }
        }
        TmstFirma(tmstFirma)
    }
}

libroXmlString = xmlWriter.toString()
libroXmlString = libroXmlString.replaceAll("[^\\x00-\\xFF]", "")
xmlWriter.close()
Document doc2 = MoquiDTEUtils.parseDocument(libroXmlString.getBytes())
byte[] libroXml = MoquiDTEUtils.sign(doc2, "#" + idLibro, dteConfig.pkey, dteConfig.certificate, "#" + idLibro, "EnvioLibro")
libroXmlString = new String(libroXml, "ISO-8859-1")

doc = MoquiDTEUtils.parseDocument(libroXml)

try {
    MoquiDTEUtils.validateDocumentSii(ec, libroXml, schemaLocation)
} catch (Exception e) {
    ec.logger.info("Failed validation for libro: ${libroXmlString}")
    ec.message.addError("Failed validation: " + e.getMessage())
}

ts = ec.user.nowTimestamp
if (MoquiDTEUtils.verifySignature(doc, "/sii:LibroGuia/sii:EnvioLibro", "./sii:TmstFirma/text()")) {
    xmlContentLocation = "dbresource://moit/erp/dte/Libros/GuiaDespacho/${rutOrganizacion}/${idLibro}.xml"
    envioRr = ec.resource.getLocationReference(xmlContentLocation)
    envioRr.putBytes(libroXml)
    fileName = envioRr.fileName
    ec.logger.warn("Libro generado OK")
} else {
    xmlContentLocation = "dbresource://moit/erp/dte/Libros/GuiaDespacho/${rutOrganizacion}/${idLibro}-mala.xml"
    envioRr = ec.resource.getLocationReference(xmlContentLocation)
    envioRr.putBytes(libroXml)
    fileName = envioRr.fileName
    ec.logger.warn("Error al generar libro")
}

return
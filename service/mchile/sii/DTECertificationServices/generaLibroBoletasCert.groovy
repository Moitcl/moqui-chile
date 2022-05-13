import org.w3c.dom.Document

import org.moqui.BaseArtifactException
import org.moqui.entity.EntityValue
import org.moqui.context.ExecutionContext

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

import cl.moit.dte.MoquiDTEUtils

import groovy.xml.MarkupBuilder

ExecutionContext ec = context.ec

issuerPartyId = activeOrgId;
// Recuperacion de parametros de la organizacion -->
ec.context.putAll(ec.service.sync().name("mchile.sii.DTEServices.load#DTEConfig").parameter("partyId", issuerPartyId).call())

//vatTaxRate = ec.service.sync().name("mchile.TaxServices.get#VatTaxRate").parameter("date", new Timestamp(fechaEmision.time)).call().taxRate

// Giro Emisor
giroOutMap = ec.service.sync().name("mchile.sii.DTEServices.get#GiroPrimario").parameter("partyId", issuerPartyId).call()
if (giroOutMap == null) {
    ec.message.addError("No se encuentra giro primario para partyId ${issuerPartyId}")
    return
}
giroEmisor = giroOutMap.description

idDocumento = "Dte-" + ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
String tmstFirmaResp = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")

//if (giroReceptor.length() > 39)
//    giroReceptor = giroReceptor.substring(0,39)
//razonSocialReceptorTimbre = razonSocialReceptor.length() > 39? razonSocialReceptor.substring(0,39): razonSocialReceptor

StringWriter xmlWriterTimbre = new StringWriter()
MarkupBuilder xmlBuilderTimbre = new MarkupBuilder(new IndentPrinter(new PrintWriter(xmlWriterTimbre), "", false))


StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)

String schemaLocation = 'http://www.sii.cl/SiiDte LibroBOLETA_v10.xsd'
xmlBuilder.LibroBoleta(xmlns: 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version: '1.0', 'xsi:schemaLocation': schemaLocation) {
    EnvioLibro(ID: idDocumento) {
        Caratula {
            RUTEmisorLibro("")
            RUTEnvia("12857517-0")
            PeriodoTributario("2022-05")
            FchResol("2018-10-24")
            NroResol("0")
            TipoLibro("ESPECIAL")
            TipoEnvio("TOTAL")
            FolioNotificacion(1)
        }
        ResumenPeriodo {
            TotalesPeriodo {
                    TpoDoc("39")
                    TotAnulado(0)
                    TotDoc(5)
                    TotMntExe(2000)
                    TotMntNeto(43832)
                    TotMntIVA(8328)
                    TasaIVA(19)
                    TotMntTotal(54160)
            }
        }
    }
}

uri = "#" + idDocumento

String facturaXmlString = xmlWriter.toString()
facturaXmlString = facturaXmlString.replaceAll("[^\\x00-\\xFF]", "")
xmlWriter.close()
Document doc2 = MoquiDTEUtils.parseDocument(facturaXmlString.getBytes())
byte[] facturaXml = MoquiDTEUtils.sign(doc2, uri, pkey, certificate, uri, "EnvioLibro")

//try {
//    MoquiDTEUtils.validateDocumentSii(ec, facturaXml, schemaLocation)
//} catch (Exception e) {
//    ec.message.addError("Failed validation: " + e.getMessage())
//}

doc2 = MoquiDTEUtils.parseDocument(facturaXml)
/*if (MoquiDTEUtils.verifySignature(doc2, "/sii:DTE/sii:Documento", "/sii:DTE/sii:Documento/sii:Encabezado/sii:IdDoc/sii:FchEmis/text()")) {
    ec.logger.warn("DTE folio ${folio} generada OK")
} else {
    ec.message.addError("Error al generar DTE folio ${folio}: firma inválida")
}*/

// Se deja archivo en /tmp

dirSalida = '/home/cherrera/moit/cowork/moqui-framework/runtime/component/moquichile/DTE/TEMP/libroBoleta.xml'

File file = new File(dirSalida);

OutputStream os = new FileOutputStream(file);
os.write(facturaXml);
os.close();



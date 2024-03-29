import org.w3c.dom.Document
import org.moqui.context.ExecutionContext
import cl.moit.dte.MoquiDTEUtils
import groovy.xml.MarkupBuilder

ExecutionContext ec = context.ec

if (fechaInicio > fechaFin) {
    ec.message.addError("Fecha fin debe ser mayor o igual a fecha inicio")
    return
}

// Recuperacion de parametros de la organizacion -->
ec.context.putAll(ec.service.sync().name("mchile.sii.dte.DteInternalServices.load#DteConfig").parameter("partyId", organizationPartyId).call())


// Buscar lista de DTE 39 que se hayan emitido/anulado
mapBoleta = ec.service.sync().name("mchile.sii.dte.DteRcofServices.get#ResumenRcof").parameters([fechaInicio:fechaInicio, fechaFin:fechaFin, fiscalTaxDocumentTypeEnumId:'Ftdt-39', organizationPartyId:organizationPartyId]).call()
// Buscar lista de DTE 41
mapBoletaExenta = ec.service.sync().name("mchile.sii.dte.DteRcofServices.get#ResumenRcof").parameters([fechaInicio:fechaInicio, fechaFin:fechaFin, fiscalTaxDocumentTypeEnumId:'Ftdt-41', organizationPartyId:organizationPartyId]).call()
// Buscar lista de DTE 61 que anulen boletas
mapNotaCredito = ec.service.sync().name("mchile.sii.dte.DteRcofServices.get#ResumenRcof").parameters([fechaInicio:fechaInicio, fechaFin:fechaFin, fiscalTaxDocumentTypeEnumId:'Ftdt-61', organizationPartyId:organizationPartyId]).call()


tmst = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
idDocumento = "Rcof-" + tmst
String tmstFirmaResp = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)

String schemaLocation = 'http://www.sii.cl/SiiDte ConsumoFolio_v10.xsd'
xmlBuilder.ConsumoFolios(xmlns: 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version: '1.0', 'xsi:schemaLocation': schemaLocation) {
    DocumentoConsumoFolios(ID: idDocumento) {
        Caratula(version: '1.0') {
            RutEmisor(rutOrganizacion)
            RutEnvia(rutEnviador)
            FchResol(fechaResolucionSii)
            // NroResol is 0 in Certification
            //NroResol(0)
            NroResol(numeroResolucionSii)
            FchInicio(fechaInicio)
            FchFinal(fechaFin)
            Correlativo(1)
            SecEnvio(1)
            TmstFirmaEnv(tmstFirmaResp)
        }
        Resumen {
            TipoDocumento(39)
            MntNeto(mapBoleta.totalMontoNeto)
            MntIva(mapBoleta.totalMontoIva)
            TasaIVA(19)
            MntExento(mapBoleta.totalMontoExento)
            MntTotal(mapBoleta.totalMontoTotal)
            FoliosEmitidos(mapBoleta.cantDocEmitidos)
            FoliosAnulados(mapBoleta.cantFoliosAnulados)
            FoliosUtilizados(mapBoleta.cantDocUtilizados)
            RangoUtilizados {
                mapBoleta.rangosFoliosUtilizados.each { rangoField ->
                    setInicial(rangoField[0])
                    setFinal(rangoField[1])
                }
            }
        }
    }
}

uri = "#" + idDocumento

String rcofString = xmlWriter.toString()
//ec.logger.warn("Salida: "+rcofString)

xmlWriter.close()
Document doc2 = MoquiDTEUtils.parseDocument(rcofString.getBytes())
byte[] rcofXml = MoquiDTEUtils.sign(doc2, uri, pkey, certificate, uri, "DocumentoConsumoFolios")

xmlContentLocation = "dbresource://moit/erp/dte/${rutOrganizacion}/RCOF/RCOF-${idDocumento}.xml"
createMap = [envioTypeEnumId:'Ftdt-Rcof', issuerPartyId:organizationPartyId, rutEmisor:rutOrganizacion, receiverPartyId:receiverPartyId, statusId:"Ftde-Created", registerDate:ts, documentLocation:xmlContentLocation]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.DteEnvio").parameters(createMap).call())
ec.context.putAll(ec.service.sync().name("create#mchile.dte.RCof").parameters([envioId:envioId, fechaInicio:fechaInicio, fechaFin:fechaFin]).call())

ec.resource.getLocationReference(xmlContentLocation).putBytes(rcofXml)

return
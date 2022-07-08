import org.w3c.dom.Document

import org.moqui.context.ExecutionContext

import cl.moit.dte.MoquiDTEUtils

import groovy.xml.MarkupBuilder

ExecutionContext ec = context.ec

if (fechaInicio > fechaFin) {
    ec.message.addError("Fecha fin debe ser mayor o igual a fecha inicio")
    return
}

rutEmisor = ec.service.sync().name("mchile.GeneralServices.get#RutForParty").parameters([partyId:organizationPartyId, failIfNotFound:true]).call().rut

// Validación rut
// ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameters([rut:rutReceptor]).call()

// Recuperacion de parametros de la organizacion -->
ec.context.putAll(ec.service.sync().name("mchile.sii.DTEServices.load#DTEConfig").parameter("partyId", organizationPartyId).call())


// Buscar lista de DTE 39 que se hayan emitido/anulado
mapBoleta = ec.service.sync().name("mchile.sii.DTEServices.get#ResumenRcof").parameters([fechaInicio:fechaInicio, fechaFin:fechaFin, fiscalTaxDocumentTypeEnumId:'Ftdt-39', organizationPartyId:organizationPartyId]).call()
// Buscar lista de DTE 41
mapBoletaExenta = ec.service.sync().name("mchile.sii.DTEServices.get#ResumenRcof").parameters([fechaInicio:fechaInicio, fechaFin:fechaFin, fiscalTaxDocumentTypeEnumId:'Ftdt-41', organizationPartyId:organizationPartyId]).call()
// Buscar lista de DTE 61 que anulen boletas
mapNotaCredito = ec.service.sync().name("mchile.sii.DTEServices.get#ResumenRcof").parameters([fechaInicio:fechaInicio, fechaFin:fechaFin, fiscalTaxDocumentTypeEnumId:'Ftdt-61', organizationPartyId:organizationPartyId]).call()


tmst = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
idDocumento = "Rcof-" + tmst
String tmstFirmaResp = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)

String schemaLocation = 'http://www.sii.cl/SiiDte ConsumoFolio_v10.xsd'
xmlBuilder.ConsumoFolios(xmlns: 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version: '1.0', 'xsi:schemaLocation': schemaLocation) {
    DocumentoConsumoFolios(ID: idDocumento) {
        Caratula(version: '1.0') {
            RutEmisor(rutEmisor)
            RutEnvia(rutEnvia)
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
            RangoAnulados {
                mapBoleta.rangosFoliosAnulados.each { rangoField ->
                    setInicial(rangoField[0])
                    setFinal(rangoField[1])
                }
            }
        }
        Resumen {
            TipoDocumento(41)
            MntNeto(mapBoletaExenta.totalMontoNeto)
            MntIva(mapBoletaExenta.totalMontoIva)
            TasaIVA(19)
            MntExento(mapBoletaExenta.totalMontoExento)
            MntTotal(mapBoletaExenta.totalMontoTotal)
            FoliosEmitidos(mapBoletaExenta.cantDocEmitidos)
            FoliosAnulados(mapBoletaExenta.cantFoliosAnulados)
            FoliosUtilizados(mapBoletaExenta.cantDocUtilizados)
            RangoUtilizados {
                mapBoletaExenta.rangosFoliosUtilizados.each { rangoField ->
                    setInicial(rangoField[0])
                    setFinal(rangoField[1])
                }
            }
            RangoAnulados {
                mapBoleta.rangosFoliosAnulados.each { rangoField ->
                    setInicial(rangoField[0])
                    setFinal(rangoField[1])
                }
            }
        }
    }
}

uri = "#" + idDocumento

String facturaXmlString = xmlWriter.toString()
ec.logger.warn("Salida: "+facturaXmlString)

xmlWriter.close()
Document doc2 = MoquiDTEUtils.parseDocument(facturaXmlString.getBytes())
byte[] facturaXml = MoquiDTEUtils.sign(doc2, uri, pkey, certificate, uri, "DocumentoConsumoFolios")

// Just in case you need to create xml outside of Moqui
/*FileOutputStream fos = new FileOutputStream("/home/cherrera/moit/cowork/moqui-framework/runtime/component/moquichile/DTE/TEMP/RCOF-"+tmst+".xml")
fos.write(facturaXml);
fos.close();*/

// Creación de registro en FiscalTaxDocument -->
createMap = [fiscalTaxDocumentTypeEnumId:'Ftdt-Rcof', fiscalTaxDocumentId:tmst, fiscalTaxDocumentNumber:tmst, issuerPartyId:organizationPartyId, issuerPartyIdValue:rutEmisor,issuerPartyIdTypeEnumId:'PtidNationalTaxId',receiverPartyId:receiverPartyId, statusId:"Ftd-Issued", sentAuthStatusId:"Ftd-NotSentAuth", sentRecStatusId:"Ftd-NotSentRec", fechaInicio:fechaInicio, fechaFin:fechaFin, date:ts]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocument").parameters(createMap).call())

xmlContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/RCOF-${idDocumento}.xml"

// Creacion de registros en FiscalTaxDocumentContent
createMapBase = [fiscalTaxDocumentId:tmst, contentDte:tmst]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:xmlContentLocation]).call())
ec.resource.getLocationReference(xmlContentLocation).putBytes(facturaXml)


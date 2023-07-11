import org.w3c.dom.Document

import org.moqui.BaseArtifactException
import org.moqui.entity.EntityValue
import org.moqui.context.ExecutionContext

import cl.moit.dte.MoquiDTEUtils

import groovy.xml.MarkupBuilder

ExecutionContext ec = context.ec


// Recuperacion de parametros de la organizacion -->
ec.context.putAll(ec.service.sync().name("mchile.sii.dte.DteInternalServices.load#DteConfig").parameter("partyId", issuerPartyId).call())

/*
CASO-1
==========

Item                                    Cantidad        Precio Unitario con IVA
Cambio de aceite                        1                       19900
Alineacion y balanceo                   1                       9900


CASO-2
=========

Item                                    Cantidad        Precio Unitario con IVA
Papel de regalo                         17                      120


CASO-3
=========

Item                                    Cantidad        Precio Unitario con IVA
Sandwic                                 2                       1500
Bebida                                  2                       550


CASO-4
=========

Item                                    Cantidad        Precio Unitario con IVA
item afecto 1                           8                       1590
item exento 2                           2                       1000

CASO-5
=========

Item                                    Cantidad        Precio Unitario con IVA
Arroz                                   5                       700

OBSERVACION: "Se debe informar en el XML Unidad de medida en Kg."

*/


//Obtención de folio y CAF Caso 1
folioResult = ec.service.sync().name("mchile.sii.dte.DteFolioServices.get#Folio").parameters([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, partyId:issuerPartyId]).call()
folio = folioResult.folio
codRef = 0 as Integer


idDocumento = "Bol-" + ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
String tmstFirmaResp = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)

// Timbre (con item 1 de Caso 1
String detalleIt1 = "Cambio de aceite"


if (detalleIt1.length() > 40)
    detalleIt1 = detalleIt1.substring(0, 40)
datosTed = "<DD><RE>${rutOrganizacion}</RE><TD>${tipoDte}</TD><F>${folio}</F><FE>${ec.l10n.format(fechaEmision, "yyyy-MM-dd")}</FE><RR>${rutReceptor}</RR><RSR>${razonSocialReceptorTimbre}</RSR><MNT>${totalInvoice}</MNT><IT1>${detalleIt1}</IT1>${folioResult.cafFragment.replaceAll('>\\s*<', '><').trim()}<TSTED>${ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")}</TSTED></DD>"

String schemaLocation = ''
xmlBuilder.DTE(xmlns: 'http://www.sii.cl/SiiDte', version: '1.0') {
    Documento(ID: idDocumento) {
        Encabezado {
            IdDoc {
                TipoDTE(39)
                Folio(folio)
                FchEmis(ec.l10n.format(fechaEmision, "yyyy-MM-dd"))
                IndServicio("3")
                if (tipoDespacho)
                    TipoDespacho(tipoDespacho)
                if (indServicio)
                    IndServicio(indServicio)
                //FmaPago(formaPago)
            }
            Emisor {
                RUTEmisor(rutOrganizacion)
                RznSocEmisor(razonSocialOrganizacion)
                GiroEmisor(giroEmisor)
                if (codigoSucursalSii)
                    CdgSIISucur(codigoSucursalSii)
                DirOrigen(direccionOrigen)
                CmnaOrigen(comunaOrigen)
                CiudadOrigen(ciudadOrigen)
            }
            Receptor {
                RUTRecep(rutReceptor)
                if (codigoInternoReceptor)
                    CdgIntRecep(codigoInternoReceptor)
                RznSocRecep(razonSocialReceptor)
                //GiroRecep(giroReceptor)
                if (contactoReceptor)
                    Contacto(contactoReceptor)
                if (correoReceptor)
                    CorreoReceptor(correoReceptor)
                DirRecep(direccionReceptor)
                CmnaRecep(comunaReceptor)
                CiudadRecep(ciudadReceptor)
            }
            Totales {
                MntNeto(Math.round(totalNeto))
                if (totalExento != null && totalExento > 0)
                    MntExe(totalExento)
            }
        }
        detalleList.each { detalle ->
            Detalle {
                NroLinDet(detalle.numeroLinea)
                detalle.codigoItem?.each { codigoItem ->
                    CdgItem {
                        TpoCodigo(codigoItem.tipoCodigo)
                        VlrCodigo(codigoItem.valorCodigo)
                    }
                }
                if (detalle.indicadorExento)
                    IndExe(detalle.indicadorExento)
                NmbItem(detalle.nombreItem)
                if (detalle.descripcionItem)
                    DscItem(detalle.descripcionItem)
                if (detalle.quantity != null)
                    QtyItem(detalle.quantity)
                if (detalle.uom)
                    UnmdItem(uom)
                PrcItem(detalle.priceItem + Math.round(detalle.priceItem * vatTaxRate))
                if (detalle.porcentajeDescuento)
                    DescuentoPct(detalle.porcentajeDescuento)
                if (detalle.montoDescuento)
                    DescuentoMonto(detalle.montoDescuento)
                MontoItem(detalle.montoItem + Math.round(detalle.montoItem * vatTaxRate))
            }
        }
        // Referencia (Se usa SET y Caso)
        referenciaList.each { referencia ->
            Referencia {
                NroLinRef(referencia.numeroLinea)
                TpoDocRef(referencia.tipoDocumento)
                //IndGlobal()
                FolioRef(referencia.folio)
                if (referencia.rutOtro)
                    RUTOtr(referencia.rutOtro)
                if (referencia.fecha)
                    FchRef(ec.l10n.format(referencia.fecha, "yyyy-MM-dd"))
                if (referencia.codigo)
                    CodRef(referencia.codigo)
                if (referencia.razon)
                    RazonRef(referencia.razon)
            }
        }
        TED (version:"1.0") {
            xmlBuilder.getMkp().yieldUnescaped(datosTed)
            FRMT(algoritmo:"SHA1withRSA", MoquiDTEUtils.firmaTimbre(datosTed, folioResult.privateKey))
        }
        TmstFirma(ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss"))
    }
}

uri = "#" + idDocumento

String facturaXmlString = xmlWriter.toString()
xmlWriter.close()

Document doc2 = MoquiDTEUtils.parseDocument(facturaXmlString.getBytes())
byte[] facturaXml = MoquiDTEUtils.sign(doc2, uri, pkey, certificate, uri, "Documento")


doc2 = MoquiDTEUtils.parseDocument(facturaXml)

if (ec.message.hasError())
    return

// Registry de boleta en base de datos y generación de PDF -->
fiscalTaxDocumentTypeEnumId = "Ftdt-${tipoDte}"

// Creación de registro en FiscalTaxDocument -->
dteEv = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, fiscalTaxDocumentNumber:folio, issuerPartyId:issuerPartyId]).one()

dteEv.receiverPartyId = receiverPartyId
dteEv.receiverPartyIdTypeEnumId = "PtidNationalTaxId"
dteEv.receiverPartyIdValue = rutReceptor.trim()
dteEv.statusId = "Ftd-Issued"
dteEv.sentAuthStatusId = "Ftd-NotSentAuth"
dteEv.sentRecStatusId = "Ftd-NotSentRec"
dteEv.invoiceId = invoiceId
dteEv.shipmentId = shipmentId
Date date = new Date()
Timestamp ts = new Timestamp(date.getTime())
dteEv.date = ts
dteEv.update()

xmlContentLocation = "dbresource://moit/erp/dte/${rutOrganizacion}/DTE-${tipoDte}-${folio}.xml"
pdfContentLocation = "dbresource://moit/erp/dte/${rutOrganizacion}/DTE-${tipoDte}-${folio}.pdf"
pdfCedibleContentLocation = "dbresource://moit/erp/dte/${rutOrganizacion}/DTE-${tipoDte}-${folio}-cedible.pdf"

// Creacion de registros en FiscalTaxDocumentContent
createMapBase = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, contentDte:ts]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:xmlContentLocation]).call())
ec.resource.getLocationReference(xmlContentLocation).putBytes(facturaXml)

// TODO
//ec.context.putAll(ec.service.sync().name("mchile.sii.dte.DteContentServices.generate#Pdf").parameters([xmlLocation:xmlContentLocation, issuerPartyId:issuerPartyId, invoiceMessage:invoiceMessage]).call())
//ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:pdfContentLocation]).call())
//ec.resource.getLocationReference(pdfContentLocation).putBytes(pdfBytes)
// TODO ?
//if ((fiscalTaxDocumentTypeEnumId as String) in dteConstituyeVentaTypeList) {
//    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-PdfCedible', contentLocation:pdfCedibleContentLocation]).call())
//    ec.resource.getLocationReference(pdfCedibleContentLocation).putBytes(pdfCedibleBytes)
//}

// Creación de registro en FiscalTaxDocumentAttributes

fechaEmisionString = ec.l10n.format(fechaEmision, "yyyy-MM-dd")
createMap = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, amount:totalInvoice, fechaEmision:fechaEmisionString, anulaBoleta:anulaBoleta, folioAnulaBoleta:folioAnulaBoleta, montoNeto:totalNeto, tasaImpuesto:19,
             montoExento:montoExento, montoIVARecuperable:montoIVARecuperable]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(createMap).call())
fiscalTaxDocumentId = dteEv.fiscalTaxDocumentId

// Caso 2
// Caso 3
// Caso 4
// Caso 5

// Envio Boleta

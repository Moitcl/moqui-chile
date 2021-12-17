import org.w3c.dom.Document

import org.moqui.BaseArtifactException
import org.moqui.entity.EntityValue
import org.moqui.context.ExecutionContext

import cl.moit.dte.MoquiDTEUtils

import groovy.xml.MarkupBuilder

ExecutionContext ec = context.ec

dteConstituyeVentaTypeList = ['Ftdt-101', 'Ftdt-102', 'Ftdt-109', 'Ftdt-110', 'Ftdt-30', 'Ftdt-32', 'Ftdt-33', 'Ftdt-34', 'Ftdt-35', 'Ftdt-38', 'Ftdt-39']
if (invoiceId != null && fiscalTaxDocumentTypeEnumId in dteConstituyeVentaTypeList) {
    existingDteList = ec.entity.find("mchile.dte.FiscalTaxDocument").condition("invoiceId", invoiceId).condition("fiscalTaxDocumentTypeEnumId", "in", dteConstituyeVentaTypeList).list()
    if (existingDteList) {
        existingFiscalTaxDocumentTypeEnumId = existingDteList.first.fiscalTaxDocumentTypeEnumId
        dteEnum = ec.entity.find("moqui.basic.Enumeration").condition("enumId", existingFiscalTaxDocumentTypeEnumId).one()
        ec.message.addError("Ya existe un DTE para la orden de cobro ${invoiceId}, de tipo ${dteEnum.description} (${dteEnum.enumId})")
        return
    }
}

// Recuperacion de parametros de la organizacion -->
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameter("partyId", issuerPartyId).call())

vatTaxRate = ec.service.sync().name("mchile.TaxServices.get#VatTaxRate").parameter("date", new Timestamp(fechaEmision.time)).call().taxRate

// Giro Emisor
giroOutMap = ec.service.sync().name("mchile.DTEServices.get#GiroPrimario").parameter("partyId", issuerPartyId).call()
if (giroOutMap == null) {
    ec.message.addError("No se encuentra giro primario para partyId ${issuerPartyId}")
    return
}
giroEmisor = giroOutMap.description

// Recuperación del código SII de DTE -->
codeOut = ec.service.sync().name("mchile.DTEServices.get#SIICode").parameter("fiscalTaxDocumentTypeEnumId", fiscalTaxDocumentTypeEnumId).call()
tipoDte = codeOut.siiCode

// Formas de pago
if (settlementTermId.equals('Immediate'))
    formaPago = 1 // Contado
/*
else if (settlementTermId.equals('Net10'))
    formaPago = "2" // Credito (usar GlosaPagos)
else if (settlementTermId.equals('Net15'))
    formaPago = "2" // Credito (usar GlosaPagos)
else if (settlementTermId.equals('Net30'))
    formaPago = "2" // Credito (usar GlosaPagos)
else if (settlementTermId.equals('Net60'))
    formaPago = "2" // Credito (usar GlosaPagos)
else if (settlementTermId.equals('Net90'))
    formaPago = "2" // Credito (usar GlosaPagos)
*/
else if (settlementTermId == "3")
    formaPago = 3 // Sin costo
else
    formaPago = 2 // Credito (usar GlosaPagos)

//Obtención de folio y CAF -->
folioResult = ec.service.sync().name("mchile.DTEServices.get#Folio").parameters([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, partyId:issuerPartyId]).call()
folio = folioResult.folio
codRef = 0 as Integer

// Indicador Servicio
// 3 para Factura de Servicios
// Para Facturas de Exportación:
// 4 Servicios de Hotelería
// 5 Servicio de Transporte Terrestre Internacional
//iddoc.setIndServicio(BigInteger.valueOf(3))

// Campos para elaboración de libro
montoNeto = 0 as Long
montoExento = 0 as Long
montoIVARecuperable = 0 as Long
totalNeto = 0 as Long
totalExento = 0 as Long

// Campo para guardar resumen atributos -->
amount = 0 as Long
uom = null

// Reference to buying order
EntityValue invoice = null
if (invoiceId) {
    invoice = ec.entity.find("mantle.account.invoice.Invoice").condition([invoiceId:invoiceId]).one()
    if (invoice.otherPartyOrderId) {
        itemBillingList = ec.entity.find("mantle.order.OrderItemBilling").condition([invoiceId:invoiceId]).selectField("orderId,orderItemSeqId").list()
        if (itemBillingList) {
            orderList = ec.entity.find("mantle.order.OrderItemAndPart").condition([otherPartyOrderId:invoice.otherPartyOrderId, orderId:itemBillingList.orderId, orderId_op:"in", orderItemSeqId:itemBillingList.orderItemSeqId, orderItemSeqId_op:"in"]).orderBy("-otherPartyOrderDate").list()
            fecha = orderList.first?.otherPartyOrderDate?:ec.user.nowTimestamp
        } else
            fecha = ec.user.nowTimestamp
        reference = ec.entity.makeValue("mchile.dte.ReferenciaDte")
        reference.folio = invoice.otherPartyOrderId
        reference.razonReferencia = "Orden de Compra"
        reference.referenciaTypeEnumId = "RefDteTypeInvoice"
        reference.fecha = fecha
        reference.fiscalTaxDocumentTypeEnumId = "Ftdt-801"
        referenciaList.add(reference)
    }
}
if (tipoDte == 33) {
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "InvoiceItem")
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
    numberAfectos = detMap.numberAfectos
    numberExentos = detMap.numberExentos
    if (numberAfectos == 0 && numberExentos > 0)
        throw new BaseArtifactException("Factura afecta tiene solamente ítemes exentos")
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutReceptor, tipoDte)
    referenciaList = refMap.referenciaList
} else if (tipoDte == 34) {
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "InvoiceItem")
    detalleList = detMap.detalleList
    numberAfectos = detMap.numberAfectos
    numberExentos = detMap.numberExentos
    if (numberAfectos > 0)
        throw new BaseArtifactException("Factura exenta tiene ítemes afectos")
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutReceptor, tipoDte)
    referenciaList = refMap.referenciaList
} else if (tipoDte == 61) {
    // Nota de Crédito Electrónica
    ec.logger.warn("Creando DTE tipo 61")
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, null, tipoDte)
    referenciaList = refMap.referenciaList
    anulaBoleta = refMap.anulaBoleta
    folioAnulaBoleta = refMap.folioAnulaBoleta
    BigInteger codRef = ref[ref.length()-1].getCodRef()
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "InvoiceItem", codRef)
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto

    if (codRef == 2 && det.length() > 1) {
        ec.message.addError("codRef = 2 && det.length() = ${det.length()}")
        return
    }

    // Si la razon es modifica texto (2) no van los montos
    ec.logger.warn("Codref: " + codRef + ", dteExenta: " + dteExenta)
    if(codRef == 2) {
        totalNeto = 0
        totalExento = 0
    }
} else if (tipoDte == 56) {
    // Nota de Débito Electrónica
    ec.logger.warn("Creando DTE tipo 56")

    int i = 0
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutReceptor, tipoDte)
    referenciaList = refMap.referenciaList
    dteExenta = refMap.dteExenta
    // codRef = refMap.codRef
    codRef = 1

    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "DebitoItem", codRef)
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto

    // Si la razon es modifica texto (2) no van los montos
    // Notas de débito son siempre afectas
    if(codRef == 2) {
        totalNeto = 0
        totalExento = 0
    }
} else if (tipoDte == 52) {
    // Guías de Despacho
    ec.logger.warn("Creando DTE tipo 52")

    // TODO: Si la referencia es tipo fe de erratas, Monto Item puede ser 0
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutReceptor, tipoDte)
    referenciaList = refMap.referenciaList
    dteExenta = refMap.dteExenta
    codRef = refMap.codRef

    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "ShipmentItem", codRef)
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
}

// Descuento Global
if(globalDiscount != null && Integer.valueOf(globalDiscount) > 0) {
    if (totalNeto != null)
        totalNeto = totalNeto - Math.round(totalNeto?:0 * (Long.valueOf(globalDiscount) / 100))
    if (totalExento != null)
        totalExento = totalExento - Math.round(totalExento?:0 * (Long.valueOf(globalDiscount) / 100))
    // Creación entradas en XML
    discountMap = [:]
    discountMap.numeroLinea = 1
    discountMap.tipo = "D"
    discountMap.tipoValor = "%"
    discountMap.valor = globalDiscount
    discountMap.glosa = globalDr
    globalDiscountOrChargeList = [discountMap]
}
// Totales
if (totalNeto != null) {
    long totalIVA = Math.round(totalNeto * vatTaxRate)
    montoIVARecuperable = totalIVA
    totalInvoice = totalNeto + totalIVA + totalExento
} else
    totalInvoice = totalExento

// Chequeo de valores entre Invoice y calculados
if (invoice) {
    if (invoice.invoiceTotal != totalInvoice) {
        ec.message.addError("No coinciden valores totales, calculado: ${totalInvoice}, en invoice ${invoiceId}: ${invoice.invoiceTotal}")
        return
    }
}

idDocumento = "Dte-" + ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
String tmstFirmaResp = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)

if (giroReceptor.length() > 39)
    giroReceptor = giroReceptor.substring(0,39)

// Timbre
datosTed = "<DD><RE>${rutEmisor}</RE><TD>${tipoDte}</TD><F>${folio}</F><FE>${}</FE><RR>${rutReceptor}</RR><RSR>${razonSocialReceptor}</RSR><MNT>${totalInvoice}</MNT><IT1>${detalleList.get(0).nombreItem}</IT1>${folioResult.cafFragment.replaceAll('>\\s*<', '><').trim()}<TSTED>${ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")}</TSTED></DD>"

xmlBuilder.DTE(xmlns: 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version: '1.0', 'xsi:schemaLocation': 'http://www.sii.cl/SiiDte DTE_v10.xsd') {
    Documento(ID: idDocumento) {
        Encabezado {
            IdDoc {
                TipoDTE(tipoDte)
                Folio(folio)
                FchEmis(ec.l10n.format(fechaEmision, "yyyy-MM-dd"))
                //IndNoRebaja()
                if (tipoDespacho)
                    TipoDespacho(tipoDespacho)
                if (tipoDte == 52)
                    IndTraslado(indTraslado)
                if (indServicio)
                    IndServicio(indServicio)
                FmaPago(formaPago)
                if (fechaCancelacion)
                    FchCancel(ec.l10n.format(fechaCancelacion, 'yyyy-MM-dd'))
                if (montoCancelacion)
                    MntCancel(montoCancelacion)
                if (saldoInsoluto)
                    SaldoInsol(saldoInsoluto)
                //MntPagos{}
                //PeriodoDesde()
                //PeriodoHasta()
                MedioPago(medioPago?:'PE')
                //TpoCtaPago()
                //NumCtaPago()
                //BcoPago()
                //TermPagoCdg
                if (glosaPagos)
                    TermPagoGlosa(glosaPagos)
                //TermPagoDias
                if (fechaVencimiento)
                    FchVenc(ec.l10n.format(fechaVencimiento, "yyyy-MM-dd"))
            }
            Emisor {
                RUTEmisor(rutEmisor)
                RznSoc(razonSocialEmisor)
                GiroEmis(giroEmisor)
                Telefono(fonoContacto)
                CorreoEmisor(mailContacto)
                codigosActividadEconomica.split(',').each { codigoActividad ->
                    Acteco(codigoActividad)
                }
                //Sucursal(sucursal)
                if (codigoSucursalSii)
                    CdgSIISucur(codigoSucursalSii)
                DirOrigen(direccionOrigen)
                CmnaOrigen(comunaOrigen)
                CiudadOrigen(ciudadOrigen)
                if (codigoVendedor)
                    CdgVendedor(codigoVendedor)
                if (identificadorAdicionalEmisor)
                    IdAdicEmisor(identificadorAdicionalEmisor)
            }
            Receptor {
                RUTRecep(rutReceptor)
                if (codigoInternoReceptor)
                    CdgIntRecep(codigoInternoReceptor)
                RznSocRecep(razonSocialReceptor)
                GiroRecep(giroReceptor)
                if (contactoReceptor)
                    Contacto(contactoReceptor)
                if (correoReceptor)
                    CorreoReceptor(correoReceptor)
                DirRecep(direccionReceptor)
                CmnaRecep(comunaReceptor)
                CiudadRecep(ciudadReceptor)
                //DirPostal
                //CmnaPostal
                //CiudadPostal
            }
            //RUTSolicita()
            //Transporte{}
            Totales {
                MntNeto(Math.round(totalNeto))
                if (totalExento != null && totalExento > 0)
                    MntExe(totalExento)
                //MntBase()
                //MntMargenCom()
                TasaIVA(ec.l10n.format(vatTaxRate*100, "##"))
                IVA(Math.round(totalNeto * vatTaxRate))
                //IVAProp()
                //IVATerc()
                //ImptoReten{}
                //IVANoRet()
                //CredEC()
                //GrntDep()
                //Comisiones{}
                MntTotal(totalInvoice)
                if (montoNoFacturable)
                    MontoNF(montoNoFacturable)
                //MontoPeriodo()
                //SaldoAnterior()
                //VlrPagar()
            }
            //OtraMoneda{}
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
                //Retenedor{}
                NmbItem(detalle.nombreItem)
                if (detalle.descripcionItem)
                    DscItem(detalle.descripcionItem)
                //QtyRef()
                //UnmdRef()
                //PrcRef()
                if (detalle.quantity != null)
                    QtyItem(detalle.quantity)
                //Subcantidad{}
                //FchElabor()
                //FchVencim()
                if (detalle.uom)
                    UnmdItem(uom)
                PrcItem(detalle.priceItem)
                //OtrMnda{}
                if (detalle.porcentajeDescuento)
                    DescuentoPct(detalle.porcentajeDescuento)
                if (detalle.montoDescuento)
                    DescuentoMonto(detalle.montoDescuento)
                //SubDscto{}
                //RecargoPct()
                //RecargoMonto()
                //SubRecargo{}
                //CodImpAdic()
                MontoItem(detalle.montoItem)
            }
        }
        //SubTotInfo{}
        globalDiscountOrChargeList?.each { discountOrChargeMap ->
            DscRcgGlobal{
                NroLinDR(discountOrChargeMap.numeroLinea)
                TpoMov(discountOrChargeMap.tipo)
                GlosaDR(discountOrChargeMap.glosa)
                TpoValor(discountOrChargeMap.tipoValor)
                ValorDR(discountOrChargeMap.valor)
                //ValorDROtrMnda()
                //IndExeDR()
            }
        }
        referenciaList.each { referencia ->
            Referencia {
                NroLinRef(referencia.numeroLinea)
                TpoDocRef()
                //IndGlobal()
                FolioRef(referencia.folio)
                if (referencia.rutOtro)
                    RUTOtr(referencia.rutOtro)
                if (referencia.fecha)
                    FchRef(ec.l10n.format(referencia.fecha, "yyyy-MM-dd"))
                if (referencia.codigo)
                    CodRef(codigo)
                if (referencia.razon)
                    RazonRef(razon)
            }
        }
        //Comisiones{}
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

if (MoquiDTEUtils.verifySignature(doc2, "/sii:DTE/sii:Documento", "/sii:DTE/sii:Documento/sii:Encabezado/sii:IdDoc/sii:FchEmis/text()")) {
    ec.logger.warn("DTE folio ${folio} generada OK")
} else
    ec.message.addError("Error al generar DTE folio ${folio}")

// Registry de DTE en base de datos y generación de PDF -->
fiscalTaxDocumentTypeEnumId = "Ftdt-${tipoDte}"
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.genera#PDF").parameters([dte:facturaXml, issuerPartyId:issuerPartyId, invoiceMessage:invoiceMessage]).call())

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

xmlContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/DTE-${tipoDte}-${folio}.xml"
pdfContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/DTE-${tipoDte}-${folio}.pdf"
pdfCedibleContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/DTE-${tipoDte}-${folio}-cedible.pdf"

// Creacion de registros en FiscalTaxDocumentContent
createMapBase = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, contentDte:ts]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:xmlContentLocation]).call())
ec.resource.getLocationReference(xmlContentLocation).putBytes(facturaXml)

ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:pdfContentLocation]).call())
ec.resource.getLocationReference(pdfContentLocation).putBytes(pdfBytes)

if ((fiscalTaxDocumentTypeEnumId as String) in dteConstituyeVentaTypeList) {
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-PdfCedible', contentLocation:pdfCedibleContentLocation]).call())
    ec.resource.getLocationReference(pdfCedibleContentLocation).putBytes(pdfCedibleBytes)
}

// Creación de registro en FiscalTaxDocumentAttributes
fechaEmisionString = ec.l10n.format(fechaEmision, "yyyy-MM-dd")
createMap = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, amount:totalInvoice, fechaEmision:fechaEmisionString, anulaBoleta:anulaBoleta, folioAnulaBoleta:folioAnulaBoleta, montoNeto:montoNeto, tasaImpuesto:19,
             montoExento:montoExento, montoIVARecuperable:montoIVARecuperable]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(createMap).call())
fiscalTaxDocumentId = dteEv.fiscalTaxDocumentId
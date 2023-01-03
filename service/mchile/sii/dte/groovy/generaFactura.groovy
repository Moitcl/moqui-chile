import org.w3c.dom.Document

import org.moqui.entity.EntityValue
import org.moqui.context.ExecutionContext

import cl.moit.dte.MoquiDTEUtils

import groovy.xml.MarkupBuilder

ExecutionContext ec = context.ec

dteConstituyeVentaTypeList = ['Ftdt-101', 'Ftdt-102', 'Ftdt-109', 'Ftdt-110', 'Ftdt-30', 'Ftdt-32', 'Ftdt-33', 'Ftdt-34', 'Ftdt-35', 'Ftdt-38', 'Ftdt-39', 'Ftdt-41']
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
ec.context.putAll(ec.service.sync().name("mchile.sii.dte.DteInternalServices.load#DteConfig").parameter("partyId", issuerPartyId).call())

vatTaxRate = ec.service.sync().name("mchile.TaxServices.get#VatTaxRate").parameter("date", new Timestamp(fechaEmision.time)).call().taxRate

// Giro Emisor
giroOutMap = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#GiroPrimario").parameter("partyId", issuerPartyId).call()
if (giroOutMap == null) {
    ec.message.addError("No se encuentra giro primario para partyId ${issuerPartyId}")
    return
}
giroEmisor = giroOutMap.description

// Recuperación del código SII de DTE -->
tipoDte = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#SiiCode").parameters([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId]).call().siiCode

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
codRef = 0 as Integer

// Indicador Servicio
// 3 para Factura de Servicios
// Para Facturas de Exportación:
// 4 Servicios de Hotelería
// 5 Servicio de Transporte Terrestre Internacional
//iddoc.setIndServicio(BigInteger.valueOf(3))

// Campos para elaboración de libro
montoIVARecuperable = 0 as Long
totalNeto = 0 as Long
totalExento = 0 as Long
totalDescuentos = 0 as Long

// Campo para guardar resumen atributos -->
amount = 0 as Long
uom = null

// Reference to buying order
EntityValue invoice = null
if (invoiceId) {
    referenciaTypeEnumId = "RefDteTypeFactura"
    invoice = ec.entity.find("mantle.account.invoice.Invoice").condition([invoiceId:invoiceId]).one()
    if (invoice.otherPartyOrderId) {
        itemBillingList = ec.entity.find("mantle.order.OrderItemBilling").condition([invoiceId:invoiceId]).selectField("orderId,orderItemSeqId").list()
        if (itemBillingList) {
            orderList = ec.entity.find("mantle.order.OrderItemAndPart").condition([otherPartyOrderId:invoice.otherPartyOrderId, orderId:itemBillingList.orderId, orderId_op:"in", orderItemSeqId:itemBillingList.orderItemSeqId, orderItemSeqId_op:"in"]).orderBy("-otherPartyOrderDate").list()
            fecha = orderList.first?.otherPartyOrderDate?:ec.user.nowTimestamp
            orderId = orderList.first?.orderId
        } else
            fecha = ec.user.nowTimestamp
        reference = ec.entity.makeValue("mchile.dte.ReferenciaDte")
        reference.folio = invoice.otherPartyOrderId
        reference.razonReferencia = "Orden de Compra"
        reference.referenciaTypeEnumId = referenciaTypeEnumId
        reference.fecha = fecha
        reference.fiscalTaxDocumentTypeEnumId = "Ftdt-801"
        referenciaList.add(reference)
        if (orderId) {
            orderContentList = ec.entity.find("mantle.order.OrderContent").condition([orderId:orderId]).selectField("orderContentTypeEnumId,externalContentId,externalContentDate").list()
            if(orderContentList) {
                orderContentList.each {
                    if (!['Oct-801', 'Oct-Others'].contains(it.orderContentTypeEnumId)) {
                        referenceType = ec.entity.find("moqui.basic.Enumeration").condition([enumId:it.orderContentTypeEnumId]).one()
                        reference = ec.entity.makeValue("mchile.dte.ReferenciaDte")
                        reference.folio = it.externalContentId
                        reference.razonReferencia = referenceType.description
                        reference.referenciaTypeEnumId = referenciaTypeEnumId
                        reference.fecha = it.externalContentDate
                        reference.fiscalTaxDocumentTypeEnumId = referenceType.relatedEnumId
                        referenciaList.add(reference)
                    }
                }
            }
        }
    }
}
if (tipoDte == 33) {
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "InvoiceItem")
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
    totalExento = detMap.totalExento
    numberAfectos = detMap.numberAfectos
    numberExentos = detMap.numberExentos
    if (numberAfectos == 0 && numberExentos > 0) {
        ec.message.addMessage("Factura Electrónica solamente tiene ítemes exentos, cambiando tipo a Factura Electrónica Exenta")
        tipoDte = 34
        fiscalTaxDocumentTypeEnumId = 'Ftdt-34'
    }
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutReceptor, tipoDte)
    referenciaList = refMap.referenciaList
    totalDescuentos = detMap.totalDescuentos
} else if (tipoDte == 34) {
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "InvoiceItem")
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
    totalExento = detMap.totalExento
    numberAfectos = detMap.numberAfectos
    numberExentos = detMap.numberExentos
    if (numberAfectos > 0) {
        ec.message.addMessage("Factura Electrónica Exenta tiene ítemes afectos, cambiando tipo a Factura Electrónica")
        tipoDte = 33
        fiscalTaxDocumentTypeEnumId = 'Ftdt-33'
    }
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutReceptor, tipoDte)
    referenciaList = refMap.referenciaList
    totalDescuentos = detMap.totalDescuentos
} else if (tipoDte == 61) {
    // Nota de Crédito Electrónica
    ec.logger.warn("Creando DTE tipo 61")
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, null, tipoDte)
    referenciaList = refMap.referenciaList
    anulaBoleta = refMap.anulaBoleta
    folioAnulaBoleta = refMap.folioAnulaBoleta
    codRef = referenciaList[0].codigo

    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "InvoiceItem", codRef as Integer)
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
    totalExento = detMap.totalExento

    if (codRef == 2 && detalleList.size() > 1) {
        ec.message.addError("codRef = 2 && detalleList.size() = ${detalleList.size()}")
        return
    }

    // Si la razon es modifica texto (2) no van los montos
    ec.logger.warn("Codref: " + codRef + ", dteExenta: " + dteExenta)
    if (codRef == 2) {
        totalNeto = 0
        totalExento = 0
    }
    totalDescuentos = detMap.totalDescuentos
} else if (tipoDte == 56) {
    // Nota de Débito Electrónica
    ec.logger.warn("Creando DTE tipo 56")
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, null, tipoDte)
    referenciaList = refMap.referenciaList
    dteExenta = refMap.dteExenta
    codRef = referenciaList[0].codigo

    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "DebitoItem", codRef as Integer)
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
    totalExento = detMap.totalExento

    if (codRef == 2 && detalleList.size() > 1) {
        ec.message.addError("codRef = 2 && detalleList.size() = ${detalleList.size()}")
        return
    }

    // Si la razon es modifica texto (2) no van los montos
    // Notas de débito son siempre afectas
    if (codRef == 2) {
        totalNeto = 0
        totalExento = 0
    }
    totalDescuentos = detMap.totalDescuentos
} else if (tipoDte == 52) {
    // Guías de Despacho
    ec.logger.warn("Creando DTE tipo 52")
    if (indTrasladoEnumId == null)
        ec.message.addError("No indTrasladoEnumId for tipoDte 52 (Guía de Despacho)")
    if (indTrasladoEnumId in ['IndTraslado-1', 'IndTraslado-9'] && !invoiceId)
        ec.message.addError("No se puede generar una Guía de Despacho sin un invoiceId")
    indTraslado = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#SiiCode").parameters([fiscalTaxDocumentTypeEnumId:indTrasladoEnumId, enumTypeId:'IndTraslado']).call().siiCode
    // TODO: Si la referencia es tipo fe de erratas, Monto Item puede ser 0
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutReceptor, tipoDte)
    referenciaList = refMap.referenciaList
    dteExenta = refMap.dteExenta
    codRef = refMap.codigo

    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "ShipmentItem", codRef as Integer)
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
    totalExento = detMap.totalExento
    totalDescuentos = detMap.totalDescuentos
}

//Obtención de folio y CAF -->
folioResult = ec.service.sync().name("mchile.sii.dte.DteFolioServices.get#Folio").parameters([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, partyId:issuerPartyId]).call()
folio = folioResult.folio
if (folio == null)
    return

descuentoORecargoGlobalList.each {
    if (it.tipo == 'D') {
        if (it.afecto)
            descuentoGlobalAfecto = (descuentoGlobalAfecto?:0) - it.monto
        else
            descuentoGlobalExento = (descuentoGlobalExento?:0) - it.monto
    }
}

// Totales
if (totalNeto != null) {
    totalNeto = totalNeto - (descuentoGlobalAfecto?:0)
    long totalIVA = Math.round(totalNeto * vatTaxRate)
    montoIVARecuperable = totalIVA
    totalInvoice = (totalNeto?:0) + totalIVA + (totalExento?:0)
} else
    totalInvoice = (totalExento?:0) - (descuentoGlobalExento?:0)

// Chequeo de valores entre Invoice y calculados
if (invoice) {
    if (invoice.invoiceTotal != totalInvoice) {
        ec.message.addError("No coinciden valores totales, calculado: ${totalInvoice}, en invoice ${invoiceId}: ${invoice.invoiceTotal}")
        return
    }
}

idDocumento = "Dte-" + ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
String tmstFirmaResp = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")

if (giroReceptor.length() > 39)
    giroReceptor = giroReceptor.substring(0,39)
razonSocialReceptorTimbre = razonSocialReceptor.length() > 39? razonSocialReceptor.substring(0,39): razonSocialReceptor

StringWriter xmlWriterTimbre = new StringWriter()
MarkupBuilder xmlBuilderTimbre = new MarkupBuilder(new IndentPrinter(new PrintWriter(xmlWriterTimbre), "", false))

// Timbre
String detalleIt1 = detalleList.get(0).nombreItem
if (detalleIt1.length() > 40)
    detalleIt1 = detalleIt1.substring(0, 40)

xmlBuilderTimbre.DD() {
    RE(rutEmisor)
    TD(tipoDte)
    F(folio)
    FE(ec.l10n.format(fechaEmision, "yyyy-MM-dd"))
    RR(rutReceptor)
    RSR(razonSocialReceptorTimbre)
    MNT(totalInvoice)
    IT1(detalleIt1)
    xmlBuilderTimbre.getMkp().yieldUnescaped(folioResult.cafFragment.replaceAll('>\\s*<', '><').trim())
    TSTED(ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss"))
}
datosTed = xmlWriterTimbre.toString()

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)

String schemaLocation = 'http://www.sii.cl/SiiDte DTE_v10.xsd'
xmlBuilder.DTE(xmlns: 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version: '1.0', 'xsi:schemaLocation': schemaLocation) {
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
                MntNeto(Math.round(totalNeto?:0))
                if (totalExento != null && totalExento > 0)
                    MntExe(totalExento)
                //MntBase()
                //MntMargenCom()
                if (tipoDte != 34) TasaIVA(ec.l10n.format(vatTaxRate*100, "##"))
                IVA(Math.round((totalNeto?:0) * vatTaxRate))
                //IVAProp()
                //IVATerc()
                //ImptoReten{}
                //IVANoRet()
                //CredEC()
                //GrntDep()
                //Comisiones{}
                MntTotal(totalInvoice as Integer)
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
                if (detalle.priceItem != null && detalle.priceItem != 0 && detalle.priceItem != "0")
                    PrcItem((detalle.priceItem as BigDecimal).setScale(6, java.math.RoundingMode.HALF_UP))
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
                MontoItem(detalle.montoItem as Integer)
            }
        }
        //SubTotInfo{}
        descuentoORecargoGlobalList?.each { discountOrChargeMap ->
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
facturaXmlString = facturaXmlString.replaceAll("[^\\x00-\\xFF]", "")
xmlWriter.close()

ec.logger.warn(facturaXmlString);

Document doc2 = MoquiDTEUtils.parseDocument(facturaXmlString.getBytes())
byte[] facturaXml = MoquiDTEUtils.sign(doc2, uri, pkey, certificate, uri, "Documento")

try {
    MoquiDTEUtils.validateDocumentSii(ec, facturaXml, schemaLocation)
} catch (Exception e) {
    ec.message.addError("Failed validation: " + e.getMessage())
}

doc2 = MoquiDTEUtils.parseDocument(facturaXml)
if (MoquiDTEUtils.verifySignature(doc2, "/sii:DTE/sii:Documento", "/sii:DTE/sii:Documento/sii:Encabezado/sii:IdDoc/sii:FchEmis/text()")) {
    ec.logger.warn("DTE folio ${folio} generada OK")
} else {
    ec.message.addError("Error al generar DTE folio ${folio}: firma inválida")
}

if (ec.message.hasError())
    return

// Registry de DTE en base de datos y generación de PDF -->
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
Timestamp ts = new Timestamp(fechaEmision.time)
dteEv.date = ts
dteEv.update()

if (tipoDte == 52) {
    ec.service.sync().name("store#mchile.dte.GuiaDespacho").parameters([fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, indTrasladoEnumId:indTrasladoEnumId]).call()
}

xmlContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/DTE-${tipoDte}-${folio}.xml"
pdfContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/DTE-${tipoDte}-${folio}.pdf"
pdfCedibleContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/DTE-${tipoDte}-${folio}-cedible.pdf"

// Creacion de registros en FiscalTaxDocumentContent
createMapBase = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, contentDte:ts]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:xmlContentLocation]).call())
ec.resource.getLocationReference(xmlContentLocation).putBytes(facturaXml)

ec.context.putAll(ec.service.sync().name("mchile.sii.dte.DteContentServices.generate#Pdf").parameters([xmlLocation:xmlContentLocation, issuerPartyId:issuerPartyId, invoiceMessage:invoiceMessage]).call())
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:pdfContentLocation]).call())
ec.resource.getLocationReference(pdfContentLocation).putBytes(pdfBytes)

if ((fiscalTaxDocumentTypeEnumId as String) in dteConstituyeVentaTypeList) {
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-PdfCedible', contentLocation:pdfCedibleContentLocation]).call())
    ec.resource.getLocationReference(pdfCedibleContentLocation).putBytes(pdfCedibleBytes)
}

// Creación de registro en FiscalTaxDocumentAttributes
createMap = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, amount:totalInvoice, fechaEmision:fechaEmision, fechaVencimiento:fechaVencimiento, anulaBoleta:anulaBoleta, folioAnulaBoleta:folioAnulaBoleta, montoNeto:totalNeto, tasaImpuesto:19,
             montoExento:totalExento, montoIVARecuperable:montoIVARecuperable, razonSocialEmisor:razonSocialEmisor, razonSocialReceptor:razonSocialReceptor]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(createMap).call())
fiscalTaxDocumentId = dteEv.fiscalTaxDocumentId

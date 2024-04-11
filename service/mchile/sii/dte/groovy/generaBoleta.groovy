import org.w3c.dom.Document

import org.moqui.entity.EntityValue
import org.moqui.context.ExecutionContext

import cl.moit.dte.MoquiDTEUtils

import groovy.xml.MarkupBuilder

ExecutionContext ec = context.ec

dteConstituyeVentaTypeList = ['Ftdt-101', 'Ftdt-102', 'Ftdt-109', 'Ftdt-110', 'Ftdt-30', 'Ftdt-32', 'Ftdt-33', 'Ftdt-34', 'Ftdt-35', 'Ftdt-38', 'Ftdt-39', 'Ftd-41']
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

if (razonSocialOrganizacion == null || razonSocialOrganizacion.size() < 3) {
    ec.message.addError("Razón Social de emisor no puede tener menos de 3 caracteres")
}

vatTaxRate = ec.service.sync().name("mchile.TaxServices.get#VatTaxRate").parameter("date", new Timestamp(fechaEmision.time)).call().taxRate

// Giro Emisor
giroOutMap = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#GiroPrimario").parameter("partyId", issuerPartyId).call()
if (giroOutMap.giroId == null)
    ec.message.addError("No se encuentra giro primario para emisor ${issuerPartyId}")
if (giroOutMap.description == null || giroOutMap.description == '')
    ec.message.addError("No se encuentra descripción de giro primario para emisor ${issuerPartyId}")
giroEmisor = giroOutMap.description

// Recuperación del código SII de DTE -->
codeOut = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#SiiCode").parameters([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId]).call()
tipoDte = codeOut.siiCode

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

// Formas de pago
if (invoice != null && invoice.invoiceTotal == 0)
    formaPago = 3 // Sin costo
else if (invoice != null && invoice.unpaidTotal == 0) {
    formaPago = 1 // Contado (ya pagado)
    ec.message.addError("fechaCancelacion needs to be determined (unimplemented)")
    //fechaCancelacion
} else
    formaPago = 2 // Crédito (usar GlosaPagos)

if (tipoDte == 39) {
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, draft? "OrderItem" : "InvoiceItem", issuerPartyId)
    detalleList = detMap.detalleList
    //throw new BaseArtifactException("Lista:"+detMap.totalExento)
    totalNeto = detMap.totalNeto
    if(detMap.totalExento)
        totalExento = detMap.totalExento
    numberAfectos = detMap.numberAfectos
    numberExentos = detMap.numberExentos

    if (numberAfectos == 0 && numberExentos > 0) {
        ec.message.addMessage("Boleta Electrónica solamente tiene ítemes exentos, cambiando tipo a Boleta Electrónica Exenta")
        tipoDte = 41
        fiscalTaxDocumentTypeEnumId = 'Ftdt-41'
    }
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutOrganizacion, tipoDte)
    referenciaList = refMap.referenciaList
} else if (tipoDte == 41) {
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "InvoiceItem", issuerPartyId)
    detalleList = detMap.detalleList
    numberAfectos = detMap.numberAfectos
    numberExentos = detMap.numberExentos
    if (numberAfectos > 0) {
        ec.message.addMessage("Boleta Electrónica Exenta tiene ítemes afectos, cambiando tipo a Boleta Electrónica")
        tipoDte = 39
        fiscalTaxDocumentTypeEnumId = 'Ftdt-39'
    }
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutOrganizacion, tipoDte)
    referenciaList = refMap.referenciaList
}

//Obtención de folio y CAF -->
if (draft) {
    folio = "1"
    folioResult = [cafFragment:'<CAF version="1.0"> <DA> <RE>12345677-7</RE> <RS>CAF PARA USO DE PREVISUALIZACION</RS> <TD>33</TD> <RNG> <D>1324</D> <H>1503</H> </RNG> <FA>2021-11-10</FA> <RSAPK> <M>uX02i4mqmJ8hwWPwrRBJTBls5y3tylaSdXc9WQnkmUbnlHyGD2GH/zwSb5IYvOwGlChHtz0aKSITiPOiHTFfmw==</M> <E>Aw==</E> </RSAPK> <IDK>100</IDK> </DA> <FRMA algoritmo="SHA1withRSA">Fsh0/AFUH8dCcSgvXjiIT2wfO9QLYp94KNvGbeKc46jEMj2XgLxq7tBv83rVJ2bIgwytLoJCHaUOi4OnpkVjyg==</FRMA> </CAF>',
                   publicKey:''''-----BEGIN PUBLIC KEY-----
MFowDQYJKoZIhvcNAQEBBQADSQAwRgJBALl9NouJqpifIcFj8K0QSUwZbOct7cpW
knV3PVkJ5JlG55R8hQ9ih/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQM=
-----END PUBLIC KEY-----''',
                   privateKey:'''-----BEGIN RSA PRIVATE KEY-----
MIIBOgIBAAJBALl9NouJqpifIcFj8K0QSUwZbOct7cpWknV3PVkJ5JlG55R8hQ9i
h/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQMCQHuozwexHGW/a9ZCoHNgMN1m
SJoenobkYaOk05CxQxDYy64cThXXFJA5wsn/MTxMBUGpvx4fq3rJEB/6v3J8NXsC
IQDxJBA2u1nUvzOjBm+h79bC0fGGA0RcJ6xUyQnXAtuE5QIhAMTrQdkzRhRnscs6
I6zyo2HfuCMGyTzJSCaP8avum4p/AiEAoMK1edI74yoibK71Fp/kgeFLrqzYPW/I
OIYGj1c9A0MCIQCDR4E7d4QNmnaHfBfIocJBP9AXWdt924VvCqEdSb0G/wIhAJ+3
q4+htPABTvIWzZcF4LILEDnaZS791SWJYxbbE72D
-----END RSA PRIVATE KEY-----''']
} else {
    folioResult = ec.service.sync().name("mchile.sii.dte.DteFolioServices.get#Folio").parameters([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, partyId:issuerPartyId]).call()
    folio = folioResult.folio
    if (folio == null) {
        ec.message.addError("No se encuentra folio para generar boleta")
    }
}

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
if (invoice && invoice.invoiceTotal != totalInvoice) {
    ec.message.addError("No coinciden valores totales, calculado: ${ec.l10n.formatCurrency(totalInvoice, 'CLP')}, en invoice ${invoiceId}: ${ec.l10n.formatCurrency(invoice.invoiceTotal, 'CLP')}")
    return
}

idDocumento = "Bol-" + ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
String tmstFirmaResp = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)

if (giroReceptor && giroReceptor.length() > 39)
    giroReceptor = giroReceptor.substring(0,39)
razonSocialReceptorTimbre = razonSocialReceptor.length() > 39? razonSocialReceptor.substring(0,39): razonSocialReceptor

// Timbre
String detalleIt1 = detalleList.get(0).nombreItem

if (detalleIt1.length() > 40)
    detalleIt1 = detalleIt1.substring(0, 40)
datosTed = "<DD><RE>${rutOrganizacion}</RE><TD>${tipoDte}</TD><F>${folio}</F><FE>${ec.l10n.format(fechaEmision, "yyyy-MM-dd")}</FE><RR>${rutReceptor}</RR><RSR>${razonSocialReceptorTimbre}</RSR><MNT>${totalInvoice}</MNT><IT1>${detalleIt1}</IT1>${folioResult.cafFragment.replaceAll('>\\s*<', '><').trim()}<TSTED>${ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")}</TSTED></DD>"

String schemaLocation = ''
xmlBuilder.DTE(xmlns: 'http://www.sii.cl/SiiDte', version: '1.0') {
    Documento(ID: idDocumento) {
        Encabezado {
            IdDoc {
                TipoDTE(tipoDte)
                Folio(folio)
                FchEmis(ec.l10n.format(fechaEmision, "yyyy-MM-dd"))
                IndServicio("3")
                //IndNoRebaja()
                if (tipoDespacho)
                    TipoDespacho(tipoDespacho)
                if (tipoDte == 52)
                    IndTraslado(indTraslado)
                if (indServicio)
                    IndServicio(indServicio)
                //FmaPago(formaPago)
                if (fechaCancelacion)
                    FchCancel(ec.l10n.format(fechaCancelacion, 'yyyy-MM-dd'))
                if (montoCancelacion)
                    MntCancel(montoCancelacion)
                if (saldoInsoluto)
                    SaldoInsol(saldoInsoluto)
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
                if (direccionReceptor && comunaReceptor && ciudadReceptor) {
                    DirRecep(direccionReceptor)
                    CmnaRecep(comunaReceptor)
                    CiudadRecep(ciudadReceptor)
                }
            }
            //RUTSolicita()
            //Transporte{}
            Totales {
                if (totalNeto)
                    MntNeto(Math.round(totalNeto))
                if (totalExento != null && totalExento > 0)
                    MntExe(totalExento)
                //TasaIVA(ec.l10n.format(vatTaxRate*100, "##"))
                if (totalNeto)
                    IVA(Math.round((totalNeto?:0) * vatTaxRate))
                //IVAProp()
                //IVATerc()
                //ImptoReten{}
                //IVANoRet()
                //CredEC()
                //GrntDep()
                //Comisiones{}
                MntTotal(totalInvoice)
                //if (montoNoFacturable)
                //    MontoNF(montoNoFacturable)
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
                NmbItem(detalle.nombreItem)
                if (detalle.descripcionItem)
                    DscItem(detalle.descripcionItem)
                if (detalle.quantity != null)
                    QtyItem(detalle.quantity)
                //Subcantidad{}
                //FchElabor()
                //FchVencim()
                if (detalle.uom)
                    UnmdItem(detalle.uom)
                //PrcItem(detalle.priceItem)
                if(detalle.indicadorExento && detalle.priceItem != null && detalle.priceItem != 0 && detalle.priceItem != "0")
                    PrcItem(Math.round(detalle.priceItem))
                if(!detalle.indicadorExento && detalle.priceItem != null && detalle.priceItem != 0 && detalle.priceItem != "0")
                    PrcItem(Math.round(detalle.priceItem + Math.round(detalle.priceItem * vatTaxRate)))
                //OtrMnda{}
                //if (detalle.porcentajeDescuento)
                   // DescuentoPct(detalle.porcentajeDescuento)
                //if (detalle.montoDescuento)
                //    DescuentoMonto(detalle.montoDescuento)

                if(detalle.indicadorExento)
                    MontoItem(Math.round(detalle.montoItem))
                if(!detalle.indicadorExento)
                    MontoItem(Math.round(detalle.montoItem + Math.round(detalle.montoItem * vatTaxRate)))
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
                //TpoDocRef(referencia.tipoDocumento)
                //FolioRef(referencia.folio)
                //if (referencia.rutOtro)
                    //RUTOtr(referencia.rutOtro)
                //if (referencia.fecha)
                    //FchRef(ec.l10n.format(referencia.fecha, "yyyy-MM-dd"))
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

Document doc2 = MoquiDTEUtils.parseDocument(facturaXmlString.getBytes())
byte[] facturaXml = MoquiDTEUtils.sign(doc2, uri, pkey, certificate, uri, "Documento")

// Validacion siempre fallara por estructura de boletas (deben ir en un envio siempre)
/*try {
    MoquiDTEUtils.validateDocumentSii(ec, facturaXml, schemaLocation)
} catch (Exception e) {
    ec.message.addError("Failed validation: " + e.getMessage())
}*/

doc2 = MoquiDTEUtils.parseDocument(facturaXml)
if (MoquiDTEUtils.verifySignature(doc2, "/sii:DTE/sii:Documento", "/sii:DTE/sii:Documento/sii:Encabezado/sii:IdDoc/sii:FchEmis/text()")) {
    ec.logger.warn("Boleta folio ${folio} generada OK")
} else {
    ec.message.addError("Error al generar Boleta folio ${folio}: firma inválida")
}

if (ec.message.hasError())
    return

// Registry de boleta en base de datos y generación de PDF -->
fiscalTaxDocumentTypeEnumId = "Ftdt-${tipoDte}"

if (!draft) {
    // Creación de registro en FiscalTaxDocument -->
    dteEv = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([fiscalTaxDocumentTypeEnumId: fiscalTaxDocumentTypeEnumId, fiscalTaxDocumentNumber: folio, issuerPartyId: issuerPartyId]).one()

    dteEv.receiverPartyId = receiverPartyId
    dteEv.receiverPartyIdTypeEnumId = "PtidNationalTaxId"
    dteEv.receiverPartyIdValue = rutReceptor.trim()
    dteEv.statusId = "Ftd-Issued"
    dteEv.issuedByUserId = ec.user.userId
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
    createMapBase = [fiscalTaxDocumentId: dteEv.fiscalTaxDocumentId, contentDte: ts]
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase + [fiscalTaxDocumentContentTypeEnumId: 'Ftdct-Xml', contentLocation: xmlContentLocation]).call())
} else {
    xmlContentFile = File.createTempFile("draftDteXml", "pdf")
    xmlContentLocation = xmlContentFile.getAbsolutePath()
}

ec.resource.getLocationReference(xmlContentLocation).putBytes(facturaXml)
ec.context.putAll(ec.service.sync().name("mchile.sii.dte.DteContentServices.generate#Pdf").parameters(
        [xmlLocation:xmlContentLocation, templatePartyId:issuerPartyId, invoiceMessage:invoiceMessage, type:'boleta', draft:draft]).call())

if (draft) {
    previewPdf = pdfBytes
    xmlContentFile.delete()
} else {
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase + [fiscalTaxDocumentContentTypeEnumId: 'Ftdct-Pdf', contentLocation: pdfContentLocation]).call())
    ec.resource.getLocationReference(pdfContentLocation).putBytes(pdfBytes)

    // Creación de registro en FiscalTaxDocumentAttributes
    createMap = [fiscalTaxDocumentId: dteEv.fiscalTaxDocumentId, amount: totalInvoice, fechaEmision: fechaEmision, anulaBoleta: anulaBoleta, folioAnulaBoleta: folioAnulaBoleta, montoNeto: totalNeto, tasaImpuesto: 19,
                 montoExento        : totalExento, montoIVARecuperable: montoIVARecuperable, razonSocialEmisor: razonSocialOrganizacion, razonSocialReceptor: razonSocialReceptor]
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(createMap).call())
    fiscalTaxDocumentId = dteEv.fiscalTaxDocumentId
}
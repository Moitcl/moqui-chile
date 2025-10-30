import org.w3c.dom.Document

import org.moqui.entity.EntityValue
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition

import java.math.RoundingMode
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException

import cl.moit.dte.MoquiDTEUtils

import groovy.xml.MarkupBuilder

ExecutionContext ec = context.ec

// Convert generarXmlMontosBrutos boolean to indicadorMontosBrutos integer for XML generation
// Only set to 1 if true, otherwise leave as null (don't set 0)
indicadorMontosBrutos = generarXmlMontosBrutos ? 1 : null

dteConstituyeVentaTypeList = ['Ftdt-101', 'Ftdt-102', 'Ftdt-109', 'Ftdt-110', 'Ftdt-30', 'Ftdt-32', 'Ftdt-33', 'Ftdt-34', 'Ftdt-35', 'Ftdt-38', 'Ftdt-39', 'Ftdt-41']
EntityValue invoice = null
if (invoiceId) {
    invoice = ec.entity.find("mantle.account.invoice.Invoice").condition("invoiceId", invoiceId).forUpdate(true).one()
}
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

if (codigosActividadEconomica == '')
    ec.message.addError("Emisor no define códigos de Actividad Económica")

telefonosEmisor = [fonoContacto]

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
tipoDteMap = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#SiiCode").parameters([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId]).call()
tipoDte = tipoDteMap.siiCode
dteTypeDescription = tipoDteMap.dteTypeDescription

if (invoiceId) {
    referenciaTypeEnumId = "RefDteTypeFactura"
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

// Formas de pago
if (invoice != null && invoice.invoiceTotal == 0) {
    formaPago = 3 // Sin costo
    formaPagoEnumId = 'Ftdfp-Gratuita'
} else if (invoice != null && invoice.unpaidTotal == 0) {
    formaPago = 1 // Contado (ya pagado)
    formaPagoEnumId = 'Ftdfp-Contado'
    EntityCondition invoiceCondition = ec.entity.conditionFactory.makeCondition([ec.entity.conditionFactory.makeCondition("invoiceId", EntityCondition.EQUALS, invoiceId),
                        ec.entity.conditionFactory.makeCondition("toInvoiceId", EntityCondition.EQUALS, invoiceId)], EntityCondition.OR)
    EntityValue lastPaymentApplication = ec.entity.find("mantle.account.payment.PaymentApplication").condition(invoiceCondition).orderBy("-appliedDate").list().first
    fechaCancelacion = lastPaymentApplication?.appliedDate
    if (lastPaymentApplication?.appliedDate == null) {
        ec.message.addError("Invoice pagada, no se encuentra fecha de cancelación")
        return
    }
} else {
    formaPago = 2 // Crédito (usar GlosaPagos)
    formaPagoEnumId = 'Ftdfp-Credito'
}

currencyUomId = 'CLP'
ec.logger.info("Creando DTE tipo ${tipoDte} (${dteTypeDescription})")
tasaIva = ec.l10n.format(vatTaxRate*100, "##")
if (tipoDte in ['33', '46', '43']) {
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, fiscalTaxDocumentTypeEnumId, detailList, draft ? "OrderItem" : "InvoiceItem", issuerPartyId, preciosIncluyenIva, generarXmlMontosBrutos)
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
    totalIva = detMap.totalIva
    if (detMap.totalExento > 0)
        totalExento = detMap.totalExento
    if (detMap.totalExentoClp > 0)
        totalExentoClp = detMap.totalExentoClp
    numberAfectos = detMap.numberAfectos
    numberExentos = detMap.numberExentos
    if (numberAfectos == 0 && numberExentos > 0 && tipoDte != '43') {
        ec.message.addMessage("Factura Electrónica solamente tiene ítemes exentos, cambiando tipo a Factura Electrónica Exenta")
        tipoDte = '34'
        fiscalTaxDocumentTypeEnumId = 'Ftdt-34'
        tasaIva = null
    }
    originalReferenciaList = referenciaList
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutOrganizacion, tipoDte)
    referenciaList = refMap.referenciaList
    totalDescuentos = detMap.totalDescuentos
} else if (tipoDte in ['110', '111', '112']) {
    // DTE Exportación: Factura de Exportación Electrónica, Nota de Débito de Exportación Electrónica, Nota de Crédito de Exportación Electrónica
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, fiscalTaxDocumentTypeEnumId, detailList, draft ? "OrderItem" : "InvoiceItem", issuerPartyId, preciosIncluyenIva, generarXmlMontosBrutos)
    detalleList = detMap.detalleList
    totalExento = detMap.totalExento?:0
    if (detMap.totalNeto > 0)
        ec.message.addError("No se puede tener valor neto en factura de exportación")
    if (detMap.totalExentoClp > 0)
        totalExentoClp = detMap.totalExentoClp
    totalNeto = null
    numberAfectos = detMap.numberAfectos
    numberExentos = detMap.numberExentos
    originalReferenciaList = referenciaList
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutOrganizacion, tipoDte)
    referenciaList = refMap.referenciaList
    totalDescuentos = detMap.totalDescuentos
    tasaIva = null
    ec.context.putAll(ec.service.sync().name("mchile.sii.dte.DteInfoServices.get#CurrencyUomMap").call())
    if (detMap.originCurrencyUomId) {
        currencyUomId = detMap.originCurrencyUomId
        tipoMoneda = uomToSiiCurrencyMap[detMap.originCurrencyUomId]
        /*
        if (totalesOtraMonedaMap == null)
            totalesOtraMonedaMap = [:]
        totalesOtraMonedaMap.codigo = tipoMoneda
        if (detMap.totalExentoOriginCurrency)
            totalExento = detMap.totalExentoOriginCurrency
         */
    }
    else
        tipoMoneda = uomToSiiCurrencyMap['CLP']
} else if (tipoDte == '34') {
    // Factura Exenta Electrónica
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, fiscalTaxDocumentTypeEnumId, detailList, draft? "OrderItem" : "InvoiceItem", issuerPartyId, preciosIncluyenIva, generarXmlMontosBrutos)
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
    totalIva = detMap.totalIva
    totalExento = detMap.totalExento
    numberAfectos = detMap.numberAfectos
    numberExentos = detMap.numberExentos
    if (numberAfectos > 0) {
        ec.message.addMessage("Factura Electrónica Exenta tiene ítemes afectos, cambiando tipo a Factura Electrónica")
        tipoDte = '33'
        fiscalTaxDocumentTypeEnumId = 'Ftdt-33'
    }
    originalReferenciaList = referenciaList
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutOrganizacion, tipoDte)
    referenciaList = refMap.referenciaList
    totalDescuentos = detMap.totalDescuentos
    tasaIva = null
} else if (tipoDte == '61') {
    // Nota de Crédito Electrónica
    originalReferenciaList = referenciaList
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutOrganizacion, tipoDte)
    referenciaList = refMap.referenciaList
    anulaBoleta = refMap.anulaBoleta
    folioAnulaBoleta = refMap.folioAnulaBoleta
    if (referenciaList == null || referenciaList.size() == 0) {
        ec.message.addError("No se puede crear Nota de Crédito sin referencias")
        return
    }
    codRef = referenciaList[0].codigo

    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, fiscalTaxDocumentTypeEnumId, detailList, "InvoiceItem", codRef as Integer, issuerPartyId, preciosIncluyenIva, generarXmlMontosBrutos)
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
    totalIva = detMap.totalIva
    totalExento = detMap.totalExento

    if (codRef == "2" && detalleList.size() > 1) {
        ec.message.addError("codRef = 2 && detalleList.size() = ${detalleList.size()}")
        return
    }

    if (!codRef) {
        ec.message.addError("No se define Código de Referencia para Nota de Crédito")
        return
    }

    // Si la razon es modifica texto (2) no van los montos
    //ec.logger.warn("Codref: " + codRef + ", dteExenta: " + dteExenta)
    if (codRef == "2") {
        totalNeto = 0
        totalExento = 0
    }
    totalDescuentos = detMap.totalDescuentos
} else if (tipoDte == '56') {
    // Nota de Débito Electrónica
    originalReferenciaList = referenciaList
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutOrganizacion, tipoDte)
    referenciaList = refMap.referenciaList
    dteExenta = refMap.dteExenta
    if (referenciaList == null || referenciaList.size() == 0) {
        ec.message.addError("No se puede crear Nota de Débito sin referencias")
        return
    }
    codRef = referenciaList[0].codigo

    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, fiscalTaxDocumentTypeEnumId, detailList, "DebitoItem", codRef as Integer, issuerPartyId, preciosIncluyenIva, generarXmlMontosBrutos)
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
    totalIva = detMap.totalIva
    totalExento = detMap.totalExento

    if (codRef == "2" && detalleList.size() > 1) {
        ec.message.addError("codRef = 2 && detalleList.size() = ${detalleList.size()}")
        return
    }

    if (codRef == "1") {
        referenciaNoNC = referenciaList.find { it.tipoDocumento != '61' }
        if (referenciaNoNC) {
            ec.message.addError("Una Nota de Débito de Anulación solamente puede anular una Nota de Crédito")
            return
        }
    }

    // Si la razon es modifica texto (2) no van los montos
    // Notas de débito son siempre afectas
    if (codRef == "2") {
        totalNeto = 0
        totalExento = 0
    }
    totalDescuentos = detMap.totalDescuentos
} else if (tipoDte == '52') {
    // Guía de Despacho Electrónica
    if (indTrasladoEnumId == null)
        ec.message.addError("No indTrasladoEnumId for tipoDte 52 (Guía de Despacho)")
    if (indTrasladoEnumId in ['IndTraslado-1', 'IndTraslado-9'] && !invoiceId)
        ec.message.addError("No se puede generar una Guía de Despacho que constituye venta sin un invoiceId")
    indicadorTraslado = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#SiiCode").parameters([fiscalTaxDocumentTypeEnumId: indTrasladoEnumId, enumTypeId: 'IndTraslado']).call().siiCode
    if (tipoDespachoEnumId == null)
        ec.message.addError("No tipoDespachoEnumId for tipoDte 52 (Guía de Despacho)")
    tipoDespacho = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#SiiCode").parameters([fiscalTaxDocumentTypeEnumId: tipoDespachoEnumId, enumTypeId: 'TipoDespacho']).call().siiCode

    // TODO: Si la referencia es tipo fe de erratas, Monto Item puede ser 0
    originalReferenciaList = referenciaList
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(ec, referenciaList, rutOrganizacion, tipoDte)
    referenciaList = refMap.referenciaList
    dteExenta = refMap.dteExenta
    codRef = refMap.codigo

    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, fiscalTaxDocumentTypeEnumId, detailList, "ShipmentItem", codRef as Integer, issuerPartyId, preciosIncluyenIva, generarXmlMontosBrutos)
    detalleList = detMap.detalleList
    totalNeto = detMap.totalNeto
    totalIva = detMap.totalIva
    totalExento = detMap.totalExento
    totalDescuentos = detMap.totalDescuentos
} else {
    ec.message.addError("Cálculo de totales no implementado para tipo ${tipoDte}")
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
    if (folio == null)
        return
}

descuentoORecargoGlobalList.each { dor ->
    if (dor.indicadorExento == null)
        descuentoORecargoAfecto = true
    else if (dor.indicadorExento == 1)
        descuentoORecargoAfecto = false
    else if (dor.indicadorExento == 2)
        return // No Facturable
    else
        ec.message.addError("indicadorExento en lista de descuento o recargo global debe tener valor nulo, 1 o 2")
    BigDecimal valor = null
    BigDecimal valorOrigin = null
    if (dor.tipoValor == "%")
        esPorcentaje = true
    else
        esPorcentaje = false
    try {
        ec.logger.info("procesando descuento o recargo global: ${dor}")
        valor = dor.valor as BigDecimal
        valorOrigin = dor.montoOrigin as BigDecimal
    } catch (Exception e) {
        ec.message.addError("Valor inválido para recargo o descuento: ${dor.valor}")
    }
    if (dor.tipo == 'D') {
        if (descuentoORecargoAfecto)
            if (dor.montoOrigin != null) {
                descuentoGlobalAfecto = (descuentoGlobalAfecto?:0.0) + (valorOrigin * (esPorcentaje ? (0.01 * totalNeto) : 1.0))
                descuentoGlobalAfectoClp = (descuentoGlobalAfecto?:0.0) + (valor * (esPorcentaje ? (0.01 * totalNeto) : 1.0))
            } else {
                descuentoGlobalAfecto = (descuentoGlobalAfecto?:0.0) + (valor * (esPorcentaje ? (0.01 * totalNeto) : 1.0))
            }
        else
            if (dor.montoOrigin != null) {
                descuentoGlobalExento = (descuentoGlobalExento?:0.0) + (valorOrigin * (esPorcentaje ? (0.01 * totalExento) : 1.0))
                descuentoGlobalExentoClp = (descuentoGlobalExento?:0.0) + (valor * (esPorcentaje ? (0.01 * totalExento) : 1.0))
            } else {
                descuentoGlobalExento = (descuentoGlobalExento?:0.0) + (valor * (esPorcentaje ? (0.01 * totalExento) : 1.0))
            }
    } else if (dor.tipo == 'R') {
        if (descuentoORecargoAfecto)
            if (dor.montoOrigin != null) {
                recargoGlobalAfecto = (descuentoGlobalAfecto?:0.0) + (valorOrigin * (esPorcentaje ? (0.01 * totalNeto) : 1.0))
                recargoGlobalAfectoClp = (descuentoGlobalAfecto?:0.0) + (valor * (esPorcentaje ? (0.01 * totalNeto) : 1.0))
            } else {
                recargoGlobalAfecto = (descuentoGlobalAfecto?:0.0) + (valor * (esPorcentaje ? (0.01 * totalNeto) : 1.0))
            }
        else
            if (dor.montoOrigin != null) {
                recargoGlobalExento = (descuentoGlobalExento?:0.0) + (valorOrigin * (esPorcentaje ? (0.01 * totalExento) : 1.0))
                recargoGlobalExentoClp = (descuentoGlobalExento?:0.0) + (valor * (esPorcentaje ? (0.01 * totalExento) : 1.0))
            } else {
                recargoGlobalExento = (descuentoGlobalExento?:0.0) + (valor * (esPorcentaje ? (0.01 * totalExento) : 1.0))
            }
    }
}

// Totales
if (totalNeto != null || descuentoGlobalAfecto != null || recargoGlobalAfecto != null) {
    if (totalNetoClp != null) {
        totalNetoClp = totalNetoClp - (descuentoGlobalAfectoClp?:0.0) + (recargoGlobalExentoClp?:0.0)
        montoIVARecuperable = totalIVAClp
        totalInvoice = (totalNetoClp?:0.0) + totalIVAClp + (totalExentoClp?:0.0) - (descuentoGlobalExentoClp?:0.0) + (recargoGlobalExentoClp?:0.0)
    } else {
        totalNeto = (totalNeto?:0.0) - (descuentoGlobalAfecto?:0.0) + (recargoGlobalAfecto?:0.0)
        montoIVARecuperable = totalIva
        totalInvoice = (totalNeto?:0.0) + (totalIva?:0.0) + (totalExento?:0.0) - (descuentoGlobalExento?:0.0) + (recargoGlobalExento?:0.0)
    }
} else {
    if (totalExentoClp != null)
        totalInvoice = (totalExentoClp?:0.0) - (descuentoGlobalExentoClp?:0.0) + (recargoGlobalExentoClp?:0.0)
    else
        totalInvoice = (totalExento?:0.0) - (descuentoGlobalExento?:0.0) + (recargoGlobalExento?:0.0)
}

// Chequeo de valores entre Invoice y calculados
if (invoice) {
    if (invoice.invoiceTotal.setScale(0, RoundingMode.HALF_UP) != totalInvoice.setScale(0, RoundingMode.HALF_UP)) {
        ec.message.addError("No coinciden valores totales de DTE, calculado: ${ec.l10n.formatCurrency(totalInvoice, invoice.currencyUomId)}, en invoice ${invoiceId}: ${ec.l10n.formatCurrency(invoice.invoiceTotal, invoice.currencyUomId)}")
    }
}

totalInvoice = ec.l10n.roundCurrency(totalInvoice, currencyUomId) as Integer
if (totalExento != null)
    totalExento = ec.l10n.roundCurrency(totalExento, currencyUomId) as Integer
if (totalNeto != null)
    totalNeto = ec.l10n.roundCurrency(totalNeto, currencyUomId) as Integer
if (totalIva != null)
    totalIva = ec.l10n.roundCurrency(totalIva, currencyUomId) as Integer

// Validate IVA based on whether amounts are gross or net
if (indicadorMontosBrutos != 1 && !skipIvaMismatchCalculation) {
    // When MntBruto not set, totalNeto contains net amounts and IVA is calculated from net
    // Validation: IVA should equal totalNeto * vatTaxRate
    if (Math.round(totalIva ?: 0.0) != Math.round((totalNeto ?: 0.0) * (vatTaxRate ?: 0.0)))
        ec.message.addError("Error en Iva, recibido de detalles: ${totalIva}, calculado: ${Math.round((totalNeto ?: 0.0) * (vatTaxRate ?: 0.0))}")
}
// When MntBruto=1: No validation needed - IVA was extracted per-item in prepareDetails
// The per-item extraction is the correct approach and avoids rounding differences

idDocumento = "Dte-" + ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
String tmstFirmaResp = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")

if (giroReceptor == null && fiscalTaxDocumentTypeEnumId in ['Ftdt-33','Ftdt-34','Ftdt-52','Ftdt-43', 'Ftdt-46'])
    ec.message.addError("No se define giro y es obligatorio para tipo de DTE ${fiscalTaxDocumentTypeEnumId}")

if (giroReceptor && giroReceptor.length() > 39)
    giroReceptor = giroReceptor.substring(0,39)
razonSocialReceptorTimbre = razonSocialReceptor.length() > 39? razonSocialReceptor.substring(0,39): razonSocialReceptor

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(new IndentPrinter(new PrintWriter(xmlWriter), "", false))

if (detalleList.size() == 0) {
    ec.message.addError("DTE debe tener al menos 1 ítem")
    return
}

// Timbre
String detalleIt1 = detalleList.get(0).nombreItem
if (detalleIt1.length() > 40)
    detalleIt1 = detalleIt1.substring(0, 40)

xmlBuilder.DD() {
    RE(rutOrganizacion)
    TD(tipoDte)
    F(folio)
    FE(ec.l10n.format(fechaEmision, "yyyy-MM-dd"))
    RR(rutReceptor)
    RSR(razonSocialReceptorTimbre)
    MNT(totalInvoice)
    IT1(detalleIt1)
    xmlBuilder.getMkp().yieldUnescaped(folioResult.cafFragment.replaceAll('>\\s*<', '><').trim())
    TSTED(ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss"))
}
datosTed = xmlWriter.toString()
xmlWriter.close()

if (forceIva) {
    diff = forceIva - (totalIva ?: 0)
    totalIva = forceIva
    totalInvoice += diff
}

xmlWriter = new StringWriter()
xmlBuilder = new MarkupBuilder(xmlWriter)
xmlBuilder.printer.incrementIndent()
xmlBuilder.printer.incrementIndent()
xmlBuilder.Encabezado() {
    IdDoc {
        TipoDTE(tipoDte)
        Folio(folio)
        FchEmis(ec.l10n.format(fechaEmision, "yyyy-MM-dd"))
        if (indicacionNoRebaja)
            IndNoRebaja(indicacionNoRebaja)
        if (tipoDespacho)
            TipoDespacho(tipoDespacho)
        if (tipoDte == '52')
            IndTraslado(indicadorTraslado)
        if (tipoImpresion)
            TpoImpresion(tipoImpresion)
        if (indicadorServicio)
            IndServicio(indicadorServicio)
        if (indicadorMontosBrutos)
            MntBruto(indicadorMontosBrutos)
        if (tipoTransaccionComprador)
            TpoTranCompra(tipoTransaccionCompra)
        if (tipoTransaccionVendedor)
            TpoTranVenta(tipoTransaccionVendedor)
        if (formaPago)
            FmaPago(formaPago)
        if (condicionesComercialesExportacionMap?.formaPagoExportacion)
            FmaPagoExp(condicionesComercialesExportacionMap?.formaPagoExportacion)
        if (fechaCancelacion)
            FchCancel(ec.l10n.format(fechaCancelacion, 'yyyy-MM-dd'))
        if (montoCancelacion)
            MntCancel(montoCancelacion)
        if (saldoInsoluto)
            SaldoInsol(saldoInsoluto)
        pagosList.each { pago ->
            MntPagos {
                FchPago(ec.l10n.format(pago.fechaPago, 'yyyy-MM-dd'))
                MntPago(pago.monto)
                if (pago.glosa)
                    GlosaPagos(pago.glosa)
            }
        }
        if (periodoDesde)
            PeriodoDesde(ec.l10n.format(periodoDesde, 'yyyy-MM-dd'))
        if (periodoHasta)
            PeriodoHasta(ec.l10n.format(periodoHasta, 'yyyy-MM-dd'))
        if (medioPago)
            MedioPago(medioPago)
        if (tipoCuentaPago)
            TpoCtaPago(tipoCuentaPago)
        if (numeroCuentaPago)
            NumCtaPago(numeroCuentaPago)
        if (bancoPago)
            BcoPago(bancoPago)
        if (terminoPagoCodigo)
            TermPagoCdg(terminoPagoCodigo)
        if (terminoPagoGlosa)
            TermPagoGlosa(terminoPagoGlosa)
        if (terminoPagoDias)
            TermPagoDias(terminoPagoDias)
        if (fechaVencimiento)
            FchVenc(ec.l10n.format(fechaVencimiento, "yyyy-MM-dd"))
        if (tipoFacturaEspecial)
            TipoFactEsp(tipoFacturaEspecial)
    }
    Emisor {
        RUTEmisor(rutOrganizacion)
        RznSoc(razonSocialOrganizacion)
        GiroEmis(giroEmisor)
        telefonosEmisor.each { telefono ->
            if (telefono)
                Telefono(telefono)
        }
        if (mailContacto)
            CorreoEmisor(mailContacto)
        codigosActividadEconomica.split(',').each { codigoActividad ->
            Acteco(codigoActividad)
        }
        if (guiaExportacionMap)
            GuiaExport {
                if (guiaExportacionMap.codigoTraslado)
                    CdgTraslado(guiaExportacionMap.codigoTraslado)
                if (guiaExportacionMap.folioAutorizacion)
                    FolioAut(guiaExportacionMap.folioAutorizacion)
                if (guiaExportacionMap.fechaAutorizacion)
                    FchAut(l10n.format(guiaExportacionMap.fechaAutorizacion, 'yyyy-MM-dd'))
            }
        if (sucursal)
            Sucursal(sucursal)
        if (codigoSucursalSii)
            CdgSIISucur(codigoSucursalSii)
        if (codigoAdicionalSucursal)
            CdgAdicSucur(codigoAdicionalSucursal)
        if (direccionOrigen)
            DirOrigen(direccionOrigen)
        if (comunaOrigen)
            CmnaOrigen(comunaOrigen)
        if (ciudadOrigen)
            CiudadOrigen(ciudadOrigen)
        if (codigoVendedor)
            CdgVendedor(codigoVendedor)
        if (identificadorAdicionalEmisor)
            IdAdicEmisor(identificadorAdicionalEmisor)
    }
    if (rutMandante)
        RUTMandante(rutMandante)
    Receptor {
        RUTRecep(rutReceptor)
        if (codigoInternoReceptor)
            CdgIntRecep(codigoInternoReceptor)
        RznSocRecep(razonSocialReceptor)
        if (receptorExtranjero)
            Extranjero{
                if (receptorExtranjero.idValue)
                    NumId(receptorExtranjero.idValue)
                if (receptorExtranjero.nacionalidad)
                    Nacionalidad(receptorExtranjero.nacionalidad)
                if (receptorExtranjero.tipoDocumento)
                    TipoDocID(receptorExtranjero.tipoDocumento)
                if (receptorExtranjero.idAdicional)
                    IdAdicRecep(receptorExtranjero.idAdicional)
            }
        if (giroReceptor && giroReceptor.replaceAll('\s', '') != '')
            GiroRecep(giroReceptor)
        if (contactoReceptor)
            Contacto(contactoReceptor)
        if (correoReceptor)
            CorreoRecep(correoReceptor)
        if (direccionReceptor)
            DirRecep(direccionReceptor)
        if (comunaReceptor)
            CmnaRecep(comunaReceptor)
        if (ciudadReceptor)
            CiudadRecep(ciudadReceptor)
        if (direccionPostalReceptor)
            DirPostal(direccionPostalReceptor)
        if (comunaPostalReceptor)
            CmnaPostal(comunaPostalReceptor)
        if (ciudadPostalReceptor)
            CiudadPostal(ciudadPostalReceptor)
    }
    if (rutSolicitante)
        RUTSolicita(rutSolicitante)
    if (transporteMap)
        Transporte{
            if (transporteMap.patente)
                Patente(transporteMap.patente)
            if (transporteMap.rutTransportista)
                RUTTrans(transporteMap.rutTransportista)
            if (transporteMap.rutChofer || transporteMap.nombreChofer)
                Chofer {
                    if (transporteMap.rutChofer)
                        RUTChofer(transporteMap.rutChofer)
                    if (chofer.nombreChofer)
                        NombreChofer(transporteMap.nombreChofer)
                }
            if (transporteMap.direccionDestino)
                DirDest(transporteMap.direccionDestino)
            if (transporteMap.comunaDestino)
                CmnaDest(transporteMap.comunaDestino)
            if (transporteMap.ciudadDestino)
                CiudadDest(transporteMap.ciudadDestino)
        }
    if (aduanaMap)
        Aduana {
            if (aduanaMap.codigoModalidadVenta)
                CodModVenta(aduanaMap.codigoModalidadVenta)
            if (aduanaMap.codigoClausulaVenta)
                CodClauVenta(aduanaMap.codigoClausulaVenta)
            if (aduanaMap.totalClausulaVenta)
                TotClauVenta(aduanaMap.totalClausulaVenta)
            if (aduanaMap.codigoViaTransporte)
                CodViaTransp(aduanaMap.codigoViaTransporte)
            if (aduanaMap.nombreMedioTransporte)
                NombreTransp(aduanaMap.nombreMedioTransporte)
            if (aduanaMap.rutCompaniaTransporte)
                RUTCiaTransp(aduanaMap.rutCompaniaTransporte)
            if (aduanaMap.nombreCompaniaTransporte)
                NomCiaTransp(aduanaMap.nombreCompaniaTransporte)
            if (aduanaMap.idAdicionalCompaniaTransporte)
                IdAdicTransp(aduanaMap.idAdicionalCompaniaTransporte)
            if (aduanaMap.booking)
                Booking(aduanaMap.booking)
            if (aduanaMap.operador)
                Operador(aduanaMap.operador)
            if (aduanaMap.codigoPuertoEmbarque)
                CodPtoEmbarque(aduanaMap.codigoPuertoEmbarque)
            if (aduanaMap.idAdicionalPuertoEmbarque)
                IdAdicPtoEmb(aduanaMap.idAdicionalPuertoEmbarque)
            if (aduanaMap.codigoPuertoDesembarque)
                CodPtoDesemb(aduanaMap.codigoPuertoDesembarque)
            if (aduanaMap.idAdicionalPuertoDesembarque)
                IdAdicPtoDesemb(aduanaMap.idAdicionalPuertoDesembarque)
            if (aduanaMap.tara)
                Tara(aduanaMap.tara)
            if (aduanaMap.unidadMedidaTara)
                CodUnidMedTara(aduanaMap.unidadMedidaTara)
            if (aduanaMap.pesoBruto)
                PesoBruto(aduanaMap.pesoBruto)
            if (aduanaMap.unidadMedidaPesoBruto)
                CodUnidPesoBruto(aduanaMap.unidadMedidaPesoBruto)
            if (aduanaMap.pesoNeto)
                PesoNeto(aduanaMap.pesoNeto)
            if (aduanaMap.unidadMedidaPesoNeto)
                CodUnidPesoNeto(aduanaMap.unidadMedidaPesoNeto)
            if (aduanaMap.totalItems)
                TotItems(aduanaMap.totalItems)
            if (aduanaMap.totalBultos)
                TotBultos(aduanaMap.totalBultos)
            aduanaTipoBultos.each { tipoBulto ->
                TipoBultos {
                    if (tipoBulto.codigoTipo)
                        CodTpoBultos(tipoBulto.codigoTipo)
                    if (tipoBulto.cantidadBultos)
                        CantBultos(tipoBulto.cantidadBultos)
                    if (tipoBulto.marcas)
                        Marcas(tipoBulto.marcas)
                    if (tipoBulto.idContainer)
                        IdContainer(tipoBulto.idContainer)
                    if (tipoBulto.sello)
                        Sello(tipoBulto.sello)
                    if (tipoBulto.emisorSello)
                        EmisorSello(tipoBulto.emisorSello)
                }
            }
            if (aduanaMap.montoFlete)
                MntFlete(aduanaMap.montoFlete)
            if (aduanaMap.montoSeguro)
                MntSeguro(aduanaMap.montoSeguro)
            if (aduanaMap.codigoPaisReceptor)
                CodPaisRecep(aduanaMap.codigoPaisReceptor)
            if (aduanaMap.codigoPaisDestino)
                CodPaisDestin(aduanaMap.codigoPaisDestino)
        }
    Totales {
        if (tipoMoneda)
            TpoMoneda(tipoMoneda)
        if (totalNeto != null)
            MntNeto(totalNeto)
        if (totalExento != null)
            MntExe(totalExento)
        if (montoBase)
            MntBase(montoBase)
        if (montoMargenComercializacion)
            MntMargenCom(montoMargenComercializacion)
        if (tasaIva)
            TasaIVA(tasaIva)
        if (totalIva)
            IVA(totalIva)
        if (ivaPropio)
            IVAProp(ivaPropio)
        if (ivaTerceros)
            IVATerc(ivaTerceros)
        impuestoRetenido.each { impRet ->
            ImptoReten{
                TipoImp(impRet.tipo)
                if (impRet.tasa)
                    TasaImp(impRet.tasa)
                MontoImp(impRet.monto)
            }
        }
        if (ivaNoRetenido)
            IVANoRet(ivaNoRetenido)
        if (creditoEmpresasConstructoras)
            CredEC(creditoEmpresasConstructoras)
        if (garantiaEnvases)
            GrntDep(garantiaEnvases)
        if (totalesComisiones)
            Comisiones {
                if (totalesComisiones.valorNeto)
                    ValComNeto(totalesComisiones.valorNeto)
                if (totalesComisiones.valorExento)
                    ValComExe(totalesComisiones.valorExento)
                if (totalesComisiones.valorIva)
                    ValComIVA(totalesComisiones.valorIva)
            }
        MntTotal(totalInvoice)
        if (montoNoFacturable)
            MontoNF(montoNoFacturable)
        if (montoPeriodo)
            MontoPeriodo(montoPeriodo)
        if (saldoAnterior)
            SaldoAnterior(saldoAnterior)
        if (valorPagar)
            VlrPagar(valorPagar)
    }
    if (totalesOtraMonedaMap)
        OtraMoneda{
            TpoMoneda(totalesOtraMonedaMap.codigo)
            if (totalesOtraMonedaMap.tipoCambio)
                TpoCambio(totalesOtraMonedaMap.tipoCambio)
            if (totalesOtraMonedaMap.montoNeto)
                MntNetoOtrMnda(totalesOtraMonedaMap.montoNeto)
            if (totalesOtraMonedaMap.montoExento)
                MntExeOtrMnda(totalesOtraMonedaMap.montoExento)
            if (totalesOtraMonedaMap.montoFaenamientoCarne)
                MntFaeCarneOtrMnda(totalesOtraMonedaMap.montoFaenamientoCarne)
            if (totalesOtraMonedaMap.margenComercializacion)
                MntMargComOtrMnda(totalesOtraMonedaMap.margenComercializacion)
            if (totalesOtraMonedaMap.iva)
                IVAOtrMnda(totalesOtraMonedaMap.iva)
            totalesOtraMonedaMap.impuestosRentenciones.each { ret ->
                ImpRetOtrMnda {
                    TipoImpOtrMnda(ret.tipo)
                    if (ret.tasa)
                        TasaImpOtrMnda(ret.tasa)
                    VlrImpOtrMnda(ret.valor)
                }
            }
            if (totalesOtraMonedaMap.ivaNoRetenido)
                IVANoRetOtrMnda(totalesOtraMonedaMap.ivaNoRetenido)
            MntTotOtrMnda(totalesOtraMonedaMap.montoTotal)
        }
}
xmlEncabezado = xmlWriter.toString()
xmlWriter.close()

if (detalleList) {
    xmlWriter = new StringWriter()
    xmlBuilder = new MarkupBuilder(xmlWriter)
    xmlBuilder.printer.incrementIndent()
    xmlBuilder.printer.incrementIndent()
    xmlBuilder.DetallesContainer {
        detalleList.each { detalle ->
            Detalle {
                NroLinDet(detalle.numeroLinea)
                detalle.codigoItem.each { codigoItem ->
                    CdgItem {
                        TpoCodigo(codigoItem.tipoCodigo)
                        VlrCodigo(codigoItem.valorCodigo)
                    }
                }
                if (detalle.tipoDocumentoLiquidacion)
                    TpoDocLiq(detalle.tipoDocumentoLiquidacion)
                if (detalle.indicadorExento)
                    IndExe(detalle.indicadorExento)
                if (detalle.retenedor)
                    Retenedor {
                        IndAgente(detalle.retenedor.indicadorAgente)
                        if (detalle.retenedor.montoBase)
                            MntBaseFaena(detalle.retenedor.montoBase)
                        if (detalle.retenedor.montoMargenComercializacion)
                            MntMargComer(detalle.retenedor.montoMargenComercializacion)
                        if (detalle.retenedor.precioUnitarioConsumidorFinal)
                            PrcConsFinal(detalle.retenedor.precioUnitarioConsumidorFinal)
                    }
                NmbItem(detalle.nombreItem)
                if (detalle.descripcionItem)
                    DscItem(detalle.descripcionItem)
                if (detalle.cantidadUnidadReferencia)
                    QtyRef(detalle.cantidadUnidadReferencia)
                if (detalle.unidadMedidaReferencia)
                    UnmdRef(detalle.unidadMedidaReferencia)
                if (detalle.precioUnidadReferencia)
                    PrcRef(detalle.precioUnidadReferencia)
                if (detalle.quantity != null)
                    if (tipoDte == '43')
                        QtyItem(detalle.cantidadDocumentosLiquidados)
                    else
                        QtyItem(detalle.quantity)
                detalle.subcantidad.each { subcant ->
                    SubCantidad {
                        SubQty(subcant.quantity)
                        SubCod(subcant.codigo)
                        if (subcant.tipoCodigo)
                            TipCodSubQty(subcant.tipoCodigo)
                    }
                }
                if (detalle.fechaElaboracion)
                    FchElabor(detalle.fechaElaboracion)
                if (detalle.fechaVencimiento)
                    FchVencim(detalle.fechaVencimiento)
                if (detalle.uom)
                    UnmdItem(detalle.uom)
                if (detalle.priceItem != null && detalle.priceItem != 0 && detalle.priceItem != "0")
                    PrcItem((detalle.priceItem as BigDecimal).setScale(6, java.math.RoundingMode.HALF_UP))
                if (detalle.otraMoneda)
                    OtrMnda {
                        PrcOtrMon(detalle.otraMoneda.precio)
                        Moneda(detalle.otraMoneda.moneda)
                        if (detalle.otraMoneda.factorConversion)
                            FctConv(detalle.otraMoneda.factorConversion)
                        if (detalle.otraMoneda.descuento)
                            DctoOtrMnda(detalle.otraMoneda.descuento)
                        if (detalle.otraMoneda.recargo)
                            RecargoOtrMnda(detalle.otraMoneda.recargo)
                        MontoItemOtrMnda(detalle.otraMoneda.monto)
                    }
                if (detalle.porcentajeDescuento)
                    DescuentoPct(detalle.porcentajeDescuento)
                if (detalle.montoDescuento)
                    DescuentoMonto(detalle.montoDescuento.setScale(0, java.math.RoundingMode.HALF_UP))
                detalle.subDescuento.each { sd ->
                    SubDscto {
                        TipoDscto(sd.tipo)
                        ValorDscto(sd.valor)
                    }
                }
                if (detalle.recargoPorcentaje)
                    RecargoPct(detalle.recargoPorcentaje)
                if (detalle.recargoMonto)
                    RecargoMonto(detalle.recargoMonto)
                detalle.subRecargo.each { sr ->
                    SubRecargo {
                        TipoRecargo(sr.tipo)
                        ValorRecargo(sr.valor)
                    }
                }
                detalle.codigoImpuestoAdicional.each { codigoImpuestoAdicional ->
                    CodImpAdic(codigoImpuestoAdicional)
                }
                MontoItem((detalle.montoItem as BigDecimal).setScale(0, java.math.RoundingMode.HALF_UP))
            }
        }
    }
    xmlDetalles = xmlWriter.toString().replaceFirst("\\s*<DetallesContainer>", "").replaceFirst("\\s*</DetallesContainer>", "")
    xmlWriter.close()
} else
    detalles = ""

if (subTotales) {
    xmlWriter = new StringWriter()
    xmlBuilder = new MarkupBuilder(xmlWriter)
    xmlBuilder.printer.incrementIndent()
    xmlBuilder.printer.incrementIndent()
    xmlBuilder.SubtotalesContainer {
        subTototales.each {
            SubTotInfo { subTot ->
                NroSTI(subTot.seqNum)
                GlosaSTI(subTot.glosa)
                if (subTot.orden)
                    OrdenSTI(subTot.orden)
                if (subTot.valorNeto)
                    SubTotNetoSTI(subTot.valorNeto)
                if (subTot.valorIva)
                    SubTotIVASTI(subTot.valorIva)
                if (subTot.impustosAdicionales)
                    SubTotAdicSTI(subTot.impustosAdicionales)
                if (subTot.valorExento)
                    SubTotExeSTI(subTot.valorExento)
                if (subTot.total)
                    ValSubtotSTI(subTot.total)
                subTot.numeroLineasDetalle.each { nroLinDet ->
                    LineasDeta(nroLinDet)
                }
            }
        }
    }
    xmlSubTotales = xmlWriter.toString().replaceFirst("\\s*<SubtotalesContainer>", "").replaceFirst("\\s*</SubtotalesContainer>", "")
    xmlWriter.close()
} else
    xmlSubTotales = ""

if (descuentoORecargoGlobalList) {
    xmlWriter = new StringWriter()
    xmlBuilder = new MarkupBuilder(xmlWriter)
    xmlBuilder.printer.incrementIndent()
    xmlBuilder.printer.incrementIndent()
    xmlBuilder.DescuentoORecargoGlobalContainer {
        descuentoORecargoGlobalList.each { discountOrChargeMap ->
            DscRcgGlobal{
                NroLinDR(discountOrChargeMap.numeroLinea)
                TpoMov(discountOrChargeMap.tipo)
                GlosaDR(discountOrChargeMap.glosa)
                TpoValor(discountOrChargeMap.tipoValor)
                ValorDR(discountOrChargeMap.valor)
                if (discountOrChargeMap.valorOtraMoneda)
                    ValorDROtrMnda(discountOrChargeMap.valorOtraMoneda)
                if (discountOrChargeMap.indicadorExento)
                    IndExeDR(discountOrChargeMap.indicadorExento)
            }
        }
    }
    xmlDescuentosORecargosGlobales = xmlWriter.toString().replaceFirst("\\s*<DescuentoORecargoGlobalContainer>", "").replaceFirst("\\s*</DescuentoORecargoGlobalContainer>", "")
    xmlWriter.close()
} else
    xmlDescuentosORecargosGlobales = ""

if (referenciaList) {
    xmlWriter = new StringWriter()
    xmlBuilder = new MarkupBuilder(xmlWriter)
    xmlBuilder.printer.incrementIndent()
    xmlBuilder.printer.incrementIndent()
    xmlBuilder.ReferenciasContainer {
        referenciaList.each { referencia ->
            Referencia {
                NroLinRef(referencia.numeroLinea)
                TpoDocRef(referencia.tipoDocumento)
                if (referencia.indicadorGlobal)
                    IndGlobal('1')
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
    }
    xmlReferencias = xmlWriter.toString().replaceFirst("\\s*<ReferenciasContainer>", "").replaceFirst("\\s*</ReferenciasContainer>", "")
    xmlWriter.close()
} else
    xmlReferencias = ""

if (comisionesList) {
    xmlWriter = new StringWriter()
    xmlBuilder = new MarkupBuilder(xmlWriter)
    xmlBuilder.printer.incrementIndent()
    xmlBuilder.printer.incrementIndent()
    xmlBuilder.ComisionesContainer {
        comisionesList.each { comision ->
            Comisiones {
                NroLinCom(comision.seqNum)
                TipoMovim(comision.tipoMovimiento)
                Glosa(comision.glosa)
                if (comision.tasa)
                    TasaComision(comision.tasa)
                if (comision.valorComisionNeto)
                    ValComNeto(comision.valorComisionNeto)
                if (comision.valorComisionExento)
                    ValComExe(comision.valorComisionExento)
                if (comision.valorComisionIva)
                    ValComIva(comision.valorComisionIva)
            }
        }
    }
    xmlComisiones = xmlWriter.toString().replaceFirst("\\s*<ComisionesContainer>", "").replaceFirst("\\s*</ComisionesContainer>", "")
    xmlWriter.close()
} else
    xmlComisiones = ""

xmlWriter = new StringWriter()
xmlBuilder = new MarkupBuilder(xmlWriter)

String schemaLocation = 'http://www.sii.cl/SiiDte DTE_v10.xsd'
if (rutReceptor == '61603000-0') {
    // Fonasa no soporta recibir el schemaLocation dentro del XML de intercambio (Envío DTE)
    dteAttributesMap = [xmlns: 'http://www.sii.cl/SiiDte', version: '1.0']
} else
    dteAttributesMap = [xmlns: 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version: '1.0', 'xsi:schemaLocation': schemaLocation]

if (tipoDte in ['33', '34', '46', '52', '56', '61']) {
    descripcionTipo = "${tipoDte}"
    signingTag = "Documento"
    notAllowedFieldList = ['codigoAdicionalSucursal', 'tipoMoneda', 'receptorExtranjero.idAdicional']
    requiredFieldList = []
    if (tipoDte == '52') {
        descripcionTipo = "Guía de Despacho"
        requiredFieldList = ['indicadorTraslado']
    }
    notAllowedFieldListDetalle = ['tipoDocumentoLiquidacion']
    notAllowedFieldListDetalleSubcantidad = ['tipoCodigo']
} else if (tipoDte in ['110', '111', '112']) {
    descripcionTipo = "Facturas de Exportación"
    notAllowedFieldList = ['tipoDespacho', 'tipoImpresion', 'indicadorMontosBrutos', 'tipoTransaccionComprador', 'tipoTransaccionVendedor', 'tipoFacturaEspecial', 'rutMandante',
                           'rutSolicitante', 'totalNeto', 'montoBase', 'montoMargenComercializacion', 'tasaIva', 'totalIva', 'ivaPropio', 'ivaTerceros', 'impuestoRetenido',
                           'ivaNoRetenido', 'creditoEmpresasConstructoras', 'garantiaEnvases', 'comisionesList', 'montoNoFacturable', 'montoPeriodo', 'saldoAnterior', 'valorPagar',
                           'totalesOtraMonedaMap.montoNeto', 'totalesOtraMonedaMap.montoFaenamientoCarne', 'totalesOtraMonedaMap.margenComercializacion', 'totalesOtraMonedaMap.iva', 'totalesOtraMonedaMap.impuestosRentenciones',
                           'totalesOtraMonedaMap.ivaNoRetenido', 'receptorExtranjero.tipoDocumento']
    notAllowedFieldListDetalle = ['retenedor', 'codigoImpuestoAdicional', 'tipoDocumentoLiquidacion']
    requiredFieldList = ['tipoMoneda']
    signingTag = "Exportaciones"
} else if (tipoDte in ['43']) {
    notAllowedFieldList = ['indicacionNoRebaja', 'tipoDespacho', 'tipoImpresion', 'condicionesComercialesExportacionMap', 'tipoFacturaEspecial', 'guiaExportacionMap', 'codigoAdicionalSucursal',
                           'identificadorAdicionalEmisor', 'rutMandante', 'rutSolicitante', 'transporteMap', 'aduanaMap', 'tipoMoneda', 'montoBase', 'montoMargenComercializacion',
                           'ivaNoRetenido', 'creditoEmpresasConstructoras', 'garantiaEnvases', 'montoNoFacturable', 'totalesOtraMonedaMap', 'receptorExtranjero.tipoDocumento']
    notAllowedFieldListDetalle = ['retenedor', 'otraMoneda', 'porcentajeDescuento', 'montoDescuento', 'subDescuento', 'recargoPorcentaje', 'recargoMonto', 'subRecargo']
    notAllowedFieldListReferencia = ['rutOtro']
    notAllowedFieldListDetalleSubcantidad = ['tipoCodigo']
    signingTag = "Liquidacion"
} else {
    ec.message.addError("Unable to generate DTE of type ${tipoDte}")
}
notAllowedFieldList.each { fieldName ->
    if (ec.context[fieldName] != null)
        ec.message.addError("${descripcionTipo} no puede tener campo ${fieldName}")
}
requiredFieldList.each { fieldName ->
    if (ec.context[fieldName] == null)
        ec.message.addError("${descripcionTipo} debe tener campo ${fieldName}")
}
detalleList.each { detalle ->
    notAllowedFieldListDetalle.each { fieldName ->
        if (detalle[fieldName != null])
            ec.message.addError("${descripcionTipo} debe tener campo ${fieldName} en detalles")
    }
    detalle.subcantidad.each { subcantidad ->
        notAllowedFieldListDetalleSubcantidad.each { fieldName ->
            if (subcantidad[fieldName != null])
                ec.message.addError("${descripcionTipo} debe tener campo ${fieldName} en detalles.subcantidad")
        }
    }
}
referenciaList.each { referencia ->
    notAllowedFieldListReferencia.each { fieldName ->
        if (referencia[fieldName != null])
            ec.message.addError("${descripcionTipo} debe tener campo ${fieldName} en referencia")
    }
}
notAllowedFieldListDetalle

if (ec.message.hasError())
    return

xmlBuilder.DTE(dteAttributesMap) {
    if (tipoDte in ['33', '34', '46', '52', '56', '61'])
        Documento(ID: idDocumento) {
            xmlBuilder.getMkp().yieldUnescaped(xmlEncabezado)
            xmlBuilder.getMkp().yieldUnescaped(xmlDetalles)
            xmlBuilder.getMkp().yieldUnescaped(xmlSubTotales)
            xmlBuilder.getMkp().yieldUnescaped(xmlDescuentosORecargosGlobales)
            xmlBuilder.getMkp().yieldUnescaped(xmlReferencias)
            xmlBuilder.getMkp().yieldUnescaped(xmlComisiones)
            TED (version:"1.0") {
                xmlBuilder.getMkp().yieldUnescaped(datosTed)
                FRMT(algoritmo:"SHA1withRSA", MoquiDTEUtils.firmaTimbre(datosTed, folioResult.privateKey))
            }
            TmstFirma(ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss"))
        }
    else if (tipoDte in ['110', '111', '112'])
        Exportaciones(ID: idDocumento) {
            xmlBuilder.getMkp().yieldUnescaped(xmlEncabezado)
            xmlBuilder.getMkp().yieldUnescaped(xmlDetalles)
            xmlBuilder.getMkp().yieldUnescaped(xmlSubTotales)
            xmlBuilder.getMkp().yieldUnescaped(xmlDescuentosORecargosGlobales)
            xmlBuilder.getMkp().yieldUnescaped(xmlReferencias)
            TED (version:"1.0") {
                xmlBuilder.getMkp().yieldUnescaped(datosTed)
                FRMT(algoritmo:"SHA1withRSA", MoquiDTEUtils.firmaTimbre(datosTed, folioResult.privateKey))
            }
            TmstFirma(ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss"))
        }
    else if (tipoDte == '43')
        Liquidacion(ID: idDocumento) {
            xmlBuilder.getMkp().yieldUnescaped(xmlEncabezado)
            xmlBuilder.getMkp().yieldUnescaped(xmlDetalles)
            xmlBuilder.getMkp().yieldUnescaped(xmlSubTotales)
            xmlBuilder.getMkp().yieldUnescaped(xmlReferencias)
            xmlBuilder.getMkp().yieldUnescaped(xmlComisiones)
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
try {
    certificate.checkValidity()
} catch (CertificateExpiredException e) {
    ec.message.addError("Certificado expirado, no se puede emitir DTE")
} catch (CertificateNotYetValidException e) {
    ec.message.addError("Certificado aún no válido, no se puede emitir DTE")
}
if (ec.message.hasError()) return
try {
    certificate.checkValidity(fechaEmision)
} catch (CertificateExpiredException e) {
    ec.message.addError("Certificado expirado en fecha de emisión, no se puede emitir DTE")
} catch (CertificateNotYetValidException e) {
    ec.message.addError("Certificado aún no válido en fecha de emisión, no se puede emitir DTE")
}
if (ec.message.hasError()) return
byte[] facturaXml = MoquiDTEUtils.sign(doc2, uri, pkey, certificate, uri, signingTag)

try {
    MoquiDTEUtils.validateDocumentSii(ec, facturaXml, schemaLocation)
} catch (Exception e) {
    ec.logger.error("Failed generated DTE validation", e)
    ec.logger.warn("XML:\n${facturaXmlString}")
    ec.message.addError("Failed generated DTE validation: ${e.message}")
}

doc2 = MoquiDTEUtils.parseDocument(facturaXml)
if (!MoquiDTEUtils.verifySignature(doc2, "/sii:DTE/sii:${signingTag}", "/sii:DTE/sii:${signingTag}/sii:TmstFirma/text()")) {
    ec.message.addError("Error al generar DTE tipo ${tipoDte} (${dteTypeDescription}) folio ${folio}: firma inválida")
}

if (ec.message.hasError()) {
    ec.logger.info(facturaXmlString);
    return
}
ec.logger.info(facturaXmlString);

ec.logger.info("DTE tipo ${tipoDte} (${dteTypeDescription}) folio ${folio} generada OK")

if (!draft) {
    // Creación de registro en FiscalTaxDocument -->
    dteEv = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, fiscalTaxDocumentNumber:folio, issuerPartyId:issuerPartyId]).one()

    dteEv.receiverPartyId = receiverPartyId
    dteEv.receiverPartyIdTypeEnumId = "PtidNationalTaxId"
    dteEv.receiverPartyIdValue = rutReceptor.trim()
    dteEv.statusId = "Ftd-Issued"
    dteEv.issuedByUserId = ec.user.userId
    dteEv.sentAuthStatusId = "Ftd-NotSentAuth"
    dteEv.sentRecStatusId = "Ftd-NotSentRec"
    dteEv.invoiceId = invoiceId
    dteEv.shipmentId = shipmentId
    Timestamp ts = new Timestamp(fechaEmision.time)
    dteEv.date = ts
    dteEv.formaPagoEnumId = formaPagoEnumId
    dteEv.update()

    if (tipoDte == '52') {
        ec.service.sync().name("store#mchile.dte.GuiaDespacho").parameters([fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, indTrasladoEnumId:indTrasladoEnumId]).call()
    }

    xmlContentLocation = "dbresource://moit/erp/dte/${rutOrganizacion}/DTE-${tipoDte}-${folio}.xml"
    pdfContentLocation = "dbresource://moit/erp/dte/${rutOrganizacion}/DTE-${tipoDte}-${folio}.pdf"

    // Creacion de registros en FiscalTaxDocumentContent
    createMapBase = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, contentDte:ts]
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:xmlContentLocation]).call())
} else {
    xmlContentFile = File.createTempFile("draftDteXml", "pdf")
    xmlContentLocation = xmlContentFile.getAbsolutePath()
}

if (ec.message.hasError()) return

ec.resource.getLocationReference(xmlContentLocation).putBytes(facturaXml)
pdfBytes = ec.service.sync().name("mchile.sii.dte.DteContentServices.generate#Pdf").parameters(
        [xmlLocation:xmlContentLocation, templatePartyId:issuerPartyId, invoiceMessage:invoiceMessage, draft:draft, formatoPdf:formatoPdf]).call().pdfBytes

if (draft) {
    previewPdf = pdfBytes
    xmlContentFile.delete()
} else {
    ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:pdfContentLocation]).call()
    ec.resource.getLocationReference(pdfContentLocation).putBytes(pdfBytes)

    if ((fiscalTaxDocumentTypeEnumId as String) in dteConstituyeVentaTypeList) {
        pdfCedibleBytes = ec.service.sync().name("mchile.sii.dte.DteContentServices.generate#Pdf").parameters(
                [xmlLocation:xmlContentLocation, templatePartyId:issuerPartyId, invoiceMessage:invoiceMessage, draft:draft, formatoPdf:formatoPdf, cedible:true]).call().pdfBytes
        pdfCedibleContentLocation = "dbresource://moit/erp/dte/${rutOrganizacion}/DTE-${tipoDte}-${folio}-cedible.pdf"
        ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-PdfCedible', contentLocation:pdfCedibleContentLocation]).call()
        ec.resource.getLocationReference(pdfCedibleContentLocation).putBytes(pdfCedibleBytes)
    }

    // Creación de registro en FiscalTaxDocumentAttributes
    createMap = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, amount:totalInvoice, fechaEmision:fechaEmision, fechaVencimiento:fechaVencimiento, anulaBoleta:anulaBoleta, folioAnulaBoleta:folioAnulaBoleta, montoNeto:totalNeto, tasaImpuesto:19,
                 montoExento:totalExento, montoIVARecuperable:montoIVARecuperable, razonSocialEmisor:razonSocialOrganizacion, razonSocialReceptor:razonSocialReceptor]
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(createMap).call())
    fiscalTaxDocumentId = dteEv.fiscalTaxDocumentId

    // Creación de referencias en BD
    Integer nroLinea = 0
    originalReferenciaList.each { referencia ->
        nroLinea++
        tipoEnumEv = ec.entity.find("moqui.basic.Enumeration").condition("enumId", referencia.fiscalTaxDocumentTypeEnumId).one()
        esTipoTributario = (tipoEnumEv?.parentEnumId in ['Ftdt-DT','Ftdt-DTE'])
        if (tipoEnumEv)
            tipoDocumento = tipoEnumEv.enumCode
        else
            tipoDocumento = referencia.tipoDocumento
        if (referencia.rutEmisorFolio) {
            rutEmisorFolio = referencia.rutEmisorFolio
        } else if (esTipoTributario) {
            // Mismo rut del emisor del presente documento
            rutEmisorFolio = rutEmisor
        } else {
            rutEmisorFolio = null
        }
        ec.service.sync().name("create#mchile.dte.ReferenciaDte")
                .parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, referenciaTypeEnumId:'RefDteTypeFiscalTaxDocument', fiscalTaxDocumentTypeEnumId:referencia.fiscalTaxDocumentTypeEnumId,
                             folio:referencia.folio, fecha: referencia.fecha, codigoReferenciaEnumId:referencia.codigoReferenciaEnumId, razonReferencia:referencia.razonReferencia,
                             rutEmisorFolio:rutEmisorFolio, nroLinea:nroLinea, tipoDocumento:tipoDocumento]).call()
    }
    ec.service.sync().name("mchile.sii.dte.DteModificationRegistrationServices.check#DteModificationReferences").parameter("fiscalTaxDocumentId", fiscalTaxDocumentId).call()
}

if (importInvoiceFromXml) {
    outMap = ec.service.sync().name("mchile.sii.dte.DteLoadServices.load#DteFromDom").parameters([domNode:doc2, organizationPartyIdAsOwnerWhenCreating:issuerPartyId, sendResponse: false,
                                                                                                  createUnknownIssuer:false, requireIssuerInternalOrg:true, createUnknownReceiver:true, requireReceiverInternalOrg:false]).call()
    outMap.errorMessages.each { errorMessage ->
        ec.message.addError(errorMessage)
    }
}

return
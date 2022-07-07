import org.moqui.context.ExecutionContext
import org.w3c.dom.Document

import java.math.RoundingMode
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import cl.moit.dte.MoquiDTEUtils

ExecutionContext ec = context.ec

boolean processDocument = true

if (domNode == null) {
    ec.message.addError("No domNode present")
    return
}

groovy.util.Node documento = MoquiDTEUtils.dom2GroovyNode(domNode)

errorMessages = []
discrepancyMessages = []
warningMessages = []
internalErrors = []
Integer invoiceItemCount = 0

byte[] dteXml = MoquiDTEUtils.getRawXML(domNode, true)
Document doc2 = MoquiDTEUtils.parseDocument(dteXml)
namespace = MoquiDTEUtils.getNamespace(domNode)
boolean verified = false
if (namespace == "http://www.sii.cl/SiiDte") {
    ec.logger.info("Namespace is SII")
    documentPath = "/sii:DTE/sii:Documento"
    try {verified = MoquiDTEUtils.verifySignature(doc2, documentPath, null)} catch (Exception e) {
        ec.logger.error("Verifying signature: ${e.toString()}")
    }
    if (!verified) {
        ec.logger.info("Verifying without namespace")
        dteXml = new String(MoquiDTEUtils.getRawXML(doc2, true), "ISO-8859-1").replaceAll(" xmlns=\"http://www.sii.cl/SiiDte\"", "").getBytes("ISO-8859-1")
        doc2 = MoquiDTEUtils.parseDocument(dteXml)
        documentPath = "/DTE/Documento"
        try {verified = MoquiDTEUtils.verifySignature(doc2, documentPath, null)} catch (Exception e) {
            ec.logger.error("Verifying signature: ${e.toString()}")
        }
    }
} else {
    ec.logger.info("No namespace")
    documentPath = "/DTE/Documento"
    try {verified = MoquiDTEUtils.verifySignature(doc2, documentPath, null)} catch (Exception e) {
        ec.logger.error("Verifying signature: ${e.toString()}")
    }
    if (!verified) {
        ec.logger.info("Verifying with namespace")
        new cl.moit.dte.XmlNamespaceTranslator().addTranslation(null, "http://www.sii.cl/SiiDte").addTranslation("", "http://www.sii.cl/SiiDte").translateNamespaces(doc2)
        dteXml = MoquiDTEUtils.getRawXML(doc2, true)
        doc2 = MoquiDTEUtils.parseDocument(dteXml)
        domNode = doc2.getDocumentElement()
        documentPath = "/sii:DTE/sii:Documento"
        try {verified = MoquiDTEUtils.verifySignature(doc2, documentPath, null)} catch (Exception e) {
            ec.logger.error("Verifying signature: ${e.toString()}")
        }
    }
}
if (!verified) {
    if (ignoreSignatureErrors)
        discrepancyMessages.add("Signature mismatch for document ${documento.Documento.'@ID'.text()}")
    else
        errorMessages.add("Signature mismatch for document ${documento.Documento.'@ID'.text()}")
}

vatTaxRate = ec.service.sync().name("mchile.TaxServices.get#VatTaxRate").call().taxRate

totalCalculadoIva = 0 as BigDecimal
totalNoFacturable = 0 as BigDecimal
totalBruto = 0 as BigDecimal
// tipo de DTE
groovy.util.NodeList encabezado = documento.Documento.Encabezado

// ToDo: verify Timbre
/*
verResult = dteArray[i].verifyTimbre()
if (verResult.code != VerifyResult.TED_OK)
    ec.logger.warn("Error en timbre documento ${i}: ${verResult.message}")
 */
tipoDte = encabezado.IdDoc.TipoDTE.text()
folioDte = encabezado.IdDoc.Folio.text() as Integer
montosBrutos = encabezado.IdDoc.MntBruto?.text() == "1"
indTraslado = encabezado.IdDoc.IndTraslado?.text()

reserved = ec.service.sync().name("mchile.sii.SIIServices.get#RutEspeciales").call()

emisor = encabezado.Emisor
rutEmisor = emisor.RUTEmisor.text()
if (rutEmisor in reserved.rutList) {
    discrepancyMessages.add("Rut de emisor es Rut reservado, no se puede importar automáticamente")
    return
}

issuerPartyId = ec.service.sync().name("mchile.sii.DTECommServices.get#PartyIdByRut").parameters([idValue:rutEmisor, createUnknown:createUnknownIssuer, razonSocial:emisor.RznSoc.text(), roleTypeId:'Supplier',
                                                                                              giro:emisor.GiroEmis.text(), direccion:emisor.DirOrigen.text(), comuna:emisor.CmnaOrigen.text(), ciudad:emisor.CiudadOrigen.text()]).call().partyId
issuerTaxName = null
EntityValue issuer = ec.entity.find("mantle.party.PartyDetail").condition("partyId", issuerPartyId).one()
issuerTaxName = issuer.taxOrganizationName
if (issuerTaxName == null || issuerTaxName.size() == 0)
    issuerTaxName = ec.resource.expand("PartyNameOnlyTemplate", null, issuer)

if (rutEmisorCaratula != null && rutEmisor != rutEmisorCaratula) {
    discrepancyMessages.add("Rut mismatch: carátula indica Rut emisor ${rutEmisorCaratula}, pero documento ${i} indica ${rutEmisor}")
}

internalRole = ec.entity.find("mantle.party.PartyRole").condition([partyId:issuerPartyId, roleTypeId:'OrgInternal']).one()
issuerIsInternalOrg = (internalRole != null)
if (requireIssuerInternalOrg && !issuerIsInternalOrg) {
    ec.message.addError("Sujeto emisor de documento ${i} (${ec.resource.expand('PartyNameTemplate', null, issuer)}, rut ${rutEmisor}) no es organización interna")
}

razonSocialEmisor = emisor.RznSoc.text()
rsResult = ec.service.sync().name("mchile.sii.DTEServices.compare#RazonSocial").parameters([rs1:issuerTaxName, rs2:razonSocialEmisor]).call()
if (!rsResult.equivalent) {
    discrepancyMessages.add("Razón Social mismatch, en BD '${issuerTaxName}', en documento '${razonSocialEmisor}'")
}

ec.logger.warn("folio: ${folioDte}")

// Totales
BigDecimal montoNeto = (encabezado.Totales.MntNeto.text() ?: 0) as BigDecimal
montoTotal = (encabezado.Totales.MntTotal.text() ?: 0) as BigDecimal // es retornado, si se especifica clase se considera var local y no retorna
BigDecimal montoNoFacturable = (encabezado.Totales.MontoNF.text() ?: 0) as BigDecimal
BigDecimal montoExento = (encabezado.Totales.MntExe.text() ?: 0) as BigDecimal
BigDecimal tasaIva = (encabezado.Totales.TasaIVA.text() ?: 0) as BigDecimal
BigDecimal iva = (encabezado.Totales.IVA.text() ?: 0) as BigDecimal
BigDecimal impuestos = 0

impuestosMap = [:]
encabezado.Totales.ImptoReten.each { it ->
    tipoImpuesto = it.TipoImp.text()
    tasaImpuesto = it.TasaImp.text() as BigDecimal
    montoImpuesto = it.MontoImp.text() as BigDecimal
    impuestos += montoImpuesto
    if (impuestosMap[tipoImpuesto] == null) {
        impuesto = [tipo:tipoImpuesto, tasa:tasaImpuesto, monto:montoImpuesto]
        impuestosMap[tipoImpuesto] = impuesto
    } else {
        if (impuesto.tasa != tasa)
            ec.message.addError("Tasa impuesto mismatch para impuesto ${tipoImpuesto}")
        impuesto.monto = impuesto.monto + montoImpuesto
    }
}

if ((montoNeto + montoExento + iva + impuestos) != montoTotal) errorMessages.add("Total inválido (montoTotal no coincide con suma de monto neto, monto exento, iva e impuestos)")
if (montoNeto > 0 && tasaIva / 100 != vatTaxRate) errorMessages.add("Tasa IVA no coincide: esperada: ${vatTaxRate*100}%, recibida: ${tasaIva}%")

// Datos receptor
rutReceptor = encabezado.Receptor.RUTRecep.text()
if (rutReceptor in reserved.rutList) {
    errorMessages.add("Rut de receptor es Rut reservado, no se puede importar automáticamente")
    estadoRecepDte = 2
    recepDteGlosa = 'RECHAZADO, Errores: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
    sentRecStatusId = 'Ftd-ReceiverReject'
    return
}

if (rutReceptorCaratula != null && rutReceptorCaratula != rutReceptor) {
    discrepancyMessages.add("Rut mismatch: carátula indica Rut receptor ${rutEmisorCaratula}, pero documento indica ${rutEmisor}")
}

mapOut = ec.service.sync().name("mchile.sii.DTEServices.get#MoquiSIICode").parameter("siiCode", tipoDte).call()
tipoDteEnumId = mapOut.fiscalTaxDocumentTypeEnumId
existingDteList = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([issuerPartyIdValue:rutEmisor, fiscalTaxDocumenTypeEnumId:tipoDteEnumId, fiscalTaxDocumentNumber:folioDte])
        .disableAuthz().list()
isDuplicated = false
fechaEmision = encabezado.IdDoc.FchEmis.text() // es retornado, si se especifica clase se considera variable local y no se retorna valor
if (existingDteList) {
    dte = existingDteList.first
    if (dte.sentRecStatusId == 'Ftd-ReceiverReject') {
        ec.logger.info("Existente tiene estado rechazado, eliminando para partir de cero")
        // remove existing DTE and start from scratch
        ec.service.sync().name("delete#mchile.dte.FiscalTaxDocumentAttributes").parameter("fiscalTaxDocumentId", dte.fiscalTaxDocumentId).call()
        ec.service.sync().name("delete#mchile.dte.FiscalTaxDocumentContent").parameter("fiscalTaxDocumentId", dte.fiscalTaxDocumentId).parameter("fiscalTaxDocumentContentId", "*").call()
        ec.service.sync().name("delete#mchile.dte.FiscalTaxDocumentEmailMessage").parameter("fiscalTaxDocumentId", dte.fiscalTaxDocumentId).parameter("fiscalTaxDocumentEmailMessageId", "*").call()
        ec.service.sync().name("delete#mchile.dte.ReferenciaDte").parameter("fiscalTaxDocumentId", dte.fiscalTaxDocumentId).parameter("referenciaId", "*").call()
        ec.service.sync().name("delete#mchile.dte.DteEnvioFiscalTaxDocument").parameter("fiscalTaxDocumentId", dte.fiscalTaxDocumentId).parameter("envioId", "*").call()
        ec.service.sync().name("delete#mchile.dte.FiscalTaxDocument").parameter("fiscalTaxDocumentId", dte.fiscalTaxDocumentId).call()
    } else {
        contentList = ec.entity.find("mchile.dte.FiscalTaxDocumentContent").condition([fiscalTaxDocumentId:dte.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml'])
                .disableAuthz().list()
        if (dte.sentRecStatusId in ['Ftd-ReceiverAck', 'Ftd-ReceiverAccept'] && contentList) {
            ec.logger.error("Contenido existe, DTE está aprobado")
            xmlInDb = ec.resource.getLocationReference(contentList.first().contentLocation).openStream().readAllBytes()
            if (xmlInDb == dteXml) {
                estadoRecepDte = 0
                recepDteGlosa = 'ACEPTADO OK'
                sentRecStatusId = 'Ftde-DuplicateNotProcessed'
                fechaEmision = ec.l10n.format(dte.date, 'yyyy-MM-dd')
                isDuplicated = true
                return
            }
        }
        errorMessages.add("Ya existe registrada DTE tipo ${tipoDte} para emisor ${rutEmisor} y folio ${folioDte}, diferente al recibido")
        estadoRecepDte = 2
        recepDteGlosa = 'RECHAZADO, Errores: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
        isDuplicated = true
        return
    }
}

DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd")
String fechaVencimiento = encabezado.IdDoc.FchVenc.text()
Date date = formatter.parse(fechaEmision)
Timestamp issuedTimestamp = new Timestamp(date.getTime())
Timestamp dueTimestamp
if (fechaVencimiento != null && fechaVencimiento != 'null' && fechaVencimiento != '') {
    date = formatter.parse(fechaVencimiento)
    dueTimestamp = new Timestamp(date.getTime())
} else {
    dueTimestamp = null
}

receptor = encabezado.Receptor
String razonSocialReceptor = receptor.RznSocRecep.text()
receiverPartyId = ec.service.sync().name("mchile.sii.DTECommServices.get#PartyIdByRut").parameters([idValue:rutReceptor, createUnknown:createUnknownReceiver, razonSocial:razonSocialReceptor, roleTypeId:'Customer',
                                                                                              giro:receptor.GiroRecep.text(), direccion:receptor.DirRecep.text(), comuna:receptor.CmnaRecep.text(), ciudad:receptor.CiudadRecep.text()]).call().partyId
receiver = ec.entity.find("mantle.party.PartyDetail").condition("partyId", receiverPartyId).one()
// Verificación de Razón Social en XML vs lo guardado en Moqui
String razonSocialDb = receiver.taxOrganizationName
if (razonSocialDb == null || razonSocialDb.size() == 0)
    razonSocialDb = ec.resource.expand("PartyNameOnlyTemplate", null, receiver)
rsResult = ec.service.sync().name("mchile.sii.DTEServices.compare#RazonSocial").parameters([rs1:razonSocialReceptor, rs2:razonSocialDb]).call()
if ((!rsResult.equivalent)) {
    ec.logger.warn("Razón social en XML no coincide con la registrada: $razonSocialReceptor != $razonSocialDb")
}

internalRole = ec.entity.find("mantle.party.PartyRole").condition([partyId:receiverPartyId, roleTypeId:'OrgInternal']).one()
receiverIsInternalOrg = internalRole != null
if (requireReceiverInternalOrg && !receiverIsInternalOrg) {
    errorMessages.add("Sujeto receptor de documento ${i} (${ec.resource.expand('PartyNameTemplate', null, receiver)}, rut ${rutReceptor}) no es organización interna")
}

if (issuerPartyId == null)
    ec.message.addError("Empty issuerPartyId")
if (receiverPartyId == null)
    ec.message.addError("Empty receiverPartyId")

// Creación de orden de cobro
if (tipoDteEnumId == 'Ftdt-61') {
    // Nota de crédito, se invierten from y to
    fromPartyId = receiverPartyId
    toPartyId = issuerPartyId
    invoiceTypeEnumId = 'InvoiceCreditMemo'
} else {
    fromPartyId = issuerPartyId
    toPartyId = receiverPartyId
    invoiceTypeEnumId = 'InvoiceSales'
}
if (ec.message.hasError()) {
    estadoRecepDte = 2
    recepDteGlosa = 'RECHAZADO, Errores: ' + ec.message.getErrors().join(', ') + ((errorMessages.size() > 0) ? (', ' + errorMessages.join(', ')) : '')
        + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
    sentRecStatusId = 'Ftd-ReceiverReject'
    return
}

invoiceCreateMap =  [fromPartyId:fromPartyId, toPartyId:toPartyId, invoiceTypeEnumId:invoiceTypeEnumId, invoiceDate:issuedTimestamp, currencyUomId:'CLP', statusId:invoiceStatusId]
if (dueTimestamp)
    invoiceCreateMap.dueDate = dueTimestamp
if (tipoDteEnumId in ['Ftdt-101', 'Ftdt-102', 'Ftdt-109', 'Ftdt-110', 'Ftdt-111', 'Ftdt-112', 'Ftdt-30', 'Ftdt-32', 'Ftdt-33', 'Ftdt-34', 'Ftdt-35', 'Ftdt-38', 'Ftdt-39']) {
    invoiceMap = ec.service.sync().name("mantle.account.InvoiceServices.create#Invoice").parameters(invoiceCreateMap).disableAuthz().call()
    invoiceId = invoiceMap.invoiceId
}

BigDecimal montoItem = 0 as BigDecimal
detalleList = documento.Documento.Detalle
ec.logger.warn("Recorriendo detalles: ${detalleList.size()}")
int nroDetalles = 0
BigDecimal totalCalculado = 0
BigDecimal totalExento = 0
detalleList.each { detalle ->
    nroDetalles++
    // Adición de items a orden
    ec.logger.warn("-----------------------------------")
    ec.logger.warn("Leyendo línea detalle " + nroDetalles + ",")
    ec.logger.warn("Indicador exento: ${detalle.IndExe.text()}")
    ec.logger.warn("Nombre item: ${detalle.NmbItem.text()}")
    ec.logger.warn("Cantidad: ${detalle.QtyItem.text()}")
    ec.logger.warn("Precio: ${detalle.PrcItem.text()}")
    ec.logger.warn("Descuento: ${detalle.DescuentoMonto?.text()}")
    ec.logger.warn("Monto: ${detalle.MontoItem.text()}")
    itemDescription = detalle.NmbItem?.text()
    BigDecimal quantity = detalle.QtyItem ? (detalle.QtyItem.text() as BigDecimal) : null
    price = detalle.PrcItem ? (detalle.PrcItem.text() as BigDecimal) : null
    montoItem = detalle.MontoItem ? (detalle.MontoItem.text() as BigDecimal) : null
    montoItemBruto = null
    descuentoMonto = detalle.DescuentoMonto ? (detalle.DescuentoMonto.text() as BigDecimal) : 0 as BigDecimal
    descuentoMonto = descuentoMonto.setScale(0, RoundingMode.HALF_UP)
    if (montoItem && montosBrutos) {
        montoItemBruto = montoItem
        montoItem = ec.service.sync().name("mchile.TaxServices.calculate#NetFromGrossPrice").parameters([grossPrice:montoItemBruto]).call().netPrice
        priceBrtuto = price
        if (price != null) {
            price = ec.service.sync().name("mchile.TaxServices.calculate#NetFromGrossPrice").parameters([grossPrice:price]).call().netPrice
        }
        descuentoMontoBruto = descuentoMonto
        descuentoMonto = ec.service.sync().name("mchile.TaxServices.calculate#NetFromGrossPrice").parameters([grossPrice:descuentoMonto]).call().netPrice
        totalBruto += montoItemBruto
        ec.message.addMessage("Recalculando montoItem, montoItemBruto ${montoItemBruto}, montoItem ${montoItem}, totalBruto ${totalBruto}")
    }
    if (((price?:0) * (quantity?:0)) == 0 && montoItem != null) {
        if (quantity == null)
            quantity = 1 as BigDecimal
        price = (montoItem+descuentoMonto) / quantity
    } else if (((price * quantity) - descuentoMonto).setScale(0, RoundingMode.HALF_UP) != montoItem) {
        if (montosBrutos) {
            if (montoItemBruto && priceBruto && ((priceBruto * quantity) - descuentoMontoBruto).setScale(0, RoundingMode.HALF_UP) != montoItemBruto)
                discrepancyMessages.add("En detalle ${nroDetalles} (${itemDescription?:''}), montoItem (${montoItemBruto}) no calza con el valor unitario (${priceBruto}) multiplicado por cantidad (${quantity}) menos descuento (${descuentoMontoBruto}), redondeado")
        } else {
            discrepancyMessages.add("En detalle ${nroDetalles} (${itemDescription?:''}), montoItem (${montoItem}) no calza con el valor unitario (${price}) multiplicado por cantidad (${quantity}) menos descuento (${descuentoMonto}), redondeado")
        }
        dteAmount = price
        price = (montoItem+descuentoMonto)/quantity
    }
    try {
        indExe = detalle.IndExe?.text() as Integer
    } catch (NumberFormatException e) {
        indExe = null
    }
    // Si el indicador es no exento hay que agregar IVA como item aparte
    // Se puede ir sumando el IVA y si es mayor que 0 crear el item
    Boolean itemExento = false
    Boolean montoEsFacturable = true
    if (indExe == null && (tipoDte != '34')) {
        // Item y documento afecto
    } else if ((tipoDte == '34' && indExe == null) || indExe == 1) {
        itemExento = true
    } else if (indExe == 2) {
        // Producto o servicio no es facturable
        montoEsFacturable = false
        if (tipoDte == '34')
            itemExento = true
    } else if (indExe == 3) {
        // Garantía de depósito por envases (Cervezas, Jugos, Aguas Minerales, Bebidas Analcohólicas u otros autorizados por Resolución especial)
        montoEsFacturable = false
        if (tipoDte == '34')
            itemExento = true
    } else if (indExe == 4) {
        // Ítem No Venta. Para facturas y guías de despacho (ésta última con Indicador Tipo de Traslado de Bienes igual a 1) y este ítem no será facturado.
        montoEsFacturable = false
        if (tipoDte == '34')
            itemExento = true
    } else if (indExe == 5) {
        // Ítem a rebajar. Para guías de despacho NO VENTA que rebajan guía anterior. En el área de referencias se debe indicar la guía anterior.
        montoEsFacturable = false
        if (tipoDte == '34')
            itemExento = true
    } else if (indExe == 6) {
        // Producto o servicio no facturable negativo (excepto en liquidaciones-factura)
        montoEsFacturable = false
        if (tipoDte == '34')
            itemExento = true
    } else {
        errorMessages.add("Valor inválido para indicador exento (IndExe): ${indExe}")
    }
    if (montoEsFacturable) {
        if (itemExento)
            totalExento += montoItem
        totalCalculado += montoItem
    } else
        totalNoFacturable += montoItem

    roundingAdjustmentItemAmount = 0 as BigDecimal
    if (quantity * price != montoItem) {
        roundingAdjustmentItemAmount = montoItem - descuentoMonto - (quantity * price).setScale(6, RoundingMode.HALF_UP) as BigDecimal
        if (((quantity * price) + roundingAdjustmentItemAmount) != montoItem) {
            roundingAdjustmentItemAmount = 0
            dteQuantity = quantity
            dteAmount = price
            price = (price * quantity).setScale(0, RoundingMode.HALF_UP)
            quantity = 1
        }
    }

    Map itemMap = null
    if (!attemptProductMatch && invoiceId) {
        itemMap = ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemSales', dteQuantity:dteQuantity, dteAmount:dteAmount,
                                                                                                productId: (itemExento? 'SRVCEXENTO': null), description: itemDescription, quantity: quantity, amount: price]).call()
        invoiceItemCount++
    } else {
        ec.logger.warn("Buscando código item")
        cdgItemList = detalle.CdgItem
        int k = 0
        String productId
        cdgItemList.each { cdgItem ->
            // Check explicit product relation with external ID
            ec.logger.warn("Leyendo codigo ${k}, valor: ${cdgItem.VlrCodigo.text()}")
            if (issuerIsInternalOrg) {
                // Look up product directly
                pseudoId = cdgItem.VlrCodigo.text()
                product = ec.entity.find("mantle.product.Product").condition("pseudoId", pseudoId).one()
                if (product) {
                    productId = product.productId
                    return
                }
            } else {
                productPartyList = ec.entity.find("mantle.product.ProductParty").condition([partyId: issuerPartyId, otherPartyItemId: cdgItem.VlrCodigo.text(), roleTypeId: 'Supplier'])
                        .conditionDate("fromDate", "thruDate", issuedTimestamp).orderBy("-fromDate").list()
                if (productPartyList) {
                    productId = productPartyList.first.productId
                    return
                }
            }
            k++
        }

        // Check Exento category
        if (productId) {
            exentoList = ec.entity.find("mantle.product.category.ProductCategoryMember").condition([productCategoryId:'', productId:productId])
                    .conditionDate("fromDate", "thruDate", issuedTimestamp).list()
            exentoBd = exentoList.size() > 0
            if (exentoBd != itemExento)
                discrepancyMessages.add("Exento mismatch, XML dice ${itemExento? '' : 'no '} exento, producto en BD dice ${exentoBd? '' : 'no '} exento")
            product = ec.entity.find("mantle.product.Product").condition("productId", productId).one()
            if (product.productName.toString().trim().toLowerCase() != itemDescription.trim().toLowerCase())
                discrepancyMessages.add("Description mismatch, XML dice ${itemDescription}, producto en BD dice ${product.productName}")
            ec.logger.info("Agregando producto preexistente ${productId}, cantidad ${quantity} *************** orderId: ${orderId}")
        } else {
            if (itemExento)
                productId = 'SRVCEXENTO'
            ec.logger.warn("Producto ${itemDescription} no existe en el sistema, se creará como genérico")
        }
        if (invoiceId) {
            itemMap = ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemSales', dteQuantity:dteQuantity, dteAmount:dteAmount,
                                                                                                              productId: productId, description: itemDescription, quantity: quantity, amount: price]).call()
            invoiceItemCount++
        }
    }
    if (descuentoMonto && invoiceId) {
        parentItemSeqId = itemMap.invoiceItemSeqId
        ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, parentItemSeqId:parentItemSeqId, itemTypeEnumId:'ItemDiscount',
                                                                                                description: 'Descuento', quantity: 1, amount:-descuentoMonto]).call()
        invoiceItemCount++
    }

    if (roundingAdjustmentItemAmount != 0) {
        description = "Ajuste redondeo DTE (precio ${dteAmount?:price}, cantidad ${dteQuantity?:quantity}, montoItem ${montoItem}"
        if (itemMap?.invoiceItemSeqId == null) {
            ec.message.addMessage("Need to add rounding adjustment item but did not create a parent item, itemMap ${itemMap}, invoiceId: ${invoiceId}")
            ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId  : invoiceId, itemTypeEnumId: 'ItemDteRoundingAdjust',
                                                                                                    description: description, quantity: 1, amount: roundingAdjustmentItemAmount]).call()
        } else {
            parentItemSeqId = itemMap.invoiceItemSeqId
            ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, parentItemSeqId:parentItemSeqId, itemTypeEnumId:'ItemDteRoundingAdjust',
                                                                                                    description: description, quantity: 1, amount:roundingAdjustmentItemAmount]).call()
        }
    }

}

impuestosMap.each { impuestoCode, impuestoMap ->
    impuestoEnum = ec.entity.find("moqui.basic.Enumeration").condition([parentEnumId: "ItemTCChlDte", enumCode:impuestoCode]).one()
    if (impuestoEnum == null)
        internalErrors.add("Did not find impuesto for code ${impuestoCode}")
    else if (invoiceId) {
        ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId  : invoiceId, itemTypeEnumId: impuestoEnum.enumId,
                                                                                                description: impuestoEnum.description, quantity: 1, amount: impuestoMap.monto]).call()
        invoiceItemCount++
    }
}

//errorMessages.add("Test")

globalList = documento.Documento.DscRcgGlobal
Integer globalItemCount = 0
globalList.each { globalItem ->
    globalItemCount++
    tpoMov = globalItem.TpoMov.text()
    nroLinea = globalItem.NroLinDR.text() as Integer
    try {
        indExe = globalItem.IndExeDR?.text() as Integer
    } catch (NumberFormatException e) {
        indExe = null
    }
    if (globalItemCount != nroLinea)
        errorMessages.add("Valor número línea Descuento o Recargo no calza, esperado ${globalItemCount}, recibido ${nroLinea}")
    if (tpoMov == 'D') {
        // descuento
        itemTypeEnumId = 'ItemDiscount'
    } else if (tpoMov == 'R') {
        itemTypeEnumId = 'ItemMiscCharge'
    } else {
        errorMessages.add("Tipo movimiento inválido DscRcgGlobal ${globalItemCount}, se esperaba D o R y se recibió ${tpoMov}")
    }
    tpoVal = globalItem.TpoValor.text()
    BigDecimal amount = 0
    BigDecimal pctValue
    if (tpoVal == '$') {
        amount = (globalItem.ValorDR.text() as BigDecimal).setScale(0, RoundingMode.HALF_UP)
    } else if (tpoVal == '%') {
        pctValue = globalItem.ValorDR.text() as BigDecimal
        amount = (totalCalculado / 100.0 * pctValue).setScale(0, RoundingMode.HALF_UP)
    } else {
        errorMessages.add("Tipo valor inválido DscRcgGlobal ${globalItemCount}, se esperaba \$ o % y se recibió ${tpoVal}")
    }
    if (itemTypeEnumId == 'ItemDiscount')
        amount = -1 * amount
    if (invoiceId) {
        ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:itemTypeEnumId,
                                                                                                description: globalItem.GlosaDR.text(), quantity:1, amount: amount]).call()
        invoiceItemCount++
    }
    ec.logger.warn("DescuentoORecargo, indExe: ${indExe}")
    if (indExe == null)
        totalCalculado += amount
    else if (indExe == 1) {
        totalExento += amount
        totalCalculado += amount
    } else if (indExe == 2)
        totalNoFacturable += amount
    else
        errorMessages.add("Valor inválido para registro IndExeDR: ${indExe}")
}

newInvoiceStatusId = null

totalCalculadoIva = (montoNeto * vatTaxRate).setScale(0, RoundingMode.HALF_UP)
if (totalCalculado != (montoNeto + montoExento)) discrepancyMessages.add("Monto total (neto + exento) no coincide, calculado: ${totalCalculado}, en totales de DTE: ${montoNeto + montoExento}")
if (totalExento != montoExento) {
    discrepancyMessages.add("Monto exento no coincide, calculado: ${totalExento}, en totales de DTE: ${montoExento}")
    newInvoiceStatusId = 'InvoiceRequiresManualIntervention'
}
if (totalNoFacturable != montoNoFacturable) {
    discrepancyMessages.add("Monto no facturable no coincide, calculado: ${totalNoFacturable}, en totales de DTE: ${montoNoFacturable}")
    newInvoiceStatusId = 'InvoiceRequiresManualIntervention'
}

if (iva != totalCalculadoIva) {
    discrepancyMessages.add("No coincide monto IVA, DTE indica ${iva}, calculado: ${totalCalculadoIva}")
}
if (totalCalculadoIva > 0 && invoiceId) {
    ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemVatTax', description: 'IVA', quantity: 1, amount: totalCalculadoIva, taxAuthorityId:'CL_SII']).call()
}

if (invoiceId) {
    invoice = ec.entity.find("mantle.account.invoice.Invoice").condition("invoiceId", invoiceId).one()
    if (invoice.invoiceTotal != montoTotal) {
        diff = invoice.invoiceTotal - montoTotal
        if (diff == iva && totalExento == montoExento) {
            factor = 1-(diff/invoice.invoiceTotal)
            itemList = ec.entity.find("mantle.account.invoice.InvoiceItem").condition("invoiceId", invoiceId).forUpdate(true).list()
            itemList.each {
                boolean afecto = true
                if (it.productId) {
                    afectoMap = ec.service.sync().name("mchile.sii.DTEServices.check#Afecto").parameter("productId", it.productId).call()
                    afecto = afectoMap.afecto
                }
                if (afecto) {
                    dteAmount = it.dteAmount ?: it.amount
                    decimals = 2
                    it.amount = (it.amount * factor).setScale(decimals, RoundingMode.HALF_UP)
                    while (decimals > 0 && (it.amount * it.quantity).setScale(0, RoundingMode.HALF_UP) != it.amount * it.quantity) {
                        decimals--
                        it.amount = it.amount.setScale(decimals, RoundingMode.HALF_UP)
                    }
                    if ((it.amount * it.quantity).setScale(0, RoundingMode.HALF_UP) != it.amount * it.quantity) {
                        it.dteQuantity = it.dteQuantity ?: it.quantity
                        it.amount = (it.amount * it.quantity).setScale(0, RoundingMode.HALF_UP)
                        it.quantity = 1
                    }
                }
                it.update()
            }
            invoice = ec.entity.find("mantle.account.invoice.Invoice").condition("invoiceId", invoiceId).one()
            diff = invoice.invoiceTotal - montoTotal
        }
        diffAbs = (diff < 0) ? -diff : diff
        if (diffAbs <= invoiceItemCount && totalExento == montoExento) {
            itemList = ec.entity.find("mantle.account.invoice.InvoiceItem").condition("invoiceId", invoiceId).forUpdate(true).list()
            for (int i = 0; diff != 0 && i < itemList.size(); i++) {
                increment = diff > 0 ? -1 : 1
                diffAbs = (diff < 0) ? -diff : diff
                if (diffAbs < 1) increment = -diff
                EntityValue item = itemList.get(i)
                parentItemSeqId = item.invoiceItemSeqId
                ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, parentItemSeqId:parentItemSeqId, itemTypeEnumId:'ItemDteRoundingAdjust',
                                                                                                        description: 'Ajuste redondeo DTE', quantity: 1, amount:increment]).call()
                diff += increment
            }
            invoice = ec.entity.find("mantle.account.invoice.Invoice").condition("invoiceId", invoiceId).one()
            diff = invoice.invoiceTotal - montoTotal
            if (diff != 0)
                ec.message.addError("Could not handle diff for document ${documento.Documento.'@ID'.text()}")
        } else {
            // No se puede resolver automáticamente la diferencia, se trata como discrepancia
            discrepancyMessages.add("No coinciden totales, DTE indica total ${montoTotal} y exento ${montoExento}, calculado: total ${invoice.invoiceTotal} y exento ${totalExento}")
            newInvoiceStatusId = 'InvoiceRequiresManualIntervention'
        }
    }
}

if (errorMessages.size() > 0) {
    estadoRecepDte = 2
    recepDteGlosa = 'RECHAZADO, Errores: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
    sentRecStatusId = 'Ftd-ReceiverReject'
    ec.logger.error(recepDteGlosa)
    if (recepDteGlosa.length() > 256)
        recepDteGlosa = recepDteGlosa.substring(0, 256)
    if (invoice) {
        invoice.statusId = 'InvoiceCancelled'
        invoice.invoiceMessage = recepDteGlosa
        invoice.update()
    }
} else if (discrepancyMessages.size() > 0) {
    estadoRecepDte = 1
    recepDteGlosa = 'ACEPTADO CON DISCREPANCIAS: ' + discrepancyMessages.join(', ')
    ec.logger.warn(recepDteGlosa)
    if (recepDteGlosa.length() > 256)
        recepDteGlosa = recepDteGlosa.substring(0, 256)
    sentRecStatusId = 'Ftd-ReceiverAck'
    if (invoice) {
        invoice.invoiceMessage = recepDteGlosa
        if (newInvoiceStatusId && invoice) {
            invoice.statusId = newInvoiceStatusId
        }
        invoice.update()
    }
} else {
    estadoRecepDte = 0
    recepDteGlosa = 'ACEPTADO OK'
    sentRecStatusId = 'Ftd-ReceiverAck'
}

if (existingDteList)
    return

// Se guarda DTE recibido en la base de datos
createMap = [issuerPartyId:issuerPartyId, issuerPartyIdTypeEnumId:'PtidNationalTaxId', issuerPartyIdValue:rutEmisor, fiscalTaxDocumentTypeEnumId:tipoDteEnumId, fiscalTaxDocumentNumber:folioDte,
             receiverPartyId:receiverPartyId, receiverPartyIdTypeEnumId:'PtidNationalTaxId', receiverPartyIdValue:rutReceptor, date:issuedTimestamp, invoiceId:invoiceId, statusId:'Ftd-Issued',
             sentAuthStatusId:'Ftd-SentAuthAccepted', sentRecStatusId:sentRecStatusId]
mapOut = ec.service.sync().name("create#mchile.dte.FiscalTaxDocument").parameters(createMap).call()
fiscalTaxDocumentId = mapOut.fiscalTaxDocumentId
if (tipoDteEnumId == 'Ftdt-52') {
    if (!indTraslado)
        errorMessages.add("Guía de despacho no indica tipo de traslado (IndTraslado): ${indTraslado}")
    else {
        indTrasladoEnumId = ec.service.sync().name("mchile.sii.DTEServices.get#MoquiSIICode").parameters([fiscalTaxDocumentTypeEnumId:indTrasladoEnumId, enumTypeId:'IndTraslado']).call().fiscalTaxDocumentTypeEnumId
        if (!indTrasladoEnumId)
            errorMessages.add("Guía de despacho indica tipo de traslado (IndTraslado) desconocido: ${indTraslado}")
        else {
            ec.service.sync.name("store#mchile.dte.GuiaDespacho").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, indTrasladoEnumId:indTrasladoEnumId]).call()
        }
    }
}

createMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, date:ec.user.nowTimestamp, amount:montoTotal, montoNeto:montoNeto, montoExento:montoExento, tasaImpuesto:tasaIva, tipoImpuesto:1, montoIvaRecuperable:montoIva, montoIvaNoRecuperable:0,
            fechaEmision:issuedTimestamp]
mapOut = ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(createMap).call()

locationReferenceBase = "dbresource://moit/erp/dte/${rutEmisor}/DTE-${tipoDte}-${folioDte}"
contentLocationXml = "${locationReferenceBase}.xml"
docRrXml = ec.resource.getLocationReference("${locationReferenceBase}.xml")
docRrXml.putBytes(dteXml)

createMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:contentLocationXml, contentDate:issuedTimestamp]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
if (pdfBytes) {
    contentLocationPdf = "${locationReferenceBase}.pdf"
    createMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:contentLocationPdf, contentDate:issuedTimestamp]
    docRrPdf = ec.resource.getLocationReference(contentLoationPdf)
    docRrPdf.putBytes(pdfBytes)
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
}

// Se agregan las referencias
referenciasList = documento.Documento.Referencia
Integer nroRef = 0
referenciasList.each { groovy.util.Node referencia ->
    nroRef++
    Integer nroLinRef = null
    try {
        nroLinRef = referencia.NroLinRef.text() as Integer
    } catch (NumberFormatException e) {
        errorMessages.add("Valor inválido en referencia ${nroRef}: ${referencia.NroLinRef.text()}")
        return
    }
    if (nroLinRef != nroRef)
        errorMessages.add("Valor inesperado en referencia, campo NroLinRef, esperado ${nroRef}, recibido ${referencia.NroLinRef.text()}")
    mapOut = ec.service.sync().name("mchile.sii.DTEServices.get#MoquiSIICode").parameter("siiCode", referencia.TpoDocRef.text()).call()
    tipoDteEnumId = mapOut.fiscalTaxDocumentTypeEnumId
    Date refDate = null
    try {
        refDate = formatter.parse(fechaEmision)
    } catch (ParseException e) {
        errorMessages.add("Valor inválido en referencia ${nroRef}, campo FchRef: ${referencia.FchRef.text()}")
        return
    }
    codRefEnum = ec.entity.find("moqui.basic.Enumeration").condition([enumTypeId:"FtdCodigoReferencia", enumCode:referencia.CodRef.text()]).list().first
    codRefEnumId = codRefEnum?.enumId
    folio = referencia.FolioRef.text()
    if (!codRefEnumId)
        errorMessages.add("Valor inválido en referencia ${nroRef}, campo CodRef: ${referencia.CodRef.text()}")
    if (tipoDteEnumId == "Ftdt-801") {
        // Orden de Compra, va en el Invoice y no en mchile.dte.ReferenciaDte
        if (invoiceId)
            ec.service.sync().name("update#mantle.account.invoice.Invoice").parameters([invoiceId:invoiceId, otherPartyOrderId:folio, otherPartyOrderDate:refDate]).call()
    } else if (tipoDteEnumId && refDate) {
        ec.service.sync().name("create#mchile.dte.ReferenciaDte").parameters([invoiceId:invoiceId, referenciaTypeEnumId:'RefDteTypeInvoice', fiscalTaxDocumentTypeEnumId:tipoDteEnumId,
                                                                              folio:folio, fecha: refDate, codigoReferenciaEnumId:codRefEnumId, razonReferencia:referencia.RazonRef?.text()]).call()
    }

}

if (envioId)
    ec.service.sync().name("create#mchile.dte.DteEnvioFiscalTaxDocument").parameters([envioId:envioId, fiscalTaxDocumentId:fiscalTaxDocumentId]).call()
if (envioRespuestaId)
    ec.service.sync().name("create#mchile.dte.DteEnvioFiscalTaxDocument").parameters([envioId:envioRespuestaId, fiscalTaxDocumentId:fiscalTaxDocumentId]).call()

return
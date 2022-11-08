import org.moqui.context.ExecutionContext
import java.math.RoundingMode

ExecutionContext ec = context.ec

boolean processDocument = true

if (domNode == null) {
    ec.message.addError("No domNode present")
    return
}

errorMessages = []
discrepancyMessages = []
internalErrors = []
Integer invoiceItemCount = 0

Map<String, Object> dteMap = ec.service.sync().name("mchile.sii.dte.DteLoadServices.parse#Dte").parameter("dte", domNode).call()
if (ec.message.hasError())
    return
errorMessages.addAll(dteMap.errorMessages)
discrepancyMessages.addAll(dteMap.discrepancyMessages)

reserved = ec.service.sync().name("mchile.sii.SIIServices.get#RutEspeciales").call()

if (dteMap.rutEmisor in reserved.rutList) {
    discrepancyMessages.add("Rut de emisor es Rut reservado, no se puede importar automáticamente")
    return
}

issuerPartyId = ec.service.sync().name("mchile.sii.DTECommServices.get#PartyIdByRut").parameters([idValue:dteMap.rutEmisor, createUnknown:createUnknownIssuer, razonSocial:dteMap.razonSocialEmisor, roleTypeId:'Supplier',
                                                                                              giro:dteMap.giroEmisor, direccion:dteMap.direccionOrigen, comuna:dteMap.comunaOrigen, ciudad:dteMap.ciudadOrigen]).call().partyId
issuerTaxName = null
EntityValue issuer = ec.entity.find("mantle.party.PartyDetail").condition("partyId", issuerPartyId).one()
issuerTaxName = issuer.taxOrganizationName
if (issuerTaxName == null || issuerTaxName.size() == 0)
    issuerTaxName = ec.resource.expand("PartyNameOnlyTemplate", null, issuer)

if (rutEmisorCaratula != null && dteMap.rutEmisor != rutEmisorCaratula) {
    discrepancyMessages.add("Rut mismatch: carátula indica Rut emisor ${rutEmisorCaratula}, pero documento ${i} indica ${dteMap.rutEmisor}")
}

internalRole = ec.entity.find("mantle.party.PartyRole").condition([partyId:issuerPartyId, roleTypeId:'OrgInternal']).one()
issuerIsInternalOrg = (internalRole != null)
if (requireIssuerInternalOrg && !issuerIsInternalOrg) {
    ec.message.addError("Sujeto emisor de documento ${i} (${ec.resource.expand('PartyNameTemplate', null, issuer)}, rut ${rutEmisor}) no es organización interna")
}

rsResult = ec.service.sync().name("mchile.sii.DTEServices.compare#RazonSocial").parameters([rs1:issuerTaxName, rs2:dteMap.razonSocialEmisor]).call()
if (!rsResult.equivalent) {
    discrepancyMessages.add("Razón Social mismatch, en BD '${issuerTaxName}', en documento '${dteMap.razonSocialEmisor}'")
}

// Datos receptor
if (dteMap.rutReceptor in reserved.rutList) {
    errorMessages.add("Rut de receptor es Rut reservado, no se puede importar automáticamente")
    estadoRecepDte = 2
    recepDteGlosa = 'RECHAZADO, Errores: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
    sentRecStatusId = 'Ftd-ReceiverReject'
    return
}

if (rutReceptorCaratula != null && rutReceptorCaratula != dteMap.rutReceptor) {
    discrepancyMessages.add("Rut mismatch: carátula indica Rut receptor ${rutReceptorCaratula}, pero documento indica ${dteMap.rutReceptor}")
}

existingDteList = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([issuerPartyIdValue:dteMap.rutEmisor, fiscalTaxDocumenTypeEnumId:dteMap.tipoDteEnumId, fiscalTaxDocumentNumber:dteMap.fiscalTaxDocumentNumber])
        .disableAuthz().list()
isDuplicated = false

locationReferenceBase = "dbresource://moit/erp/dte/${dteMap.rutEmisor}/DTE-${dteMap.tipoDte}-${dteMap.fiscalTaxDocumentNumber}"

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
        if (contentList.size() == 0) {
            attrib = ec.entity.find("mchile.dte.FiscalTaxDocumentAttributes").condition("fiscalTaxDocumentId", fiscalTaxDocumentId).one()
            attributeMap = [amount:'montoTotal', montoNeto:'montoNeto', montoExento:'montoExento', tasaImpuesto:'tasaIva', tipoImpuesto:'tipoImpuesto', montoIvaRecuperable:'iva',
                            montoIvaNoRecuperable:'montoIvaNoRecuperable', fechaEmision:'fechaEmision', razonSocialEmisor:'razonSocialEmisor', razonSocialReceptor:'razonSocialReceptor']
            dteMap.tipoImpuesto = 1
            dteMap.montoIvaNoRecuperable = 0
            attributeMap.each { entityFieldName, mapFieldName ->
                if (attrib[entityFieldName] != dteMap[mapFieldName])
                    ec.message.addError("Value mismatch for attribute field ${entityFieldName}, XML value: ${dteMap[mapFieldName]}, DB value: ${attrib[entityFieldName]}")
            }
            if (ec.message.hasError())
                return
            contentLocationXml = "${locationReferenceBase}.xml"
            docRrXml = ec.resource.getLocationReference("${locationReferenceBase}.xml")
            docRrXml.putBytes(dteMap.dteBytes)
            return
        } else {
            if (dte.sentRecStatusId in ['Ftd-ReceiverAck', 'Ftd-ReceiverAccept'] && contentList && sendResponse) {
                ec.logger.warn("Contenido existe, DTE está aprobado, enviando aceptación")
                xmlInDb = ec.resource.getLocationReference(contentList.first().contentLocation).openStream().readAllBytes()
                if (xmlInDb == dteMap.dteBytes) {
                    estadoRecepDte = 0
                    recepDteGlosa = 'ACEPTADO OK'
                    sentRecStatusId = 'Ftde-DuplicateNotProcessed'
                    if (envioId)
                        ec.service.sync().name("create#mchile.dte.DteEnvioFiscalTaxDocument").parameters([envioId:envioId, fiscalTaxDocumentId:dte.fiscalTaxDocumentId]).call()
                isDuplicated = true
                fiscalTaxDocumentId = dte.fiscalTaxDocumentId
                return
                }
            }
            errorMessages.add("Ya existe registrada DTE tipo ${dteMap.tipoDte} para emisor ${dteMap.rutEmisor} y folio ${dteMap.fiscalTaxDocumentNumber}, diferente al recibido")
            estadoRecepDte = 2
            recepDteGlosa = 'RECHAZADO, Errores: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
            isDuplicated = true
            return
        }
    }
}

receiverPartyId = ec.service.sync().name("mchile.sii.DTECommServices.get#PartyIdByRut").parameters([idValue:dteMap.rutReceptor, createUnknown:createUnknownReceiver, razonSocial:dteMap.razonSocialReceptor, roleTypeId:'Customer',
                                                                                              giro:dteMap.giroReceptor, direccion:dteMap.direccionReceptor, comuna:dteMap.comunaReceptor, ciudad:dteMap.ciudadReceptor]).call().partyId
receiver = ec.entity.find("mantle.party.PartyDetail").condition("partyId", receiverPartyId).one()
// Verificación de Razón Social en XML vs lo guardado en Moqui
String razonSocialDb = receiver.taxOrganizationName
if (razonSocialDb == null || razonSocialDb.size() == 0)
    razonSocialDb = ec.resource.expand("PartyNameOnlyTemplate", null, receiver)
rsResult = ec.service.sync().name("mchile.sii.DTEServices.compare#RazonSocial").parameters([rs1:dteMap.razonSocialReceptor, rs2:razonSocialDb]).call()
if ((!rsResult.equivalent)) {
    ec.logger.warn("Razón social en XML no coincide con la registrada: $dteMap.razonSocialReceptor != $razonSocialDb")
}

internalRole = ec.entity.find("mantle.party.PartyRole").condition([partyId:receiverPartyId, roleTypeId:'OrgInternal']).one()
receiverIsInternalOrg = internalRole != null
if (requireReceiverInternalOrg && !receiverIsInternalOrg) {
    errorMessages.add("Sujeto receptor de documento ${i} (${ec.resource.expand('PartyNameTemplate', null, receiver)}, rut ${dteMap.rutReceptor}) no es organización interna")
}

if (issuerPartyId == null)
    ec.message.addError("Empty issuerPartyId")
if (receiverPartyId == null)
    ec.message.addError("Empty receiverPartyId")

// Creación de orden de cobro
if (dteMap.tipoDteEnumId == 'Ftdt-61') {
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

invoiceCreateMap =  [fromPartyId:fromPartyId, toPartyId:toPartyId, invoiceTypeEnumId:invoiceTypeEnumId, invoiceDate:dteMap.fechaEmision, currencyUomId:'CLP', statusId:invoiceStatusId]
if (dteMap.fechaVencimiento)
    invoiceCreateMap.dueDate = dteMap.fechaVencimiento
if (dteMap.tipoDteEnumId in ['Ftdt-101', 'Ftdt-102', 'Ftdt-109', 'Ftdt-110', 'Ftdt-111', 'Ftdt-112', 'Ftdt-30', 'Ftdt-32', 'Ftdt-33', 'Ftdt-34', 'Ftdt-35', 'Ftdt-38', 'Ftdt-39']) {
    invoiceMap = ec.service.sync().name("mantle.account.InvoiceServices.create#Invoice").parameters(invoiceCreateMap).disableAuthz().call()
    invoiceId = invoiceMap.invoiceId
}

dteMap.detalleList.each { detalle ->
    Map itemMap = null
    if (!attemptProductMatch && invoiceId) {
        itemMap = ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemSales', dteQuantity:detalle.dteQuantity, dteAmount:detalle.dteAmount,
                                                                                                productId: (detalle.itemExento? 'SRVCEXENTO': null), description: detalle.itemDescription, quantity: detalle.quantity, amount: detalle.amount]).call()
        invoiceItemCount++
    } else {
        ec.logger.warn("Buscando código item")
        String productId
        detalle.codigoList.each { codigo ->
            // Check explicit product relation with external ID
            ec.logger.warn("Leyendo codigo ${k}, valor: ${codigo}")
            if (issuerIsInternalOrg) {
                // Look up product directly
                product = ec.entity.find("mantle.product.Product").condition("pseudoId", codigo).one()
                if (product) {
                    productId = product.productId
                    return
                }
            } else {
                productPartyList = ec.entity.find("mantle.product.ProductParty").condition([partyId: issuerPartyId, otherPartyItemId: codigo, roleTypeId: 'Supplier'])
                        .conditionDate("fromDate", "thruDate", fechaEmision).orderBy("-fromDate").list()
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
                    .conditionDate("fromDate", "thruDate", fechaEmision).list()
            exentoBd = exentoList.size() > 0
            if (exentoBd != detalle.itemExento)
                discrepancyMessages.add("Exento mismatch, XML dice ${detalle.itemExento? '' : 'no '} exento, producto en BD dice ${exentoBd? '' : 'no '} exento")
            product = ec.entity.find("mantle.product.Product").condition("productId", productId).one()
            if (product.productName.toString().trim().toLowerCase() != detalle.itemDescription.trim().toLowerCase())
                discrepancyMessages.add("Description mismatch, XML dice ${detalle.itemDescription}, producto en BD dice ${product.productName}")
            ec.logger.info("Agregando producto preexistente ${productId}, cantidad ${detalle.quantity} *************** orderId: ${orderId}")
        } else {
            if (detalle.itemExento)
                productId = 'SRVCEXENTO'
            ec.logger.warn("Producto ${detalle.itemDescription} no existe en el sistema, se creará como genérico")
        }
        if (invoiceId) {
            itemMap = ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemSales', dteQuantity:detalle.dteQuantity, dteAmount:detalle.dteAmount,
                                                                                                              productId: productId, description: detalle.itemDescription, quantity: detalle.quantity, amount: detalle.amount]).call()
            invoiceItemCount++
        }
    }
    if (detalle.descuentoMonto && invoiceId) {
        parentItemSeqId = itemMap.invoiceItemSeqId
        ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, parentItemSeqId:parentItemSeqId, itemTypeEnumId:'ItemDiscount',
                                                                                                description: 'Descuento', quantity: 1, amount:-detalle.descuentoMonto]).call()
        invoiceItemCount++
    }

    if (detalle.roundingAdjustmentItemAmount != 0) {
        description = "Ajuste redondeo DTE (precio ${detalle.dteAmount?:detalle.price}, cantidad ${detalle.dteQuantity?:detalle.quantity}, montoItem ${detalle.montoItem}"
        if (itemMap?.invoiceItemSeqId == null) {
            ec.message.addMessage("Need to add rounding adjustment item but did not create a parent item, itemMap ${itemMap}, invoiceId: ${invoiceId}")
            ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId  : invoiceId, itemTypeEnumId: 'ItemDteRoundingAdjust',
                                                                                                    description: description, quantity: 1, amount: detalle.roundingAdjustmentItemAmount]).call()
        } else {
            parentItemSeqId = itemMap.invoiceItemSeqId
            ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, parentItemSeqId:parentItemSeqId, itemTypeEnumId:'ItemDteRoundingAdjust',
                                                                                                    description: description, quantity: 1, amount:detalle.roundingAdjustmentItemAmount]).call()
        }
    }

}

dteMap.impuestosMap.each { impuestoCode, impuestoMap ->
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

if (invoiceId) {
    dteMap.descuentoRecargoList.each { item ->
        ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId: item.itemTypeEnumId, description: item.glosa, quantity: 1,
                                                                                                amount: item.amount]).call()
        invoiceItemCount++
    }
}

if (dteMap.requiresManualIntervention)
    newInvoiceStatusId = 'InvoiceRequiresManualIntervention'
else
    newInvoiceStatusId = null

if (dteMap.iva > 0 && invoiceId) {
    ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemVatTax', description: 'IVA', quantity: 1, amount: dteMap.iva, taxAuthorityId:'CL_SII']).call()
}

montoTotal = dteMap.montoTotal
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

ftdCreateMap = [issuerPartyId:issuerPartyId, issuerPartyIdTypeEnumId:'PtidNationalTaxId', issuerPartyIdValue:dteMap.rutEmisor, fiscalTaxDocumentTypeEnumId:dteMap.tipoDteEnumId, fiscalTaxDocumentNumber:dteMap.fiscalTaxDocumentNumber,
                receiverPartyId:receiverPartyId, receiverPartyIdTypeEnumId:'PtidNationalTaxId', receiverPartyIdValue:dteMap.rutReceptor, date:dteMap.fechaEmision, invoiceId:invoiceId, statusId:'Ftd-Issued',
                sentAuthStatusId:'Ftd-SentAuthAccepted', sentRecStatusId:sentRecStatusId]

// Se guarda DTE recibido en la base de datos
mapOut = ec.service.sync().name("create#mchile.dte.FiscalTaxDocument").parameters(ftdCreateMap).call()
fiscalTaxDocumentId = mapOut.fiscalTaxDocumentId

attributeCreateMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, date:ec.user.nowTimestamp, amount:dteMap.montoTotal, montoNeto:dteMap.montoNeto, montoExento:dteMap.montoExento, tasaImpuesto:dteMap.tasaIva, tipoImpuesto:1, montoIvaRecuperable:dteMap.iva, montoIvaNoRecuperable:0,
                      fechaEmision:dteMap.fechaEmision, razonSocialEmisor:dteMap.razonSocialEmisor, razonSocialReceptor:dteMap.razonSocialReceptor]

if (dteMap.tipoDteEnumId == 'Ftdt-52') {
    ec.service.sync().name("store#mchile.dte.GuiaDespacho").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, indTrasladoEnumId:dteMap.indTrasladoEnumId]).call()
}

mapOut = ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(attributeCreateMap).call()

contentLocationXml = "${locationReferenceBase}.xml"
docRrXml = ec.resource.getLocationReference("${locationReferenceBase}.xml")
docRrXml.putBytes(dteMap.dteBytes)

createMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:contentLocationXml, contentDate:dteMap.fechaEmision]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
if (pdfBytes) {
    contentLocationPdf = "${locationReferenceBase}.pdf"
    createMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:contentLocationPdf, contentDate:dteMap.fechaEmision]
    docRrPdf = ec.resource.getLocationReference(contentLoationPdf)
    docRrPdf.putBytes(pdfBytes)
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
}

// Se agregan las referencias
referenciaList.each { referencia ->
    if (invoiceId) {
        if (referencia.referenciaTipoDteEnumId == "Ftdt-801") {
            // Orden de Compra, va en el Invoice y no en mchile.dte.ReferenciaDte
            ec.service.sync().name("update#mantle.account.invoice.Invoice").parameters([invoiceId:invoiceId, otherPartyOrderId:referencia.folio, otherPartyOrderDate:referencia.refDate]).call()
        } else if (referencia.referenciaTipoDteEnumId && refDate) {
            ec.service.sync().name("create#mchile.dte.ReferenciaDte").parameters([invoiceId:invoiceId, referenciaTypeEnumId:'RefDteTypeInvoice', fiscalTaxDocumentTypeEnumId:referencia.referenciaTipoDteEnumId,
                                                                                  folio:referencia.folio, fecha: referencia.refDate, codigoReferenciaEnumId:referencia.codRefEnumId, razonReferencia:referencia.razonReferencia]).call()
        }
    }
    if (referencia.referenciaTipoDteEnumId)
        ec.service.sync().name("create#mchile.dte.ReferenciaDte").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, referenciaTypeEnumId:'RefDteTypeFiscalTaxDocument', fiscalTaxDocumentTypeEnumId:referencia.referenciaTipoDteEnumId,
                                                                              folio:referencia.folio, fecha: referencia.refDate, codigoReferenciaEnumId:referencia.codRefEnumId, razonReferencia:referencia.razonReferencia]).call()
}

if (envioId) {
    ec.service.sync().name("create#mchile.dte.DteEnvioFiscalTaxDocument").parameters([envioId:envioId, fiscalTaxDocumentId:fiscalTaxDocumentId]).call()
    envio = ec.entity.find("mchile.dte.DteEnvio").condition("envioId", envioId).forUpdate(true).one()
    if (envio.envioTypeEnumId == 'Ftde-EnvioDte') {
        if (envio.issuerPartyId == null)
            envio.issuerPartyId = issuerPartyId
        if (envio.receiverPartyId == null)
            envio.receiverPartyId = receiverPartyId
        envio.update()
    }
}
if (envioRespuestaId)
    ec.service.sync().name("create#mchile.dte.DteEnvioFiscalTaxDocument").parameters([envioId:envioRespuestaId, fiscalTaxDocumentId:fiscalTaxDocumentId]).call()

return
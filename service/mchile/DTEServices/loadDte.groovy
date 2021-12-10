import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.w3c.dom.Document

import java.math.RoundingMode
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import cl.moit.dte.MoquiDTEUtils

ExecutionContext ec = context.ec

boolean processDocument = true

if (domNode == null)
    ec.message.addError("No domNode present")

groovy.util.Node documento = MoquiDTEUtils.dom2GroovyNode(domNode)

errorMessages = []
discrepancyMessages = []
warningMessages = []

byte[] dteXml = MoquiDTEUtils.getRawXML(domNode)
Document doc2 = MoquiDTEUtils.parseDocument(dteXml)
if (!MoquiDTEUtils.verifySignature(doc2, "/sii:DTE/sii:Documento", null)) {
    errorMessages.add("Signature mismatch for document ${i}")
}

vatTaxRate = ec.service.sync().name("mchile.TaxServices.get#VatTaxRate").call().taxRate

totalCalculadoIva = 0 as BigDecimal
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
fchEmis = encabezado.IdDoc.FchEmis.text()

emisor = encabezado.Emisor
rutEmisor = emisor.RUTEmisor.text()

issuerPartyId = null
issuerTaxName = null
issuerPartyIdentificationList = ec.entity.find("mantle.party.PartyIdentification").condition([idValue:rutEmisor, partyIdTypeEnumId:'PtidNationalTaxId']).list()
if (issuerPartyIdentificationList.size() < 1) {
    if (createUnknownIssuer) {
        mapOut = ec.service.sync().name("mantle.party.PartyServices.create#Organization").parameters([organizationName:emisor.RznSoc.text(), taxOrganizationName:emisor.RznSoc.text(), roleTypeId:'Supplier']).call()
        issuerPartyId = mapOut.partyId
        ec.service.sync().name("create#mantle.party.PartyIdentification").parameters([partyId:issuerPartyId, partyIdTypeEnumId:'PtidNationalTaxId', idValue:rutEmisor]).call()
        ec.service.sync().name("create#mchile.dte.PartyGiro").parameters([partyId:issuerPartyId, description:emisor.GiroEmis.text(), isPrimary:'Y']).call()
        comunaList = ec.entity.find("moqui.basic.GeoAssocAndToDetail").condition("geoId", "CHL").condition(ec.entity.conditionFactory.makeCondition("geoName", EntityCondition.EQUALS, emisor.CmnaOrigen.text()).ignoreCase()).list()
        comunaId = comunaList? comunaList.first.geoId : null
        ec.service.sync().name("mantle.party.ContactServices.store#PartyContactInfo").parameters([partyId:issuerPartyId, address1:emisor.DirOrigen.text(),
                                                                                             postalContactMechPurposeId:'PostalTax', stateProvinceGeoId:comunaId, countryGeoId:"CHL", city:emisor.CiudadOrigen.text()]).call()
    } else {
        errorMessages.add("No se encuentra emisor ${rutEmisor}")
    }
} else if (issuerPartyIdentificationList.size() == 1) {
    issuerPartyId = issuerPartyIdentificationList.first.partyId
} else {
    ec.message.addError("Más de un sujeto con mismo rut de emisor (${rutEmisor}: partyIds ${issuerPartyIdentificationList.partyId}")
}

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
rsResult = ec.service.sync().name("mchile.DTEServices.compare#RazonSocial").parameters([rs1:issuerTaxName, rs2:razonSocialEmisor]).call()
if (!rsResult.equivalent) {
    discrepancyMessages.add("Razón Social mismatch, en BD '${issuerTaxName}', en documento ${i} '${razonSocialEmisor}'")
}

ec.logger.warn("folio: ${folioDte}")

// Totales
BigDecimal montoNeto = (encabezado.Totales.MntNeto.text() ?: 0) as BigDecimal
BigDecimal montoTotal = (encabezado.Totales.MntTotal.text() ?: 0) as BigDecimal
BigDecimal montoExento = (encabezado.Totales.MntExe.text() ?: 0) as BigDecimal
BigDecimal tasaIva = (encabezado.Totales.TasaIVA.text() ?: 0) as BigDecimal
BigDecimal iva = (encabezado.Totales.IVA.text() ?: 0) as BigDecimal
BigDecimal mntTotal = montoTotal

if ((montoNeto + montoExento + iva) != montoTotal) errorMessages.add("Total inválido (montoTotal no coincide con suma de monto neto, monto exento e iva.")
if (tasaIva / 100 != vatTaxRate) errorMessages.add("Tasa IVA no coincide: esperada: ${vatTaxRate*100}%, recibida: ${tasaIva}%")

// Datos receptor
rutReceptor = encabezado.Receptor.RUTRecep.text()
rutRecep = rutReceptor

if (rutReceptorCaratula != null && rutReceptorCaratula != rutReceptor) {
    discrepancyMessages.add("Rut mismatch: carátula indica Rut receptor ${rutEmisorCaratula}, pero documento ${i} indica ${rutEmisor}")
}

mapOut = ec.service.sync().name("mchile.DTEServices.get#MoquiSIICode").parameter("siiCode", tipoDte).call()
tipoDteEnumId = mapOut.fiscalTaxDocumentTypeEnumId
ec.logger.info("Buscando FiscalTaxDocument: ${[issuerPartyIdValue:rutEmisor, fiscalTaxDocumenTypeEnumId:tipoDteEnumId, fiscalTaxDocumentNumber:folioDte]}")
existingDteList = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([issuerPartyIdValue:rutEmisor, fiscalTaxDocumenTypeEnumId:tipoDteEnumId, fiscalTaxDocumentNumber:folioDte])
        .disableAuthz().list()
if (existingDteList) {
    errorMessages.add("Ya existe registrada DTE tipo ${tipoDte} para emisor ${rutEmisor} y folio ${folioDte}")
    dte = existingDteList.first
    contentList = ec.entity.find("mchile.dte.FiscalTaxDocumentContent").condition([fiscalTaxDocumentId:dte.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml'])
            .disableAuthz().list()
    if (contentList) {
    } else {
        ec.message.addError("No hay contenido local")
    }
}

DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd")
String fechaEmision = encabezado.IdDoc.FchEmis.text()
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

String razonSocialReceptor = encabezado.Receptor.RznSocRecep.text()
partyIdentificationList = ec.entity.find("mantle.party.PartyIdentification").condition([idValue:rutReceptor, partyIdTypeenumId:'PtidNationalTaxId']).list()
if (!partyIdentificationList) {
    if (createUnknownReceiver) {
        mapOut = ec.service.sync().name("mantle.party.PartyServices.create#Organization").parameters([organizationName:razonSocialReceptor, taxOrganizationName:razonSocialReceptor,
                                                                                                      roleTypeId:'Customer']).call()
        receiverPartyId = mapOut.partyId
        ec.service.sync().name("create#mantle.party.PartyIdentification").parameters([partyId:receiverPartyId, partyIdTypeEnumId:'PtidNationalTaxId', idValue:rutReceptor]).call()
        ec.service.sync().name("create#mchile.dte.PartyGiro").parameters([partyId:issuerPartyId, description:encabezado.receptor.giroRecep, isPrimary:'Y']).call()
    } else {
        ec.message.addError("No existe organización con RUT ${rutReceptor} (receptor) definida en el sistema")
        receiverPartyId = null
    }
} else if (partyIdentificationList.size() > 1) {
    ec.message.addError("Se encontró más de un sujeto con RUT ${rutEmisor}: ${partyIdentificationList.partyId}")
    receiverPartyId = partyIdentificationList.first.partyId
} else {
    receiverPartyId = partyIdentificationList.first.partyId
}
receiver = ec.entity.find("mantle.party.PartyDetail").condition("partyId", receiverPartyId).one()
// Verificación de Razón Social en XML vs lo guardado en Moqui
String razonSocialDb = receiver.taxOrganizationName
if (razonSocialDb == null || razonSocialDb.size() == 0)
    razonSocialDb = ec.resource.expand("PartyNameOnlyTemplate", null, receiver)
rsResult = ec.service.sync().name("mchile.DTEServices.compare#RazonSocial").parameters([rs1:razonSocialReceptor, rs2:razonSocialDb]).call()
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
    invoiceTypeEnumId = 'InvoiceFiscalTaxDocumentReception'
}
if (ec.message.hasError())
    return

invoiceCreateMap =  [fromPartyId:fromPartyId, toPartyId:toPartyId, invoiceTypeEnumId:invoiceTypeEnumId, invoiceDate:issuedTimestamp, currencyUomId:'CLP']
if (dueTimestamp)
    invoiceCreateMap.dueDate = dueTimestamp
invoiceMap = ec.service.sync().name("mantle.account.InvoiceServices.create#Invoice").parameters(invoiceCreateMap).disableAuthz().call()
invoiceId = invoiceMap.invoiceId
BigDecimal montoItem = 0 as BigDecimal
detalleList = documento.Documento.Detalle
ec.logger.warn("Recorriendo detalles: ${detalleList.size()}")
int j = 0
BigDecimal totalCalculado = 0
detalleList.each { detalle ->
    // Adición de items a orden
    ec.logger.warn("-----------------------------------")
    ec.logger.warn("Leyendo línea detalle " + j + ",")
    ec.logger.warn("Indicador exento: ${detalle.IndExe.text()}")
    ec.logger.warn("Nombre item: ${detalle.NmbItem.text()}")
    ec.logger.warn("Cantidad: ${detalle.QtyItem.text()}")
    ec.logger.warn("Precio: ${detalle.PrcItem.text()}")
    ec.logger.warn("Monto: ${detalle.MontoItem.text()}")
    itemDescription = detalle.NmbItem?.text()
    quantity = detalle.QtyItem ? (detalle.QtyItem.text() as BigDecimal) : null
    price = detalle.PrcItem ? (detalle.PrcItem.text() as BigDecimal) : null
    montoItem = detalle.MontoItem ? (detalle.MontoItem.text() as BigDecimal) : null
    totalCalculado += montoItem
    if (price == null && montoItem != null) {
        price = montoItem / quantity
    }
    try {
        indExe = detalle.IndExe?.text() as Integer
    } catch (NumberFormatException e) {
        indExe = null
    }
    // Si el indicador es no exento hay que agregar IVA como item aparte
    // Se puede ir sumando el IVA y si es mayor que 0 crear el item
    Boolean itemExento = false
    if (indExe == null && (tipoDte != '34')) {
        // Item y documento afecto
        ec.logger.warn("Item afecto")
        itemExento = false
    } else { // Item exento o documento exento
        ec.logger.warn("Item exento")
        itemExento = true
    }
    if (itemExento)
        totalExento += montoItem

    productId = null
    if (attemptProductMatch == 'false') {
        ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemSales',
                                                                                                productId: (itemExento? 'SRVCEXENTO': null), description: itemDescription, quantity: quantity, amount: price]).call()
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
                errorMessages.add("Exento mismatch, XML dice ${itemExento? '' : 'no '} exento, producto en BD dice ${exentoBd? '' : 'no '} exento")
            product = ec.entity.find("mantle.product.Product").condition("productId", productId).one()
            if (product.productName.toString().trim().toLowerCase() != itemDescription.trim().toLowerCase())
                errorMessages.add("Description mismatch, XML dice ${itemDescription}, producto en BD dice ${product.productName}")
            ec.logger.info("Agregando producto preexistente ${productId}, cantidad ${quantity} *************** orderId: ${orderId}")
        } else {
            if (itemExento)
                productId = 'SRVCEXENTO'
            ec.logger.warn("Producto ${itemDescription} no existe en el sistema, se creará como genérico")
        }
        ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemSales',
                                                                                                productId: productId, description: itemDescription, quantity: quantity, amount: price]).call()
    }

    j++
}

globalList = documento.Documento.DscRcgGlobal
Integer globalItemCount = 0
globalList.each { globalItem ->
    globalItemCount++
    tpoMov = globalItem.TpoMov.text()
    nroLinea = globalItem.NroLinDR.text() as Integer
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
        amount = globalItem.ValorDR.text() as BigDecimal
    } else if (tpoVal == '%') {
        pctValue = globalItem.ValorDR.text() as BigDecimal
        amount = totalCalculado / 100.0 * pctValue
    } else {
        errorMessages.add("Tipo valor inválido DscRcgGlobal ${globalItemCount}, se esperaba \$ o % y se recibió ${tpoVal}")
    }
    if (itemTypeEnumId == 'ItemDiscount')
        amount = -1 * amount
    ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:itemTypeEnumId,
                               description: globalItem.GlosaDR.text(), quantity:1, amount: amount]).call()
    totalCalculado += amount
}

if (totalCalculado != (montoNeto + montoExento)) errorMessages.add("Monto total (neto + exento) no coincide, calculado: ${totalCalculado}, recibido: ${montoNeto + montoExento}")

totalCalculadoIva = (montoNeto * vatTaxRate).setScale(0, RoundingMode.HALF_UP)

if (iva != totalCalculadoIva) {
    errorMessages.add("No coincide monto IVA, DTE indica ${iva}, calculado: ${totalCalculadoIva}")
}
if (totalCalculadoIva > 0) {
    ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemVatTax', description: 'IVA', quantity: 1, amount: totalCalculadoIva, taxAuthorityId:'CL_SII']).call()
}

invoice = ec.entity.find("mantle.account.invoice.Invoice").condition("invoiceId", invoiceId).one()
if (invoice.invoiceTotal != mntTotal)
    errorMessages.add("No coinciden totales, DTE indica ${mntTotal}, calculado: ${invoice.invoiceTotal}")

if (errorMessages.size() > 0) {
    estadoRecepDte = 2
    recepDteGlosa = 'RECHAZADO: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', ' + discrepancyMessages.join(', ')) : '')
    ec.logger.error(recepDteGlosa)
    return
} else if (discrepancyMessages.size() > 0) {
    estadoRecepDte = 1
    recepDteGlosa = 'ACEPTADO CON DISCREPANCIAS: ' + discrepancyMessages.join(', ')
    ec.logger.warn(recepDteGlosa)
    return
} else {
    estadoRecepDte = 0
    recepDteGlosa = 'ACEPTADO OK'
}

// Se guarda DTE recibido en la base de datos
createMap = [issuerPartyId:issuerPartyId, issuerPartyIdTypeEnumId:'PtidNationalTaxId', issuerPartyIdValue:rutEmisor, fiscalTaxDocumentTypeEnumId:tipoDteEnumId, fiscalTaxDocumentNumber:folioDte,
             receiverPartyId:receiverPartyId, receiverPartyIdTypeEnumId:'PtidNationalTaxId', receiverPartyIdValue:rutReceptor, date:issuedTimestamp, invoiceId:invoiceId, statusId:'Ftd-Issued',
             sendAuthStatusId:'Ftd-SentAuth', sendRecStatusId:'Ftd-SentRec']
mapOut = ec.service.sync().name("create#mchile.dte.FiscalTaxDocument").parameters(createMap).call()
fiscalTaxDocumentId = mapOut.fiscalTaxDocumentId
if (envioReciboId)
    ec.service.syn().name("create#mchile.dte.EnvioFiscalTaxDocument").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, envioReciboId:envioReciboId])

locationReferenceBase = "dbresource://moit/erp/dte/${rutEmisor}/DTE-${tipoDte}-${folioDte}"
contentLocationXml = "${locationReferenceBase}.xml"
docRrXml = ec.resource.getLocationReference("${locationReferenceBase}.xml")
docRrXml.putBytes(dteXml)

createMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:contentLocationXml, contentDate:issuedTimestamp]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
if (pdf) {
    contentLocationPdf = "${locationReferenceBase}.pdf"
    createMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:contentLocationPdf, contentDate:issuedTimestamp]
    docRrPdf = ec.resource.getLocationReference("${locationReferenceBase}.pdf")
    fileStream = pdf.getInputStream()
    try { docRrPdf.putStream(fileStream) } finally { fileStream.close() }
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
    mapOut = ec.service.sync().name("mchile.DTEServices.get#MoquiSIICode").parameter("siiCode", referencia.TpoDocRef.text()).call()
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
        ec.service.sync().name("update#mantle.account.invoice.Invoice").parameters([invoiceId:invoiceId, otherPartyOrderId:folio, otherPartyOrderDate:refDate]).call()
    } else if (tipoDteEnumId && refDate) {
        ec.service.sync().name("create#mchile.dte.ReferenciaDte").parameters([invoiceId:invoiceId, referenciaTypeEnumId:'RefDteTypeInvoice', fiscalTaxDocumentTypeEnumId:tipoDteEnumId,
                                                                              folio:folio, fecha: refDate, codigoReferenciaEnumId:codRefEnumId, razonReferencia:referencia.RazonRef?.text()]).call()
    }

}

if (envioDteId)
    ec.service.sync().name("create#mchile.dte.EnvioFiscalTaxDocument").parameters([envioId:envioDteId, fiscalTaxDocumentId:fiscalTaxDocumentId]).call()
if (envioRespuestaId)
    ec.service.sync().name("create#mchile.dte.EnvioFiscalTaxDocument").parameters([envioId:envioRespuestaId, fiscalTaxDocumentId:fiscalTaxDocumentId]).call()

import org.moqui.context.ExecutionContext

ExecutionContext ec = context.ec

boolean processDocument = true

if (domNode == null) {
    ec.message.addError("No domNode present")
    return
}

errorMessages = []
discrepancyMessages = []
internalErrors = []

Map<String, Object> dteMap = ec.service.sync().name("mchile.sii.dte.DteLoadServices.parse#Dte").parameter("dte", domNode).call()
if (ec.message.hasError())
    return
errorMessages.addAll(dteMap.errorMessages)
discrepancyMessages.addAll(dteMap.discrepancyMessages)

// Values to be returned
tipoDte = dteMap.tipoDte
folioDte = dteMap.folioDte
fechaEmision = dteMap.fechaEmision
rutEmisor = dteMap.rutEmisor
rutReceptor = dteMap.rutReceptor
montoTotal = dteMap.montoTotal
formaPago = dteMap.formaPago
formaPagoEv = ec.entity.find("moqui.basic.Enumeration").condition([enumTypeId:"FiscalTaxDocumentFormaPago"]).condition([enumCode:(formaPago as String)]).one()
formaPagoEnumId = formaPagoEv?.enumId
if (formaPagoEnumId == null)
    formaPagoEnumId = 'Ftdfp-Credito'

reserved = ec.service.sync().name("mchile.sii.SIIServices.get#RutEspeciales").call()

if (rutEmisor in reserved.rutList) {
    discrepancyMessages.add("Rut de emisor es Rut reservado, no se puede importar automáticamente")
    return
}

issuerPartyId = ec.service.sync().name("mchile.GeneralServices.get#PartyIdByRut").parameters([idValue:rutEmisor, createUnknown:createUnknownIssuer,
      organizationPartyIdAsOwnerWhenCreating:organizationPartyIdAsOwnerWhenCreating, razonSocial:dteMap.razonSocialEmisor,
      roleTypeId:'Supplier', giro:dteMap.giroEmisor, direccion:dteMap.direccionOrigen, comuna:dteMap.comunaOrigen,
      ciudad:dteMap.ciudadOrigen, failOnDuplicate:false]).call().partyId
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

rsResult = ec.service.sync().name("mchile.sii.dte.DteInternalServices.compare#RazonSocial").parameters([rs1:issuerTaxName, rs2:dteMap.razonSocialEmisor]).call()
if (!rsResult.equivalent) {
    discrepancyMessages.add("Razón Social mismatch, en BD '${issuerTaxName}', en documento '${dteMap.razonSocialEmisor}'")
}

// Datos receptor
if (rutReceptor in reserved.rutList) {
    errorMessages.add("Rut de receptor es Rut reservado, no se puede importar automáticamente")
    estadoRecepDte = 2
    recepDteGlosa = 'RECHAZADO, Errores: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
    if (recepDteGlosa.length()  > 256) recepDteGlosa = recepDteGlosa.substring(0, 256)
    sentRecStatusId = 'Ftd-ReceiverReject'
    return
}

if (rutReceptorCaratula != null && rutReceptorCaratula != rutReceptor) {
    discrepancyMessages.add("Rut mismatch: carátula indica Rut receptor ${rutReceptorCaratula}, pero documento indica ${rutReceptor}")
}

existingDteList = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([issuerPartyIdValue:rutEmisor, fiscalTaxDocumenTypeEnumId:dteMap.tipoDteEnumId, fiscalTaxDocumentNumber:dteMap.fiscalTaxDocumentNumber])
        .disableAuthz().list()
isDuplicated = false

if (existingDteList) {
    dte = existingDteList.first
    if (dte.sentRecStatusId == 'Ftd-ReceiverReject') {
        if (dte.sentRecStatusId == 'Ftd-ReceiverReject')
            ec.logger.info("Existente tiene estado rechazado, eliminando para partir de cero")
        else
            ec.logger.info("Existente era obtenido desde SII (sólo metadata), eliminando para partir de cero")
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
            attrib = ec.entity.find("mchile.dte.FiscalTaxDocumentAttributes").condition("fiscalTaxDocumentId", dte.fiscalTaxDocumentId).forUpdate(true).one()
            attributeMap = [amount               : 'montoTotal', montoNeto: 'montoNeto', montoExento: 'montoExento', tasaImpuesto: 'tasaIva', tipoImpuesto: 'tipoImpuesto', montoIVARecuperable: 'iva',
                            montoIVANoRecuperable: 'montoIvaNoRecuperable', fechaEmision: 'fechaEmision', fechaVencimiento: 'fechaVencimiento', razonSocialEmisor: 'razonSocialEmisor',
                            razonSocialReceptor  : 'razonSocialReceptor']
            dteMap.tipoImpuesto = 1
            dteMap.montoIvaNoRecuperable = 0
            changed = false
            attributeMap.each { entityFieldName, mapFieldName ->
                if (entityFieldName in ['tipoImpuesto', 'montoIvaNoRecuperable', 'tasaImpuesto', 'montoIVANoRecuperable', 'fechaVencimiento'] && attrib[entityFieldName] == null) {
                    attrib[entityFieldName] = dteMap[mapFieldName]
                    changed = true
                }
                if (attrib[entityFieldName] != dteMap[mapFieldName]) {
                    if (entityFieldName in ['razonSocialReceptor', 'razonSocialEmisor'])
                        ec.message.addMessage("Value mismatch for attribute field ${entityFieldName}, XML value: ${dteMap[mapFieldName]}, DB value: ${attrib[entityFieldName]}", "warning")
                    else
                        ec.message.addError("Value mismatch for attribute field ${entityFieldName}, XML value: ${dteMap[mapFieldName]}, DB value: ${attrib[entityFieldName]}")
                }
            }
            if (changed)
                attrib.update()
            if (ec.message.hasError())
                return
            ec.service.sync().name("mchile.sii.dte.DteContentServices.store#DteContent").parameters([fiscalTaxDocumentId:dte.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml',
                                                                                                     documentContent:dteMap.dteBytes]).call()
            if (dteMap.referenciaList)
                ec.service.sync().name("mchile.sii.dte.DteReferenceServices.store#DteReferences").parameters([fiscalTaxDocumentId:dte.fiscalTaxDocumentId, referenciaList:dteMap.referenciaList]).call()
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
            errorMessages.add("Ya existe registrada DTE tipo ${dteMap.tipoDte} para emisor ${rutEmisor} y folio ${dteMap.fiscalTaxDocumentNumber}, diferente al recibido")
            estadoRecepDte = 2
            recepDteGlosa = 'RECHAZADO, Errores: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
            if (recepDteGlosa.length()  > 256) recepDteGlosa = recepDteGlosa.substring(0, 256)
            isDuplicated = true
            return
        }
    }
}

receiverPartyId = ec.service.sync().name("mchile.GeneralServices.get#PartyIdByRut").parameters([idValue:rutReceptor, createUnknown:createUnknownReceiver,
                                    organizationPartyIdAsOwnerWhenCreating:organizationPartyIdAsOwnerWhenCreating, razonSocial:dteMap.razonSocialReceptor,
                                    roleTypeId:'Customer', giro:dteMap.giroReceptor, direccion:dteMap.direccionReceptor, comuna:dteMap.comunaReceptor, ciudad:dteMap.ciudadReceptor,
                                    failOnDuplicate: false]).call().partyId
receiver = ec.entity.find("mantle.party.PartyDetail").condition("partyId", receiverPartyId).one()
// Verificación de Razón Social en XML vs lo guardado en Moqui
String razonSocialDb = receiver.taxOrganizationName
if (razonSocialDb == null || razonSocialDb.size() == 0)
    razonSocialDb = ec.resource.expand("PartyNameOnlyTemplate", null, receiver)
rsResult = ec.service.sync().name("mchile.sii.dte.DteInternalServices.compare#RazonSocial").parameters([rs1:dteMap.razonSocialReceptor, rs2:razonSocialDb]).call()
if ((!rsResult.equivalent)) {
    ec.logger.warn("Razón social en XML no coincide con la registrada: $dteMap.razonSocialReceptor != $razonSocialDb")
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

if (ec.message.hasError()) {
    estadoRecepDte = 2
    recepDteGlosa = 'RECHAZADO, Errores: ' + ec.message.getErrors().join(', ') + ((errorMessages.size() > 0) ? (', ' + errorMessages.join(', ')) : '')
        + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
    sentRecStatusId = 'Ftd-ReceiverReject'
    return
}

ftdCreateMap = [issuerPartyId:issuerPartyId, issuerPartyIdTypeEnumId:'PtidNationalTaxId', issuerPartyIdValue:rutEmisor, fiscalTaxDocumentTypeEnumId:dteMap.tipoDteEnumId, fiscalTaxDocumentNumber:dteMap.fiscalTaxDocumentNumber,
                receiverPartyId:receiverPartyId, receiverPartyIdTypeEnumId:'PtidNationalTaxId', receiverPartyIdValue:rutReceptor, date:fechaEmision, statusId:'Ftd-Issued',
                sentAuthStatusId:'Ftd-SentAuthAccepted', sentRecStatusId:sentRecStatusId, formaPagoEnumId:formaPagoEnumId]

// Se guarda DTE recibido en la base de datos
mapOut = ec.service.sync().name("create#mchile.dte.FiscalTaxDocument").parameters(ftdCreateMap).call()
fiscalTaxDocumentId = mapOut.fiscalTaxDocumentId

attributeCreateMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, date:ec.user.nowTimestamp, amount:montoTotal, montoNeto:dteMap.montoNeto, montoExento:dteMap.montoExento, tasaImpuesto:dteMap.tasaIva, tipoImpuesto:1, montoIVARecuperable:dteMap.iva, montoIVANoRecuperable:0,
                      fechaEmision:fechaEmision, fechaVencimiento:dteMap.fechaVencimiento, razonSocialEmisor:dteMap.razonSocialEmisor, razonSocialReceptor:dteMap.razonSocialReceptor]

if (dteMap.tipoDteEnumId == 'Ftdt-52') {
    ec.service.sync().name("store#mchile.dte.GuiaDespacho").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, indTrasladoEnumId:dteMap.indTrasladoEnumId]).call()
}

mapOut = ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(attributeCreateMap).call()

ec.service.sync().name("mchile.sii.dte.DteContentServices.store#DteContent").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml',
                                                                                         documentContent:dteMap.dteBytes]).call()

if (pdfBytes) {
    ec.service.sync().name("mchile.sii.dte.DteContentServices.store#DteContent").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf',
                                                                                             documentContent:dteMap.pdfBytes]).call()
}

invoiceOut = ec.service.sync().name("mchile.sii.dte.DteLoadServices.create#InvoiceFromDte").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, parsedDteXmlMap:dteMap, hasErrors:(errorMessages.size() > 0)]).call()

invoiceId = invoiceOut.invoiceId
discrepancyMessages.addAll(invoiceOut.discrepancyMessages)

invoice = ec.entity.find("mantle.account.invoice.Invoice").condition("invoiceId", invoiceId).forUpdate(true).one()

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

ec.service.sync().name("update#mchile.dte.FiscalTaxDocument").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, invoiceId:invoiceId]).call()


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
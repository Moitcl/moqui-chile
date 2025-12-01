import org.moqui.context.ExecutionContext
import cl.moit.dte.MoquiDTEUtils
import org.w3c.dom.Element

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

partyIdByRutMap = [idValue:rutEmisor, createUnknown:createUnknownIssuer,
                   organizationPartyIdAsOwnerWhenCreating:organizationPartyIdAsOwnerWhenCreating, additionalAllowedOwnerPartyIdList:additionalAllowedOwnerPartyIdList,
                   razonSocial:dteMap.razonSocialEmisor, roleTypeId:'Supplier', giro:dteMap.giroEmisor, direccion:dteMap.direccionOrigen, comuna:dteMap.comunaOrigen,
                   ciudad:dteMap.ciudadOrigen, failOnDuplicate:false]
issuerPartyId = ec.service.sync().name("mchile.GeneralServices.get#PartyIdByRut").parameters(partyIdByRutMap).call().partyId
issuerTaxName = null
if (!issuerPartyId) {
    ec.message.addError("No se encuentra emisor, partyIdByRutMap: ${partyIdByRutMap}")
    return
}
EntityValue issuer = ec.entity.find("mantle.party.PartyDetail").condition("partyId", issuerPartyId).one()
issuerTaxName = issuer?.taxOrganizationName
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

skipReceiverRazonSocialCheck = false
// Datos receptor
if (rutReceptor in reserved.rutList) {
    createUnknownReceiver = false
    if (rutReceptor == '55555555-5' || (rutReceptor == '66666666-6' && dteMap.tipoDteEnumId in ['Ftdt-39', 'Ftdt-41'])) {
        skipReceiverRazonSocialCheck = false
    } else {
        errorMessages.add("Rut de receptor es Rut reservado, no se puede importar automáticamente")
        estadoRecepDte = 2
        recepDteGlosa = 'RECHAZADO, Errores: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
        if (recepDteGlosa.length()  > 256) recepDteGlosa = recepDteGlosa.substring(0, 256)
        sentRecStatusId = 'Ftd-ReceiverToReject'
        return
    }
}

if (rutReceptorCaratula != null && rutReceptorCaratula != rutReceptor) {
    discrepancyMessages.add("Rut mismatch: carátula indica Rut receptor ${rutReceptorCaratula}, pero documento indica ${rutReceptor}")
}

existingDteList = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([issuerPartyIdValue:rutEmisor, fiscalTaxDocumentTypeEnumId:dteMap.tipoDteEnumId, fiscalTaxDocumentNumber:dteMap.fiscalTaxDocumentNumber])
        .disableAuthz().list()
isDuplicated = false

receiverPartyId = ec.service.sync().name("mchile.GeneralServices.get#PartyIdByRut").parameters([idValue:rutReceptor, createUnknown:createUnknownReceiver,
                     organizationPartyIdAsOwnerWhenCreating:organizationPartyIdAsOwnerWhenCreating, razonSocial:dteMap.razonSocialReceptor,
                     additionalAllowedOwnerPartyIdList:additionalAllowedOwnerPartyIdList, roleTypeId:'Customer', giro:dteMap.giroReceptor, direccion:dteMap.direccionReceptor,
                     comuna:dteMap.comunaReceptor, ciudad:dteMap.ciudadReceptor, failOnDuplicate: false]).call().partyId
receiver = ec.entity.find("mantle.party.PartyDetail").condition("partyId", receiverPartyId).one()

// Verify and update PostalTax address if needed
if (receiverPartyId && dteMap.direccionReceptor) {
    // Get current PostalTax address for receiver
    Map comunaResult = null
    if (dteMap.comunaReceptor)
        comunaResult = ec.service.sync().name("mchile.GeoServices.find#Comuna").parameters([comunaName:dteMap.comunaReceptor, errorWhenNotFound:false]).call()
    Map postalAddressResultMap = ec.service.sync().name("mantle.party.ContactServices.findOrCreate#PartyPostalAddress").parameters([partyId:receiverPartyId,
                    address1:dteMap.direccionReceptor?.trim(), contactMechPurposeId:'PostalTax', stateProvinceGeoId:comunaResult?.geoId, countryGeoId:'CHL',
                    city:dteMap.ciudadReceptor]).call()
}

// Verificación de Razón Social en XML vs lo guardado en Moqui
if (!skipReceiverRazonSocialCheck) {
    String razonSocialDb = receiver?.taxOrganizationName
    if (razonSocialDb == null || razonSocialDb.size() == 0)
        razonSocialDb = ec.resource.expand("PartyNameOnlyTemplate", null, receiver)
    rsResult = ec.service.sync().name("mchile.sii.dte.DteInternalServices.compare#RazonSocial").parameters([rs1:dteMap.razonSocialReceptor, rs2:razonSocialDb]).call()
    if ((!rsResult.equivalent)) {
        ec.logger.warn("Razón social en XML no coincide con la registrada: $dteMap.razonSocialReceptor != $razonSocialDb")
    }
}

internalRole = ec.entity.find("mantle.party.PartyRole").condition([partyId:receiverPartyId, roleTypeId:'OrgInternal']).one()
receiverIsInternalOrg = internalRole != null
if (requireReceiverInternalOrg && !receiverIsInternalOrg) {
    errorMessages.add("Sujeto receptor de documento ${i} (${ec.resource.expand('PartyNameTemplate', null, receiver)}, rut ${rutReceptor}) no es organización interna")
}

if (existingDteList) {
    dte = existingDteList.first
    fiscalTaxDocumentId = dte.fiscalTaxDocumentId
    if (dte.sentRecStatusId in ['Ftd-ReceiverToReject', 'Ftd-ReceiverReject'] && issuerIsInternalOrg) {
        if (dte.sentRecStatusId in ['Ftd-ReceiverToReject', 'Ftd-ReceiverReject'] )
            ec.logger.info("Existente tiene estado rechazado, eliminando para partir de cero")
        else
            ec.logger.info("Existente era obtenido desde SII (sólo metadata), eliminando para partir de cero")
        // remove existing DTE and start from scratch
        ec.service.sync().name("delete#mchile.dte.FiscalTaxDocumentAttributes").parameter("fiscalTaxDocumentId", fiscalTaxDocumentId).call()
        ec.service.sync().name("delete#mchile.dte.FiscalTaxDocumentContent").parameter("fiscalTaxDocumentId", fiscalTaxDocumentId).parameter("fiscalTaxDocumentContentId", "*").call()
        ec.service.sync().name("delete#mchile.dte.FiscalTaxDocumentEmailMessage").parameter("fiscalTaxDocumentId", fiscalTaxDocumentId).parameter("emailMessageId", "*").call()
        ec.service.sync().name("delete#mchile.dte.ReferenciaDte").parameter("fiscalTaxDocumentId", fiscalTaxDocumentId).parameter("referenciaId", "*").call()
        ec.service.sync().name("delete#mchile.dte.DteEnvioFiscalTaxDocument").parameter("fiscalTaxDocumentId", fiscalTaxDocumentId).parameter("envioId", "*").call()
        ec.service.sync().name("delete#mchile.dte.FiscalTaxDocument").parameter("fiscalTaxDocumentId", fiscalTaxDocumentId).call()
    } else {
        contentList = ec.entity.find("mchile.dte.FiscalTaxDocumentContent").condition([fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml'])
                .disableAuthz().list()
        if (contentList.size() == 0) {
            changed = false
            dteMap.issuerPartyIdTypeEnumId = 'PtidNationalTaxId'
            dteMap.issuerPartyId = issuerPartyId
            dteMap.receiverPartyId = receiverPartyId
            dteMap.receiverPartyIdTypeEnumId = 'PtidNationalTaxId'
            dteMap.receiverPartyIdValue = rutReceptor
            dteMap.date = fechaEmision
            dteMap.statusId = 'Ftd-Issued'
            dteMap.sentAuthStatusId = sentAuthStatusId
            dteMap.formaPagoEnumId = formaPagoEnumId
            dteFieldListConstant = ['issuerPartyIdTypeEnumId', 'issuerPartyId', 'receiverPartyIdTypeEnumId', 'receiverPartyIdValue', 'date']
            dteFieldListConstant.each { entityFieldName ->
                if (dteMap[entityFieldName] != dte[entityFieldName])
                    if (dte[entityFieldName] == null) {
                        dte[entityFieldName] = dteMap[entityFieldName]
                        changed = true
                    } else
                        ec.message.addError("Value mismatch for attribute field ${entityFieldName}, XML value: ${dteMap[entityFieldName]}, DB value: ${dte[entityFieldName]}")
            }
            if (dteMap.receiverPartyId != dte.receiverPartyId) {
                // Check if dte.receiverPartyId is descendant of dteMap.receiverPartyId
                if (dte.receiverPartyId == null) {
                    dte.receiverPartyId = dteMap.receiverPartyId
                    changed = true
                } else {
                    childPartyIdList = ec.service.sync().name("mchile.sii.dte.DteLoadServices.get#Descendants").parameters([partyId:dteMap.receiverPartyId,
                                                                                                                         allowOwnRut:allowReceiverChildWithDifferentRut]).call().childPartyIdList
                    if (!(dte.receiverPartyId in childPartyIdList))
                        ec.message.addError("Mismatch for receiverPartyId, XML value: ${dteMap.receiverPartyId}, DB value: ${dte.receiverPartyId} (is not child${allowReceiverChildWithDifferentRut? '' : ' sharing same RUT'})")
                }
            }
            dteFieldListOverwrite = ['statusId', 'formaPagoEnumId']
            dteFieldListOverwrite.each { entityFieldName ->
                if (dteMap[entityFieldName] != dte[entityFieldName]) {
                    ec.logger.warn("Changing ${entityFieldName} from ${dte[entityFieldName]} to ${dteMap[entityFieldName]}")
                    dte[entityFieldName] = dteMap[entityFieldName]
                    changed = true
                }
            }
            if (changed)
                dte.update()

            attrib = ec.entity.find("mchile.dte.FiscalTaxDocumentAttributes").condition("fiscalTaxDocumentId", fiscalTaxDocumentId).forUpdate(true).one()
            if (attrib == null) {
                ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters([fiscalTaxDocumentId: fiscalTaxDocumentId]).call()
                attrib = ec.entity.find("mchile.dte.FiscalTaxDocumentAttributes").condition("fiscalTaxDocumentId", fiscalTaxDocumentId).forUpdate(true).one()
            }
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
                    if (attrib[entityFieldName] == null) {
                        attrib[entityFieldName] = dteMap[mapFieldName]
                        changed = true
                    } else {
                        if (entityFieldName in ['razonSocialReceptor', 'razonSocialEmisor'])
                            ec.message.addMessage("Value mismatch for attribute field ${entityFieldName}, XML value: ${dteMap[mapFieldName]}, DB value: ${attrib[entityFieldName]}", "warning")
                        else
                            ec.message.addError("Value mismatch for attribute field ${entityFieldName}, XML value: ${dteMap[mapFieldName]}, DB value: ${attrib[entityFieldName]}")
                    }
                }
            }
            if (changed)
                attrib.update()
            if (ec.message.hasError())
                return
        } else {
            xmlInDb = ec.resource.getLocationReference(contentList.first().contentLocation).openStream().readAllBytes()
            dbDoc = MoquiDTEUtils.parseDocument(xmlInDb)
            Element dbDocElem = dbDoc.getDocumentElement()
            String canonicalFromDb = MoquiDTEUtils.canonicalizeXmlToString(dbDocElem).replaceAll("\\s*xmlns=\"http://www.sii.cl/SiiDte\"\\s*", " ").replaceAll(">\\s+<", "><")
            String canonicalFromEnvio = MoquiDTEUtils.canonicalizeXmlToString(domNode).replaceAll("\\s*xmlns=\"http://www.sii.cl/SiiDte\"\\s*", " ").replaceAll(">\\s+<", "><")
            isDuplicated = canonicalFromDb.equals(canonicalFromEnvio)
            ec.logger.info("Comparando DTE en BD con DTE de envío usando canonicalización, son equivalentes: ${isDuplicated}")

            if (dte.sentRecStatusId in ['Ftd-ReceiverAck', 'Ftd-ReceiverAccept']) {
                if (isDuplicated) {
                    ec.logger.warn("Contenido existe, DTE está aprobado, enviando aceptación")
                    estadoRecepDte = 0
                    recepDteGlosa = 'ACEPTADO OK'
                    sentRecStatusId = 'Ftde-DuplicateNotProcessed'
                    return
                } else if (sendResponse) {
                    errorMessages.add("Ya existe registrada DTE tipo ${dteMap.tipoDte} para emisor ${rutEmisor} y folio ${dteMap.fiscalTaxDocumentNumber}, diferente al recibido")
                    estadoRecepDte = 2
                    recepDteGlosa = 'RECHAZADO, Errores: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
                    if (recepDteGlosa.length()  > 256) recepDteGlosa = recepDteGlosa.substring(0, 256)
                    return
                } else {
                    estadoRecepDte = 2
                    return
                }
            } else if (!isDuplicated) {
                errorMessages.add("Ya existe registrada DTE tipo ${dteMap.tipoDte} para emisor ${rutEmisor} y folio ${dteMap.fiscalTaxDocumentNumber}, diferente al recibido")
                estadoRecepDte = 2
                recepDteGlosa = 'RECHAZADO, Errores: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
                if (recepDteGlosa.length()  > 256) recepDteGlosa = recepDteGlosa.substring(0, 256)
                return
            }
            if (dte.receiverPartyId == null && receiverPartyId != null) {
                dte.receiverPartyId = receiverPartyId
                dte.update()
            }
        }
    }
}

if (issuerPartyId == null)
    ec.message.addError("Empty issuerPartyId")
if (receiverPartyId == null)
    ec.message.addError("Empty receiverPartyId")

if (ec.message.hasError()) {
    estadoRecepDte = 2
    recepDteGlosa = 'A Reclamar, Errores: ' + ec.message.getErrors().join(', ') + ((errorMessages.size() > 0) ? (', ' + errorMessages.join(', ')) : '')
                    + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
    if (recepDteGlosa.length()  > 256) recepDteGlosa = recepDteGlosa.substring(0, 256)
    sentRecStatusId = 'Ftd-ReceiverToReject'
    return
}

if (!dte) {

    ftdCreateMap = [issuerPartyId:issuerPartyId, issuerPartyIdTypeEnumId:'PtidNationalTaxId', issuerPartyIdValue:rutEmisor, fiscalTaxDocumentTypeEnumId:dteMap.tipoDteEnumId, fiscalTaxDocumentNumber:dteMap.fiscalTaxDocumentNumber,
                receiverPartyId:receiverPartyId, receiverPartyIdTypeEnumId:'PtidNationalTaxId', receiverPartyIdValue:rutReceptor, date:fechaEmision, statusId:'Ftd-Issued',
                sentAuthStatusId:sentAuthStatusId, sentRecStatusId:sentRecStatusId, formaPagoEnumId:formaPagoEnumId]

    // Se guarda DTE recibido en la base de datos
    mapOut = ec.service.sync().name("create#mchile.dte.FiscalTaxDocument").parameters(ftdCreateMap).call()
    fiscalTaxDocumentId = mapOut.fiscalTaxDocumentId

    attributeCreateMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, amount:montoTotal, montoNeto:dteMap.montoNeto, montoExento:dteMap.montoExento, tasaImpuesto:dteMap.tasaIva, tipoImpuesto:1, montoIVARecuperable:dteMap.iva, montoIVANoRecuperable:0,
                      fechaEmision:fechaEmision, fechaVencimiento:dteMap.fechaVencimiento, razonSocialEmisor:dteMap.razonSocialEmisor, razonSocialReceptor:dteMap.razonSocialReceptor]

    mapOut = ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(attributeCreateMap).call()
}

if (dteMap.tipoDteEnumId == 'Ftdt-52') {
    ec.service.sync().name("store#mchile.dte.GuiaDespacho").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, indTrasladoEnumId:dteMap.indTrasladoEnumId]).call()
}

if (!isDuplicated)
    ec.service.sync().name("mchile.sii.dte.DteContentServices.store#DteContent").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml',
                                                                                         documentContent:dteMap.dteBytes, failOnExisting:false]).call()

if (pdfBytes) {
    ec.service.sync().name("mchile.sii.dte.DteContentServices.store#DteContent").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf',
                                                                                             documentContent:dteMap.pdfBytes]).call()
}

invoiceId = dte?.invoiceId

if (invoiceId == null) {
    invoiceOut = ec.service.sync().name("mchile.sii.dte.DteLoadServices.create#InvoiceFromDte").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId, parsedDteXmlMap:dteMap, hasErrors:(errorMessages.size() > 0)]).call()

    invoiceId = invoiceOut.invoiceId
    discrepancyMessages.addAll(invoiceOut.discrepancyMessages)
}

invoice = ec.entity.find("mantle.account.invoice.Invoice").condition("invoiceId", invoiceId).forUpdate(true).one()

if (errorMessages.size() > 0) {
    estadoRecepDte = 2
    recepDteGlosa = 'A Reclamar, Errores: ' + errorMessages.join(', ') + ((discrepancyMessages.size() > 0) ? (', Discrepancias: ' + discrepancyMessages.join(', ')) : '')
    sentRecStatusId = 'Ftd-ReceiverToReject'
    ec.logger.error(recepDteGlosa)
    if (recepDteGlosa.length() > 255) recepDteGlosa = recepDteGlosa.substring(0, 256)
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

updateDteMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, invoiceId:invoiceId]
if (receiverIsInternalOrg)
    updateDteMap.sentRecStatusId=sentRecStatusId
ec.service.sync().name("update#mchile.dte.FiscalTaxDocument").parameters(updateDteMap).call()

if (!isDuplicated) {
    // Se agregan las referencias
    dteMap.referenciaList.each { referencia ->
        tipoEnumEv = ec.entity.find("moqui.basic.Enumeration").condition("enumId", referencia.referenciaTipoDteEnumId).one()
        esTipoTributario = (tipoEnumEv?.parentEnumId in ['Ftdt-DT', 'Ftdt-DTE'])
        if (referencia.rutOtro) {
            rutEmisorFolio = referencia.rutOtro
        } else if (esTipoTributario) {
            // Mismo rut del emisor del presente documento
            rutEmisorFolio = rutEmisor
        } else {
            rutEmisorFolio = null
        }
        if (invoiceId) {
            if (referencia.referenciaTipoDteEnumId == "Ftdt-801") {
                // Orden de Compra, va en el Invoice además de mchile.dte.ReferenciaDte
                ec.service.sync().name("update#mantle.account.invoice.Invoice").parameters([invoiceId: invoiceId, otherPartyOrderId: referencia.folio, otherPartyOrderDate: referencia.refDate]).call()
            } else if (referencia.referenciaTipoDteEnumId && refDate) {
                ec.service.sync().name("create#mchile.dte.ReferenciaDte")
                        .parameters([invoiceId     : invoiceId, referenciaTypeEnumId: 'RefDteTypeInvoice', fiscalTaxDocumentTypeEnumId: referencia.referenciaTipoDteEnumId,
                                     folio         : referencia.folio, fecha: referencia.refDate, codigoReferenciaEnumId: referencia.codRefEnumId, razonReferencia: referencia.razonReferencia,
                                     rutEmisorFolio: rutEmisorFolio, tipoDocumento: referencia.tipoDocumento, nroLinea: referencia.nroLinRef]).call()
            }
        }
        if (referencia.referenciaTipoDteEnumId)
            ec.service.sync().name("create#mchile.dte.ReferenciaDte")
                    .parameters([fiscalTaxDocumentId: fiscalTaxDocumentId, referenciaTypeEnumId: 'RefDteTypeFiscalTaxDocument', fiscalTaxDocumentTypeEnumId: referencia.referenciaTipoDteEnumId,
                                 folio              : referencia.folio, fecha: referencia.refDate, codigoReferenciaEnumId: referencia.codRefEnumId, razonReferencia: referencia.razonReferencia,
                                 rutEmisorFolio     : rutEmisorFolio, tipoDocumento: referencia.tipoDocumento, nroLinea: referencia.nroLinRef]).call()
    }
}

// When importing external DTEs, don't fail on negative modification totals as external documents may have valid edge cases
ec.service.sync().name("mchile.sii.dte.DteModificationRegistrationServices.check#DteModificationReferences")
        .parameter("fiscalTaxDocumentId", fiscalTaxDocumentId)
        .parameter("checkPositiveModifiedTotal", false)
        .call()

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

if (dteMap.correoEmisor) {
    emailValidator = org.apache.commons.validator.routines.EmailValidator.instance
    if (emailValidator.isValid(dteMap.correoEmisor)) {
        contactMechId = ec.service.sync().name("mantle.party.ContactServices.findOrCreate#PartyEmailAddress").parameters([emailAddress:dteMap.correoEmisor, partyId:issuerPartyId,
                        contactMechPurposeId:'DteIssuerEmail']).call().contactMechId
    }
    if (contactMechId && invoiceId) {
        ec.service.sync().name("store#mantle.account.invoice.InvoiceContactMech").parameters([invoiceId:invoiceId, contactMechPurposeId:'DteIssuerEmail',
                                                                                               contactMechId:contactMechId]).call()
    }
}

return
import cl.nic.dte.VerifyResult

import java.text.DateFormat
import java.text.SimpleDateFormat

import org.apache.xmlbeans.XmlOptions

import cl.sii.siiDte.EnvioDTEDocument
import cl.sii.siiDte.EnvioDTEDocument.EnvioDTE
import cl.sii.siiDte.DTEDefType.Documento.Detalle
import org.moqui.context.ExecutionContext

ExecutionContext ec = context.ec

// Debo meter el namespace porque SII no lo genera
HashMap<String, String> namespaces = new HashMap<String, String>()
namespaces.put("", "http://www.sii.cl/SiiDte")
namespaces.put("xmlns:siid", "http://www.sii.cl/SiiDte")
namespaces.put("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")

XmlOptions opts = new XmlOptions()
opts.setCharacterEncoding("ISO-8859-1")
//opts.setSaveImplicitNamespaces(namespaces)
opts.setLoadSubstituteNamespaces(namespaces)
opts.setLoadAdditionalNamespaces(namespaces)
opts.setSaveImplicitNamespaces(namespaces)

EnvioDTE.SetDTE setDTE = null
EnvioDTE envio = null

try {
    ByteArrayInputStream bais = new ByteArrayInputStream(xml)
    envio = EnvioDTEDocument.Factory.parse(bais).getEnvioDTE()
    bais.close()
    setDTE = envio.setDTE
} catch (Exception e) {
    ec.logger.warn("Could not parse as EnvioDTE (${e.toString()}")
}

// Caratula
rutEmisorCaratula = setDTE.caratula?.rutEmisor
String issuerPartyId = null
String issuerTaxName = null
if (rutEmisorCaratula) {
    issuerPartyIdentificationList = ec.entity.find("mantle.party.PartyIdentification").condition([idValue:rutEmisorCaratula, partyIdTypeEnumId:'PtidNationalTaxId']).list()
    if (issuerPartyIdentificationList.size() < 1) {
        if (createUnknownIssuer) {
            cl.sii.siiDte.DTEDefType.Documento.Encabezado.Emisor emisor = dteArray[0].documento.encabezado.emisor
            mapOut = ec.service.sync().name("mantle.party.PartyServices.create#Organization").parameters([organizationName:emisor.rznSoc, taxOrganizationName:emisor.rznSoc, roleTypeId:'Supplier']).call()
            issuerPartyId = mapOut.partyId
            ec.service.sync().name("create#mantle.party.PartyIdentification").parameters([partyId:issuerPartyId, partyIdTypeEnumId:'PtidNationalTaxId', idValue:rutEmisorCaratula]).call()
            ec.service.sync().name("create#mchile.dte.PartyGiro").parameters([partyId:issuerPartyId, description:emisor.giroEmis, isPrimary:'Y']).call()
            emisor.dirOrigen
        } else {
            ec.message.addError("No existe organización con RUT ${rutReceptor} (emisor) definida en el sistema")
        }
    } else if (issuerPartyIdentificationList.size() == 1) {
        issuerPartyId = issuerPartyIdentificationList.first.partyId
    } else {
        ec.message.addError("Más de un sujeto con mismo rut de emisor (${rutEmisorCaratula}: partyIds ${issuerPartyIdentificationList.partyId}")
    }

    EntityValue issuer = ec.entity.find("mantle.party.PartyDetail").condition("partyId", issuerPartyId).one()
    issuerTaxName = issuer.taxOrganizationName
    if (issuerTaxName == null || issuerTaxName.size() == 0)
        issuerTaxName = ec.resource.expand("PartyNameOnlyTemplate", null, issuer)
}

ec.logger.warn("Emisor según carátula: ${rutEmisorCaratula}, issuerTaxName ${issuerTaxName}")

cl.sii.siiDte.DTEDefType[] dteArray = setDTE.getDTEArray()
if (dteArray.size() < 1) {
    ec.message.addError("Documento no contiene DTEs")
    return
}

for (int i = 0; i < dteArray.size(); i++) {
    totalIva = 0 as Long
    // tipo de DTE
    cl.sii.siiDte.DTEDefType.Documento.Encabezado encabezado = dteArray[i].documento.encabezado
    /*
    verResult = dteArray[i].verifyXML()
    if (verResult.code != VerifyResult.XML_STRUCTURE_OK)
        ec.message.addError("Error en XML documento ${i}: ${verResult.message}")
     */
    verResult = dteArray[i].verifySignature()
    if (verResult.code != VerifyResult.XML_SIGNATURE_OK)
        ec.logger.warn("Error en firma documento ${i}: ${verResult.message}")
    verResult = dteArray[i].verifyTimbre()
    if (verResult.code != VerifyResult.TED_OK)
        ec.logger.warn("Error en timbre documento ${i}: ${verResult.message}")
    tipoDte = encabezado.idDoc.tipoDTE.toString()
    folioDte = encabezado.idDoc.folio

    rutEmisor = encabezado.emisor.getRUTEmisor()

    mapOut = ec.service.sync().name("mchile.DTEServices.get#MoquiSIICode").parameter("siiCode", tipoDte).call()
    tipoDteEnumId = mapOut.fiscalTaxDocumentTypeEnumId
    ec.logger.info("Buscando FiscalTaxDocument: ${[issuerPartyIdValue:rutEmisor, fiscalTaxDocumenTypeEnumId:tipoDteEnumId, fiscalTaxDocumentNumber:folioDte]}")
    existingDteList = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([issuerPartyIdValue:rutEmisor, fiscalTaxDocumenTypeEnumId:tipoDteEnumId, fiscalTaxDocumentNumber:folioDte])
            .disableAuthz().list()
    if (existingDteList) {
        ec.logger.info("Ya existe registrada DTE tipo ${tipoDteEnumId} para emisor ${rutEmisor} y folio ${folioDte}")
        dte = existingDteList.first
        contentList = ec.entity.find("mchile.dte.FiscalTaxDocumentContent").condition([fiscalTaxDocumentId:dte.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml'])
                .disableAuthz().list()
        if (contentList) {
        } else {
            ec.message.addError("No hay contenido local")
        }
        continue
    }

    if (rutEmisorCaratula == null) {
        // Is not EnvioDTE but SetDTE (no caratula, each issuer is independent)
        issuerPartyId = null
        issuerTaxName = null
        issuerPartyIdentificationList = ec.entity.find("mantle.party.PartyIdentification").condition([idValue:rutEmisor, partyIdTypeEnumId:'PtidNationalTaxId']).list()
        if (issuerPartyIdentificationList.size() < 1) {
            if (createUnknownIssuer) {
                cl.sii.siiDte.DTEDefType.Documento.Encabezado.Emisor emisor = dteArray[0].documento.encabezado.emisor
                mapOut = ec.service.sync().name("mantle.party.PartyServices.create#Organization").parameters([organizationName:emisor.rznSoc, taxOrganizationName:emisor.rznSoc, roleTypeId:'Supplier']).call()
                issuerPartyId = mapOut.partyId
                ec.service.sync().name("create#mantle.party.PartyIdentification").parameters([partyId:issuerPartyId, partyIdTypeEnumId:'PtidNationalTaxId', idValue:rutEmisor]).call()
                ec.service.sync().name("create#mchile.dte.PartyGiro").parameters([partyId:issuerPartyId, description:emisor.giroEmis, isPrimary:'Y']).call()
                emisor.dirOrigen
            } else {
                ec.message.addError("No se encuentra emisor ${rutEmisor}")
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
    } else {
        if (rutEmisor != rutEmisorCaratula) {
            ec.message.addError("Rut mismatch: carátula indica Rut ${rutEmisorCaratula}, pero documento ${i} indica ${rutEmisor}")
        }
    }

    internalRole = ec.entity.find("mantle.party.PartyRole").condition([partyId:issuerPartyId, roleTypeId:'OrgInternal']).one()
    issuerIsInternalOrg = (internalRole != null)
    if (requireIssuerInternalOrg && !issuerIsInternalOrg) {
        ec.message.addError("Sujeto emisor de documento ${i} (${ec.resource.expand('PartyNameTemplate', null, issuer)}, rut ${rutEmisor}) no es organización interna")
    }

    rsResult = ec.service.sync().name("mchile.DTEServices.compare#RazonSocial").parameters([rs1:issuerTaxName, rs2:encabezado.emisor.rznSoc]).call()
    if (!rsResult.equivalent) {
        ec.message.addError("Razón Social mismatch, en BD '${issuerTaxName}', en documento ${i} '${encabezado.emisor.rznSoc}'")
    }

    ec.logger.warn("folio: ${folioDte}")

    razonSocialEmisor = encabezado.emisor.rznSoc

    // Totales
    montoNeto = encabezado.totales.mntNeto.toString()
    montoTotal = encabezado.totales.mntTotal.toString()
    montoExento = encabezado.totales.mntExe.toString()
    tasaIva = encabezado.totales.tasaIVA.toString()
    iva = encabezado.totales.IVA.toString()

    // Datos receptor
    rutReceptor = encabezado.receptor.getRUTRecep()

    ec.logger.warn("RUT Receptor: ${rutReceptor}")

    DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd")
    String fechaEmision = encabezado.idDoc.fchEmis.toString()
    String fechaVencimiento = encabezado.idDoc.fchVenc.toString()
    Date date = formatter.parse(fechaEmision)
    Timestamp issuedTimestamp = new Timestamp(date.getTime())
    Timestamp dueTimestamp
    if (fechaVencimiento != null && fechaVencimiento != 'null') {
        date = formatter.parse(fechaVencimiento)
        dueTimestamp = new Timestamp(date.getTime())
    } else {
        dueTimestamp = null
    }

    String razonSocialReceptor = encabezado.receptor.rznSocRecep
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
    if ((razonSocialReceptor != razonSocialDb)) {
        ec.logger.warn("Razón social en XML no coincide con la registrada: $razonSocialReceptor != $razonSocialDb")
    }

    internalRole = ec.entity.find("mantle.party.PartyRole").condition([partyId:receiverPartyId, roleTypeId:'OrgInternal']).one()
    receiverIsInternalOrg = internalRole != null
    if (requireReceiverInternalOrg && !receiverIsInternalOrg) {
        ec.message.addError("Sujeto receptor de documento ${i} (${ec.resource.expand('PartyNameTemplate', null, receiver)}, rut ${rutReceptor}) no es organización interna")
    }

    // Creación de orden de compra
    invoiceCreateMap =  [fromPartyId:issuerPartyId, toPartyId:receiverPartyId, invoiceTypeEnumId:'InvoiceFiscalTaxDocumentReception', invoiceDate:issuedTimestamp, currencyUomId:'CLP']
    if (dueTimestamp)
        invoiceCreateMap.dueDate = dueTimestamp
    invoiceMap = ec.service.sync().name("mantle.account.InvoiceServices.create#Invoice").parameters(invoiceCreateMap).disableAuthz().call()
    invoiceId = invoiceMap.invoiceId
    montoItem = 0 as Long
    Detalle[] detalleArray = dteArray[i].documento.detalleArray
    ec.logger.warn("Recorriendo detalles: ${detalleArray.size()}")
    for (int j = 0; j < detalleArray.size(); j++) {
        // Adición de items a orden
        ec.logger.warn("-----------------------------------")
        ec.logger.warn("Leyendo línea detalle " + j + ",")
        ec.logger.warn("Indicador exento: ${detalleArray[j].indExe}")
        ec.logger.warn("Nombre item: ${detalleArray[j].nmbItem}")
        ec.logger.warn("Cantidad: ${detalleArray[j].qtyItem}")
        ec.logger.warn("Precio: ${detalleArray[j].prcItem}")
        ec.logger.warn("Monto: ${detalleArray[j].montoItem}")
        itemDescription = detalleArray[j].nmbItem
        quantity = detalleArray[j].qtyItem
        price = detalleArray[j].prcItem
        montoItem = detalleArray[j].montoItem
        if (price == null && montoItem != null) {
            price = montoItem / quantity
        }
        // Si el indicador es no exento hay que agregar IVA como item aparte
        // Se puede ir sumando el IVA y si es mayor que 0 crear el item
        Boolean itemExento = null
        if (detalleArray[j].indExe == null && (tipoDte != '34')) {
            // Item y documento afecto
            ec.logger.warn("Item afecto")
            itemExento = false
        } else { // Item exento o documento exento
            ec.logger.warn("Item exento")
            itemExento = true
        }
        if (!itemExento)
            totalIva = (montoItem * 0.19) + totalIva

        productId = null
        if (attemptProductMatch == 'false') {
            ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemSales',
                                                        productId: (itemExento? 'SRVCEXENTO': null), description: itemDescription, quantity: quantity, amount: price]).call()
        } else {
            ec.logger.warn("Buscando código item")
            cl.sii.siiDte.DTEDefType.Documento.Detalle.CdgItem[] cdgItem = detalleArray[j].cdgItemArray
            for (int k = 0; k < cdgItem.size(); k++) {
                // Check explicit product relation with external ID
                ec.logger.warn("Leyendo codigo ${k}, valor: ${cdgItem[k].vlrCodigo}")
                if (issuerIsInternalOrg) {
                    // Look up product directly
                    pseudoId = cdgItem[k].vlrCodigo
                    product = ec.entity.find("mantle.product.Product").condition("pseudoId", pseudoId).one()
                    if (product) {
                        productId = product.productId
                        break
                    }
                } else {
                    productPartyList = ec.entity.find("mantle.product.ProductParty").condition([partyId: issuerPartyId, otherPartyItemId: cdgItem[k].vlrCodigo, roleTypeId: 'Supplier'])
                            .conditionDate("fromDate", "thruDate", issuedTimestamp).orderBy("-fromDate").list()
                    if (productPartyList) {
                        productId = productPartyList.first.productId
                        break
                    }
                }
            }

            // Check Exento category
            if (productId) {
                exentoList = ec.entity.find("mantle.product.category.ProductCategoryMember").condition([productCategoryId:'', productId:productId])
                        .conditionDate("fromDate", "thruDate", issuedTimestamp).list()
                exentoBd = exentoList.size() > 0
                if (exentoBd != itemExento)
                    ec.message.addError("Exento mismatch, XML dice ${itemExento? '' : 'no '} exento, producto en BD dice ${exentoBd? '' : 'no '} exento")
                product = ec.entity.find("mantle.product.Product").condition("productId", productId).one()
                if (product.productName.toString().trim().toLowerCase() != itemDescription.trim().toLowerCase())
                    ec.message.addError("Description mismatch, XML dice ${itemDescription}, producto en BD dice ${product.productName}")
                ec.logger.info("Agregando producto preexistente ${productId}, cantidad ${quantity} *************** orderId: ${orderId}")
            } else {
                if (itemExento)
                    productId = 'SRVCEXENTO'
                ec.logger.warn("Producto ${itemDescription} no existe en el sistema, se creará como genérico")
            }
            ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemSales',
                                                                                                    productId: productId, description: itemDescription, quantity: quantity, amount: price]).call()
        }

    }

    ec.logger.warn("Total IVA: ${totalIva}")
    if (totalIva > 0) {
        ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem").parameters([invoiceId: invoiceId, itemTypeEnumId:'ItemVatTax', description: 'IVA', quantity: 1, amount: price, taxAuthorityId:'CL_SII']).call()
    }

    // Se guarda DTE recibido en la base de datos
    createMap = [issuerPartyId:issuerPartyId, issuerPartyIdTypeEnumId:'PtidNationalTaxId', issuerPartyIdValue:rutEmisor, fiscalTaxDocumentTypeEnumId:tipoDteEnumId, fiscalTaxDocumentNumber:folioDte,
                 receiverPartyId:receiverPartyId, receiverPartyIdTypeEnumId:'PtidNationalTaxId', receiverPartyIdValue:rutReceptor, date:issuedTimestamp, invoiceId:invoiceId, statusId:'Ftd-Issued',
                 sendAuthStatusId:'Ftd-SentAuth', sendRecStatusId:'Ftd-SentRec']
    mapOut = ec.service.sync().name("create#mchile.dte.FiscalTaxDocument").parameters(createMap).call()

    // Se guarda contenido asociado a la DTE, todas las DTE que vienen en el mismo envío comparten el mismo PDF
    locationReferenceBase = "dbresource://moit/erp/dte/${rutEmisor}/DTE-${tipoDte}-${folioDte}"
    contentLocationXml = "${locationReferenceBase}.xml"
    docRrXml = ec.resource.getLocationReference("${locationReferenceBase}.xml")
    ByteArrayOutputStream baos = new ByteArrayOutputStream()
    dteArray[i].getDocumento().save(baos, saveOpts)
    docRrXml.putBytes(baos.toByteArray())
    baos.close()
    createMap = [fiscalTaxDocumentId:mapOut.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:contentLocationXml, contentDate:issuedTimestamp]
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
    if (pdf) {
        contentLocationPdf = "${locationReferenceBase}.pdf"
        createMap = [fiscalTaxDocumentId:mapOut.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:contentLocationPdf, contentDate:issuedTimestamp]
        docRrPdf = ec.resource.getLocationReference("${locationReferenceBase}.pdf")
        fileStream = pdf.getInputStream()
        try { docRrPdf.putStream(fileStream) } finally { fileStream.close() }
        ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
    }

}
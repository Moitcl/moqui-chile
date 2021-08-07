import java.text.DateFormat
import java.text.SimpleDateFormat

import org.apache.xmlbeans.XmlOptions

import cl.sii.siiDte.EnvioDTEDocument
import cl.sii.siiDte.EnvioDTEDocument.EnvioDTE
import cl.sii.siiDte.DTEDefType.Documento.Detalle
import org.moqui.context.ExecutionContext

ExecutionContext ec = context.ec

// Carga de RUT de empresa (ya validado)
rut = ec.service.sync("mchile.GeneralServices.get#RutForParty").parameters([partyId:organizationPartyId, failIfNotFound:true]).call().rut

// Carga XML
archivoXml = xml.getName()
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameters([partyId:organizationPartyId]).call())
fileRoot = pathRecibidas
contentLocationXml = "${fileRoot}/${archivoXml}"
docRrXml = ec.resource.getLocationReference(contentLocationXml)

InputStream fileStream = xml.getInputStream()
try { docRrXml.putStream(fileStream) } finally { fileStream.close() }

// Carga PDF
archivoPdf = pdf.getName()
if (archivoPdf) {
    contentLocationPdf = "${fileRoot}/${archivoPdf}"
    docRrPdf = ec.resource.getLocationReference(contentLocationPdf)
    fileStream = pdf.getInputStream()
    try { docRrPdf.putStream(fileStream) } finally { fileStream.close() }
}
totalIva = 0 as Long

// Debo meter el namespace porque SII no lo genera
HashMap<String, String> namespaces = new HashMap<String, String>()
namespaces.put("", "http://www.sii.cl/SiiDte")
namespaces.put("xmlns:siid", "http://www.sii.cl/SiiDte")
namespaces.put("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")

XmlOptions opts = new XmlOptions()
//opts.setSaveImplicitNamespaces(namespaces)
opts.setLoadSubstituteNamespaces(namespaces)
opts.setLoadAdditionalNamespaces(namespaces)


EnvioDTE envio = EnvioDTEDocument.Factory.parse(xml.getInputStream()).getEnvioDTE()

// Caratula
rutEmisor = envio.setDTE.getCaratula().getRutEmisor().toString()

// DTE
cl.sii.siiDte.DTEDefType[] dteArray = envio.setDTE.getDTEArray()
for (int i = 0; i < dteArray.size(); i++) {
    // tipo de DTE
    tipoDte = dteArray[i].getDocumento().getEncabezado().getIdDoc().getTipoDTE().toString()
    folioDte = dteArray[i].getDocumento().getEncabezado().getIdDoc().getFolio().toString()

    ec.logger.warn("folio: ${folioDte}")

    String fechaEmision = dteArray[i].getDocumento().getEncabezado().getIdDoc().getFchEmis().toString()
    razonSocialEmisor = dteArray[i].getDocumento().getEncabezado().getEmisor().getRznSoc().toString()
    // Totales
    montoNeto = dteArray[i].getDocumento().getEncabezado().getTotales().getMntNeto().toString()
    montoTotal = dteArray[i].getDocumento().getEncabezado().getTotales().getMntTotal().toString()
    montoExento = dteArray[i].getDocumento().getEncabezado().getTotales().getMntExe().toString()
    tasaIva = dteArray[i].getDocumento().getEncabezado().getTotales().getTasaIVA().toString()
    iva = dteArray[i].getDocumento().getEncabezado().getTotales().getIVA().toString()

    // Datos receptor
    rutReceptor = dteArray[i].getDocumento().getEncabezado().getReceptor().getRUTRecep().toString()

    ec.logger.warn("RUT Receptor: ${rutReceptor}")

    DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd")
    Date date = formatter.parse(fechaEmision)
    Timestamp ts = new Timestamp(date.getTime())

    receiveDte = true

    receiverEv = ec.entity.find("mantle.party.PartyIdentification").condition([idValue:rutReceptor, partyIdTypeEnumId:'PtidNationalTaxId']).one()

    if (!receiverEv) {
        ec.logger.warn("RUT receptor ${rutReceptor} de folio folioDte no corresponde a org. activa")
        receiveDte = false
    }

    if (receiveDte) {
        receiverPartyId = receiverEv.partyId
        partyIdentificationList = ec.entity.find("mantle.party.PartyIdentification").condition([idValue:rutEmisor, partyIdTypeenumId:'PtidNationalTaxId']).list()
        // TODO: Verificar caso en que emisor tenga varias razones sociales
        if (!partyIdentificationList) {
            ec.message.addError("No existe organización con RUT ${rutEmisor} definida en el sistema")
            return
        }
        issuerPartyId = partyIdentificationList.first.partyId
        // Verificación de Razón Social en XML vs lo guardado en Moqui
        partyEv = ec.entity.find("mantle.party.Party").condition("partyId", issuerPartyId).one()
        if (!partyEv) {
            ec.message.addError("Receptor no existe")
            return
        }
        partyTypeEnumId = partyEv.partyTypeEnumId
        razonSocialMoqui = (partyEv.partyTypeEnumId == 'PtyOrganization') ? partyEv.organization?.organizationName : "${partyEv.party?.firstName} ${partyEv.party?.lastName}"
        if ((razonSocialEmisor != razonSocialMoqui) && (partyTypeEnumId == 'PtyOrganization')) {
            ec.message.addError("Razón social en XML no coincide con la registrada: $razonSocialEmisor != $razonSocialMoqui")
            return
        }
        mapOut = ec.service.sync().name("mchile.DTEServices.get#MoquiSIICode").parameter("siiCode", tipoDte).call()
        tipoDteEnumId = mapOut.fiscalTaxDocumentTypeEnumId
        // Creación de orden de compra
        purchaseOutMap = ec.service.syn().name("mchile.PurchaseServices.create#Purchase").parameter("vendorPartyId", issuerPartyId).call()
        montoItem 0 as Long
        Detalle[] detalleArray = dteArray[i].getDocumento().getDetalleArray()
        ec.logger.warn("Recorriendo detalles: ${detalleArray.size()}")
        for (int j = 0; j < detalleArray.size(); j++) {
            // Adición de items a orden
            ec.logger.warn("-----------------------------------")
            ec.logger.warn("Leyendo línea detalle " + j + ",")
            ec.logger.warn("Indicador exento: ${detalleArray[j].getIndExe()}")
            ec.logger.warn("Nombre item: ${detalleArray[j].getNmbItem()}")
            ec.logger.warn("Cantidad: ${detalleArray[j].getQtyItem()}")
            ec.logger.warn("Precio: ${detalleArray[j].getPrcItem()}")
            ec.logger.warn("Monto: ${detalleArray[j].getMontoItem()}")
            itemDescription = detalleArray[j].getNmbItem()
            quantity = detalleArray[j].getQtyItem()
            price = detalleArray[j].getPrcItem()

            montoItem = detalleArray[j].getMontoItem()
            // Si el indicador es no exento hay que agregar IVA como item aparte
            // Se puede ir sumando el IVA y si es mayor que 0 crear el item
            if (detalleArray[j].getIndExe() == null && (tipoDte != '34')) {
                // Item y documento afecto
                ec.logger.warn("Item afecto")
                indExe = null
            } else { // Item exento o documento exento
                ec.logger.warn("Item exento")
                indExe = 1
            }
            if (!indExe)
                totalIva = (montoItem * 0.19) + totalIva
            if (!invoiceId) {
                if (productMatch == 'false') {
                    ec.context.putAll(ec.service.sync().name("mantle.order.OrderServices.create#OrderItem").parameters([orderId: purchaseOutMap.orderId, orderPartSeqId: purchaseOutMap.orderPartSeqId, itemDescription: itemDescription, quantity: quantity, unitAmount: price, itemTypeEnumId: 'ItemExpOther']).call())
                } else {
                    ec.logger.warn("Buscando código item")
                    cl.sii.siiDte.DTEDefType.Documento.Detalle.CdgItem[] cdgItem = detalleArray[j].getCdgItemArray()
                    for (int k = 0; k < cdgItem.size(); k++) {
                        ec.logger.warn("Leyendo codigo ${k}, valor: ${cdgItem[k].getVlrCodigo()}")
                        pseudoId = cdgItem[k].getVlrCodigo()
                        productEv = ec.entity.find("mantle.product.Product").condition("pseudoId", pseudoId).one()
                        if (productEv) {
                            productId = productEv.productId
                            ec.context.putAll(ec.service.sync().name("mantle.order.OrderServices.add#OrderProductQuantity").parameters([orderId:purchaseOutMap.orderId,
                                                                                                                                     orderPartSeqId:purchaseOutMap.orderPartSeqId, productId:productId, description:itemDescription, quantity:quantity, unitAmount: price]).call())
                            ec.logger.info("Agregando producto preexistente $productId, cantidad $quantity *************** orderId: $orderId")
                        } else {
                            ec.logger.warn("Producto $itemDescription no existe en el sistema, se creará como genérico")
                            ec.context.putAll(ec.service.sync().name("mantle.order.OrderServices.create#OrderItem").parameters([orderId:purchaseOutMap.orderId,
                                                                                                                             orderPartSeqId:purchaseOutMap.orderPartSeqId, itemDescription:itemDescription, quantity:quantity, unitAmount:price, itemTypeEnumId:'ItemExpOther']).call())
                        }
                    }
                }

            }
        }
    }
    ec.logger.warn("Total IVA: $totalIva")
    if (!invoiceId) {
        if (totalIva > 0) {
            ec.context.putAll(ec.service.sync().name("mantle.order.OrderServices.create#OrderItem").parameters([orderId:purchaseOutMap.orderId,
                                                                                                             orderPartSeqId:purchaseOutMap.orderPartSeqId, itemDescription:'Monto IVA Total', quantity:1, unitAmount:totalIva,
                                                                                                             itemTypeEnumId:'ItemVatTax']).call())
        }
        // Cierre de orden de compra
        placePurchaseOut = ec.service.sync().name("mchile.PurchaseServices.place#Order").parameters([orderId:purchaseOutMap.orderId,
                                                                                                     orderPartSeqId:purchaseOutMap.orderPartSeqId]).call()
        approveMap = ec.service.sync().name("mantle.order.OrderServices.autoApprove#Order").parameters([orderId:purchaseOutMap.orderId,
                                                                                                        orderPartSeqId:purchaseOutMap.orderPartSeqId]).call()
        // Creación de Invoice
        invoiceOutMap = ec.service.sync().name("mantle.account.InvoiceServices.create#EntireOrderPartInvoice").parameters([orderId:purchaseOutMap.orderId, orderPartSeqId:purchaseOutMap.orderPartSeqId]).call()
        ec.logger.warn("Invoice $invoiceOutMap.invoiceId creada para factura XML")
        receiveOrderOut = ec.service.sync().name("mchile.PurchaseServices.receive#Order").parameters([orderId:purchaseOutMap.orderId,
                                                                                                      orderPartSeqId:purchaseOutMap.orderPartSeqId,facilityId:facilityId, activeOrgId:organizationPartyId]).call()
        invoiceId = invoiceOutMap.invoiceId
    }

    // Se guarda DTE recibido en la base de datos
    createMap = [issuerPartyId:issuerPartyId, issuerPartyIdTypeEnumId:'PtidNationalTaxId', fiscalTaxDocumentTypeEnumId:tipoDteEnumId, fiscalTaxDocumentNumber:folioDte,
                 receiverPartyId:organizationPartyId, receiverPartyIdTypeEnumId:'PtidNationalTaxId', date:ts, invoiceId:invoiceId]
    mapOut = ec.service.sync().name("create#mchile.dte.FiscalTaxDocument").parameters(createMap).call()

    // Se guarda contenido asociado a la DTE, todas las DTE que vienen en el mismo envío comparten el mismo XML
    createMap = [fiscalTaxDocumentId:mapOut.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:contentLocationXml, contentDate:ts]
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
    if (contentLocationPdf) {
        createMap = [fiscalTaxDocumentId:mapOut.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:contentLocationPdf, contentDate:ts]
        ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
    }
}
import java.text.DateFormat
import java.text.SimpleDateFormat

import org.apache.xmlbeans.XmlOptions

import cl.sii.siiDte.boletas.EnvioBOLETADocument
import cl.sii.siiDte.boletas.BOLETADefType.Documento.Detalle
import org.moqui.context.ExecutionContext

ExecutionContext ec = context.ec

// Carga de RUT de empresa (ya validado)
rut = ec.service.sync("mchile.GeneralServices.get#RutForParty").parameters([partyId:organizationPartyId, failIfNotFound:true]).call().rut
// Carga XML
archivoXml = xml.getName()
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameters([partyId:organizationPartyId]).call())
contentLocationXml = "dbresource://moit/erp/dte/${rut}/DTERECIBIDA-39-${invoiceId}.xml"
docRrXml = ec.resource.getLocationReference(contentLocationXml)

// Se guardan ambos archivos
InputStream fileStream = xml.getInputStream()
try { docRrXml.putStream(fileStream) } finally { fileStream.close() }

// Carga PDF
archivoPdf = pdf.getName()
if (archivoPdf) {
    contentLocationPdf = "dbresource://moit/erp/dte/${rut}/DTERECIBIDA-39-${invoiceId}.pdf"
    docRrPdf = ec.resource.getLocationReference(contentLocationPdf)
    // Se guardan ambos archivos
    fileStream = pdf.getInputStream()
    try { docRrPdf.putStream(fileStream) } finally { fileStream.close() }
}

totalIva = 0 as Long

// Debo meter el namespace porque SII no lo genera
HashMap<String, String> namespaces = new HashMap<String, String>()
namespaces.put("", "http://www.sii.cl/SiiDte")
namespaces.put("xmlns:siid", "http://www.sii.cl/SiiDte")
namespaces.put("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
//namespaces.put("xsi:schemaLocation","http://www.sii.cl/SiiDte EnvioDTE_v10.xsd")
//XmlOptions opts = new XmlOptions()
//opts.setLoadSubstituteNamespaces(namespaces)

XmlOptions opts = new XmlOptions()
//opts.setSaveImplicitNamespaces(namespaces)
opts.setLoadSubstituteNamespaces(namespaces)
opts.setLoadAdditionalNamespaces(namespaces)


cl.sii.siiDte.boletas.EnvioBOLETADocument.EnvioBOLETA boleta = EnvioBOLETADocument.Factory.parse(xml.getInputStream()).getEnvioBOLETA()
// Caratula
String rutEmisor = boleta.setDTE.getCaratula().getRutEmisor().toString()
String rutReceptor = boleta.setDTE.getCaratula().getRutReceptor().toString()

ec.logger.warn("Emisor: " + rutEmisor + ", receptor: " + rutReceptor)


//montoNeto = envio.setDTE.getDTEArray().toString()
//cl.sii.siiDte.boletas.BOLETADefType[] boletaArray = envio.setDTE.getDTEArray()
cl.sii.siiDte.boletas.BOLETADefType[] boletaArray = boleta.setDTE.getDTEArray()

for (int i = 0; i < boletaArray.size(); i++) {
    // tipo de DTE
    tipoDte = boletaArray[i].getDocumento().getEncabezado().getIdDoc().getTipoDTE().toString()
    folioDte = boletaArray[i].getDocumento().getEncabezado().getIdDoc().getFolio().toString()
    String fechaEmision = boletaArray[i].getDocumento().getEncabezado().getIdDoc().getFchEmis().toString()
    razonSocialEmisor = boletaArray[i].getDocumento().getEncabezado().getEmisor().getRznSocEmisor().toString()
    // Totales
    montoNeto = boletaArray[i].getDocumento().getEncabezado().getTotales().getMntNeto().toString()
    montoTotal = boletaArray[i].getDocumento().getEncabezado().getTotales().getMntTotal().toString()
    montoExento = boletaArray[i].getDocumento().getEncabezado().getTotales().getMntExe().toString()
    //tasaIva = boletaArray[i].getDocumento().getEncabezado().getTotales().getTasaIVA().toString()
    iva = boletaArray[i].getDocumento().getEncabezado().getTotales().getIVA().toString()

    ec.logger.warn("Leído: ${tipoDte} - ${folioDte} - ${fechaEmision} - ${montoNeto} - ${iva}")
    ec.logger.warn("MontoExe: ${montoExento} - Razon social: ${razonSocialEmisor}")
    DateFormat formatter = new SimpleDateFormat("yyyy-MM-dd")
    Date date = formatter.parse(fechaEmision)
    ec.logger.warn("Date: " + date)
    Timestamp ts = new Timestamp(date.getTime())

    receiverEv = ec.entity.find("mantle.party.PartyIdentification").condition([idValue:rutReceptor, partyIdTypeEnumId:"PtidNationalTaxId"]).one()
    receiverPartyId = receiverEv.partyId
    if (receiverPartyId != organizationPartyId) {
        ec.message.addError("Receptor en Boleta no corresponde a receptor especificado ($receiverPartyId != $organizationPartyId)")
        return
    }
    partyIdentificationList = ec.entity.find("mantle.party.PartyIdentification").condition([idValue:rutEmisor, partyIdTypeEnumId: "PtidNationalTaxId"]).list()
    // TODO: Verificar caso en que emisor tenga varias razones sociales -->
    if (!partyIdentificationList) {
        ec.message.addError("Organización no tiene RUT definido")
        return
    }
    issuerPartyId = partyIdentificationList.partyId[0]
    // Verificación de Razón Social en XML vs lo guardado en Moqui
    partyEv = ec.entity.find("mantle.party.Party").condition("partyId", issuerPartyId).one()
    if (!partyEv) {
        ec.message.addError("Receptor no existe")
        return
    }
    partyTypeEnumId = partyEv.partyTypeEnumId
    razonSocialMoqui = null
    if (partyTypeEnumId == "PtyOrganization") {
        partyOrgEv = ec.entity.find("mantle.party.Organization").condition("partyId", issuerPartyId).one()
        if (partyOrgEv)
            razonSocialMoqui = partyOrgEv.organizationName
    } else {
        partyPersonEv = ec.entity.find("mantle.party.Person").condition("partyId", issuerPartyId).one()
        razonSocialMoqui = partyPersonEv.firstName + " " + partyPersonEv.lastName
    }
    if ((razonSocialEmisor != razonSocialMoqui) && (partyTypeEnumId == 'PtyOrganization')) {
        ec.message.addError("Razón social en XML no coindice con la registrada: $razonSocialEmisor != $razonSocialMoqui")
        return
    }
    mapOut = ec.service.sync().name("mchile.DTEServices.get#MoquiSIICode").parameters([siiCode:tipoDte]).call()
    tipoDteEnumId = mapOut.fiscalTaxDocumentTypeEnumId
    // Creación de orden de compra
    purchaseOutMap = ec.service.sync().name("mchile.PurchaseServices.create#Purchase").parameters([vendorPartyId:issuerPartyId]).call()
    itemDescription = ""
    quantity = ""
    price = ""
    indExe = "" // 1 exento, 2 no facturable, 3 garantía dep. envases, 4 item no venta (guia despacho), 5 guia despacho, 6 no facturable)
    productId = ""
    pseudoId = ""
    montoItem = 0 as Long
    Detalle[] detalleArray = boletaArray[i].getDocumento().getDetalleArray()
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
        if(detalleArray[j].getIndExe() == null && (tipoDte != '34') ) { // Item y documento afecto
            ec.logger.warn("Item afecto")
            indExe = null
        } else { // Item exento o documento exento
            ec.logger.warn("Item exento")
            indExe = 1
        }
        if (!indExe) {
            // TODO: get IVA rate from service
            totalIva += montoItem * 0.19
        }
        if (!invoiceId) {
            if (productMatch == 'false') {

                ec.context.putAll(ec.service.sync().name("mantle.order.OrderServices.create#OrderItem").parameters([orderId:purchaseOutMap.orderId,
                                   orderPartSeqId:purchaseOutMap.orderPartSeqId, itemDescription:itemDescription, quantity:quantity, unitAmount:price, itemTypeEnumId:'ItemExpOther']).call())
            } else {
                // Se especificó buscar productos preexistentes, pueden haber hasta 5 códigos
                ec.logger.warn("Buscando código item")
                cl.sii.siiDte.DTEDefType.Documento.Detalle.CdgItem[] cdgItem = detalleArray[j].getCdgItemArray()
                for (int k = 0; k < cdgItem.size(); k++) {
                    ec.logger.warn("Leyendo codigo ${k}, valor: ${cdgItem[k].getVlrCodigo()}")
                    pseudoId = cdgItem[k].getVlrCodigo()

                    productEv = ec.entity.find("mantle.product.Product").condition("pseudoId", pseudoId).one()
                    if (productEv) {
                        productId = productEv.productId
                        ec.logger.info("Agregando producto preexistente $productId, cantidad $quantity ***************")
                        ec.context.putAll(ec.service.sync().name("mantle.order.OrderServices.add#OrderProductQuantity").parameters([orderId:purchaseOutMap.orderId,
                            orderPartSeqId:purchaseOutMap.orderPartSeqId, productId:productId, description:itemDescription, quantity:quantity, unitAmount:price]).call())
                    } else {
                        ec.logger.warn("Producto $itemDescription no existe en el sistema, se creará como genérico")
                        ec.context.putAll(ec.service.sync().name("mantle.order.OrderServices.create#OrderItem").parameters([orderId:purchaseOutMap.orderId,
                            orderPartSeqId:purchaseOutMap.orderPartSeqId, itemDescription:itemDescription, quantity:quantity, unitAmount:price, itemTypeEnumId:'ItemExpOther']).call())
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
        placePurchaseOut = ec.service.sync().name("mchile.PurchaseServices.place#Order").parameters([orderId:purchaseOutMap.orderId, orderPartSeqId:purchaseOutMap.orderPartSeqId]).call()

        //Creación de Invoice
        invoiceOutMap = ec.service.sync().name("mantle.account.InvoiceServices.create#EntireOrderPartInvoice").parameters([orderId:purchaseOutMap.orderId, orderPartSeqId:purchaseOutMap.orderPartSeqId]).call()
        receiveOrderOut = ec.service.sync().name("mchile.PurchaseServices.receive#Order").parameters([orderId:purchaseOutMap.orderId, orderPartSeqId:purchaseOutMap.orderPartSeqId,]).call()
        invoiceId = invoiceOutMap.invoiceId
    }
    // Se guarda DTE recibido en la base de datos -->
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

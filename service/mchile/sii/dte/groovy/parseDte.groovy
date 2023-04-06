import java.math.RoundingMode
import java.nio.charset.Charset
import org.w3c.dom.Document
import java.text.ParseException
import cl.moit.dte.MoquiDTEUtils

errorMessages = []
discrepancyMessages = []

dteBytes

if (dte == null)
    return
if (dte instanceof String) {
    dteBytes = dte.getBytes(charset ?: Charset.forname("UTF-8"))
} else if (dte instanceof org.w3c.dom.Node) {
    dteBytes = MoquiDTEUtils.getRawXML(dte, true)
} else if (dte instanceof byte[]) {
    dteBytes = dte
}

Document doc2 = MoquiDTEUtils.parseDocument(dteBytes)
namespace = MoquiDTEUtils.getNamespace(doc2)
groovy.util.Node documento = MoquiDTEUtils.dom2GroovyNode(doc2)
validSignature = null as Boolean

if (namespace == "http://www.sii.cl/SiiDte") {
    ec.logger.info("Namespace is SII")
    documentPath = "/sii:DTE/sii:Documento"
    try {validSignature = MoquiDTEUtils.verifySignature(doc2, documentPath, null)} catch (Exception e) {
        ec.logger.error("Verifying signature: ${e.toString()}")
    }
    if (!validSignature) {
        ec.logger.info("Verifying without namespace")
        dteBytes = new String(MoquiDTEUtils.getRawXML(doc2, true), "ISO-8859-1").replaceAll(" xmlns=\"http://www.sii.cl/SiiDte\"", "").getBytes("ISO-8859-1")
        doc2 = MoquiDTEUtils.parseDocument(dteBytes)
        documentPath = "/DTE/Documento"
        try {validSignature = MoquiDTEUtils.verifySignature(doc2, documentPath, null)} catch (Exception e) {
            ec.logger.error("Verifying signature: ${e.toString()}")
        }
    }
} else {
    ec.logger.info("No namespace")
    documentPath = "/DTE/Documento"
    try {validSignature = MoquiDTEUtils.verifySignature(doc2, documentPath, null)} catch (Exception e) {
        ec.logger.error("Verifying signature: ${e.toString()}")
    }
    if (!validSignature) {
        ec.logger.info("Verifying with namespace")
        new cl.moit.dte.XmlNamespaceTranslator().addTranslation(null, "http://www.sii.cl/SiiDte").addTranslation("", "http://www.sii.cl/SiiDte").translateNamespaces(doc2)
        dteBytes = MoquiDTEUtils.getRawXML(doc2, true)
        doc2 = MoquiDTEUtils.parseDocument(dteBytes)
        domNode = doc2.getDocumentElement()
        documentPath = "/sii:DTE/sii:Documento"
        try {validSignature = MoquiDTEUtils.verifySignature(doc2, documentPath, null)} catch (Exception e) {
            ec.logger.error("Verifying signature: ${e.toString()}")
        }
    }
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
fiscalTaxDocumentNumber = folioDte
montosBrutos = encabezado.IdDoc.MntBruto?.text() == "1"
indTraslado = encabezado.IdDoc.IndTraslado?.text()

emisor = encabezado.Emisor
rutEmisor = emisor.RUTEmisor.text()
razonSocialEmisor = emisor.RznSoc.text()
giroEmisor = emisor.GiroEmis.text()
direccionOrigen = emisor.DirOrigen.text()
comunaOrigen = emisor.CmnaOrigen.text()
ciudadOrigen = emisor.CiudadOrigen.text()

// Totales
montoNeto = (encabezado.Totales.MntNeto.text() ?: 0) as BigDecimal
montoTotal = (encabezado.Totales.MntTotal.text() ?: 0) as BigDecimal // es retornado, si se especifica clase se considera var local y no retorna
BigDecimal montoNoFacturable = (encabezado.Totales.MontoNF.text() ?: 0) as BigDecimal
montoExento = (encabezado.Totales.MntExe.text() ?: 0) as BigDecimal
tasaIva = (encabezado.Totales.TasaIVA.text() ?: 0) as BigDecimal
iva = (encabezado.Totales.IVA.text() ?: 0) as BigDecimal
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

rutReceptor = encabezado.Receptor.RUTRecep.text()
tipoDteEnumId = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#MoquiCode").parameter("siiCode", tipoDte).call().enumId
fechaEmision = ec.l10n.parseDate(encabezado.IdDoc.FchEmis.text(), "yyyy-MM-dd")
fechaVencimiento = ec.l10n.parseDate(encabezado.IdDoc.FchVenc.text(), "yyyy-MM-dd")

receptor = encabezado.Receptor
razonSocialReceptor = receptor.RznSocRecep.text()
giroReceptor = receptor.GiroRecep.text()
direccionReceptor = receptor.DirRecep.text()
comunaReceptor = receptor.CmnaRecep.text()
ciudadReceptor = receptor.CiudadRecep.text()

detalleDteList = documento.Documento.Detalle

BigDecimal montoItem = 0 as BigDecimal
ec.logger.warn("Recorriendo detalles: ${detalleDteList.size()}")
int nroDetalles = 0
BigDecimal totalCalculado = 0
BigDecimal totalExento = 0
detalleList = []
detalleDteList.each { detalleDte ->
    nroDetalles++
    // Adición de items a orden
    ec.logger.warn("-----------------------------------")
    ec.logger.warn("Leyendo línea detalle " + nroDetalles + ",")
    ec.logger.warn("Indicador exento: ${detalleDte.IndExe.text()}")
    ec.logger.warn("Nombre item: ${detalleDte.NmbItem.text()}")
    ec.logger.warn("Cantidad: ${detalleDte.QtyItem.text()}")
    ec.logger.warn("Precio: ${detalleDte.PrcItem.text()}")
    ec.logger.warn("Descuento: ${detalleDte.DescuentoMonto?.text()}")
    ec.logger.warn("Monto: ${detalleDte.MontoItem.text()}")
    itemDescription = detalleDte.NmbItem?.text()
    BigDecimal quantity = detalleDte.QtyItem ? (detalleDte.QtyItem.text() as BigDecimal) : null
    price = detalleDte.PrcItem ? (detalleDte.PrcItem.text() as BigDecimal) : null
    montoItem = detalleDte.MontoItem ? (detalleDte.MontoItem.text() as BigDecimal) : null
    montoItemBruto = null
    dteQuantity = null
    dteAmount = null
    descuentoMonto = detalleDte.DescuentoMonto ? (detalleDte.DescuentoMonto.text() as BigDecimal) : 0 as BigDecimal
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
        indExe = detalleDte.IndExe?.text() as Integer
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

    cdgItemList = detalleDte.CdgItem
    int k = 0
    codigoList = []
    detalleDte.CdgItem.each { cdgItem ->
        codigoList.add(cdgItem.VlrCodigo.text())
    }

    detalleList.add([quantity:quantity, dteAmount:dteAmount, itemExento:itemExento, itemDescription:itemDescription, dteQuantity:dteQuantity, amount:price, codigoList:codigoList,
                     descuentoMonto:descuentoMonto, roundingAdjustmentItemAmount:roundingAdjustmentItemAmount])
}

descuentoRecargoList = []
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
    descuentoRecargoList.add([itemTypeEnumId:itemTypeEnumId, glosa:globalItem.GlosaDR.text(), amount:amount])
}

requiresManualIntervention = false
totalCalculadoIva = (montoNeto * vatTaxRate).setScale(0, RoundingMode.HALF_UP)
if (totalCalculado != (montoNeto + montoExento)) discrepancyMessages.add("Monto total (neto + exento) no coincide, calculado: ${totalCalculado}, en totales de DTE: ${montoNeto + montoExento}")
if (totalExento != montoExento) {
    discrepancyMessages.add("Monto exento no coincide, calculado: ${totalExento}, en totales de DTE: ${montoExento}")
}
if (totalNoFacturable != montoNoFacturable) {
    discrepancyMessages.add("Monto no facturable no coincide, calculado: ${totalNoFacturable}, en totales de DTE: ${montoNoFacturable}")
    requiresManualIntervention = true
}

if (iva != totalCalculadoIva) {
    discrepancyMessages.add("No coincide monto IVA, DTE indica ${iva}, calculado: ${totalCalculadoIva}")
}

if (tipoDteEnumId == 'Ftdt-52') {
    if (!indTraslado)
        errorMessages.add("Guía de despacho no indica tipo de traslado (IndTraslado): ${indTraslado}")
    else {
        indTrasladoEnumId = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#MoquiCode").parameters([siiCode:indTraslado, enumTypeId:'IndTraslado']).call().enumId
        if (!indTrasladoEnumId) {
            errorMessages.add("Guía de despacho indica tipo de traslado (IndTraslado) desconocido: ${indTraslado}")
        }
    }
}

referenciaList = []
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
    referenciaTipoDteEnumId = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#MoquiCode").parameter("siiCode", referencia.TpoDocRef.text()).call().enumId
    Date refDate = null
    if (referencia.FchRef.text() != null) {
        try {
            refDate = formatter.parse(referencia.FchRef.text())
        } catch (ParseException e) {
            errorMessages.add("Valor inválido en referencia ${nroRef}, campo FchRef: ${referencia.FchRef.text()}")
        } catch (NullPointerException e) {
            //discrepancyMessages.add("Valor inválido en referencia ${nroRef}, campo FchRef: null")
        }
    }
    codRef = referencia.CodRef.text()
    if (codRef != null && codRef != '') {
        codRefEnum = ec.entity.find("moqui.basic.Enumeration").condition([enumTypeId:"FtdCodigoReferencia", enumCode:codRef]).list().first
        codRefEnumId = codRefEnum?.enumId
        if (!codRefEnumId)
            errorMessages.add("Valor inválido en referencia ${nroRef}, campo CodRef: ${referencia.CodRef.text()}")
    } else
        codRefEnumId = null
    folio = referencia.FolioRef.text()
    referenciaList.add([referenciaTipoDteEnumId:referenciaTipoDteEnumId, folio:folio, refDate:refDate, codRefEnumId:codRefEnumId, razonReferencia:referencia.RazonRef?.text()])
}

return;
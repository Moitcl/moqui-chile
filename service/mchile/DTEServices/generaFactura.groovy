import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat

import org.apache.xmlbeans.XmlOptions
import org.w3c.dom.Document

import cl.nic.dte.util.Signer
import cl.nic.dte.util.Utilities
import cl.nic.dte.util.XMLUtil
import cl.sii.siiDte.AUTORIZACIONDocument
import cl.sii.siiDte.AutorizacionType
import cl.sii.siiDte.DTEDefType.Documento.Detalle
import cl.sii.siiDte.DTEDefType.Documento.Encabezado.IdDoc
import cl.sii.siiDte.DTEDefType.Documento.Encabezado.Receptor
import cl.sii.siiDte.DTEDefType.Documento.Encabezado.Totales
import cl.sii.siiDte.DTEDefType.Documento.Referencia
import cl.sii.siiDte.DTEDefType.Documento.DscRcgGlobal
import cl.sii.siiDte.DTEDocument
import cl.sii.siiDte.FechaHoraType
import cl.sii.siiDte.FechaType
import cl.sii.siiDte.MedioPagoType
import org.moqui.context.ExecutionContext

ExecutionContext ec = context.ec

partyIdentificationList = ec.entity.find("mantle.party.PartyIdentification").condition([partyId:issuerPartyId, partyIdTypeEnumId:'PtidNationalTaxId']).list()
if (!partyIdentificationList) {
    ec.message.addError("Organización $issuerPartyId no tiene RUT definido")
    return
}
rutEmisor = partyIdentificationList.first.idValue

// Validación rut
ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameter("rut", rutReceptor).call()
ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameter("rut", rutEmisor).call()

// Recuperacion de parametros de la organizacion -->
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameter("partyId", issuerPartyId).call())

// Giro Emisor
giroOutMap = ec.service.sync().name("mchile.DTEServices.get#GiroPrimario").parameter("partyId", issuerPartyId).call()
giro = giroOutMap.description

// Recuperación del código SII de DTE -->
codeOut = ec.service.sync().name("mchile.DTEServices.get#SIICode").parameter("fiscalTaxDocumentTypeEnumId", fiscalTaxDocumentTypeEnumId).call()
tipoFactura = codeOut.siiCode

fechaEmision = null

// Formas de pago
if (settlementTermId.equals('FpaImmediate'))
    formaPago = "1" // Contado
else if (settlementTermId.equals('Net10'))
    formaPago = "2" // Credito (usar GlosaPagos)
else if (settlementTermId.equals('Net15'))
    formaPago = "2" // Credito (usar GlosaPagos)
else if (settlementTermId.equals('Net30'))
    formaPago = "2" // Credito (usar GlosaPagos)
else if (settlementTermId.equals('Net60'))
    formaPago = "2" // Credito (usar GlosaPagos)
else if (settlementTermId.equals('Net90'))
    formaPago = "2" // Credito (usar GlosaPagos)
else if (settlementTermId == "3")
    formaPago = "3" // Sin costo
else
    formaPago = "2"

//Obtención de folio y path de CAF -->
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.get#Folio").parameters([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, partyId:issuerPartyId]).call())
codRef = 0 as Integer

DTEDocument doc
AutorizacionType caf
X509Certificate cert
PrivateKey key
int frmPago = 1
int listSize = 0

// Forma de pago
if(formaPago != null)
    frmPago = Integer.valueOf(formaPago)

// Debo meter el namespace porque SII no lo genera
HashMap<String, String> namespaces = new HashMap<String, String>()
namespaces.put("", "http://www.sii.cl/SiiDte")
namespaces.put("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
XmlOptions opts = new XmlOptions()
opts.setLoadSubstituteNamespaces(namespaces)

// Recuperación de archivo CAF desde BD
caf = AUTORIZACIONDocument.Factory.parse(new ByteArrayInputStream(cafData.getBytes()), opts).getAUTORIZACION()

// Construyo base a partir del template
//doc = DTEDocument.Factory.parse(new File(templateFactura), opts)
doc = DTEDocument.Factory.parse(ec.resource.getLocationStream(templateFactura), opts)

// leo certificado y llave privada del archivo pkcs12
KeyStore ks = KeyStore.getInstance("PKCS12")
ks.load(new ByteArrayInputStream(certData.decodeBase64()), passCert.toCharArray())
String alias = ks.aliases().nextElement()
cert = (X509Certificate) ks.getCertificate(alias)
String rutCertificado = Utilities.getRutFromCertificate(cert)
ec.logger.warn("Usando certificado ${alias} con Rut ${rutCertificado}")

key = (PrivateKey) ks.getKey(alias, passCert.toCharArray())

// Se recorre lista de productos para armar documento (detailList)

IdDoc iddoc = doc.getDTE().getDocumento().getEncabezado().addNewIdDoc()
iddoc.setFolio(folio)
// Obtención de ID distinto
//logger.warn("id: " + System.nanoTime())
//doc.getDTE().getDocumento().setID("N" + iddoc.getFolio())
doc.getDTE().getDocumento().setID("N" + System.nanoTime())

// Tipo de DTE
iddoc.setTipoDTE(tipoFactura as BigInteger)
iddoc.xsetFchEmis(FechaType.Factory.newValue(Utilities.fechaFormat.format(new Date())))

SimpleDateFormat formatterFechaEmision = new SimpleDateFormat("yyyy-MM-dd")
Date dateFechaEmision = new Date()
fechaEmision = formatterFechaEmision.format(dateFechaEmision)
// Indicador Servicio
// 3 para Factura de Servicios
// Para Facturas de Exportación:
// 4 Servicios de Hotelería
// 5 Servicio de Transporte Terrestre Internacional
//iddoc.setIndServicio(BigInteger.valueOf(3))

Calendar cal = Calendar.getInstance()
cal.add(Calendar.DAY_OF_MONTH, 45)
iddoc.xsetFchCancel(FechaType.Factory.newValue(Utilities.fechaFormat.format(new Date())))
// Medio y forma de pago
if (medioPago != null ) {
    iddoc.setMedioPago(MedioPagoType.Enum.forString(medioPago))
} else {
    iddoc.setMedioPago(MedioPagoType.Enum.forString("CH"))
}
iddoc.setFmaPago(BigInteger.valueOf(frmPago))

// Si es guía de despacho se configura indicador de traslado
if(tipoFactura == 52) {
    iddoc.setIndTraslado(indTraslado)
    if(tipoDespacho != null) {
        iddoc.setTipoDespacho(Long.valueOf(tipoDespacho))
    }
}
// Receptor
Receptor recp = doc.getDTE().getDocumento().getEncabezado().addNewReceptor()
recp.setRUTRecep(rutReceptor.trim())
recp.setRznSocRecep(rznSocReceptor)
if(giroReceptor.length() > 39)
    recp.setGiroRecep(giroReceptor.substring(0,39))
else
    recp.setGiroRecep(giroReceptor)
recp.setContacto(contactoReceptor)
recp.setDirRecep(dirReceptor)
recp.setCmnaRecep(cmnaReceptor)
recp.setCiudadRecep(ciudadReceptor)

// Campos para elaboración de libro
montoNeto = 0 as Long
montoExento = 0 as Long
montoIVARecuperable = 0 as Long
totalNeto = 0 as Long
totalExento = 0 as Long

// Campo para guardar resumen atributos -->
amount = 0 as Long
uom = null
if (tipoFactura == 33) {
    int i = 0
    listSize = detailList.size()
    Detalle[] det = new Detalle[listSize]
    totalInvoice = 0 as Integer
    detailList.each { detailEntry ->
        nombreItem = detailEntry.description
        Integer qtyItem = detailEntry.quantity
        codigoInterno = detailEntry.productId
        Integer priceItem = detailEntry.amount
        totalItem = qtyItem * priceItem
        afectoOutMap = ec.service.sync().name("mchile.DTEServices.check#Afecto").parameter("productId", detailEntry.productId).call()
        itemAfecto = afectoOutMap.afecto
        pctDiscount = detailEntry.pctDiscount
        if (detailEntry.quantityUomId.equals('TF_hr'))
            uom = "Hora"
        if (detailEntry.quantityUomId.equals('TF_mon'))
            uom = "Mes"

        // Agrego detalles
        det[i] = Detalle.Factory.newInstance()
        det[i].setNroLinDet(i+1)
        cl.sii.siiDte.DTEDefType.Documento.Detalle.CdgItem codigo = det[i].addNewCdgItem()
        codigo.setTpoCodigo("Interna")
        codigo.setVlrCodigo(codigoInterno)
        det[i].setNmbItem(nombreItem)
        //det[i].setDscItem(""); // Descripción Item
        det[i].setQtyItem(BigDecimal.valueOf(qtyItem))
        if(uom != null)
            det[i].setUnmdItem(uom)
        if((pctDiscount != null) && (pctDiscount > 0)) {
            ec.logger.warn("Aplicando descuento " + pctDiscount+"% a precio "+ priceItem )
            descuento = totalItem * pctDiscount / 100
            ec.logger.warn("Descuento:" + descuento)
            //totalInvoice = totalInvoice + totalItem - descuento
            det[i].setDescuentoPct(pctDiscount)
            det[i].setDescuentoMonto(Math.round(descuento))
            totalItem = totalItem - descuento
        }
        // Descuento global
        if((itemAfecto.equals("true")) && (globalDiscount != null) && (Integer.valueOf(globalDiscount) > 0)) {
            ec.logger.warn("Aplicando descuento global " + globalDiscount+"% a precio "+ priceItem )
            //descuento = totalItem * Integer.valueOf(globalDiscount) / 100
            ec.logger.warn("Descuento:" + descuento)
            //det[i].setDescuentoPct(pctDiscount)
            //det[i].setDescuentoMonto(Math.round(descuento))
            //totalItem = totalItem - Math.round(descuento)
            //logger.warn("precio inicial item: " + priceItem)
            //priceItem = Math.round(priceItem - (priceItem * Integer.valueOf(globalDiscount) / 100))
            //logger.warn("precio final item:" + priceItem)
        }
        det[i].setPrcItem(BigDecimal.valueOf(priceItem))
        det[i].setMontoItem( Math.round(totalItem))
        if(itemAfecto.equals("true")) {
            totalNeto = totalNeto + totalItem
        } else {
            totalExento = totalExento + totalItem
            det[i].setIndExe(1)
        }
        i = i + 1
    }
    i = 0
    listSize = referenciaList.size()
    Referencia[] ref = new Referencia[listSize]

    referenciaList.each { referenciaEntry ->
        folioRef = referenciaEntry.folio as Integer
        codRef = referenciaEntry.codigoReferenciaEnumId as Integer
        fechaRef = referenciaEntry.fecha

        // Agrego referencias
        ref[i] = Referencia.Factory.newInstance()
        ref[i].setNroLinRef(i+1)
        if (referenciaEntry.fiscalTaxDocumentTypeEnumId.equals('Ftdt-0')) { // Used for Set de Pruebas SII
            ref[i].setTpoDocRef('SET')
            ref[i].setFolioRef(referenciaEntry.folio.toString())
            ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(referenciaEntry.fecha)))
            ref[i].setRazonRef(referenciaEntry.razonReferencia)
        } else {
            codeOut = ec.service.sync().name("mchile.DTEServices.get#SIICode").parameters([fiscalTaxDocumentTypeEnumId:referenciaEntry.fiscalTaxDocumentTypeEnumId]).call
            tpoDocRef = codeOut.siiCode
            //ref[i].setTpoDocRef(referenciaEntry.fiscalTaxDocumentTypeEnumId)
            ref[i].setTpoDocRef(tpoDocRef as String)
            ref[i].setRUTOtr(rutReceptor)
            Date date
            if (fechaRef instanceof java.sql.Date) {
                date = new Date(fechaRef.getTime())
            } else {
                SimpleDateFormat formatter = new SimpleDateFormat("yyyy-mm-dd")
                date = formatter.parse(fechaRef)
            }
            ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(referenciaEntry.fecha)))
            if(codRef != null)
                ref[i].setCodRef(codRef)
            if(referenciaEntry.razonReferencia != null)
                ref[i].setRazonRef(referenciaEntry.razonReferencia)
            ref[i].setFolioRef(referenciaEntry.folio.toString())
        }

        i = i + 1
    }

    doc.getDTE().getDocumento().setReferenciaArray(ref)
    doc.getDTE().getDocumento().setDetalleArray(det)
    // Descuento Global
    if(globalDiscount != null && Integer.valueOf(globalDiscount) > 0) {
        ec.logger.warn("Descuento global:" + globalDiscount)
        long descuento = Math.round(totalNeto * (Long.valueOf(globalDiscount) / 100))
        ec.logger.warn("Descuento::" + descuento)
        totalNeto = totalNeto - descuento
        // Creación entradas en XML
        DscRcgGlobal dscGlobal = DscRcgGlobal.Factory.newInstance()
        // iddoc.setMedioPago(MedioPagoType.Enum.forString("CH"))
        dscGlobal.setNroLinDR(BigInteger.valueOf(1))
        dscGlobal.setTpoMov(DscRcgGlobal.TpoMov.Enum.forString("D"))
        dscGlobal.setTpoValor(cl.sii.siiDte.DineroPorcentajeType.Enum.forString("%"))
        //dscGlobal.setValorDR(BigDecimal.valueOf(descuento));// Porcentaje Dscto
        dscGlobal.setValorDR(BigDecimal.valueOf(Integer.valueOf(globalDiscount)))// Porcentaje Dscto
        dscGlobal.setGlosaDR(glosaDr)
        DscRcgGlobal[] dscGB = new DscRcgGlobal[1]
        dscGB[0] = dscGlobal
        doc.getDTE().getDocumento().setDscRcgGlobalArray(dscGB)
    }
    // Totales
    Totales tot = doc.getDTE().getDocumento().getEncabezado().addNewTotales()
    tot.setMntNeto(Math.round(totalNeto))
    montoNeto = totalNeto
    tot.setTasaIVA(BigDecimal.valueOf(19))
    // Valor de solo IVA
    long totalIVA = Math.round(totalNeto * 0.19)
    montoIVARecuperable = totalIVA
    tot.setIVA(totalIVA)
    ec.logger.warn("monto neto:" + montoNeto)
    ec.logger.warn("total IVA:" + totalIVA)
    // total neto + IVA
    totalInvoice = totalNeto + totalIVA + totalExento
    tot.setMntTotal(Math.round(totalInvoice))
    ec.logger.warn("Total Exento: " + totalExento)
    if(totalExento > 0) {
        tot.setMntExe(Math.round(totalExento))
    }
    amount = totalInvoice
}

if (tipoFactura == 34) {
    int i = 0
    listSize = detailList.size()
    Detalle[] det = new Detalle[listSize]
    Integer totalInvoice = 0 as Integer

    detailList.each { detailEntry ->
        ec.logger.warn("******* Iterando invoice $detailEntry")
        nombreItem = detailEntry.description
        qtyItem = detailEntry.quantity as Integer
        priceItem = detailEntry.amount as Integer
        totalItem = qtyItem * priceItem as Integer
        if (detailEntry.quantityUomId.equals('TF_hr'))
            uom = "Hora"
        if (detailEntry.quantityUomId.equals('TF_mon'))
            uom = "Mes"

        // Agrego detalles
        det[i] = Detalle.Factory.newInstance()
        det[i].setNroLinDet(i+1)
        det[i].setNmbItem(nombreItem)
        det[i].setQtyItem(BigDecimal.valueOf(qtyItem))
        det[i].setPrcItem(BigDecimal.valueOf(priceItem))
        det[i].setMontoItem( totalItem )
        det[i].setIndExe(1)
        if(uom != null)
            det[i].setUnmdItem(uom)
        totalInvoice = totalInvoice + totalItem
        montoNeto = 0
        montoExento = totalInvoice

        i = i + 1

    }
    i = 0
    Referencia[] ref = null
    if(referenciaList.size() != 0) {
        listSize = referenciaList.size()
        ref = new Referencia[listSize]
    } else {
        listSize = 0
    }
    //Referencia[] ref = new Referencia[listSize]

    referenciaList.each { referenciaEntry ->
        ec.logger.warn("Agregando referencia $referenciaEntry")
        folioRef = referenciaEntry.folio
        codRef = referenciaEntry.codigoReferenciaEnumId as Integer
        fechaRef = referenciaEntry.fecha

        // Agrego referencias
        ref[i] = Referencia.Factory.newInstance()
        ref[i].setNroLinRef(i+1)
        if(referenciaEntry.fiscalTaxDocumentTypeEnumId.equals('Ftdt-0')) { // Used for Set de Pruebas SII
            ref[i].setTpoDocRef('SET')
            ref[i].setFolioRef(referenciaEntry.folio.toString())
            ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(referenciaEntry.fecha)))
            ref[i].setRazonRef(referenciaEntry.razonReferencia)
        } else {
            codeOut = ec.service.sync().name("mchile.DTEServices.get#SIICode").parameters([fiscalTaxDocumentTypeEnumId:referenciaEntry.fiscalTaxDocumentTypeEnumId]).call()
            tpoDocRef = codeOut.siiCode
            //ref[i].setTpoDocRef(referenciaEntry.fiscalTaxDocumentTypeEnumId)
            ref[i].setTpoDocRef(tpoDocRef as String)
            ref[i].setRUTOtr(rutReceptor)
            Date date
            if (fechaRef instanceof java.sql.Date) {
                date = new Date(fechaRef.getTime())
            } else {
                SimpleDateFormat formatter = new SimpleDateFormat("yyyy-mm-dd")
                date = formatter.parse(fechaRef)
            }
            ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(referenciaEntry.fecha)))
            if(codRef != null)
                ref[i].setCodRef(codRef)
            if(referenciaEntry.razonReferencia != null)
                ref[i].setRazonRef(referenciaEntry.razonReferencia)
            ref[i].setFolioRef(referenciaEntry.folio.toString())
        }
        i = i + 1

    }

    doc.getDTE().getDocumento().setReferenciaArray(ref)
    doc.getDTE().getDocumento().setDetalleArray(det)
    // Totales
    Totales tot = doc.getDTE().getDocumento().getEncabezado().addNewTotales()
    tot.setMntExe(totalInvoice)
    tot.setMntTotal(totalInvoice)
    montoTotal = totalInvoice
    montoExento = totalInvoice

    amount = totalInvoice

}

// TODO: Nota de Crédito Electrónica

anulaBoleta = null
folioAnulaBoleta = null

if (tipoFactura == 61) {
    int i = 0
    listSize = detailList.size()
    Detalle[] det = new Detalle[listSize]
    totalInvoice = 0 as Integer
    ec.logger.warn("Creando DTE tipo 61")
    i = 0
    Referencia[] ref = null
    if(referenciaList.size() != 0) {
        listSize = referenciaList.size()
        ref = new Referencia[listSize]
    } else {
        listSize = 0
    }
    dteExenta = false
    // TODO: Si la referencia es tipo fe de erratas, Monto Item puede ser 0

    referenciaList.each { referenciaEntry ->
        folioRef = referenciaEntry.folio
        codRef = referenciaEntry.codigoReferenciaEnumId as Integer
        fechaRef = referenciaEntry.fecha
        codeOut = ec.service.sync().name("mchile.DTEServices.get#SIICode").parameters([fiscalTaxDocumentTypeEnumId:referenciaEntry.fiscalTaxDocumentTypeEnumId]).call()
        tpoDocRef = codeOut.siiCode

        // Agrego referencias
        ref[i] = Referencia.Factory.newInstance()
        ref[i].setNroLinRef(i+1)

        if(referenciaEntry.fiscalTaxDocumentTypeEnumId.equals('Ftdt-0')) {
            ref[i].setTpoDocRef('SET')
            ref[i].setCodRef(codRef)
            ref[i].setFolioRef(referenciaEntry.folio.toString())
        } else {
            //ref[i].setTpoDocRef(referenciaEntry.fiscalTaxDocumentTypeEnumId)
            ref[i].setTpoDocRef(tpoDocRef as String)
            ref[i].setCodRef(codRef)
            ref[i].setFolioRef(referenciaEntry.folio.toString())
            if((referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-39") || referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-41")) && codRef.equals(1) ) {
                // Nota de crédito hace referencia a Boletas Electrónicas
                anulaBoleta = 'true'
                folioAnulaBoleta = referenciaEntry.folio.toString()
            }
        }
        if(referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-34")) {
            dteExenta = true
        }
        // Valor Opcional
        //ref[i].xsetIndGlobal('0')
        //ref[i].setRUTOtr(rutReceptor)

        Date date
        if (fechaRef instanceof java.sql.Date) {
            date = new Date(fechaRef.getTime())
        }
        else {
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-mm-dd")
            date = formatter.parse(fechaRef)
        }
        //ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(new Date())))
        ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(referenciaEntry.fecha)))
        ref[i].setRazonRef(referenciaEntry.razonReferencia)

        i = i + 1

    }

    i = 0
    boolean alreadyPassed = false

    detailList.each { detailEntry ->

        ec.logger.info("Processing detail ${detailEntry}")
        nombreItem = detailEntry.description
        qtyItem = detailEntry.returnQuantity as Integer

        afectoOutMap = ec.service.sync().name("mchile.DTEServices.check#Afecto").parameters([productId:detailEntry.productId]).call()
        itemAfecto = afectoOutMap.afecto

        priceItem = detailEntry.returnPrice as Integer
        totalItem = qtyItem * priceItem as Integer

        pctDiscount = detailEntry.pctDiscount
        ec.logger.warn("Descuento item: $pctDiscount")

        // Agrego detalles
        if(!alreadyPassed) {
            det[i] = Detalle.Factory.newInstance()
            det[i].setNroLinDet(i+1)
            det[i].setNmbItem(nombreItem)
            if(dteExenta) {
                det[i].setIndExe(1)
            }
            if((pctDiscount != null) && (BigDecimal.valueOf(pctDiscount) > 0)) {
                ec.logger.warn("Aplicando descuento " + pctDiscount+"% a precio "+ priceItem )
                descuento = totalItem * pctDiscount / 100
                ec.logger.warn("Descuento:" + descuento)
                //totalInvoice = totalInvoice + totalItem - descuento
                det[i].setDescuentoPct(pctDiscount)
                det[i].setDescuentoMonto(Math.round(descuento))
                totalItem = totalItem - Math.round(descuento)
            }

            if(BigDecimal.valueOf(codRef) != 2 && BigDecimal.valueOf(codRef) != 1) { // Corrige montos o anula documento
                ec.logger.warn("codRef == 1 o codRef == 2 :" + codRef)
                if(BigDecimal.valueOf(qtyItem) > 0) {
                    det[i].setQtyItem(BigDecimal.valueOf(qtyItem))
                }
                if(BigDecimal.valueOf(priceItem) > 0) {
                    det[i].setPrcItem(BigDecimal.valueOf(priceItem))
                }
                det[i].setMontoItem( totalItem )
            } else if (BigDecimal.valueOf(codRef) == 2){ // codRef == 2 (Corrige giro) no lleva montos
                ec.logger.warn("codRef = 2")
                //det[i].setQtyItem(BigDecimal.valueOf(qtyItem))
                //det[i].setPrcItem( BigDecimal.valueOf(priceItem))
                //det[i].setMontoItem(totalItem)
                det[i].setNmbItem("CORRIGE GIROS")
                det[i].setMontoItem(0)
                alreadyPassed = true
                //totalItem = 0
            } else if( BigDecimal.valueOf(codRef) == 1 ){ // Cod ref == 1
                ec.logger.warn("codigo ref == 1")
                det[i].setQtyItem(BigDecimal.valueOf(qtyItem))
                det[i].setPrcItem( BigDecimal.valueOf(priceItem))
                det[i].setMontoItem(totalItem)
                //totalItem = 0
            }
            totalInvoice = totalInvoice + totalItem
            if(itemAfecto.equals("true") && !dteExenta) {
                ec.logger.warn("IFFFF1")
                totalNeto = totalNeto + totalItem
                montoNeto = totalNeto
            } else {
                totalExento = totalExento + totalItem
                montoExento = totalExento
                det[i].setIndExe(1)
                ec.logger.warn("IFFFF2, monto exento: "+ montoExento)
            }
            i = i + 1
        }

    }

    doc.getDTE().getDocumento().setReferenciaArray(ref)

    // Corrección de arreglo para sacar items nulos
    if (BigDecimal.valueOf(codRef) == 2) {
        ec.logger.warn("Corrigiendo lista de detalles")
        Detalle[] detFixed = new Detalle[1]
        detFixed[0] = det[0]
        doc.getDTE().getDocumento().setDetalleArray(detFixed)
    } else {
        doc.getDTE().getDocumento().setDetalleArray(det)
    }
    // Totales
    Totales tot = doc.getDTE().getDocumento().getEncabezado().addNewTotales()

    long montoExe = 0
    montoNeto = Long.valueOf(Math.round(totalNeto))
    long montoIva = Math.round(montoNeto * 0.19)
    long montoTotal = montoIva + montoNeto + totalExento
    long montoIvaExento = 0
    // Si la razon es modifica texto (2) no van los montos
    ec.logger.warn("Codref: " + codRef + ", dteExenta: " + dteExenta)
    if(BigDecimal.valueOf(codRef) == 1) { // Anulación
        ec.logger.warn("IF:" )
        if(!dteExenta) {
            ec.logger.warn("IF2:" + montoIva)
            tot.setTasaIVA(BigDecimal.valueOf(19))
            tot.setMntExe(totalExento)
            tot.setIVA(montoIva)
            tot.setMntNeto(montoNeto)
            tot.setMntTotal(montoTotal)
            montoExento = totalExento
            montoIVARecuperable = montoIva
            amount = montoTotal
        } else {
            ec.logger.warn("IF3: montoNeto: " +montoNeto + ", montoExento: " + montoExento)
            tot.setMntExe(montoExento)
            tot.setMntTotal(montoExento)
            montoExento = montoNeto
            montoIVARecuperable = 0
            amount = montoNeto
        }
    } else if(BigDecimal.valueOf(codRef) != 2) {
        ec.logger.warn("codRef != 2")
        if(!dteExenta) {
            ec.logger.warn("DTE no Exenta")
            tot.setTasaIVA(BigDecimal.valueOf(19))
            tot.setMntExe(totalExento)
            tot.setIVA(montoIva)
            tot.setMntNeto(montoNeto)
            tot.setMntTotal(montoTotal)
            montoExento = montoExe
            montoIVARecuperable = montoIva
            amount = montoTotal
        } else {
            ec.logger.warn("DTE Exenta, monto neto:"+ montoNeto)
            //tot.setMntTotal(montoNeto)
            if(!dteExenta) {
                montoExento = montoNeto
            }
            tot.setMntExe(montoExento)
            tot.setMntTotal(montoExento)
            montoIVARecuperable = 0
            amount = montoNeto
        }
    } else { // Modifica Texto
        if(!dteExenta) {
            //tot.setMntExe(montoNeto)
            //tot.setMntTotal(montoTotal)
            tot.setMntNeto(0)
            tot.setTasaIVA(BigDecimal.valueOf(19))
            tot.setIVA(0)
            tot.setMntTotal(0)
            amount = 0
        } else {
            tot.setMntTotal(0)
            amount = 0
        }
    }

    //totalInvoice = totalNeto + Math.round(totalIVA) + totalExento
    //tot.setMntTotal(Math.round(totalInvoice))
    //if(totalExento &gt; 0) {
    // tot.setMntExe(totalExento)
    //}

}

// Nota de Débito Electrónica
if (tipoFactura == 56) {
    //iddoc.setMntBruto(BigInteger.valueOf(1))
    int i = 0
    if(detailList != null) {
        listSize = detailList.size()
    } else {
        listSize = 0
    }
    Detalle[] det = new Detalle[listSize]
    totalInvoice = 0 as Long
    totalItempTmp = 0 as Long
    dteExenta = false

    Referencia[] ref = null
    if(referenciaList.size() != 0) {
        listSize = referenciaList.size()
        ref = new Referencia[listSize]
    } else {
        listSize = 0
    }

    // La referencia es solo a una Nota de Crédito
    referenciaList.each { referenciaEntry ->
        ec.logger.warn("Iterando referencias ${referenciaEntry}")
        ec.logger.warn("Folio:" + referenciaEntry.folio)

        folioRef = referenciaEntry.folio
        // Guardamos el código de referencia real, en lugar del SET
        codRef = referenciaEntry.codigoReferenciaEnumId as Integer
        fechaRef = referenciaEntry.fecha
        dteTypeRef = referenciaEntry.fiscalTaxDocumentTypeEnumId

        // Agrego referencias
        ref[i] = Referencia.Factory.newInstance()
        ref[i].setNroLinRef(i+1)

        if(referenciaEntry.fiscalTaxDocumentTypeEnumId.equals('Ftdt-0')) {
            ref[i].setTpoDocRef('SET')
            ref[i].setCodRef(codRef)
            ref[i].setFolioRef(referenciaEntry.folio.toString())
        } else {
            codeOut = ec.service.sync().name("mchile.DTEServices.get#SIICode").parameters([fiscalTaxDocumentTypeEnumId:referenciaEntry.fiscalTaxDocumentTypeEnumId]).call()
            tpoDocRef = codeOut.siiCode
        }
        //ref[i].setTpoDocRef(referenciaEntry.fiscalTaxDocumentTypeEnumId)
        ref[i].setTpoDocRef(tpoDocRef as String)
        //ref[i].setTpoDocRef(referenciaEntry.fiscalTaxDocumentTypeEnumId)
        ref[i].setCodRef(codRef)
        ref[i].setFolioRef(referenciaEntry.folio.toString())
        ec.logger.warn("DTE Type: " + referenciaEntry.fiscalTaxDocumentTypeEnumId)
        if(referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-34") || referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-61") ) {
            dteExenta = true
        }
        // Valor Opcional
        //ref[i].xsetIndGlobal('0')
        //ref[i].setRUTOtr(rutReceptor)

        Date date
        if (fechaRef instanceof java.sql.Date) {
            date = new Date(fechaRef.getTime())
        } else {
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-mm-dd")
        date = formatter.parse(fechaRef)
        }
        //ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(new Date())))
        ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(referenciaEntry.fecha)))
        ref[i].setRazonRef(referenciaEntry.razonReferencia)

        i = i + 1

    }

    i = 0

    detailList.each { detailEntry ->

        ec.logger.warn("******* Iterando invoice $detailEntry")
        detailTemp = detailEntry instanceof List ? detailEntry : detailEntry.split('-') as List
        itemNumber = detailTemp[0]
        qtyItem = detailTemp[1] ?: 0 as Long
        priceItem = detailTemp[2] ?: 0
        nombreItem = detailTemp[3]

        ec.logger.warn("DTE Exenta: " + dteExenta)
        priceItem = priceItem.replace(".","")
        totalItemTmp = qtyItem * Long.valueOf(priceItem)
        // Agrego detalles
        det[i] = Detalle.Factory.newInstance()
        det[i].setNroLinDet(i+1)
        det[i].setNmbItem(nombreItem)
        if(dteExenta) {
            if(BigDecimal.valueOf(codRef) != 2 && BigDecimal.valueOf(codRef) != 1) {
                det[i].setIndExe(BigInteger.valueOf(1))
                det[i].setQtyItem(qtyItem)
                det[i].setPrcItem(Long.valueOf(priceItem))
                det[i].setMontoItem(Long.valueOf(totalItemTmp))
            } else {
                det[i].setMontoItem(0)
                det[i].setNmbItem("ANULA DOCUMENTO DE REFERENCIA")
                totalItemTmp = 0
                //det[i].setQtyItem(qtyItem)
                //det[i].setPrcItem(0)
            }
        } else {
            if(BigDecimal.valueOf(codRef) != 2 && BigDecimal.valueOf(codRef) != 1) {
                det[i].setQtyItem(qtyItem)
                det[i].setPrcItem(Long.valueOf(priceItem))
                det[i].setMontoItem(Long.valueOf(totalItemTmp))
            } else {
                det[i].setMontoItem(0)// En simulación debe ser igual a monto de DTE anulada
                //det[i].setMontoItem(Long.valueOf(totalItemTmp));// En simulación debe ser igual a monto de DTE anulada
                det[i].setNmbItem("ANULA DOCUMENTO DE REFERENCIA")
            }
        }
        totalInvoice = totalInvoice + totalItemTmp
        i = i + 1
    }

    // Corrección de arreglo para sacar items nulos
    if (BigDecimal.valueOf(codRef) == 1){
        ec.logger.warn("Corrigiendo lista de detalles")
        Detalle[] detFixed = new Detalle[1]
        detFixed[0] = det[0]
        doc.getDTE().getDocumento().setDetalleArray(detFixed)
    } else {
        doc.getDTE().getDocumento().setDetalleArray(det)
    }

    doc.getDTE().getDocumento().setDetalleArray(det)
    // Totales
    Totales tot = doc.getDTE().getDocumento().getEncabezado().addNewTotales()

    long montoExe = 0
    montoNeto = Long.valueOf(totalInvoice)
    long montoIva = montoNeto * 0.19
    long montoTotal = montoIva + montoNeto

    ec.logger.warn("codRef:" + codRef +", dteExenta:" +dteExenta)
    // Si la razon es modifica texto (2) no van los montos
    // Notas de débito son siempre afectas
    if(codRef != 2 && codRef != 1) {
        if(!dteExenta) {
            ec.logger.warn("Else 4")
            tot.setTasaIVA(BigDecimal.valueOf(19))
            tot.setMntExe(montoExe)
            tot.setIVA(montoIva)
            tot.setMntNeto(montoNeto)
            tot.setMntTotal(montoTotal)
            montoExento = montoExe
            montoIvaRecuperable = montoIva
            amount = montoTotal
        } else { // Cod con factura exenta en la NC
            tot.setMntExe(montoNeto)
            tot.setMntTotal(montoNeto)
            tot.setMntNeto(0)
            tot.setIVA(0)
            //tot.setTasaIVA(BigDecimal.valueOf(19))
            montoExento = montoNeto
            montoIVARecuperable = 0
            amount = montoNeto
        }
    } else {
        ec.logger.warn("CodRef == 1, " + dteExenta)
        if(!dteExenta) {
            //tot.setMntExe(montoNeto)
            tot.setMntTotal(0)
            tot.setMntTotal(montoTotal)
            amount = 0
        } else {
            tot.setMntTotal(0)
            tot.setMntTotal(montoTotal)
            amount = 0
        }
    }
    i = 0

    doc.getDTE().getDocumento().setReferenciaArray(ref)
}

// Guías de Despacho
if (tipoFactura == 52) {
    int i = 0
    listSize = detailList.size()
    Detalle[] det = new Detalle[listSize]
    totalInvoice = 0 as Integer
    ec.logger.warn("Creando DTE tipo 52")

    i = 0
    Referencia[] ref = null
    if(referenciaList.size() != 0) {
        listSize = referenciaList.size()
        ref = new Referencia[listSize]
    } else {
        listSize = 0
    }
    dteExenta = false

    // TODO: Si la referencia es tipo fe de erratas, Monto Item puede ser 0
    referenciaList.each { referenciaEntry ->
        folioRef = referenciaEntry.folio
        codRef = referenciaEntry.codigoReferenciaEnumId as Integer
        fechaRef = referenciaEntry.fecha

        // Agrego referencias
        ref[i] = Referencia.Factory.newInstance()
        ref[i].setNroLinRef(i+1)

        if(referenciaEntry.fiscalTaxDocumentTypeEnumId.equals('Ftdt-0')) {
            ref[i].setTpoDocRef('SET')
            //ref[i].setCodRef(codRef)
            ref[i].setFolioRef(referenciaEntry.folio.toString())
        } else {
            codeOut = ec.service.sync().name("mchile.DTEServices.get#SIICode").parameters([fiscalTaxDocumentTypeEnumId:referenciaEntry.fiscalTaxDocumentTypeEnumId]).call()
            tpoDocRef = codeOut.siiCode
            //ref[i].setTpoDocRef(referenciaEntry.fiscalTaxDocumentTypeEnumId)
            ref[i].setTpoDocRef(tpoDocRef as String)
            //ref[i].setCodRef(codRef)
            ref[i].setFolioRef(referenciaEntry.folio.toString())
        }
        if(referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-34")) {
            dteExenta = true
        }
        // Valor Opcional
        //ref[i].xsetIndGlobal('0')
        //ref[i].setRUTOtr(rutReceptor)

        Date date
        if (fechaRef instanceof java.sql.Date) {
            date = new Date(fechaRef.getTime())
        }
        else {
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-mm-dd")
            date = formatter.parse(fechaRef)
        }
        //ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(new Date())))
        ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(referenciaEntry.fecha)))
        ref[i].setRazonRef(referenciaEntry.razonReferencia)

        i = i + 1
    }
    i = 0
    ec.logger.info("det después de referencias: ${det}")

    detailList.each { detailEntry ->
        nombreItem = detailEntry.productName
        if (nombreItem == null) {
            productEv = ec.entity.find("mantle.product.Product").condition("productId", detailEntry.productId).useCache(false).one()
            nombreItem = productEv.productName
        }
        qtyItem = detailEntry.quantity as Integer
        Map<String, Object> afectoOutMap = ec.service.sync().name("mchile.DTEServices.check#Afecto").parameter("productId", detailEntry.productId).call()
        itemAfecto = afectoOutMap.afecto
        sisList = ec.entity.find("mantle.shipment.ShipmentItemSource").condition([shipmentId:shipmentId, productId:detailEntry.productId]).list()
        totalItem = 0 as BigDecimal
        quantityHandled = 0
        ec.logger.info("handling price for productId ${detailEntry.productId}")

        if (sisList) {
            sisList.each { sis ->
                ec.logger.info("processing sis ${sis}")
                if (sis.invoiceId) {
                    invoiceItem = ec.entity.find("mantle.account.invoice.InvoiceItem").condition([invoiceId:sis.invoiceId, invoiceItemSeqId:sis.invoiceItemSeqId]).one()
                    totalItem = totalItem + sis.quantity * invoiceItem.amount
                    quantityHandled = quantityHandled + sis.quantity
                } else if (sis.orderId) {
                    invoiceItem = ec.entity.find("mantle.account.invoice.InvoiceItem").condition([invoiceId:sis.invoiceId, invoiceItemSeqId:sis.invoiceItemSeqId]).one()
                    totalItem = totalItem + sis.quantity * orderItem.unitAmount
                    quantityHandled = quantityHandled + sis.quantity
                }

            }
        }

        if (quantityHandled < qtyItem) {
            ec.logger.info("pending ${qtyItem-quantityHandled} out of ${qtyItem}")
            shipment = ec.entity.find("mantle.shipment.Shipment").condition("shipmentId", shipmentId).one()
            shipmentDate = shipment.estimatedShipDate ?: shipment.shipAfterDate ?: shipment.entryDate ?: ec.user.nowTimestamp
            price = ec.service.sync().name("mantle.product.PriceServices.get#ProductPrice").parameters([productId:detailEntry.productId, quantity:qtyItem, validDate:shipmentDate]).call()
            totalItem = totalItem + (qtyItem - quantityHandled)*price.price
        }

        priceItem = totalItem/qtyItem as BigDecimal
        totalItem = totalItem.setScale(0, BigDecimal.ROUND_HALF_UP) as Long

        // Agrego detalles
        det[i] = Detalle.Factory.newInstance()
        det[i].setNroLinDet(i+1)
        det[i].setNmbItem(nombreItem)

        det[i].setQtyItem(BigDecimal.valueOf(qtyItem))
        ec.logger.warn("priceInclude = " + priceInclude)
        if(Math.round(priceItem) > 0) {
            det[i].setPrcItem(BigDecimal.valueOf(Math.round(priceItem)))
        }
        det[i].setMontoItem(totalItem as Long)
        totalInvoice = totalInvoice + totalItem
        //if(itemAfecto.equals("true")) {
        totalNeto = totalNeto + totalItem
        //} else {
        //    totalExento = totalExento + totalItem
        //det[i].setIndExe(1)
        //}

        i = i + 1

    }

    doc.getDTE().getDocumento().setReferenciaArray(ref)
    ec.logger.info("det: ${det}")
    doc.getDTE().getDocumento().setDetalleArray(det)
    // Totales
    Totales tot = doc.getDTE().getDocumento().getEncabezado().addNewTotales()

    long montoExe = 0
    montoNeto = Long.valueOf(Math.round(totalNeto))
    long montoIva = Math.round(montoNeto * 0.19)
    long montoTotal = montoIva + montoNeto + totalExento
    long montoIvaExento = 0
    // Si la razon es modifica texto (2) no van los montos
    ec.logger.warn("Codref: " + codRef + ", dteExenta: " + dteExenta)
    tot.setTasaIVA(BigDecimal.valueOf(19))
    tot.setMntExe(totalExento)
    tot.setIVA(montoIva)
    tot.setMntNeto(montoNeto)
    tot.setMntTotal(montoTotal)
    montoExento = montoExe
    //montoIvaRecuperable = montoIva

    amount = montoTotal
}

// Timbro

doc.getDTE().timbrar(caf.getCAF(), caf.getPrivateKey(null))

// antes de firmar le doy formato a los datos
opts = new XmlOptions()
opts.setSaveImplicitNamespaces(namespaces)
opts.setLoadSubstituteNamespaces(namespaces)
opts.setLoadAdditionalNamespaces(namespaces)
opts.setSavePrettyPrint()
opts.setSavePrettyPrintIndent(4)

// releo el doc para que se reflejen los cambios de formato
doc = DTEDocument.Factory.parse(doc.newInputStream(opts), opts)


//logger.warn("Documento: " + doc)

// Guardo
opts = new XmlOptions()
opts.setCharacterEncoding("ISO-8859-1")
opts.setSaveImplicitNamespaces(namespaces)

String uri = ""
FechaHoraType now = FechaHoraType.Factory.newValue(Utilities.fechaHoraFormat.format(new Date()))

if (doc.getDTE().isSetDocumento()) {
    uri = doc.getDTE().getDocumento().getID()
    doc.getDTE().getDocumento().xsetTmstFirma(now)
} else if (doc.getDTE().isSetLiquidacion()) {
    uri = doc.getDTE().getLiquidacion().getID()
    doc.getDTE().getLiquidacion().xsetTmstFirma(now)
} else if (doc.getDTE().isSetExportaciones()) {
    uri = doc.getDTE().getExportaciones().getID()
    doc.getDTE().getExportaciones().xsetTmstFirma(now)
}

uri = "#" + uri

ByteArrayOutputStream out = new ByteArrayOutputStream()
doc.save(out, opts)
Document doc2 = XMLUtil.parseDocument(out.toByteArray())
byte[] facturaXml = Signer.sign(doc2, uri, key, cert, uri, "Documento")
doc2 = XMLUtil.parseDocument(facturaXml)

if (Signer.verify(doc2, "Documento")) {
    ec.logger.warn("DTE folio ${folio} generada OK")
} else {
    ec.logger.warn("Error al generar DTE folio ${folio}")
}

// Registro de DTE en base de datos y generación de PDF -->
fiscalTaxDocumentTypeEnumId = "Ftdt-${tipoFactura}"
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.genera#PDF").parameters([dte:facturaXml, issuerPartyId:issuerPartyId, glosaPagos:glosaPagos]).call())

// Creación de registro en FiscalTaxDocument -->
dteEv = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, fiscalTaxDocumentNumber:folio, issuerPartyId:issuerPartyId]).one()

dteEv.receiverPartyId = receiverPartyId
dteEv.receiverPartyIdTypeEnumId = "PtidNationalTaxId"
dteEv.fiscalTaxDocumentStatusEnumId = "Ftdt-Issued"
dteEv.fiscalTaxDocumentSentStatusEnumId = "Ftdt-NotSent"
dteEv.invoiceId = invoiceId
dteEv.shipmentId = shipmentId
Date date = new Date()
Timestamp ts = new Timestamp(date.getTime())
dteEv.date = ts
dteEv.update()

xmlName = "dbresource://moit/erp/dte/${rutEmisor}/DTE${tipoFactura}-${folio}.xml"
pdfName = "dbresource://moit/erp/dte/${rutEmisor}/DTE${tipoFactura}-${folio}.pdf"
pdfCedibleName = "dbresource://moit/erp/dte/${rutEmisor}/DTE${tipoFactura}-${folio}-cedible.pdf"

// Creacion de registros en FiscalTaxDocumentContent
createMapBase = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, contentDte:ts]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:xmlName]).call())
ec.resource.getLocationReference(xmlName).putBytes(facturaXml)


ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:pdfName]).call())
ec.resource.getLocationReference(pdfName).putBytes(pdfBytes)

ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-PdfCedible', contentLocation:pdfCedibleName]).call())
ec.resource.getLocationReference(pdfCedibleName).putBytes(pdfCedibleBytes)

// Creación de registro en FiscalTaxDocumentAttributes
createMap = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, amount:amount, fechaEmision:fechaEmision, anulaBoleta:anulaBoleta, folioAnulaBoleta:folioAnulaBoleta, montoNeto:montoNeto, tasaImpuesto:19, fechaEmision:fechaEmision,
             montoExento:montoExento, montoIVARecuperable:montoIVARecuperable]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(createMap).call())
fiscalTaxDocumentId = dteEv.fiscalTaxDocumentId
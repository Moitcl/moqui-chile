import org.moqui.BaseArtifactException

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
actecoTag = codigosActividadEconomica.split(',').collect { "        <Acteco>${it}</Acteco>\n"}.join()
templateFactura = """
<DTE version="1.0">
  <Documento ID="N150">
    <Encabezado>
      <Emisor>
        <RUTEmisor>${rutEmisor}</RUTEmisor>
        <RznSoc>${rznSocEmisor}</RznSoc>
        <GiroEmis>${giro}</GiroEmis>
        <Telefono>${fonoContacto}</Telefono>
        <CorreoEmisor>${mailContacto}</CorreoEmisor>
${actecoTag}        <DirOrigen>${dirOrigen}</DirOrigen>
        <CmnaOrigen>${cmnaOrigen}</CmnaOrigen>
        <CiudadOrigen>${ciudadOrigen}</CiudadOrigen>
      </Emisor>
    </Encabezado>
  </Documento>
</DTE>
"""
doc = DTEDocument.Factory.parse(new ByteArrayInputStream(templateFactura.bytes), opts)

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
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "InvoiceItem")
    Detalle[] det = detMap.detailArray
    totalNeto = detMap.totalNeto
    numberAfectos = detMap.numberAfectos
    numberExentos = detMap.numberExentos
    if (numberAfectos == 0 && numberExentos > 0)
        throw new BaseArtifactException("Factura afecta tiene solamente ítemes exentos")
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(referenciaList, rutReceptor, tipoFactura)
    Referencia[] ref = refMap.referenceArray

    doc.getDTE().getDocumento().setDetalleArray(det)
    doc.getDTE().getDocumento().setReferenciaArray(ref)

    // Descuento Global
    if(globalDiscount != null && Integer.valueOf(globalDiscount) > 0) {
        if (totalNeto != null)
            totalNeto = totalNeto - Math.round(totalNeto?:0 * (Long.valueOf(globalDiscount) / 100))
        if (totalExento != null)
            totalExento = totalExento - Math.round(totalExento?:0 * (Long.valueOf(globalDiscount) / 100))
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
    if (totalNeto != null) {
        tot.setMntNeto(Math.round(totalNeto))
        tot.setTasaIVA(BigDecimal.valueOf(19))
        // Valor de solo IVA
        long totalIVA = Math.round(totalNeto * 0.19)
        montoIVARecuperable = totalIVA
        tot.setIVA(totalIVA)
        totalInvoice = totalNeto + totalIVA + totalExento
    } else
        totalInvoice = totalExento
    tot.setMntTotal(Math.round(totalInvoice))
    ec.logger.warn("Total Exento: " + totalExento)
    if(totalExento != null && totalExento > 0) {
        tot.setMntExe(Math.round(totalExento))
    }
    amount = totalInvoice
}

if (tipoFactura == 34) {
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "InvoiceItem")
    det = detMap.detailArray
    Long totalInvoice = detMap.totalInvoice
    numberAfectos = detMap.numberAfectos
    numberExentos = detMap.numberExentos
    if (numberAfectos > 0)
        throw new BaseArtifactException("Factura exenta tiene ítemes afectos")
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(referenciaList, rutReceptor, tipoFactura)
    Referencia[] ref = refMap.referenceArray

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

// Nota de Crédito Electrónica
if (tipoFactura == 61) {
    ec.logger.warn("Creando DTE tipo 61")
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(referenciaList, rutReceptor, tipoFactura)
    Referencia[] ref = refMap.referenceArray
    anulaBoleta = refMap.anulaBoleta
    folioAnulaBoleta = refMap.folioAnulaBoleta
    BigInteger codRef = ref[ref.length-1].getCodRef()
    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "InvoiceItem", codRef)
    Detalle[] det = detMap.detailArray
    totalNeto = detMap.totalNeto
    totalInvoice = detMap.totalInvoice

    doc.getDTE().getDocumento().setReferenciaArray(ref)

    if (codRef == 2 && det.length > 1) {
        ec.message.addError("codRef = 2 && det.length = ${det.length}")
        return
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

    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(referenciaList, rutReceptor, tipoFactura)
    Referencia[] ref = refMap.referenceArray
    dteExenta = refMap.dteExenta
    codRef = refMap.codRef

    Map<String, Object> detMap = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "DebitoItem", codRef)
    Detalle[] det = detMap.detailArray
    totalNeto = detMap.totalNeto
    totalInvoice = detMap.totalInvoice

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

    // TODO: Si la referencia es tipo fe de erratas, Monto Item puede ser 0
    Map<String, Object> refMap = cl.moit.dte.MoquiDTEUtils.prepareReferences(referenciaList, rutReceptor, tipoFactura)
    Referencia[] ref = refMap.referenceArray
    dteExenta = refMap.dteExenta
    codRef = refMap.codRef

    det = cl.moit.dte.MoquiDTEUtils.prepareDetails(ec, detailList, "ShipmentItem")

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
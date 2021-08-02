import org.moqui.context.ExecutionContext
import java.text.SimpleDateFormat
import cl.sii.siiDte.FechaHoraType
import cl.sii.siiDte.FechaType
import cl.nic.dte.util.Signer
import cl.nic.dte.util.BoletaSigner
import cl.nic.dte.util.Utilities
import cl.nic.dte.util.XMLUtil

import org.apache.xmlbeans.XmlOptions
import javax.xml.namespace.QName
import org.apache.xmlbeans.XmlCursor
import java.security.cert.X509Certificate
import java.security.KeyStore
import java.security.PrivateKey
import org.w3c.dom.*

import cl.sii.siiDte.AUTORIZACIONDocument
import cl.sii.siiDte.AutorizacionType
import cl.sii.siiDte.boletas.BOLETADefType
import cl.sii.siiDte.boletas.EnvioBOLETADocument
import cl.sii.siiDte.boletas.BOLETADefType.Documento.Detalle
import cl.sii.siiDte.boletas.BOLETADefType.Documento.Referencia
import cl.sii.siiDte.boletas.BOLETADefType.Documento.Encabezado.Emisor
import cl.sii.siiDte.boletas.BOLETADefType.Documento.Encabezado.Receptor
import cl.sii.siiDte.boletas.BOLETADefType.Documento.Encabezado.Totales
import cl.sii.siiDte.boletas.EnvioBOLETADocument.EnvioBOLETA
import cl.sii.siiDte.boletas.EnvioBOLETADocument.EnvioBOLETA.SetDTE
import cl.sii.siiDte.boletas.EnvioBOLETADocument.EnvioBOLETA.SetDTE.Caratula
import cl.sii.siiDte.boletas.EnvioBOLETADocument.EnvioBOLETA.SetDTE.Caratula.SubTotDTE

ExecutionContext ec

partyIdentificationList = ec.entity.find("mantle.party.PartyIdentification").condition([partyId:activeOrgId, partyIdTypeEnumId:"PtidNationalTaxId"]).list()
if (!partyIdentificationList) {
    ec.message.addError("Organización no tiene RUT definido")
    return
}
rutEmisor = partyIdentificationList.idValue[0]

// Validación rut
ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameter("rut", rutReceptor).call()

// Recuperacion de parametros de la organizacion -->
context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameter("partyId", activeOrgId).call())
passS = passCert
resultS = pathResults
// REVISAR
if (cdgSIISucur == "LOCAL")
    cdgSIISucur = "0"
if (continua)
    pdfTemplateBoleta = pdfTemplateBoletaContinua

if (!pdfTemplateBoleta) {
    ec.message.addError("Emisor no tiene plantilla para PDF de boletas")
    return
}

// Giro del emisor
giroOutMap = ec.service.sync().name("mchile.DTEServices.get#GiroPrimario").parameter("partyId", activeOrgId).call()
giroEmisor = giroOutMap.description

// Recuperación del código SII de DTE
codeOut = ec.service.sync().name("mchile.DTEServices.get#SIICode").parameters([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId]).call()
tipoFactura = codeOut.siiCode
tipoFacturaS = codeOut.siiCode

// Obtención de folio y path de CAF -->
context.putAll(ec.service.sync().name("mchile.DTEServices.get#Folio").parameters([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, partyId:activeOrgId]).call())
codRef = 0 as Integer
idS = "BO"

Date dNow = new Date()
SimpleDateFormat ft = new SimpleDateFormat("yyMMddhhmmssMs")
String datetime = ft.format(dNow)
idS = idS + datetime
String uriBoleta = "#"+idS

AutorizacionType caf
X509Certificate cert
PrivateKey key
int tipoFactura
int frmPago = 1
int listSize = 0

tipoFactura = Integer.valueOf(tipoFacturaS)
if(formaPago != null)
    frmPago = Integer.valueOf(formaPago)

// Debo meter el namespace porque SII no lo genera
HashMap<String, String> namespaces = new HashMap<String, String>()
namespaces.put("", "http://www.sii.cl/SiiDte")
namespaces.put("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
XmlOptions opts = new XmlOptions()
//opts.setSaveImplicitNamespaces(namespaces)
opts.setLoadSubstituteNamespaces(namespaces)
//opts.setLoadAdditionalNamespaces(namespaces)
opts.setSavePrettyPrint()
opts.setSavePrettyPrintIndent(0)

// Recuperación de archivo CAF desde BD
caf = AUTORIZACIONDocument.Factory.parse(cafData.getBinaryStream(), opts).getAUTORIZACION()

BOLETADefType boleta; // boleta tiene cargada toda la información correspondiente

// (emisor, receptor, detalle, totales, etc)
opts = new XmlOptions()
opts.setSavePrettyPrint()
opts.setSavePrettyPrintIndent(0)
boleta = BOLETADefType.Factory.newInstance(opts)

System.out.println("BOLETA1:"+boleta.toString())

boleta.addNewDocumento()
boleta.getDocumento().addNewEncabezado()
// IdDoc
boleta.getDocumento().getEncabezado().addNewIdDoc()
// Detalles
boleta.getDocumento().addNewDetalle()

// leo certificado y llave privada del archivo pkcs12
KeyStore ks = KeyStore.getInstance("PKCS12")
ks.load(certData.getBinaryStream(), passS.toCharArray())
String alias = ks.aliases().nextElement()

cert = (X509Certificate) ks.getCertificate(alias)
key = (PrivateKey) ks.getKey(alias, passS.toCharArray())

ec.logger.warn("Usando certificado " + alias + " del archivo PKCS12: " + cert)

// Se recorre lista de productos para armar documento (detailList)
//doc.addNewDocumento()
//doc.getDocumento().addNewEncabezado()

//IdDoc iddoc = doc.getDocumento().getEncabezado().addNewIdDoc()
boleta.getDocumento().getEncabezado().getIdDoc().setFolio(folio)
boleta.getDocumento().setID(idS)
boleta.setVersion(new BigDecimal("1.0"))

// Para boleta
//XmlCursor cursor2 = boleta.newCursor()
//cursor2.toFirstChild()
//cursor2.setAttributeText(new QName("", "xmlns"), "http://www.sii.cl/SiiDte")
//cursor2.dispose()
System.out.println("BOLETA2:"+boleta.xmlText())

// Tipo de DTE
boleta.getDocumento().getEncabezado().getIdDoc().setTipoDTE(BigInteger.valueOf(tipoFactura))
boleta.getDocumento().getEncabezado().getIdDoc().xsetFchEmis(FechaType.Factory.newValue(Utilities.fechaFormat.format(new Date())))

SimpleDateFormat formatterFechaEmision = new SimpleDateFormat("yyyy-MM-dd")
Date dateFechaEmision = new Date()
fechaEmision = formatterFechaEmision.format(dateFechaEmision)

// Indicador Servicio
// 1 Boleta de servicios periódicos
// 2 Boleta de servicios periódicos domiciliarios
// 3 Boleta de Venta de Servicios (soportado)
//boleta.getDocumento().getEncabezado().getIdDoc().setIndServicio(BigInteger.valueOf(3))

Calendar cal = Calendar.getInstance()
cal.add(Calendar.DAY_OF_MONTH, 45)
//boleta.getDocumento().getEncabezado().getIdDoc().xsetFchCancel(FechaType.Factory.newValue(Utilities.fechaFormat.format(new Date())))
// Medio y forma de pago
//if (medioPago != null ) {
// iddoc.setMedioPago(MedioPagoType.Enum.forString(medioPago))
//} else {
// iddoc.setMedioPago(MedioPagoType.Enum.forString("CH"))
//}
//iddoc.setFmaPago(BigInteger.valueOf(frmPago))

if (rutReceptor == "66666666-6") {
    // Receptor
    Receptor recp = boleta.getDocumento().getEncabezado().addNewReceptor()
    recp.setRUTRecep(rutReceptor.trim())
    recp.setRznSocRecep("Venta a publico sin nombre receptor")
    // Campo giro receptor no existe en esquema BOLETADefType
} else {
    // Receptor
    Receptor recp = boleta.getDocumento().getEncabezado().addNewReceptor()
    recp.setRUTRecep(rutReceptor.trim())
    recp.setRznSocRecep(rznSocReceptor)
// Campo giro receptor no existe en esquema BOLETADefType
    recp.setContacto(contactoReceptor)
    recp.setDirRecep(dirReceptor)
    recp.setCmnaRecep(cmnaReceptor)
    recp.setCiudadRecep(ciudadReceptor)
}

// Emisor
Emisor emisor = boleta.getDocumento().getEncabezado().addNewEmisor()
emisor.setRUTEmisor(rutEmisor)
emisor.setRznSocEmisor(rznSocEmisor)
emisor.setGiroEmisor(giroEmisor)
emisor.setCdgSIISucur(Integer.valueOf(cdgSIISucur))
emisor.setDirOrigen(dirOrigen)
emisor.setCmnaOrigen(cmnaOrigen)
emisor.setCiudadOrigen(ciudadOrigen)

// Campos para elaboración de libro -->
montoNeto = 0 as Long
montoExento = 0 as Long
montoIVARecuperable = 0 as Long

if (tipoFactura == 39) {
    int i = 0
    listSize = detailList.size()
    Detalle[] det = new Detalle[listSize]
    totalInvoice = 0 as Integer

    detailList.each { detailEntry ->
        nombreItem = detailEntry.description
        qtyItem = detailEntry.quantity as Integer
        // Obtener precio de productId
        priceItem = detailEntry.amount as Integer
        totalItem = qtyItem * priceItem as Integer
        unmdItem = detailEntry.quantityUomId

        // Verificar si item es afecto o exento
        afectoOutMap = ec.service.sync().name("mchile.DTEServices.check#Afecto").parameter("productId", detailEntry.productId).call()
        itemAfecto = afectoOutMap.afecto
        ec.logger.warn("Item afecto: $itemAfecto, $totalItem")

        // Agrego detalles
        det[i] = Detalle.Factory.newInstance()
        if(itemAfecto.equals("true")) {
            //totalNeto = totalNeto + totalItem
            long neto = Math.round(totalItem/1.19)
            montoNeto = montoNeto + neto
            montoIVARecuperable = montoIVARecuperable + (totalItem - neto )
        } else {
            //totalExento = totalExento + totalItem
            montoExento = montoExento + totalItem
            det[i].setIndExe(1)
        }

        // TODO: Unidad de medida en última caso de prueba (UnmdItem, antes de precio)
        det[i].setNroLinDet(i+1)
        det[i].setNmbItem(nombreItem)
        det[i].setQtyItem(BigDecimal.valueOf(qtyItem))
        if(unmdItem.equals("WT_kg"))
            det[i].setUnmdItem("Kg.")
        det[i].setPrcItem(BigDecimal.valueOf(priceItem))
        det[i].setMontoItem( totalItem )
        totalInvoice = totalInvoice + totalItem

        i = i + 1

    }
    i = 0
    listSize = referenciaList.size()
    Referencia[] ref = new Referencia[listSize]

    referenciaList.each { referenciaEntry ->
        folioRef = referenciaEntry.folio as Integer
        codRef = referenciaEntry.codigoReferenciaEnumid as Integer
        fechaRef = referenciaEntry.fecha
        // Agrego referencias
        ref[i] = Referencia.Factory.newInstance()
        ref[i].setNroLinRef(i+1)
        if(referenciaEntry.fiscalTaxDocumentTypeEnumId.equals('Ftdt-0')) { // Used for Set de Pruebas SII
            //ref[i].setTpoDocRef('SET')
            ref[i].setFolioRef(referenciaEntry.folio.toString())
            ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(referenciaEntry.fecha)))
            ref[i].setRazonRef(referenciaEntry.razonReferencia)
        } else {
            codeOut = ec.service.sync().name("mchile.DTEServices.get#SIICode").parameters([fiscalTaxDocumentTypeEnumId:referenciaEntry.fiscalTaxDocumentTypeEnumId]).call()
            tpoDocRef = codeOut.siiCode
            //ref[i].setTpoDocRef(tpoDocRef)
            ref[i].setRUTOtr(rutReceptor)
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-mm-dd")
            Date date = formatter.parse(fechaRef)
            ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(referenciaEntry.fecha)))
            ref[i].setCodRef(codRef)
            ref[i].setRazonRef(referenciaEntry.razonReferencia)
            ref[i].setFolioRef(referenciaEntry.folio.toString())
        }
        i = i + 1
    }
    boleta.getDocumento().setReferenciaArray(ref)

    boleta.getDocumento().setDetalleArray(det)
    // Totales
    Totales tot = boleta.getDocumento().getEncabezado().addNewTotales()
    //montoNeto = totalInvoice
    ec.logger.warn("monto neto:" + montoNeto)
    ec.logger.warn("Total: " + totalInvoice)
    tot.setMntTotal(totalInvoice)
    amount=totalInvoice
}

if (tipoFactura == 41) {
    int i = 0
    listSize = detailList.size()
    Detalle[] det = new Detalle[listSize]
    totalInvoice = 0 as Integer

    detailList.each { detailEntry ->
        nombreItem = detailEntry.description
        qtyItem = detailEntry.quantity as Integer

        // TODO: obtener precio de productId -->
        priceItem = detailEntry.amount as Integer
        totalItem = qtyItem * priceItem as Integer
        unmdItem = null
        if (detailEntry.quantityUomId)
            unmdItem = detailEntry.quantityUomId
        ec.logger.warn("String: " + nombreItem)
        // Agrego detalles

        det[i] = Detalle.Factory.newInstance()
        det[i].setNroLinDet(i+1)
        det[i].setNmbItem(nombreItem)
        det[i].setQtyItem(BigDecimal.valueOf(qtyItem))
        det[i].setPrcItem(BigDecimal.valueOf(priceItem))
        det[i].setMontoItem( totalItem )
        det[i].setIndExe(1)
        //if(unm != null)
        //    det[i].setUnmdItem(uom)
        if(unmdItem.equals("WT_kg"))
            det[i].setUnmdItem("Kg.")
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
            ref[i].setTpoDocRef(tpoDocRef)
            ref[i].setRUTOtr(rutReceptor)
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-mm-dd")
            Date date = formatter.parse(fechaRef)
            ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(referenciaEntry.fecha)))
            ref[i].setCodRef(codRef)
            ref[i].setRazonRef(referenciaEntry.razonReferencia)
            ref[i].setFolioRef(referenciaEntry.folio.toString())
        }
        i = i + 1
    }
    boleta.getDocumento().setReferenciaArray(ref)
    boleta.getDocumento().setDetalleArray(det)
    // Totales
    Totales tot = boleta.getDocumento().getEncabezado().addNewTotales()
    tot.setMntExe(totalInvoice)
    tot.setMntTotal(totalInvoice)
    montoTotal = totalInvoice
    montoExento = totalInvoice
    amount = totalInvoice
}
// Timbro
//boleta.timbrar(caf.getCAF(), caf.getPrivateKey(null))

// Se puede parsear de nuevo antes de firmar?

//cursor2 = boleta.newCursor()
//cursor2.setAttributeText(new QName("", "xmlns"), "http://www.sii.cl/SiiDte")
//cursor2.dispose()

// Firma de boleta
// Fin contenido incluido
FechaHoraType now = FechaHoraType.Factory.newValue(Utilities.fechaHoraFormat.format(new Date()))
// formatear?

HashMap<String, String> namespaces4 = new HashMap<String, String>()
namespaces4.put("", "http://www.sii.cl/SiiDte")
opts = new XmlOptions()
opts.setUseDefaultNamespace()
//opts.setSaveImplicitNamespaces(namespaces4)
//opts.setLoadSubstituteNamespaces(namespaces4)
//opts.setLoadAdditionalNamespaces(namespaces4)
//opts.setSavePrettyPrint()
//opts.setSavePrettyPrintIndent(0)
//boleta = BOLETADefType.Factory.parse(boleta.newInputStream(opts))

boleta.timbrar(caf.getCAF(), caf.getPrivateKey(null))
boleta.getDocumento().xsetTmstFirma(now)
//boleta.sign(key, cert)
//boleta.verifySignature(BOLETADefType.Factory.parse(boleta.newInputStream(opts)))

cl.sii.siiDte.boletas.EnvioBOLETADocument envioBoletaDocument = EnvioBOLETADocument.Factory.parse(ec.resource.getLocationStream(templateEnvioBoleta))
EnvioBOLETA eb = EnvioBOLETA.Factory.newInstance()
SetDTE sdte = SetDTE.Factory.newInstance()

BOLETADefType[] bolArr = new BOLETADefType[1]
bolArr[0] = boleta
sdte.setDTEArray(bolArr)
sdte.setID("ENVBO" + System.nanoTime())

// Datos de carátula
Caratula caratula = sdte.addNewCaratula()
caratula.setRutEmisor(rutEmisor)
caratula.setRutEnvia(rutEnvia)
caratula.setRutReceptor('60803000-K') // El receptor debe ser el SII
caratula.setVersion(new BigDecimal("1.0"))

Date dateFchResol = new SimpleDateFormat("yyyy-MM-dd").parse(fchResol)
caratula.xsetFchResol(FechaType.Factory.newValue(Utilities.fechaFormat.format(dateFchResol)))

caratula.setNroResol(Integer.valueOf(nroResol))
now = FechaHoraType.Factory.newValue(Utilities.fechaHoraFormat.format(new Date()))

caratula.xsetTmstFirmaEnv(now)

// Subtotales
SubTotDTE[] subtDtes = new SubTotDTE[1]
SubTotDTE subt = SubTotDTE.Factory.newInstance()
subt.setTpoDTE(new BigInteger(tipoFactura.toString()))
subt.setNroDTE(new BigInteger(1))
subtDtes[0] = subt
caratula.setSubTotDTEArray(subtDtes)

// Le doy un formato bonito (debo hacerlo antes de firmar para no afectar los DTE internos)
HashMap<String, String> namespaces3 = new HashMap<String, String>()
XmlOptions opts3 = new XmlOptions()
//opts3.setSavePrettyPrint()
////opts3.setSavePrettyPrintIndent(4)
//opts3.setSavePrettyPrintIndent(0)
//envioBoletaDocument = EnvioBOLETADocument.Factory.parse(envioBoletaDocument.newInputStream(opts3))

eb.setSetDTE(sdte)
eb.setVersion(new BigDecimal("1.0"))
envioBoletaDocument.setEnvioBOLETA(eb)

//XmlCursor cursor = envioBoletaDocument.newCursor()
//if (cursor.toFirstChild()) {
//cursor.setAttributeText(new QName("", "xmlns"), "http://www.sii.cl/SiiDte")
//cursor.setAttributeText(new QName("http://www.w3.org/2001/XMLSchema-instance", "xsi", "xmlns"), "http://www.w3.org/2001/XMLSchema-instance")
//cursor.setAttributeText(new QName("http://www.w3.org/2001/XMLSchema-instance", "schemaLocation"), "http://www.sii.cl/SiiDte EnvioBOLETA_v11.xsd")
//}
//cursor.dispose()


HashMap<String, String> namespaces2 = new HashMap<String, String>()
namespaces2.put("", "http://www.sii.cl/SiiDte")
//namespaces2.put("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
//namespaces2.put("xsi:schemaLocation", "http://www.sii.cl/SiiDte EnvioBOLETA_v11.xsd")

XmlOptions opts2 = new XmlOptions()
opts2.setSaveImplicitNamespaces(namespaces2)
opts2.setLoadSubstituteNamespaces(namespaces2)
opts2.setLoadAdditionalNamespaces(namespaces2)
opts2.setSavePrettyPrint()
opts2.setSavePrettyPrintIndent(0)

try {
    envioBoletaDocument = EnvioBOLETADocument.Factory.parse(envioBoletaDocument.newInputStream(opts2), opts2)
} catch (Exception e) {
    ec.logger.warn("Error al parsear XML:"+e.printStackTrace())
    return
}

// Debo agregar el schema location (Sino SII rechaza)
XmlCursor cursor = envioBoletaDocument.newCursor()
if (cursor.toFirstChild()) {
    cursor.setAttributeText(new QName("http://www.w3.org/2001/XMLSchema-instance", "schemaLocation"), "http://www.sii.cl/SiiDte EnvioBOLETA_v11.xsd")
}
cursor.dispose()

//boleta = envioBoletaDocument.getEnvioBOLETA().getSetDTE().getDTEArray(0)


// remover namespace de boleta
namespaces2 = new HashMap<String, String>()
namespaces2.put("", "http://www.sii.cl/SiiDte")
//namespaces2.put("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
//namespaces2.put("xsi:schemaLocation", "http://www.sii.cl/SiiDte EnvioBOLETA_v11.xsd")
opts2 = new XmlOptions()
opts2.setUseDefaultNamespace()
opts2.setSaveImplicitNamespaces(namespaces2)
//boleta = BOLETADefType.Factory.parse(boleta.newInputStream(opts2))


//boleta.timbrar(caf.getCAF(), caf.getPrivateKey(null))
//boleta.getDocumento().xsetTmstFirma(now)
//boleta.sign(key, cert)

//envioBoletaDocument.getEnvioBOLETA().getSetDTE().getDTEArray(0).timbrar(caf.getCAF(), caf.getPrivateKey(null))
//envioBoletaDocument.getEnvioBOLETA().getSetDTE().getDTEArray(0).getDocumento().xsetTmstFirma(now)
//envioBoletaDocument.getEnvioBOLETA().getSetDTE().getDTEArray(0).sign(key, cert)


String uri = ""
now = FechaHoraType.Factory.newValue(Utilities.fechaHoraFormat.format(new Date()))

uri = sdte.getID()

// Firma de boleta
//boleta.getDocumento().xsetTmstFirma(now)
//boleta.sign(key, cert)

uri = "#" + uri
ec.logger.warn("URI: " + uri)

opts = new XmlOptions()
opts.setCharacterEncoding("ISO-8859-1")
ByteArrayOutputStream out = new ByteArrayOutputStream()

envioBoletaDocument.save(new File(resultS + "BOL" + tipoFactura + "-" + folio + "-sinfirma.xml"),opts)

archivoEnvio = pathResults + "BOL" + tipoFactura + "-"+folio+ ".xml"

//byte[] salida = Files.readAllBytes(Paths.get(archivoEnvio))


//cursor1 = envioBoletaDocument.newCursor()
cl.sii.siiDte.boletas.BOLETADefType pp = envioBoletaDocument.envioBOLETA.getSetDTE().getDTEArray(0)
cursor1 = pp.newCursor()
//cursor1.toFirstChild()
//cursor1.toChild(3)
//cursor1.toNextToken()
//while(cursor1.hasNextToken()) {
//System.out.println("********************** Token type: " + cursor1.currentTokenType() + " / " + cursor1.xmlText())
//cursor1.setAttributeText(new QName("", "xmlns"), "http://www.sii.cl/SiiDte")
//cursor1.toNextToken()
//}
//cursor1.dispose()
envioBoletaDocument.save(out, opts)
System.out.println("BOLETA7:"+out)

Document doc2 = XMLUtil.parseDocument(out.toByteArray())

// Firma de BOLETA
envioBoletaDocument.envioBOLETA.getSetDTE().getDTEArray(0).getDocumento().xsetTmstFirma(now)
// Deja segunda firma mal ubicada
//byte[] salidaBoleta = Signer.sign(doc2, uriBoleta, key, cert, uriBoleta, "Documento")
// Deja firma de boleta en lugar correcto, con URI correcta
//byte[] salidaBoleta = Signer.sign2(doc2, uriBoleta, key, cert, uriBoleta, "Documento")
// Firma con metodo alterno (xpath)
byte[] salidaBoleta = BoletaSigner.signBoleta(doc2, key, cert)
//byte[] salidaBoleta = BoletaSigner2.signBoleta(doc2, key, cert, uriBoleta)
//byte[] salidaBoleta = Signer.signEmbededBoleta(doc2, uriBoleta, key, cert)
//doc2 = XMLUtil.parseDocument(salidaBoleta)
// Firma de EnvioBOLETA
byte[] facturaXml = Signer.sign(doc2, uri, key, cert, uri, "SetDTE")
doc2 = XMLUtil.parseDocument(facturaXml)


if (Signer.verify(doc2, "SetDTE")) {
    ec.logger.warn("Factura "+path+" folio "+folio+" generada OK")
} else {
    ec.logger.warn("Error al generar boleta folio "+folio)
}

// Registro de DTE en base de datos y generación de PDF -->

fiscalTaxDocumentTypeEnumId = "Ftdt-${tipoFacturaS}"
xml = "${resultS}BOL${tipoFactura}-${folio}.xml"
pdf = "${pathPdf}BOL${tipoFactura}-${folio}.pdf"
context.putAll(ec.service.sync().name("mchile.DTEServices.genera#PDF").parameters([dte:facturaXml, issuerPartyId:issuerPartyId, boleta:true, continua:continua]).call())

// Creación de registro en FiscalTaxDocument
dteEv = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, fiscalTaxDocumentNumber:folio]).forUpdate(true).one()
dteEv.issuerPartyId = activeOrgId

if (rutReceptor != "66666666-6") {
    dteEv.receiverPartyid = receiverPartyId
    dteEv.receiverPartyIdTypeEnumId = "PtidNationalTaxId"
}
dteEv.fiscalTaxDocumentStatusEnumId = "Ftdt-Issued"
dteEv.fiscalTaxDocumentSentStatusEnumId = "Ftdt-NotSent"
dteEv.invoiceId = invoiceId
dteEv.date = ec.user.nowTimestamp
dteFeild.update()
// Creación de registro en FiscalTaxDocumentAttributes
// montoNeto
// montoIVARecuperable
// montoExento
// Amount
updateMap = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, emailEmisor:emailEmisor, amount:amount,
             montoNeto:montoNeto, tasaImpuesto:19, fechaEmision:fechaEmision,
             montoExento:montoExento, montoIVARecuperable:montoIVARecuperable]
context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(updateMap).call())

// Creacion de registros en FiscalTaxDocumentContent
xmlName = "dbresource://moit/erp/dte/${rutEmisor}/DTE${tipoFactura}-${folio}.xml"
pdfName = "dbresource://moit/erp/dte/${rutEmisor}/DTE${tipoFactura}-${folio}.pdf"
createMapBase = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, contentDte:ts]
context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:xmlName]).call())
ec.resource.getLocationReference(xmlName).putBytes(facturaXml)

context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:pdfName]).call())
ec.resource.getLocationReference(pdfName).putBytes(pdfBytes)
fiscalTaxDocumentId = dteEv.fiscalTaxDocumentId
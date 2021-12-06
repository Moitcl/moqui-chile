import java.text.SimpleDateFormat
import org.moqui.context.ExecutionContext

import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

import javax.xml.namespace.QName

import org.apache.xmlbeans.XmlCursor
import org.apache.xmlbeans.XmlOptions
import org.w3c.dom.Document

import cl.moit.dte.MoquiDTEUtils
import cl.nic.dte.util.Utilities
import cl.nic.dte.util.XMLUtil
import cl.sii.siiDte.boletas.EnvioBOLETADocument
import cl.sii.siiDte.boletas.BOLETADefType
import cl.sii.siiDte.boletas.EnvioBOLETADocument.EnvioBOLETA
import cl.sii.siiDte.boletas.EnvioBOLETADocument.EnvioBOLETA.SetDTE
import cl.sii.siiDte.boletas.EnvioBOLETADocument.EnvioBOLETA.SetDTE.Caratula
import cl.sii.siiDte.boletas.EnvioBOLETADocument.EnvioBOLETA.SetDTE.Caratula.SubTotDTE
import cl.sii.siiDte.FechaHoraType

ExecutionContext ec = context.ec

// Recuperacion de parametros de la organizacion -->
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameters([partyId:organizationPartyId]).call())
idS = "Doc"

Date dNow = new Date()
SimpleDateFormat ft = new SimpleDateFormat("yyMMddhhmmssMs")
String datetime = ft.format(dNow)
idS = idS + datetime

javax.sql.rowset.serial.SerialBlob[] DTEList = new javax.sql.rowset.serial.SerialBlob[documentIdList.size()]
int j = 0

documentIdList.each { documentId ->
    fiscalTaxDocument = documentId
    dteEv = ec.entity.find("mchile.dte.FiscalTaxDocumentContent").condition([fiscalTaxDocumentId:documentId, fiscalTaxdocumentContentTypeEnumId:"Ftdct-Xml"]).selectField("contentLocation").one()
    xmlReference = ec.resource.getLocationReference(dteEv.contentLocation)
    DTEList[j] = xmlReference
    j++
}

// Validación rut
if (recepS)
    ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameters([rut:recepS]).call()
ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameters([rut:enviadorS]).call()

ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameters([partyId:organizationPartyId]).call())

// Construyo Envio
templateEnvioBoleta = """
<?xml version="1.0" encoding="ISO-8859-1"?>
<EnvioBOLETA version="1.0" xmlns="http://www.sii.cl/SiiDte" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://www.sii.cl/SiiDte EnvioBOLETA_v11.xsd">
    <SetDTE>
        <Caratula version="1.0">
            <RutEmisor>${rutEmisor}</RutEmisor>
            <FchResol>${fchResol}</FchResol>
            <NroResol>${nroResol}</NroResol>
        </Caratula>
    </SetDTE>
</EnvioBOLETA>"""
cl.sii.siiDte.boletas.EnvioBOLETADocument envioBoletaDocument = EnvioBOLETADocument.Factory.parse(new ByteArrayInputStream(templateEnvioBoleta.bytes))
System.out.println("XML: "+envioBoletaDocument.toString())

// Debo agregar el schema location (Sino SII rechaza)
//XmlCursor cursor = envioBoletaDocument.newCursor()
//cursor.toNextToken()
//cursor.insertNamespace("xmlns", "http://www.sii.cl/SiiDte")
//cursor.dispose()

//if (cursor.toFirstChild()) {
// cursor.setAttributeText(new QName("http://www.w3.org/2001/XMLSchema-instance", "schemaLocation"), "http://www.sii.cl/SiiDte EnvioBOLETA_v11.xsd")
//}
// leo certificado y llave privada del archivo pkcs12
KeyStore ks = KeyStore.getInstance("PKCS12")
ks.load(new ByteArrayInputStream(certData.decodeBase64()), passCert.toCharArray())
String alias = ks.aliases().nextElement()

X509Certificate x509 = (X509Certificate) ks.getCertificate(alias)
String enviadorS = Utilities.getRutFromCertificate(x509)
PrivateKey pKey = (PrivateKey) ks.getKey(alias, passCert.toCharArray())

ec.logger.warn("RUT envia: " + enviadorS)

EnvioBOLETA eb = EnvioBOLETA.Factory.newInstance()
SetDTE sdte = SetDTE.Factory.newInstance()
sdte.setID("EB" + System.nanoTime())

Caratula caratula = sdte.addNewCaratula()
caratula.setVersion(new BigDecimal("1.0"))
caratula.setRutEmisor(rutEmisor)
caratula.setRutEnvia(enviadorS)
caratula.setRutReceptor('60803000-K')

Calendar cal = Calendar.getInstance()
cal.clear()
cal.set(Calendar.YEAR, Integer.valueOf('2018'))
cal.set(Calendar.MONTH, Integer.valueOf('09'))
cal.set(Calendar.DAY_OF_MONTH, Integer.valueOf('24'))
caratula.setFchResol(cal)

caratula.setNroResol(Integer.valueOf(nroResol))
caratula.xsetTmstFirmaEnv(now)

// documentos a enviar
//HashMap&lt;String, String&gt; namespaces = new HashMap&lt;String, String&gt;()
//namespaces.put("", "http://www.sii.cl/SiiDte")
//XmlOptions opts = new XmlOptions()
//opts.setLoadSubstituteNamespaces(namespaces)

// Cantidad de documentos a enviar

BOLETADefType[] dtes = new BOLETADefType[DTEList.size()]

HashMap<Integer, Integer> hashTot = new HashMap<Integer, Integer>()

for (int i = 0; i < DTEList.length; i++) {
    cl.sii.siiDte.boletas.EnvioBOLETADocument.EnvioBOLETA envioBoletaOld = EnvioBOLETADocument.Factory.parse(DTEList[i].openStream()).getEnvioBOLETA()
    cl.sii.siiDte.boletas.BOLETADefType[] boletaArray = envioBoletaOld.setDTE.getDTEArray()
    dtes[i] = boletaArray[0]
    // armar hash para totalizar por tipoDTE
    if (hashTot.get(dtes[i].getDocumento().getEncabezado().getIdDoc().getTipoDTE().intValue()) != null) {
        hashTot.put(dtes[i].getDocumento().getEncabezado().getIdDoc().getTipoDTE().intValue(),
                hashTot.get(dtes[i].getDocumento().getEncabezado().getIdDoc().getTipoDTE().intValue()) + 1)
    } else {
        hashTot.put(dtes[i].getDocumento().getEncabezado().getIdDoc().getTipoDTE().intValue(), 1)
    }
}
System.out.println("Tamanno arreglo:" + hashTot.size())
SubTotDTE[] subtDtes = new SubTotDTE[hashTot.size()]
int i = 0
for (Integer tipo : hashTot.keySet()) {
    SubTotDTE subt = SubTotDTE.Factory.newInstance()
    subt.setTpoDTE(new BigInteger(tipo.toString()))
    subt.setNroDTE(new BigInteger(hashTot.get(tipo).toString()))
    subtDtes[i] = subt
    i++
}
caratula.setSubTotDTEArray(subtDtes)
// Le doy un formato bonito (debo hacerlo antes de firmar para no afectar los DTE internos)
HashMap<String, String> namespaces = new HashMap<String, String>()
XmlOptions opts = new XmlOptions()
opts.setSavePrettyPrint()
opts.setSavePrettyPrintIndent(4)
envioBoletaDocument = EnvioBOLETADocument.Factory.parse(envioBoletaDocument.newInputStream(opts))

eb.setSetDTE(sdte)
eb.setVersion(new BigDecimal("1.0"))
envioBoletaDocument.setEnvioBOLETA(eb)

envioBoletaDocument.getEnvioBOLETA().getSetDTE().setDTEArray(dtes)
FechaHoraType now = FechaHoraType.Factory.newValue(Utilities.fechaHoraFormat.format(new Date()))

envioBoletaDocument.getEnvioBOLETA().getSetDTE().getCaratula().xsetTmstFirmaEnv(now)

opts = new XmlOptions()
opts.setCharacterEncoding("ISO-8859-1")

HashMap<String, String> namespaces2 = new HashMap<String, String>()
namespaces2.put("", "http://www.sii.cl/SiiDte")
namespaces2.put("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
namespaces2.put("xsi:schemaLocation", "http://www.sii.cl/SiiDte EnvioBOLETA_v11.xsd")

XmlCursor cursor2 = envioBoletaDocument.newCursor()
if (cursor2.toFirstChild()) {
    cursor2.setAttributeText(new QName("http://www.w3.org/2001/XMLSchema-instance", "xsi", "xmlns"), "http://www.w3.org/2001/XMLSchema-instance")
    //cursor2.setAttributeText(new QName("", "xmlns"), "http://www.sii.cl/SiiDte")
    cursor2.setAttributeText(new QName("http://www.w3.org/2001/XMLSchema-instance", "schemaLocation"), "http://www.sii.cl/SiiDte EnvioBOLETA_v11.xsd")
}
cursor2.dispose()

XmlOptions opts2 = new XmlOptions()
opts2.setSaveSuggestedPrefixes(namespaces2); // deja el ssid solamente
opts2.setUseDefaultNamespace()
opts2.setSavePrettyPrint()
opts2.setSavePrettyPrintIndent(0)

String uri = envioBoletaDocument.getEnvioBOLETA().getSetDTE().getID()
ec.logger.warn("URI: " + uri)

if (saveSinFirma) {
    xmlContentReference = ec.resource.getLocationReference("dbresource://moit/erp/dte/${rutEmisor}/ENVBOL-${idS}-sinfirma.xml")
    envioBoletaDocument.save(xmlContentReference.outputStream, opts2)
}

envioBoletaDocument.sign(pKey, x509)
ByteArrayOutputStream out = new ByteArrayOutputStream()
envioBoletaDocument.save(out, opts2)

xmlContentReference = ec.resource.getLocationReference("dbresource://moit/erp/dte/${rutEmisor}/ENVBOL-${idS}.xml")
envioBoletaDocument.save(xmlContentLocation.outputStream, opts2)

Document doc2 = XMLUtil.parseDocument(out.toByteArray())

opts = new XmlOptions()
opts.setCharacterEncoding("ISO-8859-1")

if (saveSinFirma) {
    ResourceReference xmlContentResource = ec.resource.getLocationReference("dbresource://moit/erp/dte/${rutEmisor}/ENV-${idS}-sinfirma.xml")
    envioBoletaDocument.save(xmlContentReference.outputStream, opts)
}
out = new ByteArrayOutputStream()
envio.save(out, opts)

doc2 = XMLUtil.parseDocument(out.toByteArray())

byte[] salida = MoquiDTEUtils.sign(doc2, "#" + idS, pKey, x509, "#" + idS,"SetDTE")
doc2 = XMLUtil.parseDocument(salida)

if (MoquiDTEUtils.verifySignature(doc2, "//sii:SetDTE")) {
    xmlContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/ENV-${idS}.xml"
    ec.resource.getLocationReference(xmlContentLocation).putBytes(salida)
    ec.logger.warn("Envio generado OK")
} else {
    xmlContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/ENV-${idS}-mala.xml"
    ec.resource.getLocationReference(xmlContentLocation).putBytes(salida)
    ec.logger.warn("Error al generar envio")
}

// Se guarda referencia a XML de envío en BD -->
documentIdList.each { documentId ->
    createMap = [fiscalTaxDocumentId:documentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Misc', contentLocation:archivoEnvio, contentDate:ts]
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())

// Se marca DTE como enviada
    idDte = documentId
    dteEv = ec.entity.find("mchile.dte.FiscalTaxDocument").forUpdate(true).condition("fiscalTaxDocumentId", idDte).one()
    dteEv.sentAuthStatusId = "Ftd-SentAuthUnverified"
    dteEv.update()
}
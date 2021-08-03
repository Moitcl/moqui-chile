import org.moqui.context.ExecutionContext

import java.text.SimpleDateFormat
import org.moqui.resource.ResourceReference

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

import javax.xml.namespace.QName

import org.apache.xmlbeans.XmlCursor
import org.apache.xmlbeans.XmlOptions
import org.w3c.dom.Document

import cl.nic.dte.util.Signer
import cl.nic.dte.util.Utilities
import cl.nic.dte.util.XMLUtil
import cl.sii.siiDte.DTEDefType
import cl.sii.siiDte.DTEDocument
import cl.sii.siiDte.EnvioDTEDocument
import cl.sii.siiDte.FechaHoraType
import cl.sii.siiDte.EnvioDTEDocument.EnvioDTE.SetDTE.Caratula.SubTotDTE

ExecutionContext ec = context.ec

// Recuperacion de parametros de la organizacion
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameters([partyId:organizationPartyId]).call())
passS = passCert
plantillaEnvio = templateEnvio
//giro = giroEmisor

if (!templateEnvio) {
    ec.message.addError("Organizacion no tiene plantilla para envio al SII")
    return
}
idS = "Doc"

Date dNow = new Date()
SimpleDateFormat ft = new SimpleDateFormat("yyMMddhhmmssMs")
String datetime = ft.format(dNow)
idS = idS + datetime

ResourceReference[] DTEList = new ResourceReference[documentIdList.size()]
int j = 0

documentIdList.each { documentId ->
    fiscalTaxDocument = documentId
    dteEv = ec.entity.find("mchile.dte.FiscalTaxDocumentContent").condition([fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:"Ftdct-Xml"]).selectField("contentLocation").one()
    xml = dteEv.contentLocation
    DTEList[j] = ec.resource.getLocationReference((String)xml)
    ec.logger.warn("Agregado: " + DTEList[j])
    j++
}
// Validación rut
if (rutReceptor) {
    ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameters([rut:rutReceptor]).call()
}
ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameters([rut:rutenviador]).call()

// Construyo Envio
EnvioDTEDocument envio = EnvioDTEDocument.Factory.parse(ec.resource.getLocationStream((String)plantillaEnvio))

// Debo agregar el schema location (Sino SII rechaza)
XmlCursor cursor = envio.newCursor()
if (cursor.toFirstChild()) {
    cursor.setAttributeText(new QName("http://www.w3.org/2001/XMLSchema-instance", "schemaLocation"), "http://www.sii.cl/SiiDte EnvioDTE_v10.xsd")
}
// leo certificado y llave privada del archivo pkcs12
KeyStore ks = KeyStore.getInstance("PKCS12")
ks.load(new ByteArrayInputStream(certData.decodeBase64()), passS.toCharArray())
String alias = ks.aliases().nextElement()

X509Certificate x509 = (X509Certificate) ks.getCertificate(alias)
String rutEnviador = Utilities.getRutFromCertificate(x509)
PrivateKey pKey = (PrivateKey) ks.getKey(alias, passS.toCharArray())

ec.logger.warn("RUT envia: " + rutEnviador)

// Asigno un ID
envio.getEnvioDTE().getSetDTE().setID(idS)

cl.sii.siiDte.EnvioDTEDocument.EnvioDTE.SetDTE.Caratula car = envio.getEnvioDTE().getSetDTE().getCaratula()

car.setRutReceptor('60803000-K') // El receptor del envío es el SII
car.setRutEnvia(rutEnviador)

// documentos a enviar
HashMap<String, String> namespaces = new HashMap<String, String>()
namespaces.put("", "http://www.sii.cl/SiiDte")
XmlOptions opts = new XmlOptions()
opts.setLoadSubstituteNamespaces(namespaces)

// Cantidad de documentos a enviar

DTEDefType[] dtes = new DTEDefType[DTEList.size()]

HashMap<Integer, Integer> hashTot = new HashMap<Integer, Integer>()

for (int i = 0; i < DTEList.length; i++) {
    dtes[i] = DTEDocument.Factory.parse(DTEList[i].openStream(), opts).getDTE()
    // armar hash para totalizar por tipoDTE
    if (hashTot.get(dtes[i].getDocumento().getEncabezado().getIdDoc().getTipoDTE().intValue()) != null) {
        hashTot.put(dtes[i].getDocumento().getEncabezado().getIdDoc().getTipoDTE().intValue(),
                hashTot.get(dtes[i].getDocumento().getEncabezado().getIdDoc().getTipoDTE().intValue()) + 1)
    } else {
        hashTot.put(dtes[i].getDocumento().getEncabezado().getIdDoc().getTipoDTE().intValue(), 1)
    }
}
SubTotDTE[] subtDtes = new SubTotDTE[hashTot.size()]
int i = 0
for (Integer tipo : hashTot.keySet()) {
    SubTotDTE subt = SubTotDTE.Factory.newInstance()
    subt.setTpoDTE(new BigInteger(tipo.toString()))
    subt.setNroDTE(new BigInteger(hashTot.get(tipo).toString()))
    subtDtes[i] = subt
    i++
}
car.setSubTotDTEArray(subtDtes)
// Le doy un formato bonito (debo hacerlo antes de firmar para no
// afectar los DTE internos)
opts = new XmlOptions()
opts.setSavePrettyPrint()
opts.setSavePrettyPrintIndent(4)
envio = EnvioDTEDocument.Factory.parse(envio.newInputStream(opts))
envio.getEnvioDTE().getSetDTE().setDTEArray(dtes)
FechaHoraType now = FechaHoraType.Factory.newValue(Utilities.fechaHoraFormat.format(new Date()))
envio.getEnvioDTE().getSetDTE().getCaratula().xsetTmstFirmaEnv(now)

// firmo
//envio.sign(pKey, x509)

opts = new XmlOptions()
opts.setCharacterEncoding("ISO-8859-1")
ByteArrayOutputStream out = new ByteArrayOutputStream()

envio.save(new File(pathResults + "ENV" + idS + "-sinfirma.xml"), opts)
envio.save(out, opts)

Document doc2 = XMLUtil.parseDocument(out.toByteArray())

byte[] salida = Signer.sign(doc2, "#" + idS, pKey, x509, "#" + idS,"SetDTE")
doc2 = XMLUtil.parseDocument(salida)

if (Signer.verify(doc2, "SetDTE")) {
    archivoEnvio = pathResults + "ENV" + idS + ".xml"
    Path path = Paths.get(pathResults + "ENV" + idS + ".xml")
    Files.write(path, salida)
    ec.logger.warn("Envio generado OK")
} else {
    archivoEnvio = pathResults + "ENV" + idS + "-mala.xml"
    Path path = Paths.get(pathResults + "ENV" + idS + "-mala.xml")
    Files.write(path, salida)
    ec.logger.warn("Error al generar envio")
}

// Se guarda referencia a XML de envío en BD -->
documentIdList.each { documentId ->
    createMap = [fiscalTaxDocumentId:documentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Misc', contentLocation:archivoEnvio, contentDate:ts]
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())

    // Se marca DTE como enviada -->
    idDte = documentId
    dteEv = ec.entity.find("mchile.dte.FiscalTaxDocument").condition("fiscalTaxDocumentId", idDte).forUpdate(true).one()
    dteEv.fiscalTaxDocumentSentStatusEnumId = "Ftdt-Sent"
    dteEv.update()
}

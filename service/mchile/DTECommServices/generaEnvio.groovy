import org.moqui.context.ExecutionContext

import java.text.SimpleDateFormat
import org.moqui.resource.ResourceReference

import javax.xml.namespace.QName

import org.apache.xmlbeans.XmlCursor
import org.apache.xmlbeans.XmlOptions
import org.w3c.dom.Document

import cl.moit.dte.MoquiDTEUtils
import cl.nic.dte.util.Utilities
import cl.sii.siiDte.DTEDefType
import cl.sii.siiDte.DTEDocument
import cl.sii.siiDte.EnvioDTEDocument
import cl.sii.siiDte.FechaHoraType
import cl.sii.siiDte.EnvioDTEDocument.EnvioDTE.SetDTE.Caratula.SubTotDTE

ExecutionContext ec = context.ec

// Recuperacion de parametros de la organizacion
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameters([partyId:organizationPartyId]).call())
idS = "Doc"

Date dNow = new Date()
SimpleDateFormat ft = new SimpleDateFormat("yyMMddhhmmssMs")
String datetime = ft.format(dNow)
idS = idS + datetime

ResourceReference[] DTEList = new ResourceReference[documentIdList.size()]
int j = 0

documentIdList.each { fiscalTaxDocumentId ->
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

// Construyo Envio
plantillaEnvio = """<?xml version="1.0" encoding="UTF-8"?>
<EnvioDTE version="1.0" xmlns="http://www.sii.cl/SiiDte">
	<SetDTE>
		<Caratula version="1.0">
			<RutEmisor>${rutEmisor}</RutEmisor>
			<FchResol>${fchResol}</FchResol>
			<NroResol>${nroResol}</NroResol>
		</Caratula>
	</SetDTE>
</EnvioDTE>"""
EnvioDTEDocument envio = EnvioDTEDocument.Factory.parse(new ByteArrayInputStream(plantillaEnvio.bytes))

// Debo agregar el schema location (Sino SII rechaza)
XmlCursor cursor = envio.newCursor()
if (cursor.toFirstChild()) {
    cursor.setAttributeText(new QName("http://www.w3.org/2001/XMLSchema-instance", "schemaLocation"), "http://www.sii.cl/SiiDte EnvioDTE_v10.xsd")
}

// Asigno un ID
envio.getEnvioDTE().getSetDTE().setID(idS)

cl.sii.siiDte.EnvioDTEDocument.EnvioDTE.SetDTE.Caratula car = envio.getEnvioDTE().getSetDTE().getCaratula()

car.setRutReceptor(rutReceptor) // El receptor del envío es el SII
car.setRutEnvia(rutEnvia)

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

opts = new XmlOptions()
opts.setCharacterEncoding("ISO-8859-1")

if (saveSinFirma) {
    ResourceReference xmlContentReference = ec.resource.getLocationReference("dbresource://moit/erp/dte/${rutEmisor}/ENV-${idS}-sinfirma.xml")
    envioBoletaDocument.save(xmlContentReference.outputStream, opts)
}
ByteArrayOutputStream out = new ByteArrayOutputStream()
envio.save(out, opts)
Document doc2 = MoquiDTEUtils.parseDocument(out.toByteArray())

byte[] salida = MoquiDTEUtils.sign(doc2, "#" + idS, pkey, certificate, "#" + idS,"SetDTE")
doc2 = MoquiDTEUtils.parseDocument(salida)

if (MoquiDTEUtils.verifySignature(doc2, "/sii:EnvioDTE/sii:SetDTE", "./sii:Caratula/sii:TmstFirmaEnv/text()")) {
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
    createMap = [fiscalTaxDocumentId:documentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Misc', contentLocation:xmlContentLocation, contentDate:ts]
    ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
}

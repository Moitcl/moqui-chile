import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.xml.namespace.QName

import org.apache.xmlbeans.XmlOptions
import org.apache.xmlbeans.XmlCursor

import cl.nic.dte.VerifyResult
import cl.sii.siiDte.DTEDefType
import cl.sii.siiDte.EnvioDTEDocument
import cl.sii.siiDte.EnvioRecibosDocument
import cl.sii.siiDte.ReciboDefType
import cl.sii.siiDte.ReciboDocument
import cl.sii.siiDte.EnvioRecibosDocument.EnvioRecibos
import cl.sii.siiDte.EnvioRecibosDocument.EnvioRecibos.SetRecibos
import cl.sii.siiDte.ReciboDefType.DocumentoRecibo

import org.moqui.context.ExecutionContext

ExecutionContext ec = context.ec

// No se envían aceptaciones por boletas
if ((fiscalTaxDocumentTypeEnumId == 'Ftdt-39') || (fiscalTaxDocumentTypeEnumId == 'Ftdt-41') || (fiscalTaxDocumentTypeEnumId == 'PvtBoleta')) {
    ec.message.addMessage("Boletas no requieren envío de aceptación", "warning")
    return
}
rutResponde = ec.service.sync("mchile.GeneralServices.get#RutForParty").parameters([partyId:organizationPartyId, failIfNotFound:true]).call().rut
// Recuperacion de parametros de la organizacion -->
ec.context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameters([partyId:organizationPartyId]).call())

rutEnviador = rutEnvia

// Se guarda aceptacion para obtener el aceptacionDteId
createMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, rutResponde:rutResponde, rutRecibe:rutRecibe, nmbContacto:nmbContacto,
        fonoContacto:fonoContacto, mailContacto:mailContacto, issuerPartyId:organizationPartyId]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.AceptacionDte").parameters(createMap).call())

// Recuperación de datos para emitir aceptación
dteEv = ec.entity.find("mchile.dte.FiscalTaxDocumentContent").condition([fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:"Ftdct-Xml"]).selectField("contentLocation").one()
String envioRecibido = dteEv.contentLocation
idS = (int) (System.currentTimeMillis() / 1000L)

X509Certificate cert
PrivateKey key

EnvioDTEDocument envio
try {
    envio = EnvioDTEDocument.Factory.parse(new FileInputStream(envioRecibido))
} catch (Exception e) {
    ec.logger.warn("Error al cargar archivo de envio recepcionado: " + envioRecibido, e)
    return
}
VerifyResult resl = envio.verifyXML()
boolean envioEsquemaOK = true
boolean envioFirmaOK = true
String errorEsquema = ""
String errorFirma = ""

if( !resl.isOk()) {
    ec.logger.error("Envio recibido: Estructura XML incorrecta: ${resl.getMessage()}")
    errorEsquema = resl.getMessage()
    envioEsquemaOK = false
} else {
    ec.logger.warn("Envio recibido: Estructura XML OK")
}
// Revisar
resl = envio.verifySignature()
if( !resl.isOk()) {
    ec.logger.error("Envio recibido: firma XML incorrecta")
    firmaOKDTE = false
} else {
    ec.logger.debug("Envio recibido: firma XML OK")
}
boolean envioRutOK = true

String rutContribuyente = rutResponde
rutEmisor = envio.getEnvioDTE().getSetDTE().getCaratula().getRutEmisor()

if(!rutContribuyente.equals(envio.getEnvioDTE().getSetDTE().getCaratula().getRutReceptor())) {
    ec.logger.error("Error: carátula de envioDTE recibido dice que rut de receptor es: ${envio.getEnvioDTE().getSetDTE().getCaratula().getRutReceptor()} el cual es distinto al de nuestra empresa: ${rutContribuyente}")
    envioRutOK = false
}

// leo certificado y llave privada del pkcs12
KeyStore ks = KeyStore.getInstance("PKCS12")
//ks.load(new FileInputStream(certS), passCert.toCharArray())
ks.load(new ByteArrayInputStream(certData.decodeBase64()), passCert.toCharArray())
String alias = ks.aliases().nextElement()
cert = (X509Certificate) ks.getCertificate(alias)
String rutCertificado = Utilities.getRutFromCertificate(cert)
ec.logger.warn("Usando certificado ${alias}, Rut ${rutCertificado}")

key = (PrivateKey) ks.getKey(alias, passCert.toCharArray())

ReciboDocument recibo = ReciboDocument.Factory.newInstance()
ReciboDefType rec = recibo.addNewRecibo()

EnvioRecibosDocument erd = EnvioRecibosDocument.Factory.newInstance()
EnvioRecibos er = EnvioRecibos.Factory.newInstance()
er.setVersion(new BigDecimal("1.0"))

cl.sii.siiDte.EnvioRecibosDocument.EnvioRecibos.SetRecibos.Caratula caratula = cl.sii.siiDte.EnvioRecibosDocument.EnvioRecibos.SetRecibos.Caratula.Factory
        .newInstance()
caratula.setRutResponde(rutContribuyente)
caratula.setRutRecibe(rutEmisor)
//caratula.setIdRespuesta(new Long(idS))
caratula.setVersion(new BigDecimal("1.0"))
// Datos en plantilla?
caratula.setNmbContacto(nmbContacto)
caratula.setMailContacto(mailContacto)
caratula.setFonoContacto(fonoContacto)

Calendar cal = Calendar.getInstance()
caratula.setTmstFirmaEnv(cal)


SetRecibos sr = SetRecibos.Factory.newInstance()
sr.setCaratula(caratula)
sr.setID("SRM-33-1234-60910000-1")
ReciboDefType[] recArray = new ReciboDefType[envio.getEnvioDTE().getSetDTE().getDTEArray().size()]
int i = 0

// Se recorre lista de DTE en el envio

for (DTEDefType dte : envio.getEnvioDTE().getSetDTE().getDTEArray()) {
    ec.logger.warn("Recorriendo dte: " + dte)


    boolean firmaOKDTE = true
    if(!resl.isOk()) {
        ec.logger.warn("Validando DTE ID ${dte.getDocumento().getID()}: Firma XML Incorrecta: ${resl.getMessage()}")
        firmaOKDTE = false
    } else {
        ec.logger.warn("Validando DTE ID ${dte.getDocumento().getID()}: Firma XML OK")
    }
    boolean rutDTEOK = true

    if(!rutContribuyente.equals(dte.getDocumento().getEncabezado().getReceptor().getRUTRecep())) {
        ec.logger.warn("Error, DTE id: ${dte.getDocumento().getID()} folio: ${dte.getDocumento().getEncabezado().getIdDoc().getFolio()} tipo: ${dte.getDocumento().getEncabezado().getIdDoc().getTipoDTE()} contiene RUT de receptor [${dte.getDocumento().getEncabezado().getReceptor().getRUTRecep()}] que no corresponde a nuestra empresa [${rutContribuyente}]")
        rutDTEOK = false
    }
    DocumentoRecibo dr
    if( firmaOKDTE && rutDTEOK) {
        dr = rec.addNewDocumentoRecibo()
        estadoDTE = "DOK"
        dr.setFolio(dte.getDocumento().getEncabezado().getIdDoc().getFolio())
        dr.setTipoDoc(dte.getDocumento().getEncabezado().getIdDoc().getTipoDTE())
        dr.setFchEmis(dte.getDocumento().getEncabezado().getIdDoc().getFchEmis())
        dr.setRUTEmisor(dte.getDocumento().getEncabezado().getEmisor().getRUTEmisor())
        dr.setRUTRecep(dte.getDocumento().getEncabezado().getReceptor().getRUTRecep())
        dr.setMntTotal(dte.getDocumento().getEncabezado().getTotales().getMntTotal())
        dr.setRecinto("No especificado")
        dr.setRutFirma(rutEnviador)
        dr.setID(dte.getDocumento().getID())
        dr.setTmstFirmaRecibo(cal)
        dr.setDeclaracion("El acuse de recibo que se declara en este acto, de acuerdo a lo dispuesto en la letra b) del Art. 4, y la letra c) del Art. 5 de la Ley 19.983, acredita que la entrega de mercaderias o servicio(s) prestado(s) ha(n) sido recibido(s).")
        i++
    } else {
        if( !firmaOKDTE) {
            //resDTE.setEstadoDTE(new Integer(2))
            //resDTE.setEstadoDTEGlosa("DTE rechazado - Error de Firma")
            //estadoRecepEnvEnumId = 2
            ec.logger.warn("DTE Rechazado - Error de Firma")
            //rDTE.setEstadoRecepDTE(new Integer(1))
            //rDTE.setRecepDTEGlosa("DTE No Recibido - Error de Firma")
            ec.logger.warn("DTE No Recibido - Error de Firma")
        } else if(!rutDTEK) {
            //resDTE.setEstadoDTE(new Integer(2))
            //resDTE.setEstadoDTEGlosa("DTE rechazado - Error en RUT Receptor")
            ec.logger.warn("DTE rechazado - Error en RUT Receptor")
            //rDTE.setEstadoRecepDTE(new Integer(3))
            //rDTE.setRecepDTEGlosa("DTE No Recibido - Error en RUT Receptor")
            ec.logger.warn("DTE No Recibido - Error en RUT Receptor")
        }
    }
    //recArray[i] = recibo.getRecibo()
}
recArray = recibo.getRecibo()
rec.setVersion(new BigDecimal("1.0"))

namespaces = new HashMap<String, String>()
namespaces.put("", "http://www.sii.cl/SiiDte")
opts = new XmlOptions()
opts.setSaveImplicitNamespaces(namespaces)
opts.setLoadSubstituteNamespaces(namespaces)
opts.setSavePrettyPrint()
opts.setSavePrettyPrintIndent(0)

try {
    recibo = ReciboDocument.Factory.parse(recibo.newInputStream(opts), opts)
} catch (Exception e) {
    e.printStackTrace()
}

// firma del recibo
//recibo.getRecibo().sign(key, cert)

//rec.setDocumentoRecibo(dr)
rec.setVersion(new BigDecimal("1.0"))
rec.sign(key, cert)

HashMap<String, String> namespaces = new HashMap<String, String>()
namespaces.put("", "http://www.sii.cl/SiiDte")
XmlOptions opts = new XmlOptions()
opts.setSaveImplicitNamespaces(namespaces)
opts.setLoadSubstituteNamespaces(namespaces)
opts.setSavePrettyPrint()
opts.setSavePrettyPrintIndent(0)

try {
    recibo = ReciboDocument.Factory.parse(recibo.newInputStream(opts), opts)
} catch (Exception e) {
    e.printStackTrace()
}
// Se firma esto?
recibo.getRecibo().sign(key, cert)

sr.setReciboArray(recArray)

er.setSetRecibos(sr)
erd.setEnvioRecibos(er)
XmlCursor cursor = erd.newCursor()
if (cursor.toFirstChild()) {
    cursor.setAttributeText(new QName( "http://www.w3.org/2001/XMLSchema-instance", "schemaLocation"), "http://www.sii.cl/SiiDte EnvioRecibos_v10.xsd")
}

opts = new XmlOptions()
opts.setLoadSubstituteNamespaces(namespaces)
opts.setSavePrettyPrint()
opts.setSavePrettyPrintIndent(0)
opts.setUseDefaultNamespace()
opts.setSaveSuggestedPrefixes(namespaces)
try {
    erd = EnvioRecibosDocument.Factory.parse(erd.newInputStream(opts))
} catch (Exception e) {
    e.printStackTrace()
}

erd.sign(key, cert)

resl = erd.verifyXML()
if (!resl.isOk()) {
    System.out.println("Documento: Estructura XML Incorrecta: "+ resl.getMessage())
} else {
    System.out.println("Documento: Estructura XML OK")
}
//resl = erd.verifySignature()
//if (!resl.isOk()) {
// System.out.println("Documento: Firma Incorrecta: " + resl.getMessage())
//} else {
// System.out.println("Documento: Firma OK")
//}

ec.logger.warn("XML: " + erd)

ResourceReference xmlContentResource = ec.resource.getLocationReference("dbresource://moit/erp/dte/${rutEmisor}/RECIBO-MERC-${idS}.xml")
ec.logger.warn("Escribiendo ${xmlContentResource.location}")
erd.save(xmlContentResource.outputStream, opts)

return

// Recuperación del email de destinatario de aceptación
partyAceptacionEv = ec.entity.find("mantle.party.PartyIdentification").condition([idValue:rutEmisor, partyIdTypeEnumId:"PtidNationalTaxId"]).one()
if (!partyAceptacionEv) {
    ec.message.addError("Organización a enviar aceptación no tiene RUT definido")
    return
}
contactOut = ec.service.sync().name("mantle.party.ContactServices.get#PartyContactInfo").parameters([partyId:partyAceptacionEv.partyId, postalContactMechPurposeId:'PostalTax']).call()
if (!contactOut.postalContactMechId) {
    ec.message.addError("Receptor de aceptación no tiene contacto tributario asignado")
    return
}
emailAceptacion = contactOut.emailAddress

// Recuperación de algunos datos desde FiscalTaxDocument
fiscalTaxDocumentEv = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([fiscalTaxDocumentId:fiscalTaxDocumentId]).one()
folioAceptacion = fiscalTaxDocumentEv.fiscalTaxDocumentNumber

createMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, rutResponde:rutResponde, rutRecibe:rutRecibe, nmbContacto:nmbContacto, fonoContacto:fonoContacto, mailContacto:mailContacto]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.AceptacionDte").parameters(createMap).call())

aceptacionEv = ec.entity.find("mchile.dte.AceptacionDte").condition("aceptacionDteId", aceptacionDteId).forUpdate(true).one()
//aceptacionEv.nmbEnvio = fiscalTaxDocumentEv.razonSocial
aceptacionEv.fchRecep = fchRecep
aceptacionEv.codEnvio = idS
aceptacionEv.rutEmisor = rutEmisor
aceptacionEv.envioDteId = RESP-${idS}
aceptacionEv.rutEmisor = rutEmisor
aceptacionEv.rutReceptor = rutResponde
aceptacionEv.estadoRecepEnvEnumId = estadoRecepEnvEnumId
aceptacionEv.nroDetalles = 1
aceptacionEv.xml = "xmlContentResource.location"
aceptacionEv.update()

bodyParameters = [fiscalTaxDocumentId:folioAceptacion, nmbContacto:nmbContacto, mailContacto:mailContacto, fonoContacto:fonoContacto]
ec.service.async().name("org.moqui.impl.EmailServices.send#EmailTemplate").parameters([fiscalTaxDocumentId: folioAceptacion, emailTypeEnumId: emailTypeEnumId, toAddresses:emailAceptacion,
                        emailTemplateId:"Aceptacion", bodyParameters:bodyParameters])
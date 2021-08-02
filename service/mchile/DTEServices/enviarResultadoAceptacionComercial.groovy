import org.moqui.context.ExecutionContext

import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.xml.namespace.QName

import org.apache.xmlbeans.XmlOptions
import org.apache.xmlbeans.XmlCursor

import cl.nic.dte.util.Utilities
import cl.nic.dte.util.XMLUtil
import cl.nic.dte.VerifyResult
import cl.sii.siiDte.DTEDefType
import cl.sii.siiDte.EnvioDTEDocument
import cl.sii.siiDte.RespuestaDTEDocument
import cl.sii.siiDte.RespuestaDTEDocument.RespuestaDTE
import cl.sii.siiDte.RespuestaDTEDocument.RespuestaDTE.Resultado
import cl.sii.siiDte.RespuestaDTEDocument.RespuestaDTE.Resultado.Caratula
import cl.sii.siiDte.RespuestaDTEDocument.RespuestaDTE.Resultado.ResultadoDTE
import cl.sii.siiDte.FechaHoraType

ExecutionContext ec

// No se envían aceptaciones por boletas
if ((fiscalTaxDocumentTypeEnumId == 'Ftdt-39') || (fiscalTaxDocumentTypeEnumId == 'Ftdt-41') || (fiscalTaxDocumentTypeEnumId == 'PvtBoleta')) {
    ec.message.addError("Boletas no requieren envío de aceptación")
    return
}
partyIdentificationList = ec.entity.find("mantle.party.PartyIdentification").condition([partyId:activeOrgId, partyIdTypeEnumId:"PtidNationalTaxId"]).list()
if (!partyIdentificationList) {
    ec.message.addError("Organización no tiene RUT definido")
    return
}
rutResponde = partyIdentificationList.idValue[0]

// Recuperacion de parametros de la organizacion
context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameter("partyId", activeOrgId).call())

passS = passCert
resultS = pathAceptaciones
dirS = pathRecibidos

// Se guarda aceptacion para obtener el aceptacionDteId
createMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, rutResponde:rutResponde, rutRecibe:rutRecibe, nmbContacto:nmbContacto,
             fonoContacto:fonoContacto, mailContacto:mailContacto, issuerPartyId:activeOrgId]
context.putAll(ec.service.sync().name("create#mchile.dte.AceptacionDte").parameters(createMap).call())

// Recuperación de datos para emitir aceptación -->
dteEv = ec.entity.find("mchile.dte.FiscalTaxDocumentContent").condition([fiscalTaxDocumentId:fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:"Ftdct-Xml"]).selectField("contentLocation").one()
String envioRecibido = dteEv.contentLocation
idS = (int) (System.currentTimeMillis() / 1000L)
X509Certificate cert
PrivateKey key

EnvioDTEDocument envio = null
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
    ec.logger.error("Envio recibido: Estructura XML incorrecta: " + resl.getMessage())
    errorEsquema = resl.getMessage()
    envioEsquemaOK = false
} else {
    ec.logger.warn("Envio recibido: Estructura XML OK")
}
// Revisar
resl = envio.verifySignature()
if( !resl.isOk()) {
    ec.logger.error("Envio recibido: firma XML incorrecta")
} else {
    ec.logger.debug("Envio recibido: firma XML OK")
}
boolean envioRutOK = true

String rutContribuyente = rutResponde

if(!rutContribuyente.equals(envio.getEnvioDTE().getSetDTE().getCaratula().getRutReceptor())) {
    ec.logger.error("Error: carátula de envioDTE recibido dice que rut de receptor es: " + envio.getEnvioDTE().getSetDTE().getCaratula().getRutReceptor() + " el cual es distinto al de nuestra empresa: " + rutContribuyente)
    envioRutOK = false
}
//Certificado cert = new Certificado()
//CertificadoLlave certLlave = cert.getCertificado(certS, passS)
// leo certificado y llave privada del archivo pkcs12
KeyStore ks = KeyStore.getInstance("PKCS12")
//ks.load(new FileInputStream(certS), passS.toCharArray())
ks.load(certData.getBinaryStream(), passS.toCharArray())
String alias = ks.aliases().nextElement()
cert = (X509Certificate) ks.getCertificate(alias)
String rutCertificado = Utilities.getRutFromCertificate(cert)
ec.logger.warn("Usando certificado ${alias} con Rut ${rutCertificado}")

key = (PrivateKey) ks.getKey(alias, passS.toCharArray())

//ArrayList&lt;RecepcionDTE&gt; arrRecepcionDTE = new ArrayList&lt;RecepcionDTE&gt;()
ArrayList<ResultadoDTE> resultados = new ArrayList<ResultadoDTE>()
//RecepcionEnvio rre = RecepcionEnvio.Factory.newInstance()

/*File f = new File(envioRecibido)
rre.setNmbEnvio(f.getName())
rre.xsetFchRecep(FechaHoraType.Factory.newValue(Utilities.fechaHoraFormat.format(new Date())))
rre.setCodEnvio(idS)
rre.setEnvioDTEID(envio.getEnvioDTE().getSetDTE().getID())
rre.setEstadoRecepEnv(0)
rre.setRecepEnvGlosa("Envio Recibido Conforme");*/

fchRecep = FechaHoraType.Factory.newValue(Utilities.fechaHoraFormat.format(new Date())).toString()
estadoRecepEnvEnumId = 0
int nroDetalles = 0

if (envioFirmaOK && envioEsquemaOK && envioRutOK ) {
    X509Certificate x509 = XMLUtil.getCertificate(envio.getEnvioDTE().getSignature())
    ec.logger.warn("Firmado por: " + x509.getSubjectX500Principal().getName())

    for (DTEDefType dte : envio.getEnvioDTE().getSetDTE().getDTEArray()) {

        x509 = XMLUtil.getCertificate(dte.getSignature())
        ec.logger.warn("DTE ID " + dte.getDocumento().getID() + " Firmado por: " + x509.getSubjectX500Principal().getName())
        ec.logger.warn("Por almacenar en " + dirS)
        String nombreDTE = dirS + "dte-"+dte.getDocumento().getEncabezado().getEmisor().getRUTEmisor()+"-"+dte.getDocumento().getEncabezado().getIdDoc().getFolio()+".xml"
        rutEmisor = dte.getDocumento().getEncabezado().getEmisor().getRUTEmisor()


        boolean firmaOKDTE = true
        if(!resl.isOk()) {
            ec.logger.warn("Validando DTE ID " + dte.getDocumento().getID() + " : Firma XML Incorrecta: " + resl.getMessage())
            firmaOKDTE = false
        } else {
            ec.logger.warn("Validando DTE ID " + dte.getDocumento().getID() + " : Firma XML OK")
        }
        boolean rutDTEOK = true

        if(!rutContribuyente.equals(dte.getDocumento().getEncabezado().getReceptor().getRUTRecep())) {
            ec.logger.warn("Error, DTE id: " + dte.getDocumento().getID() + " folio: " + dte.getDocumento().getEncabezado().getIdDoc().getFolio() + " tipo: " + dte.getDocumento().getEncabezado().getIdDoc().getTipoDTE().toString() +
                    "contiene RUT de receptor [" + dte.getDocumento().getEncabezado().getReceptor().getRUTRecep() + "] que no corresponde a nuestra empresa [" + rutContribuyente + "]")
                    rutDTEOK = false
        }
        nroDetalles++
        ResultadoDTE resDTE = ResultadoDTE.Factory.newInstance()
        resDTE.setFolio(dte.getDocumento().getEncabezado().getIdDoc().getFolio())
        resDTE.setTipoDTE(dte.getDocumento().getEncabezado().getIdDoc().getTipoDTE())
        resDTE.setFchEmis(dte.getDocumento().getEncabezado().getIdDoc().getFchEmis())
        resDTE.setRUTEmisor(dte.getDocumento().getEncabezado().getEmisor().getRUTEmisor())
        resDTE.setRUTRecep(dte.getDocumento().getEncabezado().getReceptor().getRUTRecep())
        resDTE.setMntTotal(dte.getDocumento().getEncabezado().getTotales().getMntTotal())
        /*
        0 - Envío recibido conforme
        1 - Rechazado, error de schema
        2 - Rechazado, error de firma
        3 - Rechazado, RUT receptor no corresponde
        90 - Rechazado, archivo repetido
        91 - Rechazado, archivo ilegible
        99 - Rechazado, otros
        */

        // se asocia el ID del envio recepcionado en nuestra BD
        resDTE.setCodEnvio(new Long(idS))

        if( firmaOKDTE && rutDTEOK) {
            estadoDTE = "DOK"
            //rDTE.setEstadoRecepDTE(0)
            //rDTE.setRecepDTEGlosa("DTE Recibido")
            resDTE.setEstadoDTE(0)
            resDTE.setEstadoDTEGlosa("Envio recibido conforme")
        } else {
            if( !firmaOKDTE) {
                resDTE.setEstadoDTE(new Integer(2))
                resDTE.setEstadoDTEGlosa("DTE rechazado - Error de Firma")
                estadoRecepEnvEnumId = 2
                ec.logger.warn("DTE Rechazado - Error de Firma")
            } else if(!rutDTEK) {
                resDTE.setEstadoDTE(new Integer(2))
                resDTE.setEstadoDTEGlosa("DTE rechazado - Error en RUT Receptor")
                ec.logger.warn("DTE rechazado - Error en RUT Receptor")
            }
        }
        resultados.add(resDTE)
        //arrRecepcionDTE.add(rDTE)
    }
} else {
    ec.logger.warn("Envio no cumple con la firma o con esquema XML")
    // Revisar si es problema de esquema o de firma
    if (!envioEsquemaOK) {
        rre.setEstadoRecepEnv(1)
        estadoRecepEnvEnumId = 1
        rre.setRecepEnvGlosa("Envio Rechazado - Error de schema: " + errorEsquema)
    } else if (!envioFirmaOK) {
        rre.setEstadoRecepEnv(2)
        estadoRecepEnvEnumId = 1
        rre.setRecepEnvGlosa("Envio Rechazado - Error de Firma: " + errorFirma)
    } else if (!envioRutOK) {
        rre.setEstadoRecepEnv(3)
        estadoRecepEnvEnumId = 1
        rre.setRecepEnvGlosa("Envio Rechazado - RUT receptor no corresponde")
    }
    ec.logger.warn("Glosa respuesta envío: " + rre.getRecepEnvGlosa())
}
Caratula caratula = Caratula.Factory.newInstance()
Resultado resultado = Resultado.Factory.newInstance()

/*if (arrRecepcionDTE.size > 0) {
RecepcionDTE[] resultadoDTE = new RecepcionDTE[arrRecepcionDTE.size()]
for(int i = 0; i &lt; arrRecepcionDTE.size(); i++)
resultadoDTE[i] = arrRecepcionDTE.get(i)
rre.setRecepcionDTEArray(resultadoDTE)
caratula.setNroDetalles(arrRecepcionDTE.size())
} else {*/
if (resultados.size() > 0) {
    ResultadoDTE[] resultadoDTE = new ResultadoDTE[resultados.size()]
    for (int i = 0; i < resultados.size(); i++)
    resultadoDTE[i] = resultados.get(i)
    resultado.setResultadoDTEArray(resultadoDTE)
} else {
    caratula.setNroDetalles(1)
}
//}
caratula.setRutResponde(rutContribuyente)
caratula.setRutRecibe(rutEmisor)
caratula.setIdRespuesta(new Long(idS))
caratula.setVersion(new BigDecimal("1.0"))
// Datos en plantilla?
caratula.setNmbContacto(nmbContacto)
caratula.setMailContacto(mailContacto)
caratula.setFonoContacto(fonoContacto)
caratula.setNroDetalles(nroDetalles)

resultado.setCaratula(caratula)
resultado.setID("RESP-" + idS)

/*if(!rre.isNil()) {
ec.logger.warn("Se responde aceptacion")
RecepcionEnvio[] reArray = new RecepcionEnvio[1]
reArray[0] = rre
resultado.setRecepcionEnvioArray(reArray)
}*/
RespuestaDTE respDTE = RespuestaDTE.Factory.newInstance()
respDTE.setResultado(resultado)
respDTE.setVersion(new BigDecimal("1.0"))

RespuestaDTEDocument respuesta = RespuestaDTEDocument.Factory.newInstance()
respuesta.setRespuestaDTE(respDTE)

HashMap<String, String> namespaces = new HashMap<String, String>()
namespaces.put("http://www.sii.cl/SiiDte","")

XmlOptions opts = new XmlOptions()
opts.setSavePrettyPrint()
opts.setSavePrettyPrintIndent(0)
opts.setSaveSuggestedPrefixes(namespaces)
opts.setCharacterEncoding("ISO-8859-1")

XmlCursor cursor = respuesta.newCursor()
if(cursor.toFirstChild()) {
    cursor.setAttributeText(new QName("http://www.w3.org/2001/XMLSchema-instance", "schemaLocation"), "http://www.sii.cl/SiiDte RespuestaEnvioDTE_v10.xsd")
}
try {
    respuesta = RespuestaDTEDocument.Factory.parse(respuesta.newInputStream(opts))
} catch (Exception e) {
    ec.logger.warn("Error al obtener respuesta con formato antes de firmar", e)
}
try {
    ec.logger.warn("Respuesta antes de firmar: " + new String(respuesta.getBytes()))
    //respuesta.sign(certLlave.getPkey(), certLlave.getX509())
    respuesta.sign(key, cert)
    //respDTE.sign(key, cert)
} catch (Exception e) {
    ec.logger.error("Error al firmar respuesta" + e.printStackTrace())
    return
}

opts = new XmlOptions()
opts.setCharacterEncoding("ISO-8859-1")
opts.setSaveImplicitNamespaces(namespaces)

opts = new XmlOptions()
opts.setCharacterEncoding("ISO-8859-1")
ByteArrayOutputStream out2 = new ByteArrayOutputStream()
ec.logger.warn("Escribiendo " + resultS + "APROBCOM-" + idS + ".xml")
respuesta.save(new File(resultS + "APROBCOM-" + idS + ".xml"), opts)
respuesta.save(out2, opts)
ec.logger.warn("Escribiendo archivo temporal para attachment" + resultS + "APROBCOM.xml")
respuesta.save(new File(resultS + "APROBCOM.xml"), opts)
ByteArrayOutputStream outTemp = new ByteArrayOutputStream()
respuesta.save(outTemp, opts)

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
fiscalTaxDocumentEv = ec.entity.find("mchile.dte.FiscalTaxDocument").condition("fiscalTaxDocumentId", fiscalTaxDocumentId).one()
folioAceptacion = fiscalTaxDocumentEv.fiscalTaxDocumentNumber
createMap = [fiscalTaxDocumentId:fiscalTaxDocumentId, rutResponde:rutResponde, rutRecibe:rutRecibe, nmbContacto:nmbContacto,
             fonoContacto:fonoContacto, mailContacto:mailContacto]
context.putAll(ec.service.sync().name("create#mchile.dte.AceptacionDte").parameters(createMap).call())

aceptacionEv = ec.entity.find("mchile.dte.AceptacionDte").condition("aceptacionDteId", aceptacionDteId).forUpdate(true).one()

//aceptacionEv.nmbEnvio" from="fiscalTaxDocumentEv.razonSocial"/-->
aceptacionEv.fchRecep = fchRecep
aceptacionEv.codEnvio = idS
aceptacionEv.rutEmisor = rutEmisor
aceptacionEv.envioDteId = "RESP-${idS}"
aceptacionEv.rutEmisor = rutEmisor
aceptacionEv.rutReceptor = rutResponde
aceptacionEv.estadoRecepEnvEnumId = estadoRecepEnvEnumId
aceptacionEv.nroDetalles = 1 as Integer
aceptacionEv.xml = "${resultS}RESP-${idS}.xml"
aceptacionEv.update()
bodyParameters = [fiscalTaxDocumentId:folioAceptacion, nmbContacto:nmbContacto, mailContacto:mailContacto, fonoContacto:fonoContacto]
ec.service.async().name("org.moqui.impl.EmailServices.send#EmailTemplate").parameters([fiscalTaxDocumentId:fiscalTaxDocumentId,
                    emailTypeEnumId:emailTypeEnumId, toAddresses:emailAceptacion, emailTemplateId:"Aceptacion", bodyParameters:bodyParameters]).call()
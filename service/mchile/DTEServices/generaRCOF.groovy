import org.moqui.context.ExecutionContext

import java.text.SimpleDateFormat
import cl.sii.siiDte.FechaHoraType
import cl.sii.siiDte.FechaType
import cl.nic.dte.util.Utilities

import org.apache.xmlbeans.XmlOptions
import java.security.cert.X509Certificate
import java.security.KeyStore
import java.security.PrivateKey

import cl.helpcom.dte.util.FirmaRcof
import cl.helpcom.recursos.LectorFichero

import cl.sii.siiDte.consumofolios.ConsumoFoliosDocument
import cl.sii.siiDte.consumofolios.ConsumoFoliosDocument.ConsumoFolios
import cl.sii.siiDte.consumofolios.ConsumoFoliosDocument.ConsumoFolios.DocumentoConsumoFolios
import cl.sii.siiDte.consumofolios.ConsumoFoliosDocument.ConsumoFolios.DocumentoConsumoFolios.Caratula
import cl.sii.siiDte.consumofolios.ConsumoFoliosDocument.ConsumoFolios.DocumentoConsumoFolios.Resumen
import cl.sii.siiDte.consumofolios.ConsumoFoliosDocument.ConsumoFolios.DocumentoConsumoFolios.Resumen.RangoUtilizados
import cl.sii.siiDte.consumofolios.ConsumoFoliosDocument.ConsumoFolios.DocumentoConsumoFolios.Resumen.RangoAnulados

ExecutionContext ec

if (fechaInicio > fechaFin) {
    ec.message.addError("Fecha fin debe ser mayor o igual a fecha inicio")
    return
}

partyIdentificationList = ec.entity.find("mantle.party.PartyIdentification").condition([partyId:activeOrgId, partyIdTypeEnumId:"PtidNationalTaxId"]).one()
if (!partyIdentificationList) {
    ec.message.addError("Organización no tiene RUT definido")
    return
}
rutEmisor = partyIdentificationList.first.idValue

// Validación rut
// ec.service.sync().name("mchile.GeneralServices.verify#Rut").parameters([rut:rutReceptor]).call()

// Recuperacion de parametros de la organizacion
context.putAll(ec.service.sync().name("mchile.DTEServices.load#DTEConfig").parameters([partyId:activeOrgId]).call())
plantillaS = templateRcof
resultadoFirmado = pathResults

// Buscar lista de DTE 39 que se hayan emitido/anulado
mapBoleta = ec.service.sync().name("mchile.DTEServices.get#ResumenRcof").parameters([fechaInicio:fechaInicio, fechaFin:fechaFin, fiscalTaxDocumentTypeEnumId:'Ftdt-39', activeOrgId:activeOrgId]).call()
// Buscar lista de DTE 41
mapBoletaExenta = ec.service.sync().name("mchile.DTEServices.get#ResumenRcof").parameters([fechaInicio:fechaInicio, fechaFin:fechaFin, fiscalTaxDocumentTypeEnumId:'Ftdt-41', activeOrgId:activeOrgId]).call()
// Buscar lista de DTE 61 que anulen boletas
mapNotaCredito = ec.service.sync().name("mchile.DTEServices.get#ResumenRcof").parameters([fechaInicio:fechaInicio, fechaFin:fechaFin, fiscalTaxDocumentTypeEnumId:'Ftdt-61', activeOrgId:activeOrgId]).call()

Date dNow = new Date()
SimpleDateFormat ft = new SimpleDateFormat("yyMMddhhmmssMs")
String datetime = ft.format(dNow)
idS = idS + datetime

LectorFichero lectorFichero = new LectorFichero()

int tipoFactura
int frmPago = 1
int listSize = 0

tipoFactura = Integer.valueOf(39)
if(formaPago != null)
    frmPago = Integer.valueOf(formaPago)

// Debo meter el namespace porque SII no lo genera
HashMap<String, String> namespaces = new HashMap<String, String>()
//namespaces.put("http://www.sii.cl/SiiDte","")

namespaces.put("", "http://www.sii.cl/SiiDte")
namespaces.put("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
namespaces.put("xsi:schemaLocation","http://www.sii.cl/SiiDte ConsumoFolio_v10.xsd")
XmlOptions opts = new XmlOptions()
opts.setLoadSubstituteNamespaces(namespaces)

ec.logger.warn("Generando RCOF\n")
cl.sii.siiDte.consumofolios.ConsumoFoliosDocument consumoFoliosDocument = ConsumoFoliosDocument.Factory.newInstance()
consumoFoliosDocument = ConsumoFoliosDocument.Factory.parse(new FileInputStream(plantillaS))
ConsumoFolios cf = consumoFoliosDocument.getConsumoFolios()
// Datos de carátula
DocumentoConsumoFolios dcf = cf.addNewDocumentoConsumoFolios()
dcf.setID("RCOF"+idS)
//DocumentoConsumoFolios dcf = consumoFoliosDocument.addNewDocumentoConsumoFolios()

// leo certificado y llave privada del archivo pkcs12
KeyStore ks = KeyStore.getInstance("PKCS12")
ks.load(new ByteArrayInputStream(certData.decodeBase64()), passCert.toCharArray())
String alias = ks.aliases().nextElement()
X509Certificate cert = (X509Certificate) ks.getCertificate(alias)
String rutCertificado = Utilities.getRutFromCertificate(cert)
ec.logger.warn("Usando certificado ${alias} con Rut ${rutCertificado}")

PrivateKey pKey = (PrivateKey) ks.getKey(alias, passCert.toCharArray())

//dcf.setVersion("1.0")

Caratula caratula = dcf.addNewCaratula()

//caratula.setVersion(BigDecimal.valueOf(1.0))
caratula.setRutEmisor(rutEmisor)
caratula.setRutEnvia(rutEnvia)
//caratula.setRutReceptor(rutReceptor)


Date dateFchResol = new SimpleDateFormat("yyyy-MM-dd").parse(fchResol)

//iddoc.xsetFchEmis(FechaType.Factory.newValue(Utilities.fechaFormat.format(new Date())))
caratula.xsetFchResol(FechaType.Factory.newValue(Utilities.fechaFormat.format(dateFchResol)))

caratula.setVersion(new BigDecimal("1.0"))

caratula.setNroResol(0)
// Fecha de Inicio del Resumen
caratula.setFchInicio(calendar)
caratula.xsetFchInicio(FechaType.Factory.newValue(Utilities.fechaFormat.format(dateFchResol)))

// Fecha Final del Resumen
caratula.setFchFinal(calendar)
caratula.xsetFchFinal(FechaType.Factory.newValue(Utilities.fechaFormat.format(dateFchResol)))

caratula.setCorrelativo(1)
// Secuencia de envío
caratula.setSecEnvio(1)

caratula.setNroResol(Integer.valueOf(nroResol))
now = FechaHoraType.Factory.newValue(Utilities.fechaHoraFormat.format(new Date()))

caratula.xsetTmstFirmaEnv(now)
Resumen[] resumenArray = new Resumen[3]
// *****************************************************************
// Resumen de Boleta (39)
// *****************************************************************

Resumen resumen = Resumen.Factory.newInstance()

resumen.setTipoDocumento(39)
resumen.setMntNeto(mapBoleta.totalMontoNeto)
resumen.setMntIva(mapBoleta.totalMontoIva)
resumen.setTasaIVA(19)
resumen.setMntExento(mapBoleta.totalMontoExento)
resumen.setMntTotal(mapBoleta.totalMontoTotal)
resumen.setFoliosEmitidos(mapBoleta.cantDocEmitidos)
resumen.setFoliosAnulados(mapBoleta.cantFoliosAnulados)
resumen.setFoliosUtilizados(mapBoleta.cantDocUtilizados)

// Rango de folios emitidos

RangoUtilizados[] rangoUtilizadosArray

if(mapBoleta.rangosFoliosUtilizados != null)
    rangoUtilizadosArray = new RangoUtilizados[mapBoleta.rangosFoliosUtilizados.size()]
int i = 0

mapBoleta.rangosFoliosUtilizados.each { rangoField ->
    RangoUtilizados rangoUtilizados = RangoUtilizados.Factory.newInstance()
    rangoUtilizados.setInicial(rangoField[0])
    rangoUtilizados.setFinal(rangoField[1])
    rangoUtilizadosArray[i] = rangoUtilizados
    i++

}
resumen.setRangoUtilizadosArray(rangoUtilizadosArray)

// Rango de folios anulados
RangoAnulados[] rangoAnuladosArray
if(mapBoleta.rangosFoliosAnulados != null)
    rangoAnuladosArray = new RangoAnulados[mapBoleta.rangosFoliosAnulados.size()]
i = 0

mapBoleta.rangosFoliosAnulados.each { rangoField ->
    RangoAnulados rangoAnulados = RangoAnulados.Factory.newInstance()
    rangoAnulados.setInicial(rangoField[0])
    rangoAnulados.setFinal(rangoField[1])
    rangoAnuladosArray[i] = rangoAnulados
    i++
}
resumen.setRangoAnuladosArray(rangoAnuladosArray)

resumenArray[0] = resumen; // Resumen de Boleta

// *****************************************************************
// Resumen de Boleta Exenta Electrónica (41)
// *****************************************************************
resumen = Resumen.Factory.newInstance()

resumen.setTipoDocumento(41)
resumen.setMntNeto(0)
resumen.setMntIva(0)
//resumen.setTasaIVA(19); // No usado
resumen.setMntExento(mapBoletaExenta.totalMontoTotal)
resumen.setMntTotal(mapBoletaExenta.totalMontoTotal)
resumen.setFoliosEmitidos(mapBoletaExenta.cantDocEmitidos)
resumen.setFoliosAnulados(mapBoletaExenta.cantFoliosAnulados)
resumen.setFoliosUtilizados(mapBoletaExenta.cantDocUtilizados)

// Rango de folios emitidos
if(mapBoletaExenta.rangosFoliosUtilizados != null)
    rangoUtilizadosArray = new RangoUtilizados[mapBoletaExenta.rangosFoliosUtilizados.size()]
i = 0

mapBoletaExenta.rangosFoliosUtilizados.each { rangoField ->
    RangoUtilizados rangoUtilizados = RangoUtilizados.Factory.newInstance()
    rangoUtilizados.setInicial(rangoField[0])
    rangoUtilizados.setFinal(rangoField[1])
    rangoUtilizadosArray[i] = rangoUtilizados
    i++
}
resumen.setRangoUtilizadosArray(rangoUtilizadosArray)

// Rango de folios anulados
//RangoAnulados[] rangoAnuladosArray
if(mapBoletaExenta.rangosFoliosAnulados != null)
    rangoAnuladosArray = new RangoAnulados[mapBoletaExenta.rangosFoliosAnulados.size()]
i = 0

mapBoletaExenta.rangosFoliosAnulados.each { rangoField ->
    RangoAnulados rangoAnulados = RangoAnulados.Factory.newInstance()
    rangoAnulados.setInicial(rangoField[0])
    rangoAnulados.setFinal(rangoField[1])
    rangoAnuladosArray[i] = rangoAnulados
    i++
}
resumen.setRangoAnuladosArray(rangoAnuladosArray)

resumenArray[1] = resumen // Resumen de Boleta Exenta Electrónica

// *****************************************************************
// Resumen de Notas de Crédito (61)
// *****************************************************************
resumen = Resumen.Factory.newInstance()

resumen.setTipoDocumento(61)
resumen.setMntNeto(mapNotaCredito.totalMontoNeto)
resumen.setMntIva(mapNotaCredito.totalMontoIva)
resumen.setTasaIVA(19)
resumen.setMntExento(mapNotaCredito.totalMontoExento)
resumen.setMntTotal(mapNotaCredito.totalMontoTotal)
resumen.setFoliosEmitidos(mapNotaCredito.cantDocEmitidos)
resumen.setFoliosAnulados(mapNotaCredito.cantFoliosAnulados)
resumen.setFoliosUtilizados(mapNotaCredito.cantDocUtilizados)

// Rango de folios emitidos

if(mapNotaCredito.rangosFoliosUtilizados != null)
    rangoUtilizadosArray = new RangoUtilizados[mapNotaCredito.rangosFoliosUtilizados.size()]
i = 0

mapNotaCredito.rangosFoliosUtilizados.each { rangoField ->
    RangoUtilizados rangoUtilizados = RangoUtilizados.Factory.newInstance()
    rangoUtilizados.setInicial(rangoField[0])
    rangoUtilizados.setFinal(rangoField[1])
    rangoUtilizadosArray[i] = rangoUtilizados
    i++
}
resumen.setRangoUtilizadosArray(rangoUtilizadosArray)

// Rango de folios anulados
if(mapNotaCredito.rangosFoliosAnulados != null)
    rangoAnuladosArray = new RangoAnulados[mapNotaCredito.rangosFoliosAnulados.size()]
i = 0

mapNotaCredito.rangosFoliosAnulados.each { rangoField ->
    RangoAnulados rangoAnulados = RangoAnulados.Factory.newInstance()
    rangoAnulados.setInicial(rangoField[0])
    rangoAnulados.setFinal(rangoField[1])
    rangoAnuladosArray[i] = rangoAnulados
    i++
}
resumen.setRangoAnuladosArray(rangoAnuladosArray)

resumenArray[2] = resumen // Resumen de Notas de Crédito
dcf.setResumenArray(resumenArray)


// antes de firmar le doy formato a los datos
//opts = new XmlOptions()
//opts.setSaveImplicitNamespaces(namespaces)
//opts.setLoadSubstituteNamespaces(namespaces)
//opts.setLoadAdditionalNamespaces(namespaces)
opts.setSavePrettyPrint()
opts.setSavePrettyPrintIndent(4)

// releo el doc para que se reflejen los cambios de formato
ec.logger.warn("XML:" + consumoFoliosDocument)

consumoFoliosDocument = ConsumoFoliosDocument.Factory.parse(consumoFoliosDocument.newInputStream(opts), opts)


// Guardo
//opts = new XmlOptions()
//opts.setCharacterEncoding("ISO-8859-1")
//opts.setSaveImplicitNamespaces(namespaces)

String uri = ""
FechaHoraType now = FechaHoraType.Factory.newValue(Utilities.fechaHoraFormat.format(new Date()))

uri = cf.getDocumentoConsumoFolios().getID()
//cf.getDocumentoConsumoFolios().xsetTmstFirma(now)


ec.logger.warn("URI: " + uri)

ByteArrayOutputStream out = new ByteArrayOutputStream()
consumoFoliosDocument.save(new File(pathResults + "RCOF-" + uri + "-sinfirma.xml"), opts)
consumoFoliosDocument.save(out, opts)

ec.logger.warn("XML2:" + consumoFoliosDocument)

FirmaRcof firmaLibro = new FirmaRcof()

SimpleDateFormat formatterFechaEmision = new SimpleDateFormat("yyyy-MM-dd")
Date dateFechaEmision = new Date()
fechaEmision = formatterFechaEmision.format(dateFechaEmision)

outPDF=lectorFichero.crearFicheroMMDDFlex(resultadoFirmado, fchResol)
outPDF+="/RCOF-firmado-"+uri+".xml"

String mensaje=firmaLibro.firmarRcof(certS, passCert, pathResults + "RCOF-" + uri + "-sinfirma.xml",outPDF,10,"ENVIADO","qq","pp","xmlasdas","ESPECIAL")

return

// Registro de DTE en base de datos y generación de PDF
//fiscalTaxDocumentTypeEnumId = "Ftdt-${tipoFacturaS}"
xml = "${pathResults}BOL${tipoFactura}-${folio}.xml"
pdf = "${pathPdf}BOL${tipoFactura}-${folio}.pdf"
context.putAll(ec.service.sync().name("mchile.DTEServices.genera#PDF").parameters([pdf:pdf, dte:xml, issuerPartyId:activeOrgId, boleta:true]).call())

// Creación de registro en FiscalTaxDocument
dteEv = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, fiscalTaxDocumentNumber:folio]).forUpdate(true).one()
dteEv.issuerPartyId = activeOrgId
if (rutReceptor != '66666666-6') {
    dteEv.receiverPartyId = receiverPartyId
    dteEv.receiverPartyIdTypeEnumId = PtidNationalTaxId
}
dteEv.fiscalTaxDocumentStatusEnumId = "Ftdt-Issued"
dteEv.fiscalTaxDocumentSentStatusEnumId = "Ftdt-NotSent"
dteEv.invoiceId = invoiceId

Date date = new Date()
Timestamp ts = new Timestamp(date.getTime())
dteEv.date = ts
dteEv.update()
createMap = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:xml, contentDate:ts]
context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
createMap = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:pdf, contentDate:ts]
context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMap).call())
fiscalTaxDocumentId = dteEv.fiscalTaxDocumentId

import org.moqui.context.ExecutionContext
import org.moqui.impl.context.reference.BaseResourceReference

import java.text.SimpleDateFormat
import cl.sii.siiDte.FechaHoraType
import cl.sii.siiDte.FechaType
import cl.moit.dte.MoquiDTEUtils
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
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;


ExecutionContext ec = context.ec

// Recuperacion de parametros de la organizacion -->
ec.context.putAll(ec.service.sync().name("mchile.sii.DTEServices.load#DTEConfig").parameter("partyId", issuerPartyId).call())


String facturaXmlString = //"<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n" +
        //"<EnvioBOLETA xmlns=\"http://www.sii.cl/SiiDte\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" version=\"1.0\" xsi:schemaLocation=\"http://www.sii.cl/SiiDte EnvioBOLETA_v11.xsd\">\n" +
        //"<SetDTE ID=\"ENVBO319133781212437\">\n" +
        //"<Caratula version=\"1.0\">\n" +
        //"<RutEmisor>76222457-7</RutEmisor>\n" +
        //"<RutEnvia>12857517-0</RutEnvia>\n" +
        //"<RutReceptor>60803000-K</RutReceptor>\n" +
        //"<FchResol>2014-10-20</FchResol>\n" +
        //"<NroResol>80</NroResol>\n" +
        //"<TmstFirmaEnv>2021-12-29T23:15:04</TmstFirmaEnv>\n" +
        //"<SubTotDTE>\n" +
        //"<TpoDTE>39</TpoDTE>\n" +
        //"<NroDTE>1</NroDTE>\n" +
        //"</SubTotDTE>\n" +
        //"</Caratula>\n" +
        "<DTE version=\"1.0\">\n" +
        "<Documento ID=\"BO211229111504124\">\n" +
        "<Encabezado>\n" +
        "<IdDoc>\n" +
        "<TipoDTE>39</TipoDTE>\n" +
        "<Folio>2</Folio>\n" +
        "<FchEmis>2021-12-29</FchEmis>\n" +
        "<IndServicio>3</IndServicio>\n" +
        "</IdDoc>\n" +
        "<Emisor>\n" +
        "<RUTEmisor>76222457-7</RUTEmisor>\n" +
        "<RznSocEmisor>Kombuchacha SpA</RznSocEmisor>\n" +
        "<GiroEmisor>ELABORACION DE OTROS PRODUCTOS ALIMENTICIOS NO CLASIFICADOS EN OTRA PARTE</GiroEmisor>\n" +
        "<CdgSIISucur>223344</CdgSIISucur>\n" +
        "<DirOrigen>RUTA FREIRE VILLARRICA, KM 7</DirOrigen>\n" +
        "<CmnaOrigen>Freire</CmnaOrigen>\n" +
        "<CiudadOrigen>Freire</CiudadOrigen>\n" +
        "</Emisor>\n" +
        "<Receptor>\n" +
        "<RUTRecep>76432167-7</RUTRecep>\n" +
        "<RznSocRecep>Producciones Palacios - AV LAS FLORES</RznSocRecep>\n" +
        "<Contacto>Producciones Palacios - AV LAS FLORES</Contacto>\n" +
        "<DirRecep>AV LAS FLORES 20217 LOCAL 23 CIUDAD DE LOS VALLES -</DirRecep>\n" +
        "<CmnaRecep>Pudahuel</CmnaRecep>\n" +
        "<CiudadRecep>SANTIAGO</CiudadRecep>\n" +
        "</Receptor>\n" +
        "<Totales>\n" +
        "<MntTotal>1678</MntTotal>\n" +
        "</Totales>\n" +
        "</Encabezado>\n" +
        "<Detalle>\n" +
        "<NroLinDet>1</NroLinDet>\n" +
        "<NmbItem>B50x01TVE - Botella 500 ml Te Verde 100% Org&#225;nico</NmbItem>\n" +
        "<QtyItem>1</QtyItem>\n" +
        "<PrcItem>2100</PrcItem>\n" +
        "<MontoItem>2100</MontoItem>\n" +
        "</Detalle>\n" +
        "<Detalle>\n" +
        "<NroLinDet>2</NroLinDet>\n" +
        "<NmbItem>Descuento Canal</NmbItem>\n" +
        "<QtyItem>1</QtyItem>\n" +
        "<PrcItem>-422</PrcItem>\n" +
        "<MontoItem>-422</MontoItem>\n" +
        "</Detalle>\n" +
        "<TED version=\"1.0\">\n" +
        "<DD>\n" +
        "<RE>76222457-7</RE>\n" +
        "<TD>39</TD>\n" +
        "<F>2</F>\n" +
        "<FE>2021-12-29</FE>\n" +
        "<RR>76432167-7</RR>\n" +
        "<RSR>Producciones Palacios - AV LAS FLORES</RSR>\n" +
        "<MNT>1678</MNT>\n" +
        "<IT1>B50x01TVE - Botella 500 ml Te Verde 100%</IT1>\n" +
        "<CAF version=\"1.0\">\n" +
        "<DA>\n" +
        "<RE>76222457-7</RE>\n" +
        "<RS>INVERSIONES CJ LIMITADA</RS>\n" +
        "<TD>39</TD>\n" +
        "<RNG>\n" +
        "<D>1</D>\n" +
        "<H>100</H>\n" +
        "</RNG>\n" +
        "<FA>2018-12-05</FA>\n" +
        "<RSAPK>\n" +
        "<M>pqaXJKXG1xnGFtF46xF/4MeqZ3VUAfJ2S0kFBh8RXny0Y2+jnqdEbekFFymfG2IfX2pq7gKLU9ox1TLb+w3YuQ==</M>\n" +
        "<E>Aw==</E>\n" +
        "</RSAPK>\n" +
        "<IDK>100</IDK>\n" +
        "</DA>\n" +
        "<FRMA algoritmo=\"SHA1withRSA\">p7Wv4k+LI/X1HEdGlBLetmKcP8urL6tNMmL+a0ow8PNhc6fr1Fmnn/fiNoJVRVY6BEH/CiqV+vvB0nBBd2a1ew==</FRMA>\n" +
        "</CAF>\n" +
        "<TSTED>2021-12-29T23:15:04</TSTED>\n" +
        "</DD>\n" +
        "<FRMT algoritmo=\"SHA1withRSA\">G3Ok08XFnVyp04rheUsrQNng7itRWsuAFcU3mPOLm3BbAJWuFB20k6UGgQFCtRApKof52j1+nd/1uK+cdMg3dQ==</FRMT>\n" +
        "</TED>\n" +
        "<TmstFirma>2021-12-29T23:15:04</TmstFirma>\n" +
        "</Documento>\n" +
        "</DTE>\n"
        //"</SetDTE>\n" +
        //"</EnvioBOLETA>"

uri = "#BO211229111504124"
Document doc2 = MoquiDTEUtils.parseDocument(facturaXmlString.getBytes())
byte[] facturaXml = MoquiDTEUtils.sign(doc2, uri, pkey, certificate, uri, "Documento")

String envioBoleta = new String(facturaXml, StandardCharsets.ISO_8859_1);

envioBoleta = "<EnvioBOLETA xmlns=\"http://www.sii.cl/SiiDte\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" version=\"1.0\" xsi:schemaLocation=\"http://www.sii.cl/SiiDte EnvioBOLETA_v11.xsd\">\n" +
        "<SetDTE ID=\"ENVBO319133781212437\">\n" +
        "<Caratula version=\"1.0\">\n" +
        "<RutEmisor>76222457-7</RutEmisor>\n" +
        "<RutEnvia>12857517-0</RutEnvia>\n" +
        "<RutReceptor>60803000-K</RutReceptor>\n" +
        "<FchResol>2014-10-20</FchResol>\n" +
        "<NroResol>80</NroResol>\n" +
        "<TmstFirmaEnv>2021-12-29T23:15:04</TmstFirmaEnv>\n" +
        "<SubTotDTE>\n" +
        "<TpoDTE>39</TpoDTE>\n" +
        "<NroDTE>1</NroDTE>\n" +
        "</SubTotDTE>\n" +
        "</Caratula>\n" +
        envioBoleta +
        "</SetDTE>\n" +
        "</EnvioBOLETA>"

envioBoleta = envioBoleta.replace("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n", "")

ec.logger.warn("SALIDA: " + envioBoleta)

uri = "#ENVBO319133781212437"
Document docEnvio = MoquiDTEUtils.parseDocument(envioBoleta.getBytes())
facturaXml = MoquiDTEUtils.sign(docEnvio, uri, pkey, certificate, uri, "SetDTE")

//if (Signer.verify(doc2, "SetDTE")) {
    FileOutputStream outputStream = new FileOutputStream("/home/cherrera/moit/cowork/moqui-framework/runtime/component/moquichile/DTE/TEMP/" + "BOL" + tipoFactura + "-" + folio + ".xml")
    outputStream.write(facturaXml);
    outputStream.close();
//byte[] salidaBoleta = BoletaSigner.signBoleta(doc2, key, cert)
//salidaBoleta = BoletaSigner.signEnvioBoleta(doc2, key, cert, uriBoleta)
//byte[] salidaBoleta = Signer.signEmbededBoleta(doc2, uriBoleta, key, cert)
//doc2 = MoquiDTEUtils.parseDocument(salidaBoleta)
// Firma de EnvioBOLETA
//byte[] facturaXml = MoquiDTEUtils.signSignature(doc2, uri, key, cert, uri, "SetDTE")
doc2 = MoquiDTEUtils.parseDocument(facturaXml)

//if (MoquiDTEUtils.verify(doc2, "//sii:SetDTE")) {
//    ec.logger.warn("Factura " + path + " folio " + folio + " generada OK")
//}
//} else {
//    ec.logger.warn("Error al generar boleta folio "+folio)
//}

// Registro de DTE en base de datos y generación de PDF -->

fiscalTaxDocumentTypeEnumId = "Ftdt-${tipoFactura}"
ec.context.putAll(ec.service.sync().name("mchile.sii.DTEServices.genera#PDF").parameters([dte:facturaXml, issuerPartyId:issuerPartyId, boleta:true, continua:continua]).call())

// Creación de registro en FiscalTaxDocument
dteEv = ec.entity.find("mchile.dte.FiscalTaxDocument").condition([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, fiscalTaxDocumentNumber:folio]).forUpdate(true).one()
dteEv.issuerPartyId = issuerPartyId

if (rutReceptor != "66666666-6") {
    dteEv.receiverPartyId = receiverPartyId
    dteEv.receiverPartyIdTypeEnumId = "PtidNationalTaxId"
    dteEv.receiverPartyIdValue = rutReceptor
}
dteEv.statusId = "Ftd-Issued"
dteEv.sentAuthStatusId = "Ftd-NotSentAuth"
dteEv.sentRecStatusId = "Ftd-NotSentRec"
dteEv.invoiceId = invoiceId
dteEv.date = ec.user.nowTimestamp
dteEv.update()
//dteField.update()
// Creación de registro en FiscalTaxDocumentAttributes
// montoNeto
// montoIVARecuperable
// montoExento
// Amount

updateMap = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, emailEmisor:emailEmisor, amount:amount,
             montoNeto:montoNeto, tasaImpuesto:19, fechaEmision:fechaEmision,
             montoExento:montoExento, montoIVARecuperable:montoIVARecuperable]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes").parameters(updateMap).call())

// Creacion de registros en FiscalTaxDocumentContent
xmlContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/DTE-${tipoFactura}-${folio}.xml"
pdfContentLocation = "dbresource://moit/erp/dte/${rutEmisor}/DTE-${tipoFactura}-${folio}.pdf"
createMapBase = [fiscalTaxDocumentId:dteEv.fiscalTaxDocumentId, contentDte:ts]
ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Xml', contentLocation:xmlContentLocation]).call())
ec.resource.getLocationReference(xmlContentLocation).putBytes(facturaXml)

//ec.context.putAll(ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentContent").parameters(createMapBase+[fiscalTaxDocumentContentTypeEnumId:'Ftdct-Pdf', contentLocation:pdfContentLocation]).call())
//ec.resource.getLocationReference(pdfContentLocation).putBytes(pdfBytes)
fiscalTaxDocumentId = dteEv.fiscalTaxDocumentId
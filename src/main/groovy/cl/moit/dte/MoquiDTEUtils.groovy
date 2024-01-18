package cl.moit.dte

import org.apache.fop.apps.io.ResourceResolverFactory
import org.apache.xmlgraphics.io.Resource
import org.apache.xmlgraphics.io.ResourceResolver
import org.moqui.BaseArtifactException
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.resource.ResourceReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.NodeList
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import org.xml.sax.SAXException
import sun.security.x509.X509CertImpl

import javax.xml.XMLConstants
import javax.xml.crypto.*
import javax.xml.crypto.dsig.*
import javax.xml.crypto.dsig.dom.DOMSignContext
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.keyinfo.KeyInfo
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory
import javax.xml.crypto.dsig.keyinfo.KeyValue
import javax.xml.crypto.dsig.keyinfo.X509Data
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec
import javax.xml.crypto.dsig.spec.TransformParameterSpec
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.*
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory
import javax.xml.validation.Validator
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathExpression
import javax.xml.xpath.XPathFactory
import java.security.*
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.regex.Matcher
import java.util.regex.Pattern

class MoquiDTEUtils {

    protected final static Logger logger = LoggerFactory.getLogger(MoquiDTEUtils.class)

    public static HashMap<String, Object> prepareDetails(ExecutionContext ec, List<HashMap> detailList, String detailType, String issuerPartyId) throws BaseArtifactException {
        return prepareDetails(ec, detailList, detailType, null, issuerPartyId)
    }

    public static HashMap<String, Object> prepareDetails(ExecutionContext ec, List<HashMap> detailList, String detailType, Integer codRef, String issuerPartyId) throws BaseArtifactException {
        List detalleList = []
        // Text Correction DTEs have no detail list
        if (detailList.size() == 0 && codRef == 2) {
            if (detailType == "InvoiceItem" || detailType == "DebitoItem") {
                Map detailMap = [:]
                detailMap.numeroLinea = 1
                detailMap.nombreItem = "Corrige Dato Receptor"
                detailMap.priceItem = 0.000001
                detailMap.montoItem = 0
                detalleList.add(detailMap)
                return [detalleList:detalleList, totalNeto:0, totalExento:0, numberExentos:0, numberAfectos:0]
            }
        }

        int i = 0
        Long totalNeto = null
        Long totalExento = null
        int numberAfectos = 0
        int numberExentos = 0
        Long totalDescuentos = 0

        detailList.each { detailEntryObj ->
            HashMap detailEntry = (detailEntryObj instanceof EntityValue) ? detailEntryObj.getMap() : detailEntryObj
            String nombreItem = detailEntry.description
            if (detailType.equals("OrderItem"))
                nombreItem = detailEntry.itemDescription
            if (nombreItem == null) {
                EntityValue productEv = ec.entity.find("mantle.product.Product").condition("productId", detailEntry.productId).one()
                nombreItem = productEv? productEv.productName : ''
            }
            if (nombreItem.length() > 80) {
                ec.logger.info("Truncando descripción a 80 caracteres")
                nombreItem = nombreItem.substring(0, 80)
            }
            BigDecimal quantity = detailEntry.quantity
            BigDecimal montoDescuento = detailEntry.montoDescuento
            BigDecimal porcentajeDescuento = detailEntry.porcentajeDescuento
            BigDecimal ajusteDecimal = detailEntry.ajusteDecimal
            if (montoDescuento)
                totalDescuentos += montoDescuento
            String uom = null
            if (!detailType.equals("ShipmentItem")) {
                if (detailEntry.quantityUomId.equals('TF_hr'))
                    uom = "Hora"
                else if (detailEntry.quantityUomId.equals('TF_mon'))
                    uom = "Mes"
                else if (detailEntry.quantityUomId.equals('WT_kg'))
                    uom = "Kgs"
                else if (detailEntry.quantityUomId.equals('WT_g'))
                    uom = "grms"
                else if (detailEntry.quantityUomId.equals('LEN_m'))
                    uom = "Mtr"
                else if (detailEntry.quantityUomId.equals('VLIQ_L'))
                    uom = "Ltr"
            }
            Boolean itemAfecto = null
            if (detailEntry.productId) {
                Map<String, Object> afectoOutMap = ec.service.sync().name("mchile.sii.dte.DteInternalServices.check#Afecto").parameters([issuerPartyId:issuerPartyId, productId:detailEntry.productId]).call()
                itemAfecto = afectoOutMap.afecto
            } else {
                String productDefaultExento = ec.service.sync().name("mantle.party.PartyServices.get#PartySettingValue").parameters([partyId:issuerPartyId, partySettingTypeId:'moit.dte.ProductDefaultIsExento']).call().settingValue
                itemAfecto = productDefaultExento != 'true'
            }

            BigDecimal priceItem
            BigDecimal totalItem = 0
            if (detailType == "ShipmentItem") {
                //ec.logger.info("handling price for productId ${detailEntry.productId}")
                BigDecimal quantityHandled = 0
                List<EntityValue> sisList = ec.entity.find("mantle.shipment.ShipmentItemSource").condition([shipmentId:detailEntry.shipmentId, productId: detailEntry.productId]).list()
                if (sisList) {
                    sisList.each { sis ->
                        EntityValue item
                        if (sis.invoiceId) {
                            item = ec.entity.find("mantle.account.invoice.InvoiceItem").condition([invoiceId: sis.invoiceId, invoiceItemSeqId: sis.invoiceItemSeqId]).one()
                        } else if (sis.orderId) {
                            item = ec.entity.find("mantle.account.order.OrderItem").condition([orderId: sis.orderId, orderItemSeqId: sis.orderItemSeqId]).one()
                        }
                        if (item) {
                            totalItem = totalItem + sis.quantity * item.amount
                        } else {
                            EntityValue shipment = ec.entity.find("mantle.shipment.Shipment").condition([shipmentId:sis.shipmentId]).one()
                            item = ec.entity.find("mantle.product.ProductPrice").condition([productId: detailEntry.productId, productStoreId:shipment.productStoreId]).one()
                            totalItem = totalItem + sis.quantity * item.price
                        }
                        quantityHandled = quantityHandled + sis.quantity
                        if (item.quantityUomId.equals('TF_hr'))
                            uom = "Hora"
                        else if (item.quantityUomId.equals('TF_mon'))
                            uom = "Mes"
                        else if (item.quantityUomId.equals('WT_kg'))
                            uom = "Kgs"
                    }
                }
                if (quantityHandled < quantity) {
                    ec.logger.info("pending ${quantity - quantityHandled} out of ${quantity}")
                    EntityValue shipment = ec.entity.find("mantle.shipment.Shipment").condition("shipmentId", shipmentId).one()
                    Timestamp shipmentDate = shipment.estimatedShipDate ?: shipment.shipAfterDate ?: shipment.entryDate ?: ec.user.nowTimestamp
                    Map<String, Object> priceMap = ec.service.sync().name("mantle.product.PriceServices.get#ProductPrice").parameters([productId: detailEntry.productId, quantity: quantity, validDate: shipmentDate]).call()
                    totalItem = totalItem + (quantity - quantityHandled) * priceMap.price
                }
                priceItem = totalItem / quantity as BigDecimal
                totalItem = totalItem - (montoDescuento?:0)
                totalItem = totalItem.setScale(0, java.math.RoundingMode.HALF_UP) as Long
            } else if (detailType == "DebitoItem") {
                if(codRef == 2) {
                    quantity = null
                    priceItem = null
                    nombreItem = "Corrige Dato Receptor"
                    totalItem = 0
                } else {
                    priceItem = detailEntry.amount
                    totalItem = (quantity?:0) * (priceItem?:0) - (montoDescuento?:0)
                }
            } else if (detailType == "ReturnItem") {
                if (codRef == 2) {
                    quantity = null
                    priceItem = null
                    nombreItem = "Corrige Dato Receptor"
                    totalItem = 0
                } else {
                    priceItem = detailEntry.returnPrice
                }
            } else if (detailType == "OrderItem") {
                priceItem = detailEntry.unitAmount
                totalItem = (quantity?:0) * (priceItem?:0) - (montoDescuento?:0) + (ajusteDecimal?:0)
            } else if (detailType == "InvoiceItem") {
                priceItem = detailEntry.amount
                totalItem = (quantity?:0) * (priceItem?:0) - (montoDescuento?:0) + (ajusteDecimal?:0)
            } else {
                priceItem = detailEntry.amount
                totalItem = (quantity?:0) * (priceItem?:0) - (montoDescuento?:0) + (ajusteDecimal?:0)
            }

            if (itemAfecto)
                numberAfectos++
            else
                numberExentos++

            // Agrego detalles
            Map detailMap = [:]
            detalleList.add(detailMap)
            detailMap.numeroLinea = i+1
            if (detailEntry.productId)
                detailMap.codigoItem = [[tipoCodigo:'INT1', valorCodigo:detailEntry.productId]]
            detailMap.nombreItem = nombreItem
            if (detailEntry.detailedDescription)
                detailMap.descripcionItem = detailEntry.detailedDescription
            if (quantity != null)
                detailMap.quantity = quantity
            if(uom != null)
                detailMap.uom = uom
            if (priceItem != null && (detailType != "ShipmentItem" || Math.round(priceItem) > 0))
                detailMap.priceItem = priceItem
            detailMap.montoItem = totalItem
            if(detailType == "ShipmentItem" || itemAfecto) {
                totalNeto = (totalNeto ?: 0) + totalItem
            } else {
                totalExento = (totalExento ?: 0) + totalItem
                detailMap.indicadorExento = 1
            }
            if (detailType == "ReturnItem" && codRef == 2) {
                singleDet = [detailMap]
                return [detalleList:singleDet, totalNeto:totalNeto, totalExento:totalExento, numberExentos:numberExentos, numberAfectos:numberAfectos, totalDescuentos:totalDescuentos]
            }
            if (detailType == "DebitoItem" && codRef == 2) {
                singleDet = [detailMap]
                return [detailArray:singleDet, totalNeto:totalNeto, totalExento:totalExento, numberExentos:numberExentos, numberAfectos:numberAfectos, totalDescuentos:totalDescuentos]
            }
            if (porcentajeDescuento)
                detailMap.porcentajeDescuento = porcentajeDescuento
            if (montoDescuento)
                detailMap.montoDescuento = montoDescuento
            i = i + 1
        }
        return [detalleList:detalleList, totalNeto:totalNeto, totalExento:totalExento, numberExentos:numberExentos, numberAfectos:numberAfectos, totalDescuentos:totalDescuentos]
    }

    public static Map<String, Object> prepareReferences(ExecutionContext ec, List<HashMap> referenciaList, String rutReceptor, Long tipoFactura) {
        int listSize = referenciaList.size()
        List referenciaListOut = []
        String anulaBoleta = null
        String folioAnulaBoleta = null
        boolean dteExenta = false

        int i = 0
        referenciaList.each { referenciaEntry ->
            String folioRef = referenciaEntry.folio
            String codRef = null
            if (referenciaEntry.codigoReferenciaEnumId != null)
                codRef = ec.entity.find("moqui.basic.Enumeration").condition("enumId", referenciaEntry.codigoReferenciaEnumId).one().enumCode as String
                //codRef = ec.entity.find("moqui.basic.Enumeration").condition("enumId", referenciaEntry.codigoReferenciaEnumId).one().enumCode as Integer
            Timestamp fechaRef = referenciaEntry.fecha instanceof java.sql.Date? new Timestamp(referenciaEntry.fecha.time) : referenciaEntry.fecha

            // Agrego referencias
            Map referenciaMap = [:]
            referenciaListOut.add(referenciaMap)
            referenciaMap.numeroLinea = i+1
            referenciaMap.fecha = fechaRef
            if(referenciaEntry.razonReferencia != null)
                referenciaMap.razon = referenciaEntry.razonReferencia
            referenciaMap.folio = folioRef
            if (referenciaEntry.fiscalTaxDocumentTypeEnumId.equals('Ftdt-0')) { // Used for Set de Pruebas SII
                referenciaMap.tipoDocumento = 'SET'
            } else {
                Map<String, Object> codeOut = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#SiiCode").parameters([fiscalTaxDocumentTypeEnumId:referenciaEntry.fiscalTaxDocumentTypeEnumId]).call()
                Integer tpoDocRef = codeOut.siiCode
                referenciaMap.tipoDocumento = tpoDocRef as String
                if (rutReceptor)
                    referenciaMap.rutOtro = rutReceptor
                if(tipoFactura == 61 && (referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-39") || referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-41")) && codRef?.equals(1) ) {
                    // Nota de crédito hace referencia a Boletas Electrónicas
                    anulaBoleta = 'true'
                    folioAnulaBoleta = referenciaEntry.folio.toString()
                }
            }
            if(codRef != null)
                referenciaMap.codigo = codRef
            // TODO: ¿Por qué se asume que una Nota de Crédito es exenta al estar generando una nota de débito?
            if(referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-34") || (tipoFactura == 56 && referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-61")) ) {
                dteExenta = true
            }

            i = i + 1
        }
        return [referenciaList:referenciaListOut, anulaBoleta:anulaBoleta, folioAnulaBoleta:folioAnulaBoleta, dteExenta:dteExenta]
    }

    public static boolean verifySignature(org.w3c.dom.Node doc, String xPathExpression, String dateXPathExpression) throws NoSuchAlgorithmException, InvalidKeyException,
            IOException, ParserConfigurationException, SAXException {
        XPath xpath = XPathFactory.newInstance().newXPath()
        xpath.setNamespaceContext(new DefaultNamespaceContext().addNamespace("sii", "http://www.sii.cl/SiiDte"))
        XPathExpression expression
        Date signatureDate = null
        List<String> verifiedIdList = new LinkedList<String>()
        List<String> failedIdList = new LinkedList<String>()
        expression = xpath.compile(xPathExpression)
        NodeList nodes = (NodeList) expression.evaluate(doc, XPathConstants.NODESET)
        if (nodes == null || nodes.length < 1)
            throw new RuntimeException("Could not find any node using XPath expression ${xPathExpression}")

        nodes.each { org.w3c.dom.Node node ->
            if (dateXPathExpression != null) {
                expression = xpath.compile(dateXPathExpression)
                String signatureTimestamp = expression.evaluate(node, XPathConstants.STRING)
                SimpleDateFormat dateFormat = new SimpleDateFormat(signatureTimestamp.size() == 10 ? "yyyy-MM-dd": "yyyy-MM-dd'T'HH:mm:ss")
                signatureDate = dateFormat.parse(signatureTimestamp)
                logger.debug("got signatureTimestamp: ${signatureTimestamp}")
            }
            String signedElementId = ((Element)node).getAttribute("ID")
            ((Element)node).setIdAttributeNS(null, "ID", true)
            NodeList signatureNodeList = ((Element)node.getParentNode()).getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature");
            if (signatureNodeList == null || signatureNodeList.length < 1)
                throw (new SAXException("No se encuentra firma para verificacion"));
            org.w3c.dom.Element signatureElem = (org.w3c.dom.Element) signatureNodeList.item(signatureNodeList.length - 1);
            if (signatureElem == null) {
                throw (new SAXException("No se encuentra firma para verificacion"));
            }
            DOMValidateContext valContext = new DOMValidateContext(new X509KeySelector(), signatureElem)

            // Unmarshal the XMLSignature.
            XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
            XMLSignature signature = fac.unmarshalXMLSignature(valContext);

            X509CertImpl certificate = null;
            signature.keyInfo.content.each {
                if (it instanceof X509Data) {
                    X509Data xd = (X509Data) it;
                    Object[] entries = xd.getContent().toArray();
                    X509CRL crl = null;
                    for (int i = 0; ( i < entries.length); i++) {
                        if (entries[i] instanceof X509CRL) {
                            crl = (X509CRL) entries[i];
                        }
                        if (entries[i] instanceof X509CertImpl) {
                            certificate = (X509CertImpl) entries[i];
                            try {
                                certificate.checkValidity(signatureDate ?: new Date());
                            } catch (CertificateExpiredException expiredEx) {
                                logger.error("CERTIFICATE EXPIRED!");
                                return false;
                            } catch (CertificateNotYetValidException notYetValidEx) {
                                logger.error("CERTIFICATE NOT VALID YET!");
                                return false;
                            }
                        }
                    }
                }
            }

            if (certificate == null) {
                logger.error("No Certificate found")
                failedIdList.add(signedElementId)
                return
            }

            // Validate the XMLSignature.
            try {
                if (!signature.validate(valContext)) {
                    failedIdList.add(signedElementId)
                    return
                }
            } catch (XMLSignatureException e) {
                failedIdList.add(signedElementId)
                return
            }

            verifiedIdList.add(signedElementId)
        }
        int verifyCount = verifiedIdList.size()
        int failCount = failedIdList.size()
        int totalCount = verifyCount + failCount
        if (failCount == 0)
            logger.info("Checked ${verifyCount} signature${verifyCount > 1? 's': ''} successfully, ID${verifyCount > 1? ('s ' + verifiedIdList): ' ' + verifiedIdList.get(0)}")
        else
            logger.info("Checked ${totalCount} signature${totalCount > 1? 's': ''} , ${verifyCount} successful, ${failCount} failed: ID${failCount > 1? ('s ' + failedIdList): ' ' + failedIdList.get(0)}")
        return (failCount == 0);
    }

    public static class DefaultNamespaceContext implements NamespaceContext {
        private HashMap<String,String> keyMap = new HashMap<String,String>()

        public DefaultNamespaceContext addNamespace(String prefix, String uri) {
            keyMap.put(prefix, uri)
            return this
        }

        @Override
        String getNamespaceURI(String prefix) {
            return keyMap.get(prefix)
        }

        @Override
        String getPrefix(String namespaceURI) {
            keyMap.keySet().each { key, value ->
                if (value == namespaceURI)
                    return key
            }
            return null
        }

        @Override
        Iterator<String> getPrefixes(String namespaceURI) {
            LinkedList<String> prefixList = new LinkedList<String>()
            keyMap.keySet().each { key, value ->
                if (value == namespaceURI)
                    prefixList.add(key)
            }
            return prefixList
        }
    }

    public static class X509KeySelector extends KeySelector {
        public KeySelectorResult select(KeyInfo keyInfo,
                                        KeySelector.Purpose purpose,
                                        AlgorithmMethod method,
                                        XMLCryptoContext context)
                throws KeySelectorException {
            Iterator ki = keyInfo.getContent().iterator();
            while (ki.hasNext()) {
                XMLStructure info = (XMLStructure) ki.next();
                if (!(info instanceof X509Data))
                    continue;
                X509Data x509Data = (X509Data) info;
                Iterator xi = x509Data.getContent().iterator();
                while (xi.hasNext()) {
                    Object o = xi.next();
                    if (!(o instanceof X509Certificate))
                        continue;
                    final PublicKey key = ((X509Certificate)o).getPublicKey();
                    // Make sure the algorithm is compatible
                    // with the method.
                    if (algEquals(method.getAlgorithm(), key.getAlgorithm())) {
                        return new KeySelectorResult() {
                            public Key getKey() { return key; }
                        };
                    }
                }
            }
            throw new KeySelectorException("No key found!");
        }

        static boolean algEquals(String algURI, String algName) {
            if ((algName.equalsIgnoreCase("DSA") &&
                    algURI.equalsIgnoreCase(SignatureMethod.DSA_SHA1)) ||
                    (algName.equalsIgnoreCase("RSA") &&
                            algURI.equalsIgnoreCase(SignatureMethod.RSA_SHA1))) {
                return true;
            } else {
                return false;
            }
        }
    }

    public static byte[] sign(Document doc, String baseUri, PrivateKey pKey, X509Certificate cert, String uri, String tagName) {
        return sign(doc, baseUri, pKey, cert, uri, tagName, true)
    }
    public static byte[] sign(Document doc, String baseUri, PrivateKey pKey, X509Certificate cert, String uri, String tagName, boolean addPreamble) {
        try {
            NodeList nodes = doc.getElementsByTagName(tagName);
            if (tagName != "")
                ((Element) nodes.item(0)).setIdAttributeNS(null, "ID", true);

            String alg = pKey.getAlgorithm();
            if (!alg.equals(cert.getPublicKey().getAlgorithm()))
                throw (new Exception("ERROR DE ALGORITMO"));
            org.w3c.dom.Element root = doc.getDocumentElement();
            DOMSignContext dsc = new DOMSignContext(pKey, root);
            XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
            javax.xml.crypto.dsig.Reference ref = fac.newReference(uri, fac.newDigestMethod(DigestMethod.SHA1, null), List.of(fac.newTransform (Transform.ENVELOPED, (TransformParameterSpec) null)), null, null);
            String signatureAlgorithm = null
            if (alg.equals("RSA")) {
                if (!((RSAPrivateKey) pKey).getModulus().equals(((RSAPublicKey) cert.getPublicKey()).getModulus()))
                    throw (new Exception("ERROR DE FIRMA RSA"));
                signatureAlgorithm = "http://www.w3.org/2000/09/xmldsig#rsa-sha1"
            } else if (alg.equals("DSA")) {
                signatureAlgorithm = "http://www.w3.org/2000/09/xmldsig#dsa-sha1"
            }
            SignedInfo si = fac.newSignedInfo(fac.newCanonicalizationMethod ("http://www.w3.org/TR/2001/REC-xml-c14n-20010315", (C14NMethodParameterSpec) null), fac.newSignatureMethod(signatureAlgorithm, null), List.of(ref));
            KeyInfoFactory kif = fac.getKeyInfoFactory();
            KeyValue kv = kif.newKeyValue(cert.getPublicKey());
            X509Data certData = kif.newX509Data(Collections.singletonList ( cert ));
            KeyInfo ki = kif.newKeyInfo(List.of(kv, certData));
            XMLSignature signature = fac.newXMLSignature(si, ki);
            signature.sign(dsc);

            return getRawXML(doc, addPreamble);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static byte[] getRawXML(org.w3c.dom.Node doc, boolean addPreamble) {
        return getRawXML(doc, "ISO-8859-1", addPreamble)
    }
    public static String getStringXML(org.w3c.dom.Node doc, boolean addPreamble) {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (addPreamble)
                baos.write("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n".getBytes("UTF-8"));
            transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.transform(new DOMSource(doc), new StreamResult(baos));

            return new String(baos.toByteArray(), "UTF-8");
        } catch (TransformerConfigurationException e) {
            e.printStackTrace();
        } catch (TransformerException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null
    }
    public static byte[] getRawXML(org.w3c.dom.Node doc, String encoding) {
        return getRawXml(doc, encoding, true)
    }
    public static byte[] getRawXML(org.w3c.dom.Node doc, String encoding, boolean addPreamble) {
        try {
            String outAux = getStringXML(doc, addPreamble)
            return outAux?.getBytes(encoding)
        } catch (TransformerConfigurationException e) {
            e.printStackTrace();
        } catch (TransformerException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null
    }

    public static org.w3c.dom.Document parseDocument(byte[] entrada) {
        return parseDocument(new ByteArrayInputStream(entrada));
    }

    public static org.w3c.dom.Document parseDocument(InputStream inputStream) {
        return parseDocument(inputStream, false)
    }
    public static org.w3c.dom.Document parseDocument(InputStream inputStream, boolean validating) {
        String JAXP_SCHEMA_LANGUAGE = "http://java.sun.com/xml/jaxp/properties/schemaLanguage"
        String W3C_XML_SCHEMA = "http://www.w3.org/2001/XMLSchema"
        String JAXP_SCHEMA_SOURCE = "http://java.sun.com/xml/jaxp/properties/schemaSource"
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setIgnoringElementContentWhitespace(false);
        factory.setValidating(validating)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        if (validating) {
            factory.setAttribute(JAXP_SCHEMA_LANGUAGE, W3C_XML_SCHEMA);
        }

        DocumentBuilder builder;
        String errorMessage = ""
        try {
            builder = factory.newDocumentBuilder();
            Document doc = builder.parse(inputStream);
            return doc;
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
            errorMessage = e.toString()
        } catch (SAXException e) {
            e.printStackTrace();
            errorMessage = e.toString()
        } catch (IOException e) {
            e.printStackTrace();
            errorMessage = e.toString()
        }
        throw new RuntimeException("Error al parsear: ${errorMessage}");
    }

    public static groovy.util.Node dom2GroovyNode(org.w3c.dom.Node node) {
        String xml = getStringXML(node, true)
        return dom2GroovyNode(xml)
    }
    public static groovy.util.Node dom2GroovyNode(String xml) {
        boolean validating = false
        boolean namespaceAware = false
        return new groovy.xml.XmlParser(validating, namespaceAware).parseText(xml)
    }

    public static String firmaTimbre(String datosTed, String privateKeyData) {
        //privateKeyData = privateKeyData.replace("-----BEGIN RSA PRIVATE KEY-----\n", "").replace("\n-----END RSA PRIVATE KEY-----", "")
        try {
            PrivateKey key = cl.moit.dte.KeyHelper.parseKey(privateKeyData)
            java.security.Signature sig = Signature.getInstance("SHA1WithRSA");
            sig.initSign(key)
            sig.update(datosTed.getBytes("ISO-8859-1"))
            return Base64.mimeEncoder.encodeToString(sig.sign())
        } catch (Exception ex) {
            throw new RuntimeException("Unable to recover private key..." + ex.getMessage());
        }
    }

    public static String verificaTimbre(String timbreXml, String firma, String publicKeyData) {
        publicKeyData = publicKeyData.replace("-----BEGIN PUBLIC KEY-----\n", "").replace("\n-----END PUBLIC KEY-----", "")
        KeyFactory keyFactory = KeyFactory.getInstance("RSA")
        byte[] keyBytes = Base64.mimeDecoder.decode(publicKeyData)
        RSAPublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes))
        List rsaPublicKey = [modulus: publicKey.getModulus(), exponent: publicKey.getPublicExponent()]
    }

    /**
     * Obtiene el RUT desde un certificado digital. Busca en la extension
     * 2.5.29.17
     *
     * @param x509
     * @return
     */
    public static String getRutFromCertificate(X509Certificate x509) {
        if (x509 == null)
            return null
        String rut = null
        Pattern p = Pattern.compile("[\\d]{6,8}-[\\dkK]")
        Matcher m = p.matcher(new String(x509.getExtensionValue("2.5.29.17")))
        if (m.find())
            rut = m.group()
        return rut
    }

    public static void validateDocumentSii(ExecutionContext ec, byte[] docBytes, String schemaUri) throws SAXException {
        String schemaPrefix = "http://www.sii.cl/SiiDte "
        String schemaPrefixLocation = "component://MoquiChile/DTE/schemas/"
        ResourceReference rr = null
        if (schemaUri.startsWith(schemaPrefix)) {
            if (schemaUri.length() <= schemaPrefix.length())
                throw new RuntimeException("Unknown schema: ${schemaUri}")
            String fileName = schemaUri.substring(schemaPrefix.length())
            String schemaLocation = "${schemaPrefixLocation}${fileName}"
            rr = ec.resource.getLocationReference(schemaLocation)
            if (rr == null || !rr.exists)
                throw new RuntimeException("Unknown schema: ${schemaUri} (attempted to find it at ${schemaLocation})")

        } else
            throw new RuntimeException("Unknown schema: ${schemaUri}")
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        ResourceResolver resolver = new LocalResourceResolver(ec, schemaPrefix, schemaPrefixLocation, ResourceResolverFactory.createDefaultResourceResolver())
        factory.setResourceResolver(resolver)
        StreamSource schemaSource = new StreamSource(rr.openStream())
        Schema schema = factory.newSchema(schemaSource)
        Validator validator = schema.newValidator()
        validator.setResourceResolver(resolver)
        Source docSource = new StreamSource(new ByteArrayInputStream(docBytes))
        validator.validate(docSource)
    }

    static class LocalResourceResolver implements ResourceResolver, LSResourceResolver{
        protected ExecutionContext ec
        protected String prefix
        protected String prefixLocation
        protected ResourceResolver defaultResolver

        LocalResourceResolver(ExecutionContext ec, String prefix, String prefixLocation, ResourceResolver defaultResolver) {
            this.ec = ec
            this.prefix = prefix
            this.prefixLocation = prefixLocation
            this.defaultResolver = defaultResolver
        }

        OutputStream getOutputStream(URI uri) throws IOException {
            String uriString = uri.toString()
            logger.info("Resolving for outputStream ${uriString}")
            return defaultResolver?.getOutputStream(uri)
        }

        Resource getResource(URI uri) throws IOException {
            String uriString = uri.toString()
            logger.info("Resolving ${uriString}")
            if (uriString.startsWith(prefix) && uriString.length() > prefix.length()) {
                String fileName = uriString.substring(prefix.length())
                String schemaLocation = "${prefixLocation}${fileName}"
                ResourceReference rr = ec.resource.getLocationReference(schemaLocation)
                if (rr?.exists)
                    return new Resource(rr.openStream())
            }
            return defaultResolver?.getResource(uri)
        }

        @Override
        LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
            ResourceReference rr = null
            if (type != "http://www.w3.org/2001/XMLSchema")
                return null
            if (namespaceURI == "http://www.sii.cl/SiiDte" || namespaceURI == "http://www.w3.org/2000/09/xmldsig#") {
                rr = ec.resource.getLocationReference(prefixLocation + systemId)
            }

            if (rr?.exists)
                return new LocalLSInput(rr.openStream())
            return null
        }
    }

    static class LocalLSInput implements LSInput {

        InputStream is

        LocalLSInput(InputStream is) {
            this.is = is
        }

        @Override
        Reader getCharacterStream() {
            return null
        }

        @Override
        void setCharacterStream(Reader characterStream) {
        }

        @Override
        InputStream getByteStream() {
            return is
        }

        @Override
        void setByteStream(InputStream byteStream) {
        }

        @Override
        String getStringData() {
            return null
        }

        @Override
        void setStringData(String stringData) {

        }

        @Override
        String getSystemId() {
            return null
        }

        @Override
        void setSystemId(String systemId) {

        }

        @Override
        String getPublicId() {
            return null
        }

        @Override
        void setPublicId(String publicId) {

        }

        @Override
        String getBaseURI() {
            return null
        }

        @Override
        void setBaseURI(String baseURI) {

        }

        @Override
        String getEncoding() {
            return null
        }

        @Override
        void setEncoding(String encoding) {

        }

        @Override
        boolean getCertifiedText() {
            return false
        }

        @Override
        void setCertifiedText(boolean certifiedText) {

        }
    }

    public static String getNamespace(org.w3c.dom.Node node) {
        Element root = null
        if (node instanceof Document) {
            root = node.getDocumentElement()
        } else
            root = node.getOwnerDocument().getDocumentElement()

        HashMap<String, String> namespaces = getAllAttributes(root)
        String prefix = getPrefix(node.getNodeName())

        if (prefix == null)
            return namespaces.get("xmlns")

        return namespaces.get("xmlns:" + prefix)
    }

    public static HashMap<String, String> getAllAttributes(org.w3c.dom.Node node) {
        HashMap<String, String> attributes = new HashMap<String, String>()
        NamedNodeMap attr = node.getAttributes()
        for (int j = 0; j < attr.getLength(); j++) {
            attributes.put(attr.item(j).getNodeName(), attr.item(j).getNodeValue())
        }
        return attributes
    }

    public static String getPrefix(String value) {
        try {
            return value.substring(0, value.indexOf(":"))
        } catch (Exception e) {
        }
        return null
    }

}

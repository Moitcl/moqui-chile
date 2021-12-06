package cl.moit.dte

import cl.nic.dte.util.Utilities
import cl.sii.siiDte.DTEDefType.Documento.Detalle
import cl.sii.siiDte.DTEDefType.Documento.Referencia
import cl.sii.siiDte.FechaType
import org.apache.xml.security.exceptions.XMLSecurityException
import org.moqui.BaseArtifactException
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Document
import org.w3c.dom.NodeList
import org.xml.sax.SAXException

import javax.xml.crypto.AlgorithmMethod
import javax.xml.crypto.KeySelector
import javax.xml.crypto.KeySelectorException
import javax.xml.crypto.KeySelectorResult
import javax.xml.crypto.XMLCryptoContext
import javax.xml.crypto.XMLStructure
import javax.xml.crypto.dsig.SignatureMethod
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.keyinfo.KeyInfo
import javax.xml.crypto.dsig.keyinfo.X509Data
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.ParserConfigurationException
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathFactory
import javax.xml.xpath.XPathExpression
import javax.xml.xpath.XPathConstants
import java.security.InvalidKeyException
import java.security.Key
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.sql.Timestamp
import javax.xml.crypto.dsig.XMLSignature

class MoquiDTEUtils {

    protected final static Logger logger = LoggerFactory.getLogger(MoquiDTEUtils.class)

    public static HashMap<String, Object> prepareDetails(ExecutionContext ec, List<HashMap> detailList, String detailType) throws BaseArtifactException {
        return prepareDetails(ec, detailList, detailType, null)
    }

    public static HashMap<String, Object> prepareDetails(ExecutionContext ec, List<HashMap> detailList, String detailType, BigInteger codRef) throws BaseArtifactException {
        int i = 0
        int listSize = detailList.size()
        Detalle[] det = new Detalle[listSize]
        Long totalNeto = null
        Long totalExento = null
        int numberAfectos = 0
        int numberExentos = 0

        detailList.each { detailEntryObj ->
            HashMap detailEntry = (detailEntryObj instanceof EntityValue) ? detailEntryObj.getMap() : detailEntryObj
            String nombreItem = detailEntry.description
            if (nombreItem == null) {
                EntityValue productEv = ec.entity.find("mantle.product.Product").condition("productId", detailEntry.productId).one()
                nombreItem = productEv? productEv.productName : ''
            }
            Integer qtyItem = detailEntry.quantity
            String uom = null
            BigDecimal pctDiscount
            if (!detailType in ["ShipmentItem"]) {
                if (detailEntry.quantityUomId.equals('TF_hr'))
                    uom = "Hora"
                else if (detailEntry.quantityUomId.equals('TF_mon'))
                    uom = "Mes"
                pctDiscount = detailEntry.pctDiscount
            }
            String itemAfecto = "true"
            if (detailEntry.productId) {
                Map<String, Object> afectoOutMap = ec.service.sync().name("mchile.DTEServices.check#Afecto").parameter("productId", detailEntry.productId).call()
                itemAfecto = afectoOutMap.afecto
            }

            Integer priceItem
            BigDecimal totalItem = 0
            if (detailType == "ShipmentItem") {
                ec.logger.info("handling price for productId ${detailEntry.productId}")
                Integer quantityHandled = 0
                List<EntityValue> sisList = ec.entity.find("mantle.shipment.ShipmentItemSource").condition([shipmentId:detailEntry.shipmentId, productId: detailEntry.productId]).list()
                if (sisList) {
                    sisList.each { sis ->
                        ec.logger.info("processing sis ${sis}")
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
                    }
                }
                if (quantityHandled < qtyItem) {
                    ec.logger.info("pending ${qtyItem - quantityHandled} out of ${qtyItem}")
                    EntityValue shipment = ec.entity.find("mantle.shipment.Shipment").condition("shipmentId", shipmentId).one()
                    Timestamp shipmentDate = shipment.estimatedShipDate ?: shipment.shipAfterDate ?: shipment.entryDate ?: ec.user.nowTimestamp
                    Map<String, Object> priceMap = ec.service.sync().name("mantle.product.PriceServices.get#ProductPrice").parameters([productId: detailEntry.productId, quantity: qtyItem, validDate: shipmentDate]).call()
                    totalItem = totalItem + (qtyItem - quantityHandled) * priceMap.price
                }
                priceItem = totalItem / qtyItem as BigDecimal
                totalItem = totalItem.setScale(0, BigDecimal.ROUND_HALF_UP) as Long
            } else if (detailType == "DebitoItem") {
                if(BigDecimal.valueOf(codRef) == 2 || BigDecimal.valueOf(codRef) == 1) {
                    qtyItem = null
                    priceItem = null
                    nombreItem = "ANULA DOCUMENTO DE REFERENCIA"
                    totalItem = 0
                } else
                    priceItem = detailEntry.amount
            } else if (detailType == "ReturnItem" && codRef == 2) {
                qtyItem = null
                priceItem = null
                nombreItem = "CORRIGE GIROS"
                totalItem = 0
            } else if (detailType == "ReturnItem") {
                priceItem = detailEntry.returnPrice
            } else {
                priceItem = detailEntry.amount
                totalItem = (qtyItem?:0) * (priceItem?:0)
            }

            if (itemAfecto == "true")
                numberAfectos++
            else
                numberExentos++

            // Agrego detalles
            det[i] = Detalle.Factory.newInstance()
            det[i].setNroLinDet(i+1)
            if (detailEntry.productId) {
                String codigoInterno = detailEntry.productId
                cl.sii.siiDte.DTEDefType.Documento.Detalle.CdgItem codigo = det[i].addNewCdgItem()
                codigo.setTpoCodigo("Interna")
                codigo.setVlrCodigo(codigoInterno)
            }
            det[i].setNmbItem(nombreItem)
            if (detailEntry.detailedDescription)
                det[i].setDscItem(detailEntry.detailedDescription)
            //det[i].setDscItem(""); // Descripción Item
            if (qtyItem != null)
                det[i].setQtyItem(BigDecimal.valueOf(qtyItem))
            if(uom != null)
                det[i].setUnmdItem(uom)
            if((pctDiscount != null) && (pctDiscount > 0)) {
                ec.logger.warn("Aplicando descuento " + pctDiscount+"% a precio "+ priceItem )
                BigDecimal descuento = totalItem * pctDiscount / 100
                ec.logger.warn("Descuento:" + descuento)
                det[i].setDescuentoPct(pctDiscount)
                det[i].setDescuentoMonto(Math.round(descuento))
                totalItem = totalItem - descuento
            }
            if (priceItem != null && (detailType != "ShipmentItem" || Math.round(priceItem) > 0))
                det[i].setPrcItem(BigDecimal.valueOf(priceItem))
            det[i].setMontoItem(Math.round(totalItem))
            if(detailType == "ShipmentItem" || itemAfecto.equals("true")) {
                totalNeto = (totalNeto ?: 0) + totalItem
            } else {
                totalExento = (totalExento ?: 0) + totalItem
                det[i].setIndExe(1)
            }
            if (detailType == "ReturnItem" && codRef == 2) {
                Detalle[] singleDet = new Detalle[1];
                singleDet[0] = det[i]
                return [detailArray:singleDet, totalNeto:totalNeto, totalExento:totalExento, numberExentos:numberExentos, numberAfectos:numberAfectos]
            }
            if (detailType == "DebitoItem" && codRef == 1) {
                Detalle[] singleDet = new Detalle[1];
                singleDet[0] = det[i]
                return [detailArray:singleDet, totalNeto:totalNeto, totalExento:totalExento, numberExentos:numberExentos, numberAfectos:numberAfectos]
            }
            i = i + 1
        }
        return [detailArray:det, totalNeto:totalNeto, totalExento:totalExento, numberExentos:numberExentos, numberAfectos:numberAfectos]
    }

    public static Map<String, Object> prepareReferences(ExecutionContext ec, List<HashMap> referenciaList, String rutReceptor, Long tipoFactura) {
        int listSize = referenciaList.size()
        Referencia[] ref = new Referencia[listSize]
        String anulaBoleta = null
        String folioAnulaBoleta = null
        boolean dteExenta = false

        int i = 0
        referenciaList.each { referenciaEntry ->
            String folioRef = referenciaEntry.folio
            Integer codRef = ec.entity.find("moqui.basic.Enumeration").condition("enumId", referenciaEntry.referenciaTypeEnumId).one().enumCode as Integer
            Timestamp fechaRef = referenciaEntry.fecha instanceof java.sql.Date? new Timestamp(referenciaEntry.fecha.time) : referenciaEntry.fecha

            // Agrego referencias
            ref[i] = Referencia.Factory.newInstance()
            ref[i].setNroLinRef(i+1)
            ref[i].xsetFchRef(FechaType.Factory.newValue(Utilities.fechaFormat.format(fechaRef)))
            if(referenciaEntry.razonReferencia != null)
                ref[i].setRazonRef(referenciaEntry.razonReferencia)
            ref[i].setFolioRef(folioRef)
            if (referenciaEntry.fiscalTaxDocumentTypeEnumId.equals('Ftdt-0')) { // Used for Set de Pruebas SII
                ref[i].setTpoDocRef('SET')
            } else {
                Map<String, Object> codeOut = ec.service.sync().name("mchile.DTEServices.get#SIICode").parameters([fiscalTaxDocumentTypeEnumId:referenciaEntry.fiscalTaxDocumentTypeEnumId]).call()
                Integer tpoDocRef = codeOut.siiCode
                //ref[i].setTpoDocRef(referenciaEntry.fiscalTaxDocumentTypeEnumId)
                ref[i].setTpoDocRef(tpoDocRef as String)
                if (rutReceptor)
                    ref[i].setRUTOtr(rutReceptor)
                if(tipoFactura == 61 && (referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-39") || referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-41")) && codRef.equals(1) ) {
                    // Nota de crédito hace referencia a Boletas Electrónicas
                    anulaBoleta = 'true'
                    folioAnulaBoleta = referenciaEntry.folio.toString()
                }
            }
            if(codRef != null)
                ref[i].setCodRef(codRef)
            // TODO: ¿Por qué se asume que una Nota de Crédito es exenta al estar generando una nota de débito?
            if(referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-34") || (tipoFactura == 56 && referenciaEntry.fiscalTaxDocumentTypeEnumId.equals("Ftdt-61")) ) {
                dteExenta = true
            }

            i = i + 1
        }
        return [referenceArray:ref, anulaBoleta:anulaBoleta, folioAnulaBoleta:folioAnulaBoleta, dteExenta:dteExenta]
    }

    public static boolean verifySignature(Document doc, String xPathExpression) throws NoSuchAlgorithmException, InvalidKeyException,
            IOException, ParserConfigurationException, SAXException, XMLSecurityException {
        XPath xpath = XPathFactory.newInstance().newXPath()
        xpath.setNamespaceContext(new DefaultNamespaceContext().addNamespace("sii", "http://www.sii.cl/SiiDte"))
        XPathExpression expression = xpath.compile(xPathExpression)
        NodeList nodes = (NodeList) expression.evaluate(doc.getDocumentElement(), XPathConstants.NODESET)
        if (nodes == null || nodes.length < 1)
            throw new RuntimeException("Could not find any node using XPath expression ${xPathExpression}")
        int verificationCount = 0

        nodes.each { org.w3c.dom.Node node ->
            verificationCount++
            ((Element)node).setIdAttributeNS(null, "ID", true)
            NodeList signatureNodeList = ((Element)node.getParentNode()).getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Signature");
            if (signatureNodeList == null || signatureNodeList.length < 1)
                throw (new SAXException("No se encuentra firma para verificacion ${verificationCount}"));
            org.w3c.dom.Element signatureElem = (org.w3c.dom.Element) signatureNodeList.item(signatureNodeList.length - 1);
            if (signatureElem == null) {
                throw (new SAXException("No se encuentra firma para verificacion ${verificationCount}"));
            }
            DOMValidateContext valContext = new DOMValidateContext(new X509KeySelector(), signatureElem)

// Unmarshal the XMLSignature.
            XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");
            XMLSignature signature = fac.unmarshalXMLSignature(valContext);

// Validate the XMLSignature.
            if (!signature.validate(valContext))
                return false
        }
        logger.info("Checked ${verificationCount} signatures successfully")
        return true;
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

}

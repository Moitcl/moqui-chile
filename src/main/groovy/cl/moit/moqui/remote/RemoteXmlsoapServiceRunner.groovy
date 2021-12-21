/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package cl.moit.moqui.remote

import javax.xml.namespace.QName
import javax.xml.soap.Name
import javax.xml.soap.SOAPBody
import javax.xml.soap.SOAPBodyElement
import javax.xml.soap.SOAPConnection
import javax.xml.soap.SOAPConnectionFactory
import javax.xml.soap.SOAPEnvelope
import javax.xml.soap.SOAPHeader
import javax.xml.soap.SOAPMessage
import javax.xml.soap.SOAPPart

import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.service.ServiceRunner
import org.slf4j.Logger
import org.slf4j.LoggerFactory

public class RemoteXmlsoapServiceRunner implements ServiceRunner {
    protected ServiceFacadeImpl sfi = null
    protected final static Logger logger = LoggerFactory.getLogger(RemoteXmlsoapServiceRunner.class)

    RemoteXmlsoapServiceRunner() {}

    public ServiceRunner init(ServiceFacadeImpl sfi) { this.sfi = sfi; return this }

    public Map<String, Object> runService(ServiceDefinition sd, Map<String, Object> parameters) {
        String location = sd.serviceNode.attribute("location")
        String method = sd.serviceNode.attribute("method")

        if (!location) throw new IllegalArgumentException("Cannot call remote service [${sd.serviceName}] because it has no location specified.")
        if (!method) throw new IllegalArgumentException("Cannot call remote service [${sd.serviceName}] because it has no method specified.")

        SOAPConnection connection = SOAPConnectionFactory.newInstance().createConnection()

        SOAPMessage message = javax.xml.soap.MessageFactory.newInstance().createMessage();
        SOAPPart soapPart = message.getSOAPPart();
        SOAPEnvelope envelope = soapPart.getEnvelope();
        SOAPHeader header = envelope.getHeader();
        SOAPBody body = envelope.getBody();
        header.detachNode();

        Map<String, Object> serviceParams = (Map<String, Object>)parameters.get("xmlRpcServiceParams")
        if (serviceParams) {
            parameters.remove("xmlRpcServiceParams")
        }
        boolean debug = serviceParams?.debug
        if (debug)
            logger.info("Debug mode is ON")
        String soapAction = serviceParams.get("soapAction")

        String methodNamespace = serviceParams?.methodNamespace
        String methodNamespacePrefix = serviceParams?.methodNamespacePrefix

        boolean proxy = serviceParams?.proxy
        if (proxy) {
            logger.info("Proxy mode is ON ")
            logger.error("Proxy mode unsupported")
            def proxyhost = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(serviceParams?.proxyhost, serviceParams?.proxyport))
        }
        else if (debug) logger.info("Proxy mode is OFF")

        Map<String, Object> basicAuthAttributes = (Map<String, Object>)parameters.get("xmlRpcBasicAuthentication")
        if (basicAuthAttributes) {
            if (debug) logger.info("user: ${basicAuthAttributes.user}, pass: ${basicAuthAttributes.pass}")
            String authString = "Basic ${basicAuthAttributes.user}:${basicAuthAttributes.pass}"
            message.getMimeHeaders().addHeader("Authorization", "Basic " + Base64.mimeEncoder.encode(authString.getBytes()))
            parameters.remove("xmlRpcBasicAuthentication")
        }

        /*
        Map<String, Object> soapConfig = (Map<String, Object>)parameters.get("xmlRpcSoapAttributes")
        if (soapConfig) {
            if (soapConfig['version'] == 'v1.1') ;
            parameters.remove("xmlRpcSoapAttributes")
        }
         */

        Map<String, Object> envelopeAttributes = (Map<String, Object>)parameters.get("xmlRpcEnvelopeAttributes")
        if (envelopeAttributes) {
            parameters.remove("xmlRpcEnvelopeAttributes")
            envelopeAttributes.each { key, value ->
                envelope.addAttribute(envelope.createName(key), value)
            }
        }

        Name bodyName = envelope.createName(method, methodNamespacePrefix, methodNamespace);
        message.getMimeHeaders().addHeader("SOAPAction", soapAction);

        SOAPBodyElement bodyElement = body.addBodyElement(bodyName);
        addToBodyElement(bodyElement, parameters)

        URL endpoint = new URL(location);

        if (debug) logger.info("Parameters: ${parameters}")
        //if (debug) logger.info("ContentType: ${message.version}; ${msg.encoding}")
        if (debug) logger.info("XML String: ${body.toString()}")

        SOAPMessage response = connection.call(message, endpoint);

        SOAPPart sp = response.getSOAPPart();
        SOAPBody resultBody = sp.getEnvelope().getBody();

        Map resultMap = toMap(resultBody)
        if (debug) logger.info("XML Result: ${resultMap}")

        return toMap(resultBody)

    }

    public static void addToBodyElement(SOAPBodyElement bodyElement, Object parameters) {
        if (parameters == null)
            return
        if (parameters instanceof Collection) {
            //logger.info("Processing collection: ${parameters}")
            Collection collection = (Collection) parameters
            collection.each { addToBodyElement(bodyElement, it) }
        } else if (!parameters instanceof Map) {
            throw new RuntimeException("Unhandled object type in addToBodyElement: ${parameters.class}")
        }
        Map map = (Map)parameters
        //logger.info("Processing map: ${parameters}")
        String namespace = map.namespace
        String key = map.key
        Object value = map.value
        if (key == null) {
            //logger.info("Key is null, value: ${value}")
            map.each { intKey, intValue ->
                Map newMap = [:]
                newMap.key = intKey
                newMap.value = intValue
                if (intValue instanceof List) {
                    //logger.info("Value is list, adding each element")
                    intValue.each { item ->
                        newMap.value = item
                        addToBodyElement(bodyElement, newMap)
                    }
                } else {
                    //logger.info("Value is not list, adding")
                    addToBodyElement(bodyElement, newMap)
                }
            }
            return
        }
        //logger.info("Adding child element with name ${key}")
        SOAPBodyElement childElement = bodyElement.addChildElement(new QName(namespace, key))
        if (value instanceof List) {
            //logger.info("Value is list and key is not null, adding each element")
            List childList = (List)value
            childList.each {
                //logger.info("processing child: ${it}")
                if (it instanceof String) {
                    //logger.info("child is string, adding textNode")
                    childElement.addTextNode(it)
                } else {
                    //logger.info("child is not string, adding")
                    addToBodyElement(childElement, it)
                }
            }
        } else if (value instanceof Map) {
            //logger.info("Value is Map and key is not null, adding recursively")
            addToBodyElement(childElement, value)
        } else if (value != null) {
            //logger.info("value is neither list nor map and is not null, adding textNode")
            childElement.addTextNode(value.toString())
        }
    }

    public void destroy() { }

    Map<String, Object> toMap(org.w3c.dom.Node node) {
        if (node.getLocalName() != "Body" || node.getNamespaceURI() != "http://schemas.xmlsoap.org/soap/envelope/")
            throw new IllegalArgumentException("Result body has name: '${node.getNodeName()}'")
        org.w3c.dom.NodeList children = node.getChildNodes()
        if (children.length == 0 || node.nodeType == org.w3c.dom.Node.TEXT_NODE)
            return node.getTextContent()
        if (children.length == 1 && node.getFirstChild().nodeType == org.w3c.dom.Node.TEXT_NODE)
            return node.getFirstChild().getTextContent()
        if (children.length == 1)
            node = children.item(0) // should always be the case, one single child
        Object childRes = toMapInternal(node)
        if (childRes instanceof Map<String,Object>)
            return (Map<String, Object>)childRes
        return [(node.getLocalName()):childRes]
    }

    Object toMapInternal(org.w3c.dom.Node node) {
        org.w3c.dom.NodeList children = node.getChildNodes()
        if (children.length == 0 || node.nodeType == org.w3c.dom.Node.TEXT_NODE)
            return node.getTextContent()
        if (children.length == 1 && node.getFirstChild().nodeType == org.w3c.dom.Node.TEXT_NODE)
            return node.getFirstChild().getTextContent()
        Map<String, List<Object>> childMaps = new HashMap<String, List<Object>>()
        List<String> childTexts = new LinkedList<String>()
        children.each { org.w3c.dom.Node child ->
            if (child.nodeType == org.w3c.dom.Node.TEXT_NODE) {
                if (child.textContent != null && child.textContent.trim().size() > 0)
                    logger.error("Unhandled text for child of node ${node.getLocalName()} with name ${child.getLocalName()}: ${child.textContent}")
            } else {
                List<Object> childNameList = childMaps.get(child.getLocalName())
                if (childNameList == null) {
                    childNameList = new LinkedList<Object>()
                    childMaps.put(child.getLocalName(), childNameList)
                }
                childNameList.add(toMapInternal(child))
            }
        }
        Map<String, Object> returnMap = new HashMap<String, Object>()
        childMaps.each { key, value ->
            List<Object> list = (List<Object>) value
            if (list.size() > 1)
                returnMap.put(key, list)
            else
                returnMap.put(key, list.get(0))
        }
        return returnMap
    }

    static {
        System.setProperty("javax.xml.soap.SAAJMetaFactory", "com.sun.xml.messaging.saaj.soap.SAAJMetaFactoryImpl")
    }
}

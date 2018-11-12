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

//import groovy.transform.CompileStatic
import groovy.util.slurpersupport.GPathResult
import groovy.xml.MarkupBuilderHelper
import groovy.xml.MarkupBuilder

// import org.apache.xmlrpc.client.XmlRpcClientConfigImpl
// import org.apache.xmlrpc.client.XmlRpcClient
// import org.apache.xmlrpc.client.XmlRpcCommonsTransportFactory
import wslite.soap.SOAPClient
import wslite.soap.SOAPResponse
import wslite.soap.SOAPMessageBuilder
import wslite.soap.SOAPVersion

import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.service.ServiceRunner
import org.slf4j.Logger
import org.slf4j.LoggerFactory

//@CompileStatic
public class RemoteXmlrpcServiceRunner implements ServiceRunner {
    protected ServiceFacadeImpl sfi = null
    protected final static Logger logger = LoggerFactory.getLogger(RemoteXmlrpcServiceRunner.class)

    RemoteXmlrpcServiceRunner() {}

    public ServiceRunner init(ServiceFacadeImpl sfi) { this.sfi = sfi; return this }

    public Map<String, Object> runService(ServiceDefinition sd, Map<String, Object> parameters) {
        String location = sd.serviceNode.attribute("location")
        String method = sd.serviceNode.attribute("method")
        if (!location) throw new IllegalArgumentException("Cannot call remote service [${sd.serviceName}] because it has no location specified.")
        if (!method) throw new IllegalArgumentException("Cannot call remote service [${sd.serviceName}] because it has no method specified.")

        SOAPClient client = new SOAPClient()
        client.setServiceURL(location)

        SOAPMessageBuilder smb = new SOAPMessageBuilder()
        smb.setVersion(SOAPVersion.V1_2)

        Map<String, Object> envelopeAttributes = (Map<String, Object>)parameters.get("xmlRpcEnvelopeAttributes")
        if (envelopeAttributes) {
            smb.envelopeAttributes(envelopeAttributes)
            parameters.remove("xmlRpcEnvelopeAttributes")
        }

        Map<String, Object> requestParams = (Map<String, Object>)parameters.get("xmlRpcRequestParams")
        if (requestParams) {
            parameters.remove("xmlRpcRequestParams")
        }

        Map<String, Object> serviceParams = (Map<String, Object>)parameters.get("xmlRpcServiceParams")
        if (serviceParams) {
            parameters.remove("xmlRpcServiceParams")
        }

        boolean debug = serviceParams?.debug

       String queryXml = createQueryXml(method, parameters)
        logger.info("queryXml: ${queryXml}")

        def msg = smb.build() {
            body = {
                mkp.yieldUnescaped(queryXml)
            }
        }

        if (debug) logger.info("XML String: ${msg}")

        SOAPResponse response = client.send(requestParams, msg.version, msg.toString())

        Map<String, Object> xmlRpcResult = (Map<String, Object>) toMap(response.body)

        return xmlRpcResult

    }

    String createQueryXml(String method, Map<String, Object> parameters) {
        return "<${method}>${createQueryXml(parameters)}</${method}>"
    }

    String createQueryXml(Map<String, Object> parameters) {
        StringBuffer sb = new StringBuffer()
        parameters.each {key, value ->
            sb.append("<${key}>")
            if (value.startsWith('['))
                sb.append(createQueryXml(sfi.ecfi.resource.expression(value, null)))
            else if (value instanceof Map) {
                sb.append(createQueryXml(value))
            } else {
                sb.append(value)
            }
            sb.append("</${key}>\n")
        }
        return sb.toString()
    }

    Map<String, Object> toMap(Object node) {
        if (node instanceof Closure) {
            throw new IllegalArgumentException("Unsupported type for node: 'Closure'")
        }
        if (!node instanceof GPathResult) {
            throw new IllegalArgumentException("Unsupported type for node: '${node.class}'")
        }
        GPathResult gpr = (GPathResult)node
        if (gpr.name() != "Body")
            throw new IllegalArgumentException("Result body has name: '${gpr.name()}'")
        GPathResult children = gpr.children()
        if (children.isEmpty())
            return [(gpr.name()):null]
        else if (gpr.size() == 1)
            gpr = children.getAt(0) // should always be the case, one single child
        Object childRes = toMapInternal(gpr)
        if (childRes instanceof Map<String,Object>)
            return (Map<String, Object>)childRes
        return [(gpr.name()):childRes]
    }

    Object toMapInternal(GPathResult gpr) {
        GPathResult children = gpr.children()
        if (children.isEmpty())
            return gpr.text()
        Map<String, List<Object>> childMaps = new HashMap<String, List<Object>>()
        children.each { child ->
            List<Object> childNameList = childMaps.get(child.name())
            if (childNameList == null) {
                childNameList = new LinkedList<Object>()
                childMaps.put(child.name(), childNameList)
            }
            childNameList.add(toMapInternal(child))
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

    public void destroy() { }
}

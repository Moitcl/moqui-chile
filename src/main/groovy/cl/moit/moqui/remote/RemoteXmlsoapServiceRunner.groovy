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

import groovy.util.slurpersupport.GPathResult
import wslite.http.auth.HTTPBasicAuthorization
import wslite.soap.SOAPClient
import wslite.soap.SOAPResponse
import wslite.soap.SOAPMessageBuilder
import wslite.soap.SOAPVersion

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

        SOAPClient client = new SOAPClient()
        client.setServiceURL(location)

        SOAPMessageBuilder smb = new SOAPMessageBuilder()
        smb.setVersion(SOAPVersion.V1_2)

        Map<String, Object> serviceParams = (Map<String, Object>)parameters.get("xmlRpcServiceParams")
        if (serviceParams) {
            parameters.remove("xmlRpcServiceParams")
        }
        boolean debug = serviceParams?.debug
        if (debug) logger.info("Debug mode is ON")
        else logger.info("Debug mode is OFF")

        Map<String, Object> basicAuthAttributes = (Map<String, Object>)parameters.get("xmlRpcBasicAuthentication")
        if (basicAuthAttributes) {
            if (debug) logger.info("user: ${basicAuthAttributes['user']}, pass: ${basicAuthAttributes['pass']}")
            client.authorization = new HTTPBasicAuthorization( basicAuthAttributes['user'].toString(), basicAuthAttributes['pass'].toString() )
            parameters.remove("xmlRpcBasicAuthentication")
        }

        Map<String, Object> envelopeAttributes = (Map<String, Object>)parameters.get("xmlRpcEnvelopeAttributes")
        if (envelopeAttributes) {
            smb.envelopeAttributes(envelopeAttributes)
            parameters.remove("xmlRpcEnvelopeAttributes")
        }

        Map<String, Object> requestParams = (Map<String, Object>)parameters.get("xmlRpcRequestParams")
        if (requestParams) {
            parameters.remove("xmlRpcRequestParams")
        }

        String queryXml = createQueryXml(method, parameters)
        if (debug) logger.info("queryXml: ${queryXml}")

        def msg = smb.build() {
            body = {
                mkp.yieldUnescaped(queryXml)
            }
        }

        if (debug) logger.info("XML String: ${msg}")

        SOAPResponse response = client.send(requestParams, msg.version, msg.toString())
        Map<String, Object> xmlRpcResult = (Map<String, Object>) toMap(response.body)

        if (debug) logger.info("XML Result: ${xmlRpcResult}")

        return xmlRpcResult

    }

    String createQueryXml(String method, Map<String, Object> parameters) {
        return "<${method}>${createQueryXml(parameters)}</${method}>"
    }

    Map<String, Object> stringToMap(String value) {
        logger.warn("starting stringToMap('${value}')")
        value = value.trim()
        if (value.charAt(0) != '[')
            throw new IllegalArgumentException("String does not start with '[', not a map")
        if (value.charAt(value.length()-1) != ']')
            throw new IllegalArgumentException("String does not end with ']', not a map")
        int pos = 1
        int parLevel = 0
        int squareBrackLevel = 0
        int keyStart = -1
        int keyEnd = -1
        int valueStart = -1
        Map<String, Object> newMap = new HashMap<String, Object>()
        while (pos < value.length()) {
            switch(value.charAt(pos)) {
                case '(':
                    parLevel++
                    break
                case ')':
                    parLevel++
                    break
                case '[':
                    squareBrackLevel++
                    break
                case ':':
                    if (parLevel == 0 && squareBrackLevel == 0) {
                        keyEnd = pos
                        valueStart = pos+1
                    }
                    break
                case ']':
                    logger.warn("found ']' at position ${pos} (length: ${value.length()})")
                    if (pos < value.length()-1) {
                        squareBrackLevel--
                        break
                    } // No break outside the if because ']' as last character marks end of value and should add to the
                      // map, same case as if we found a ',' so just continue to the next
                case ',':
                    if (parLevel == 0 && squareBrackLevel == 0) {
                        if (valueStart > 0 && valueStart < pos) {
                            String key = value.substring(keyStart, keyEnd).trim()
                            String val = value.substring(valueStart, pos).trim()
                            if (val.startsWith('['))
                                newMap.put(key, stringToMap(val))
                            else
                                newMap.put(key, val)
                            keyStart = -1
                            keyEnd = -1
                            valueStart = -1
                        }
                    }
                    break
                default:
                    if (parLevel == 0 && squareBrackLevel == 0) {
                        if (keyStart == -1) {
                            keyStart = pos
                            valueStart = -1
                        }
                    }
            }
            pos++
        }
        return newMap
    }

    String createQueryXml(Map<String, Object> parameters) {
        StringBuffer sb = new StringBuffer()
        parameters.each {key, value ->
            sb.append("<${key}>")
            if (value instanceof Map) {
                sb.append(createQueryXml(value))
            } else if (value instanceof String) {
                if (value.startsWith('[')) {
                    Map<String, Object> valueMap = stringToMap(value)
                    sb.append(createQueryXml(valueMap))
                } else {
                    sb.append(value)
                }
            } else {
                throw new IllegalArgumentException("Unsupported type for parameters")
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

// Source: http://ryanbrooks.co.uk/posts/2014-08-21-groovy-soap-wslite/

import groovy.xml.*
import wslite.soap.*

def client = new SOAPClient('https://si3.bcentral.cl/sietews/sietews.asmx?wsdl')

ec.logger.warn("using username: ${user}")
ec.logger.warn("using password: ${password}")
frequency = "MONTHLY"
def response = client.send(
                connectTimeout:5000,
                readTimeout:20000,
                useCaches:false,
                followRedirects:false,
                sslTrustAllCerts:true) {
    envelopeAttributes "xmlns":"http://bancocentral.org/"
    body {
        "SearchSeries" {
            user (user)
            password (password)
            frequencyCode ("MONTHLY")
        }
    }
}

def returnVal
if (response.httpResponse.statusMessage=="OK") {
    returnVal = response.SearchSeriesResponse.SearchSeriesResult
}
ec.logger.warn("returnVal: ${returnVal}")

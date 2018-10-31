package cl.moit.ws.bcentral

import org.slf4j.LoggerFactory
import org.slf4j.Logger

import java.text.DateFormat
import java.text.SimpleDateFormat
import wslite.soap.*

class BancoCentralWSConsumer {

    protected final static Logger logger = LoggerFactory.getLogger(BancoCentralWSConsumer.class)

    protected static List<String> validFrequencies = ['UNDEFINED', 'SEMIANNUAL', 'WEEKLY', 'QUARTERLY', 'MONTHLY', 'ANNUAL', 'DAILY']

    public static List<Map<String,Object>> seriesInfosToListOfMaps(groovy.util.slurpersupport.NodeChildren seriesInfos) {
        DateFormat dateFormat = new SimpleDateFormat("d-M-y")
        List<Map<String,Object>> seriesInfoList = []
        for (seriesInfo in seriesInfos) {
            Map seriesInfoMap = [:]
            for (key in ['seriesId', 'frequency', 'frequencyCode', 'observed', 'observedCode', 'spanishTitle', 'englishTitle']) {
                seriesInfoMap[key] = seriesInfo."${key}".text()
                if (seriesInfoMap[key].getClass().name != "java.lang.String")
                    logger.warn("added key ${key} of class: ${seriesInfoMap[key].getClass()}")
            }
            for (key in ['firstObservation', 'lastObservation', 'updatedAt', 'createdAt']) {
                try {
                    seriesInfoMap[key] = dateFormat.parse(seriesInfo."${key}".text())
                } catch (java.text.ParseException e) {
                    seriesInfoMap[key] = null
                }
            }
            seriesInfoList.add(seriesInfoMap)
            //logger.info("added series ${seriesInfoMap['seriesId']}")
        }
        //logger.info("added ${seriesInfoList.size()} series")
        return seriesInfoList
    }

    public static List<Map<String,Object>> searchSeries(String user, String password, String frequency) {
        if (!frequency in validFrequencies) {
            throw new Exception("Invalid frequency '${frequency}', expected one of ${validFrequencies}")
        }

        def client = new SOAPClient('https://si3.bcentral.cl/sietews/sietews.asmx?wsdl')
        def response = client.send(
                connectTimeout:5000,
                readTimeout:20000,
                useCaches:false,
                followRedirects:false,
                sslTrustAllCerts:true,
                """<?xml version='1.0' encoding='UTF-8'?>
               <soap-env:Envelope xmlns:soap-env='http://www.w3.org/2003/05/soap-envelope' xmlns='http://bancocentral.org/'>
                   <soap-env:Body>
                       <SearchSeries>
                           <user>${user}</user>
                           <password>${password}</password>
                           <frequencyCode>${frequency}</frequencyCode>
                       </SearchSeries>
                   </soap-env:Body>
               </soap-env:Envelope>""")

        def result
        if (response.httpResponse.statusMessage=="OK") {
            result = response.SearchSeriesResponse.SearchSeriesResult
        }
        def seriesInfos = result.SeriesInfos.internetSeriesInfo

        logger.warn("Codigo: ${result.Codigo}")
        logger.warn("Descripcion: ${result.Descripcion}")
        logger.warn("SeriesInfos.class: ${seriesInfos.getClass()}")
        logger.warn("SeriesInfos[0]: ${seriesInfos[0]}")

        def seriesInfoLists = BancoCentralWSConsumer.seriesInfosToListOfMaps(seriesInfos)
    }

    public static List<Map<String,Object>> seriesResultsToListOfMaps(groovy.util.slurpersupport.NodeChildren seriesResults) {

    }

    public static List<Map<String,Object>> getSeries(String user, String password, String firstDate, String lastDate,
                                                     List seriesIds) {

        def client = new SOAPClient('https://si3.bcentral.cl/sietews/sietews.asmx?wsdl')
        String firstDateXml = firstDate? "<firstDate>${firstDate}</firstDate>": ""
        String lastDateXml = lastDate? "<lastDate>${lastDate}</lastDate>": ""
        StringBuffer seriesIdsXml = new StringBuffer("<seriesIds>")
        seriesIds.each {
            seriesIdsXml.append("\n<string>${it}</string>")
        }
        seriesIdsXml.append("</seriesIds>")
        def response = client.send(
                connectTimeout:5000,
                readTimeout:20000,
                useCaches:false,
                followRedirects:false,
                sslTrustAllCerts:true,
                """<?xml version='1.0' encoding='UTF-8'?>
               <soap-env:Envelope xmlns:soap-env='http://www.w3.org/2003/05/soap-envelope' xmlns='http://bancocentral.org/'>
                   <soap-env:Body>
                       <GetSeries>
                           <user>${user}</user>
                           <password>${password}</password>
                           ${firstDateXml}
                           ${lastDateXml}
                           ${seriesIdsXml.toString()}
                       </GetSeries>
                   </soap-env:Body>
               </soap-env:Envelope>""")

        def result
        if (response.httpResponse.statusMessage=="OK") {
            result = response.GetSeriesResponse.GetSeriesResult
        }
        def series = result.fameSeries
        // header (series ID)
        // <seriesKey><keyFamilyId>F072</keyFamilyId><seriesId>F072.XPF.USD.N.O.D</seriesId><dataStage>INTERNAL</dataStage><exists>true</exists></seriesKey><precision>2</precision>
        // lista de <obs></obs>: <obs><indexDateString>02-10-2018</indexDateString><seriesKey><keyFamilyId>F072</keyFamilyId><seriesId>F072.XPF.USD.N.O.D</seriesId><dataStage>INTERNAL</dataStage><exists>true</exists></seriesKey><statusCode>OK</statusCode><value>102.3123</value></obs>

        logger.warn("")
        logger.warn("Codigo: ${result.Codigo}")
        logger.warn("Descripcion: ${result.Descripcion}")
        logger.warn("Series.class: ${series.getClass()}")
        logger.warn("Series[0]: ${series[0]}")

        //def seriesInfoLists = BancoCentralWSConsumer.seriesInfosToListOfMaps(seriesInfos)
    }

}

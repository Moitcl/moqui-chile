package cl.moit.ws.bcentral

import org.slf4j.LoggerFactory

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.logging.Logger
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
            logger.info("added series ${seriesInfoMap['seriesId']}")
        }
        logger.info("added ${seriesInfoList.size()} series")
        return seriesInfoList
    }

    public static void updateSeries(String user, String password) {
        for (frequency in validFrequencies) {
            List<Map<String,Object>> series = searchSeries(user, password, frequency)
            // Add to entity
        }
    }

    public static SOAPResponse searchSeries(String user, String password, String frequency) {
        if (!frequency in validFrequencies) {
            throw new Exception("Invalid frequency '${frequency}', expected one of ${validFrequencies}")
        }

        def client = new SOAPClient('https://si3.bcentral.cl/sietews/sietews.asmx?wsdl')
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
                    frequencyCode (frequency)
                }
            }
        }

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

}

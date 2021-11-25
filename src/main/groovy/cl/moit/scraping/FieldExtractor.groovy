package cl.moit.scraping


import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.regex.Matcher
import java.util.regex.Pattern

class FieldExtractor {

    private static final Logger logger = LoggerFactory.getLogger(FieldExtractor.class);

    public static Map<String,String> extractFromPatternList(String text, List<String> patternList) {
        Map<String,String> campos = [:]
        patternList.each {
            //println("attempting pattern ${it.pattern} of type ${it.pattern.class}")
            String baseText = null
            Pattern pattern = null
            Matcher matcher = null
            if (it.preselectPattern) {
                //logger.warn("preselectPattern: ${it.preselectPattern}")
                pattern = Pattern.compile(it.preselectPattern, java.util.regex.Pattern.DOTALL)
                matcher = text =~ pattern
                if (matcher.find()) {
                    baseText = matcher.findAll().first()[1]
                    //logger.warn("baseText: ${baseText}")
                    //logger.warn("pattern: ${it.pattern}")
                } else baseText = text
            } else baseText = text

            pattern = java.util.regex.Pattern.compile(it.pattern, java.util.regex.Pattern.DOTALL)
            matcher = baseText =~ pattern
            List campoList = []
            matcher.findAll().each { match ->
                if (it.fieldNames) {
                    Map fieldMap = [:]
                    int i = 1
                    it.fieldNames.each { fieldName ->
                        fieldMap.put(fieldName, match[i].trim())
                        i++
                    }
                    campoList.add(fieldMap)
                } else
                    campoList.add(match[1].trim())
            }
            campos.put(it['name'], campoList.size() == 1? campoList.get(0): campoList)
        }
        return campos
    }
}

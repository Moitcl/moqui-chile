package cl.moit.scraping

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.regex.Matcher
import java.util.regex.Pattern

class FieldExtractor {

    private static final Logger logger = LoggerFactory.getLogger(FieldExtractor.class);

    /**
     *
     * @param text text to be scraped
     * @param patternList list of patterns to search within the @text. Each pattern is a Map<String,String> that contains following fields:
     *        name: field name to use in the return value for matched patterns
     *        preselectPattern: an initial filter so that the pattern (see below) is searched for in the matched text of this pattern, as
     *                          defined by the capturing group (see java.util.regex.Pattern)
     *        pattern: regex to find the match and return the desired value as a capturing group (see java.util.regex.Pattern). If the pattern is
     *                 found once within the text (pre-filtered by preselectPattern if present), the value is the String, otherwise it is a
     *                 List<String>.
     * @return a map of strings with the field name as key and the text indicated in the pattern (either a String or a List<Sting> if the pattern
     *         is matched multiple times) as the value
     */
    public static Map<String,Object> extractFromPatternList(String text, List<Map<String,String>> patternList) {
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
                } else baseText = ""
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
            campos.put(it.name, campoList.size() == 1? campoList.get(0): campoList)
        }
        return campos
    }
}

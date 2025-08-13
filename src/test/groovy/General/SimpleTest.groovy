package General

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class SimpleTest extends Specification {
    @Shared protected final static Logger logger = LoggerFactory.getLogger(SimpleTest.class)
    @Shared ExecutionContext ec

    def setupSpec() {
        logger.info("[SimpleTest] Starting simple test")
        ec = Moqui.getExecutionContext()
        logger.info("[SimpleTest] Got execution context successfully")
    }

    def cleanupSpec() {
        if (ec != null) ec.destroy()
        logger.info("[SimpleTest] Test completed")
    }

    def "Simple arithmetic test"() {
        expect:
        2 + 2 == 4
    }

    def "Simple string test"() {
        expect:
        "hello".toUpperCase() == "HELLO"
    }
}
package General

import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Unroll
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Comprehensive unit tests for MIME filename decoder service
 * Tests the mchile.GeneralServices.decode#MimeFilename service
 * for proper RFC 2047 MIME header decoding functionality.
 */
class MimeDecodingSpec extends Specification {

    private static final Logger logger = LoggerFactory.getLogger(MimeDecodingSpec.class)

    @Shared ExecutionContext ec
    
    def setupSpec() {
        ec = Moqui.getExecutionContext()

        // Login with existing test user if needed
        if (!ec.user.userId) {
            boolean loginSuccess = ec.user.loginUser("john.doe", "moqui")
            if (!loginSuccess) {
                throw new RuntimeException("Failed to login - ensure test data is loaded with correct data types")
            }
        }
    }

    def cleanupSpec() {
        ec.user.logoutUser()
        ec.destroy()
    }

    def setup() {
        ec.message.clearErrors()  // Clear any errors from previous tests
        // Follow mantle-usl pattern for per-test transaction management
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
    }

    def cleanup() {
        // Follow mantle-usl pattern: warn about unexpected errors, don't proactively clear
        if (ec.message.hasError()) {
            logger.warn("Unexpected errors in test: ${ec.message.getErrorsString()}")
            // DO NOT clear errors here - let tests fail if unexpected errors occur
        }
        ec.artifactExecution.enableAuthz()

        // Handle transaction rollback if needed
        if (ec.transaction.status == javax.transaction.Status.STATUS_MARKED_ROLLBACK) {
            ec.transaction.rollback("Marked as Rollback", null)
        } else {
            ec.transaction.commit()
        }
    }
    
    def "001 - service should decode RFC 2047 UTF-8 Q-encoding correctly"() {
        given: "A MIME-encoded UTF-8 Q-encoding filename with Spanish characters"
        String encodedFilename = "=?UTF-8?Q?Factura_Electr=C3=B3nica_123.xml?="
        String expectedDecoded = "Factura Electrónica 123.xml"
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: encodedFilename])
                .call()
        
        then: "The filename is correctly decoded"
        result.decodedFilename == expectedDecoded
        result.originalFilename == encodedFilename
        result.wasEncoded == true
        result.encoding == "Quoted-Printable"
        result.charset == "UTF-8"
        result.errors == null
    }
    
    def "002 - service should decode RFC 2047 ISO-8859-1 Q-encoding correctly"() {
        given: "A MIME-encoded ISO-8859-1 Q-encoding filename"
        String encodedFilename = "=?ISO-8859-1?Q?Boleta_Ni=F1a_456.pdf?="
        String expectedDecoded = "Boleta Niña 456.pdf"
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: encodedFilename])
                .call()
        
        then: "The filename is correctly decoded"
        result.decodedFilename == expectedDecoded
        result.originalFilename == encodedFilename
        result.wasEncoded == true
        result.encoding == "Quoted-Printable"
        result.charset == "ISO-8859-1"
        result.errors == null
    }
    
    def "003 - service should decode RFC 2047 Base64 encoding correctly"() {
        given: "A MIME-encoded Base64 filename"
        String encodedFilename = "=?UTF-8?B?ZmFjdHVyYV8xMjMueG1s?="
        String expectedDecoded = "factura_123.xml"
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: encodedFilename])
                .call()
        
        then: "The filename is correctly decoded"
        result.decodedFilename == expectedDecoded
        result.originalFilename == encodedFilename
        result.wasEncoded == true
        result.encoding == "Base64"
        result.charset == "UTF-8"
        result.errors == null
    }
    
    def "004 - service should handle multiple encoded words correctly"() {
        given: "A filename with multiple MIME-encoded segments"
        String encodedFilename = "=?UTF-8?Q?Nota_Cr=C3=A9dito?= =?UTF-8?Q?_789.xml?="
        String expectedDecoded = "Nota Crédito 789.xml"
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: encodedFilename])
                .call()
        
        then: "The filename is correctly decoded with proper concatenation"
        result.decodedFilename == expectedDecoded
        result.originalFilename == encodedFilename
        result.wasEncoded == true
        result.errors == null
    }
    
    def "005 - service should handle mixed encoded and plain text content"() {
        given: "A filename with both encoded and plain text parts"
        String encodedFilename = "DTE_=?UTF-8?Q?Aprobado_2023?=.xml"
        String expectedDecoded = encodedFilename  // Service does not decode mixed content

        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: encodedFilename])
                .call()

        then: "The filename passes through unchanged for mixed content"
        result.decodedFilename == expectedDecoded
        result.originalFilename == encodedFilename
        result.wasEncoded == false  // Mixed content is not considered encoded
        result.errors == null
    }
    
    def "006 - service should pass through plain ASCII filenames unchanged"() {
        given: "A plain ASCII filename without encoding"
        String plainFilename = "simple_document.xml"
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: plainFilename])
                .call()
        
        then: "The filename passes through unchanged"
        result.decodedFilename == plainFilename
        result.originalFilename == plainFilename
        result.wasEncoded == false
        result.encoding == null
        result.charset == null
        result.errors == null
    }
    
    def "007 - service should handle malformed encoded words gracefully"() {
        given: "A malformed MIME-encoded filename with invalid syntax"
        String malformedFilename = "=?UTF-8?Q?Invalid_Missing_End"
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: malformedFilename])
                .call()
        
        then: "The service falls back gracefully to original filename"
        result.decodedFilename == malformedFilename
        result.originalFilename == malformedFilename
        result.wasEncoded == false
        result.encoding == null
        result.charset == null
        result.errors == null
    }
    
    def "008 - service should handle unsupported character sets gracefully"() {
        given: "A MIME-encoded filename with unsupported character set"
        String unsupportedFilename = "=?INVALID-CHARSET?Q?test.xml?="
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: unsupportedFilename])
                .call()
        
        then: "The service falls back gracefully to original filename"
        result.decodedFilename == unsupportedFilename
        result.originalFilename == unsupportedFilename
        result.wasEncoded == false
        result.encoding == null
        result.charset == null
        result.errors == null
    }
    
    def "009 - service should handle invalid Base64 content gracefully"() {
        given: "A MIME-encoded filename with invalid Base64 content"
        String invalidBase64Filename = '=?UTF-8?B?InvalidBase64!@#$?='
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: invalidBase64Filename])
                .call()
        
        then: "The service falls back gracefully to original filename"
        result.decodedFilename == invalidBase64Filename
        result.originalFilename == invalidBase64Filename
        result.wasEncoded == false
        result.encoding == null
        result.charset == null
        result.errors == null
    }
    
    def "010 - service should handle invalid quoted-printable content gracefully"() {
        given: "A MIME-encoded filename with invalid quoted-printable content"
        String invalidQPFilename = "=?UTF-8?Q?Invalid=ZZ_content?="
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: invalidQPFilename])
                .call()
        
        then: "The service falls back gracefully to original filename"
        result.decodedFilename == invalidQPFilename
        result.originalFilename == invalidQPFilename
        result.wasEncoded == false
        result.encoding == null
        result.charset == null
        result.errors == null
    }
    
    def "011 - service should handle oversized headers gracefully"() {
        given: "An oversized MIME-encoded filename"
        String oversizedContent = "A" * 1000  // 1000 character string
        String oversizedFilename = "=?UTF-8?Q?${oversizedContent}?="
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: oversizedFilename])
                .call()
        
        then: "The service handles oversized content appropriately"
        result.originalFilename == oversizedFilename
        result.errors == null
        // Result should either decode successfully or fall back gracefully
        result.decodedFilename != null
    }
    
    def "012 - service should handle special Unicode characters correctly"() {
        given: "A MIME-encoded filename with special Unicode characters"
        String encodedFilename = "=?UTF-8?Q?=E2=9C=85_DTE_Aprobado_2023.xml?="
        String expectedDecoded = "✅ DTE Aprobado 2023.xml"
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: encodedFilename])
                .call()
        
        then: "The Unicode characters are correctly decoded"
        result.decodedFilename == expectedDecoded
        result.originalFilename == encodedFilename
        result.wasEncoded == true
        result.encoding == "Quoted-Printable"
        result.charset == "UTF-8"
        result.errors == null
    }
    
    def "013 - service should handle case insensitive encoding and charset parameters"() {
        given: "A MIME-encoded filename with mixed case encoding parameters"
        String encodedFilename = "=?utf-8?q?test_file.xml?="
        String expectedDecoded = "test file.xml"
        
        when: "The decode service is called"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: encodedFilename])
                .call()
        
        then: "The service handles case insensitive parameters correctly"
        result.decodedFilename == expectedDecoded
        result.originalFilename == encodedFilename
        result.wasEncoded == true
        result.encoding.toUpperCase() == "QUOTED-PRINTABLE"
        result.charset.toUpperCase() == "UTF-8"
        result.errors == null
    }
    
    @Unroll
    def "014 - service should correctly decode common DTE filenames: #description"() {
        when: "The decode service is called with DTE filename"
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: encodedFilename])
                .call()
        
        then: "The filename is correctly decoded"
        result.decodedFilename == expectedDecoded
        result.originalFilename == encodedFilename
        result.wasEncoded == wasEncoded
        result.errors == null
        
        where:
        description                          | encodedFilename                                            | expectedDecoded                    | wasEncoded
        "Factura Electrónica UTF-8"        | "=?UTF-8?Q?Factura_Electr=C3=B3nica_123.xml?="          | "Factura Electrónica 123.xml"    | true
        "Boleta with Ñ ISO-8859-1"         | "=?ISO-8859-1?Q?Boleta_Ni=F1a_456.pdf?="                | "Boleta Niña 456.pdf"             | true
        "Nota Crédito Base64"               | "=?UTF-8?B?Tm90YV9DcsOpZGl0b183ODkueG1s?="                | "Nota_Crédito_789.xml"            | true
        "DTE Aprobado with emoji"           | "=?UTF-8?Q?=E2=9C=85_DTE_Aprobado_2023.xml?="           | "✅ DTE Aprobado 2023.xml"       | true
        "Plain DTE filename"                | "dte_simple_document.xml"                                 | "dte_simple_document.xml"          | false
        "Guía de Despacho"                 | "=?UTF-8?Q?Gu=C3=ADa_Despacho_001.xml?="                | "Guía Despacho 001.xml"          | true
    }
    
    def "015 - service should measure performance within acceptable limits"() {
        given: "A standard MIME-encoded filename"
        String encodedFilename = "=?UTF-8?Q?Performance_Test_Document.xml?="

        when: "The decode service is called multiple times"
        long startTime = System.nanoTime()
        int iterations = 50  // Reasonable number for unit test
        for (int i = 0; i < iterations; i++) {
            ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                    .parameters([filename: encodedFilename])
                    .call()
        }
        long endTime = System.nanoTime()
        long avgTimeMs = (endTime - startTime) / (iterations * 1_000_000)  // Convert to milliseconds

        then: "The service performs within acceptable time limits"
        avgTimeMs < 100  // Should decode in less than 100ms on average (more reasonable)
        logger.info("Average decode time: ${avgTimeMs}ms per operation")
    }
    
    def "016 - service should handle concurrent access safely"() {
        given: "Multiple concurrent requests"
        String encodedFilename = "=?UTF-8?Q?Concurrent_Test.xml?="
        String expectedDecoded = "Concurrent Test.xml"
        List<Thread> threads = []
        List<Map> results = Collections.synchronizedList([])
        
        when: "Multiple threads call the service simultaneously"
        10.times { i ->
            Thread thread = new Thread({
                try {
                    // Create thread-specific execution context for proper isolation
                    ExecutionContext threadEc = ec.factory.getExecutionContext()
                    Map result = threadEc.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                            .parameters([filename: encodedFilename])
                            .call()
                    results.add(result)
                } catch (Exception e) {
                    results.add([error: e.message])
                }
            })
            threads.add(thread)
            thread.start()
        }
        threads.each { it.join() }
        
        then: "All requests complete successfully with consistent results"
        results.size() == 10
        results.every { it.decodedFilename == expectedDecoded && it.errors == null }
    }
    
    def "017 - service should validate memory usage for large filenames"() {
        given: "A large MIME-encoded filename"
        String largeContent = "Large_Content_" + ("X" * 500)
        String encodedFilename = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(largeContent.getBytes("UTF-8")) + "?="
        
        when: "The decode service is called"
        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        Map result = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                .parameters([filename: encodedFilename])
                .call()
        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        long memoryUsed = memoryAfter - memoryBefore
        
        then: "Memory usage is reasonable"
        result.errors == null
        result.decodedFilename != null
        memoryUsed < 1024 * 1024  // Less than 1MB for processing
    }
    
    def "018 - service should maintain consistent behavior across multiple calls"() {
        given: "A MIME-encoded filename"
        String encodedFilename = "=?UTF-8?Q?Consistency_Test.xml?="
        String expectedDecoded = "Consistency Test.xml"
        
        when: "The service is called multiple times with the same input"
        List<Map> results = []
        5.times {
            results.add(ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                    .parameters([filename: encodedFilename])
                    .call())
        }
        
        then: "All results are identical"
        results.every { 
            it.decodedFilename == expectedDecoded &&
            it.originalFilename == encodedFilename &&
            it.wasEncoded == true &&
            it.encoding == "Quoted-Printable" &&
            it.charset == "UTF-8" &&
            it.errors == null
        }
    }
}
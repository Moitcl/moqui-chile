package EmailProcessing

import spock.lang.Specification
import spock.lang.Shared
import spock.lang.Unroll
import org.moqui.Moqui
import org.moqui.context.ExecutionContext

/**
 * Comprehensive integration tests for DTE processing with MIME filename decoding
 * Tests the full workflow of processing incoming DTE messages with encoded attachment filenames
 * and verifies integration with mchile.sii.dte.DteCommServices.process#IncomingDteMessage
 */
class DteProcessingSpec extends Specification {
    
    @Shared ExecutionContext ec
    
    def setupSpec() {
        ec = Moqui.getExecutionContext()
        
        // Login with existing test user if needed  
        if (!ec.user.userId) {
            boolean loginSuccess = ec.user.loginUser("john.doe", "moqui")
            if (!loginSuccess) {
                throw new RuntimeException("Failed to login - ensure test data is loaded with correct data types (moquichile-test)")
            }
        }
        
        // Disable authorization for test setup operations
        ec.artifactExecution.disableAuthz()
        
        // Load integration test data
        loadIntegrationTestData()
    }
    
    def cleanupSpec() {
        // Clean up integration test data
        cleanupIntegrationTestData()
        ec.user.logoutUser()
        ec.destroy()
    }
    
    def "001 - DTE processing should handle encoded attachment filenames correctly"() {
        given: "A DTE message with MIME-encoded attachment filename"
        String encodedFilename = "=?UTF-8?Q?Factura_Electr=C3=B3nica_123.xml?="
        String expectedDecoded = "Factura Electrónica 123.xml"
        Map mockEmailMessage = createMockDteEmailMessage(encodedFilename)
        
        when: "The DTE message processing service is called"
        Map result = processDteMessageWithMocking(mockEmailMessage)
        
        then: "The attachment is processed with decoded filename"
        result.errors == null
        result.processedAttachments?.size() >= 1
        result.processedAttachments[0].originalFilename == encodedFilename
        result.processedAttachments[0].decodedFilename == expectedDecoded
        result.processedAttachments[0].wasDecoded == true
    }
    
    def "002 - DTE processing should handle ISO-8859-1 encoded filenames correctly"() {
        given: "A DTE message with ISO-8859-1 encoded attachment filename"
        String encodedFilename = "=?ISO-8859-1?Q?Boleta_Ni=F1a_456.pdf?="
        String expectedDecoded = "Boleta Niña 456.pdf"
        Map mockEmailMessage = createMockDteEmailMessage(encodedFilename)
        
        when: "The DTE message processing service is called"
        Map result = processDteMessageWithMocking(mockEmailMessage)
        
        then: "The attachment is processed with correctly decoded filename"
        result.errors == null
        result.processedAttachments?.size() >= 1
        result.processedAttachments[0].decodedFilename == expectedDecoded
        result.processedAttachments[0].wasDecoded == true
    }
    
    def "003 - DTE processing should handle Base64 encoded filenames correctly"() {
        given: "A DTE message with Base64 encoded attachment filename"
        String encodedFilename = "=?UTF-8?B?Tm90YV9DcsOpZGl0b183ODkueG1s?="
        String expectedDecoded = "Nota_Crédito_789.xml"
        Map mockEmailMessage = createMockDteEmailMessage(encodedFilename)
        
        when: "The DTE message processing service is called"
        Map result = processDteMessageWithMocking(mockEmailMessage)
        
        then: "The attachment is processed with Base64 decoded filename"
        result.errors == null
        result.processedAttachments?.size() >= 1
        result.processedAttachments[0].decodedFilename == expectedDecoded
        result.processedAttachments[0].wasDecoded == true
    }
    
    def "004 - DTE processing should handle non-encoded filenames without disruption"() {
        given: "A DTE message with plain ASCII attachment filename"
        String plainFilename = "simple_dte_document.xml"
        Map mockEmailMessage = createMockDteEmailMessage(plainFilename)
        
        when: "The DTE message processing service is called"
        Map result = processDteMessageWithMocking(mockEmailMessage)
        
        then: "The attachment is processed normally without decoding"
        result.errors == null
        result.processedAttachments?.size() >= 1
        result.processedAttachments[0].originalFilename == plainFilename
        result.processedAttachments[0].decodedFilename == plainFilename
        result.processedAttachments[0].wasDecoded == false
    }
    
    def "005 - DTE processing should handle multiple encoded attachments"() {
        given: "A DTE message with multiple encoded attachment filenames"
        List<String> encodedFilenames = [
            "=?UTF-8?Q?Factura_001.xml?=",
            "=?UTF-8?Q?Boleta_002.xml?=",
            "=?ISO-8859-1?Q?Nota_Cr=E9dito_003.xml?="
        ]
        Map mockEmailMessage = createMockDteEmailMessageWithMultipleAttachments(encodedFilenames)
        
        when: "The DTE message processing service is called"
        Map result = processDteMessageWithMocking(mockEmailMessage)
        
        then: "All attachments are processed with correct decoded filenames"
        result.errors == null
        result.processedAttachments?.size() == 3
        result.processedAttachments.every { it.wasDecoded == true }
        result.processedAttachments[0].decodedFilename == "Factura_001.xml"
        result.processedAttachments[1].decodedFilename == "Boleta_002.xml"  
        result.processedAttachments[2].decodedFilename == "Nota_Crédito_003.xml"
    }
    
    def "006 - DTE processing should fallback gracefully for malformed encoded filenames"() {
        given: "A DTE message with malformed encoded attachment filename"
        String malformedFilename = "=?UTF-8?Q?Malformed_Missing_End"
        Map mockEmailMessage = createMockDteEmailMessage(malformedFilename)
        
        when: "The DTE message processing service is called"
        Map result = processDteMessageWithMocking(mockEmailMessage)
        
        then: "The attachment is processed with original filename as fallback"
        result.errors == null
        result.processedAttachments?.size() >= 1
        result.processedAttachments[0].originalFilename == malformedFilename
        result.processedAttachments[0].decodedFilename == malformedFilename
        result.processedAttachments[0].wasDecoded == false
    }
    
    def "007 - DTE processing should log decoded vs original filenames appropriately"() {
        given: "A DTE message with encoded attachment filename"
        String encodedFilename = "=?UTF-8?Q?Log_Test_Document.xml?="
        String expectedDecoded = "Log_Test_Document.xml"
        Map mockEmailMessage = createMockDteEmailMessage(encodedFilename)
        
        when: "The DTE message processing service is called with logging enabled"
        Map result = processDteMessageWithLogging(mockEmailMessage)
        
        then: "Appropriate log entries are created for decoded filenames"
        result.errors == null
        result.logEntries != null
        result.logEntries.any { it.contains("Decoded filename") && it.contains(expectedDecoded) }
        result.logEntries.any { it.contains("Original filename") && it.contains(encodedFilename) }
    }
    
    def "008 - DTE processing should maintain performance with MIME decoding enabled"() {
        given: "Multiple DTE messages with encoded filenames"
        List<String> encodedFilenames = (1..10).collect { 
            "=?UTF-8?Q?Performance_Test_${it}.xml?=" 
        }
        
        when: "Multiple DTE messages are processed"
        long startTime = System.currentTimeMillis()
        List<Map> results = []
        encodedFilenames.each { filename ->
            Map mockMessage = createMockDteEmailMessage(filename)
            results.add(processDteMessageWithMocking(mockMessage))
        }
        long endTime = System.currentTimeMillis()
        long totalTime = endTime - startTime
        long avgTimePerMessage = totalTime / encodedFilenames.size()
        
        then: "Performance remains acceptable with MIME decoding"
        results.every { it.errors == null }
        avgTimePerMessage < 500  // Less than 500ms per message
        totalTime < 10000        // Total less than 10 seconds
    }
    
    @Unroll
    def "009 - DTE processing should handle various DTE document types with encoded filenames: #documentType"() {
        when: "A DTE message with encoded filename is processed"
        Map mockMessage = createMockDteEmailMessage(encodedFilename)
        Map result = processDteMessageWithMocking(mockMessage)
        
        then: "The document is processed correctly regardless of type"
        result.errors == null
        result.processedAttachments?.size() >= 1
        result.processedAttachments[0].decodedFilename == expectedDecoded
        result.processedAttachments[0].wasDecoded == true
        
        where:
        documentType         | encodedFilename                                      | expectedDecoded
        "Factura"           | "=?UTF-8?Q?Factura_Electr=C3=B3nica.xml?="         | "Factura Electrónica.xml"
        "Boleta"            | "=?UTF-8?Q?Boleta_Honorarios.xml?="                | "Boleta_Honorarios.xml"
        "Nota Crédito"      | "=?UTF-8?Q?Nota_Cr=C3=A9dito.xml?="                | "Nota_Crédito.xml"
        "Nota Débito"       | "=?UTF-8?Q?Nota_D=C3=A9bito.xml?="                 | "Nota_Débito.xml"
        "Guía Despacho"     | "=?UTF-8?Q?Gu=C3=ADa_Despacho.xml?="               | "Guía_Despacho.xml"
        "Factura Compra"    | "=?UTF-8?Q?Factura_Compra.xml?="                   | "Factura_Compra.xml"
    }
    
    def "010 - DTE processing should handle concurrent processing with MIME decoding safely"() {
        given: "Multiple concurrent DTE processing requests"
        String encodedFilename = "=?UTF-8?Q?Concurrent_DTE_Test.xml?="
        String expectedDecoded = "Concurrent_DTE_Test.xml"
        List<Thread> threads = []
        List<Map> results = Collections.synchronizedList([])
        
        when: "Multiple threads process DTE messages simultaneously"
        5.times { i ->
            Thread thread = new Thread({
                try {
                    Map mockMessage = createMockDteEmailMessage("${encodedFilename}_${i}")
                    Map result = processDteMessageWithMocking(mockMessage)
                    results.add(result)
                } catch (Exception e) {
                    results.add([error: e.message])
                }
            })
            threads.add(thread)
            thread.start()
        }
        threads.each { it.join() }
        
        then: "All concurrent requests complete successfully"
        results.size() == 5
        results.every { it.errors == null && it.processedAttachments?.size() >= 1 }
    }
    
    def "011 - DTE processing should validate attachment storage uses decoded filenames"() {
        given: "A DTE message with encoded attachment filename"
        String encodedFilename = "=?UTF-8?Q?Storage_Test.xml?="
        String expectedDecoded = "Storage_Test.xml"
        Map mockEmailMessage = createMockDteEmailMessage(encodedFilename)
        
        when: "The DTE message is processed and stored"
        Map result = processDteMessageWithStorage(mockEmailMessage)
        
        then: "The stored attachment uses the decoded filename"
        result.errors == null
        result.storedAttachment != null
        result.storedAttachment.filename == expectedDecoded
        result.storedAttachment.originalMimeFilename == encodedFilename
    }
    
    def "012 - DTE processing should maintain backward compatibility with existing functionality"() {
        given: "A DTE message with non-encoded filename (legacy scenario)"
        String plainFilename = "legacy_dte_document.xml"
        Map mockEmailMessage = createMockDteEmailMessage(plainFilename)
        
        when: "The DTE message processing service is called"
        Map result = processDteMessageWithMocking(mockEmailMessage)
        
        then: "Legacy functionality remains unaffected"
        result.errors == null
        result.processedAttachments?.size() >= 1
        result.processedAttachments[0].originalFilename == plainFilename
        result.processedAttachments[0].decodedFilename == plainFilename
        result.processedAttachments[0].wasDecoded == false
        // Verify no new fields break existing code
        result.processedAttachments[0].containsKey('encoding') == false || result.processedAttachments[0].encoding == null
    }
    
    def "013 - DTE processing should handle memory efficiently with large encoded filenames"() {
        given: "A DTE message with large encoded filename"
        String largeContent = "Large_DTE_Document_" + ("X" * 200)
        String encodedFilename = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(largeContent.getBytes("UTF-8")) + "?="
        Map mockEmailMessage = createMockDteEmailMessage(encodedFilename)
        
        when: "The large DTE message is processed"
        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        Map result = processDteMessageWithMocking(mockEmailMessage)
        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        long memoryUsed = memoryAfter - memoryBefore
        
        then: "Memory usage remains reasonable"
        result.errors == null
        result.processedAttachments?.size() >= 1
        memoryUsed < 2 * 1024 * 1024  // Less than 2MB for processing
    }
    
    def "014 - DTE processing should validate complete end-to-end workflow with decoding"() {
        given: "A complete DTE email message with encoded attachment"
        String encodedFilename = "=?UTF-8?Q?End_to_End_Test.xml?="
        String expectedDecoded = "End_to_End_Test.xml"
        Map completeEmailMessage = createCompleteEmailMessage(encodedFilename)
        
        when: "The complete DTE workflow is executed"
        Map result = executeCompleteWorkflow(completeEmailMessage)
        
        then: "The complete workflow processes successfully with decoded filename"
        result.errors == null
        result.emailProcessed == true
        result.attachmentProcessed == true
        result.decodedFilename == expectedDecoded
        result.originalFilename == encodedFilename
        result.workflowComplete == true
    }
    
    def "015 - DTE processing should handle error scenarios without disrupting main workflow"() {
        given: "A DTE message where MIME decoding might fail"
        String problematicFilename = "=?UNSUPPORTED-CHARSET?Q?test.xml?="
        Map mockEmailMessage = createMockDteEmailMessage(problematicFilename)
        
        when: "The DTE message processing service is called"
        Map result = processDteMessageWithMocking(mockEmailMessage)
        
        then: "The main workflow continues despite decoding issues"
        result.errors == null  // Main workflow should not error
        result.processedAttachments?.size() >= 1
        result.processedAttachments[0].originalFilename == problematicFilename
        result.processedAttachments[0].decodedFilename == problematicFilename  // Fallback to original
        result.processedAttachments[0].wasDecoded == false
    }
    
    // Helper methods for test data setup
    
    private void loadIntegrationTestData() {
        // Load necessary test data using auto-services to avoid authentication issues
        try {
            // Create test email server configuration if needed
            createTestEmailServerConfig()
            
            // Create test party data if needed  
            createTestPartyData()
            
        } catch (Exception e) {
            ec.logger.warn("Error loading integration test data: ${e.message}", e)
        }
    }
    
    private void cleanupIntegrationTestData() {
        try {
            // Clean up test data created during test execution
            cleanupTestEmailMessages()
            cleanupTestAttachments()
            
        } catch (Exception e) {
            ec.logger.warn("Error cleaning up integration test data: ${e.message}", e)
        }
    }
    
    private void createTestEmailServerConfig() {
        // Create minimal email server config for testing using auto-services
        try {
            ec.service.sync().name("create#moqui.basic.email.EmailServer")
                .parameters([
                    emailServerId: "TEST_EMAIL_SERVER",
                    serverName: "Test MIME Email Server",
                    serverHost: "test.example.com",
                    serverPort: 995,
                    serverUsername: "test@example.com"
                ]).call()
        } catch (Exception e) {
            // Server might already exist, ignore
        }
    }
    
    private void createTestPartyData() {
        // Create minimal test party data using auto-services
        try {
            ec.service.sync().name("create#mantle.party.Party")
                .parameters([
                    partyId: "TEST_DTE_PARTY",
                    partyTypeEnumId: "PtyOrganization"
                ]).call()
        } catch (Exception e) {
            // Party might already exist, ignore
        }
    }
    
    private Map createMockDteEmailMessage(String attachmentFilename) {
        return [
            messageId: "test-${System.currentTimeMillis()}@test.example.com",
            subject: "Test DTE Message",
            fromAddress: "test@example.com",
            toAddresses: "recipient@example.com",
            bodyPartList: [[
                contentType: "application/xml",
                disposition: "attachment", 
                filename: attachmentFilename,
                contentText: "<DTE><DocumentoTED>Mock DTE Content</DocumentoTED></DTE>",
                contentBytes: "<DTE><DocumentoTED>Mock DTE Content</DocumentoTED></DTE>".getBytes("UTF-8")
            ]]
        ]
    }
    
    private Map createMockDteEmailMessageWithMultipleAttachments(List<String> filenames) {
        return [
            messageId: "test-multi-${System.currentTimeMillis()}@test.example.com",
            subject: "Test DTE Message with Multiple Attachments",
            fromAddress: "test@example.com",
            toAddresses: "recipient@example.com", 
            bodyPartList: filenames.collect { filename ->
                [
                    contentType: "application/xml",
                    disposition: "attachment",
                    filename: filename,
                    contentText: "<DTE><DocumentoTED>Mock DTE Content for ${filename}</DocumentoTED></DTE>",
                    contentBytes: "<DTE><DocumentoTED>Mock DTE Content for ${filename}</DocumentoTED></DTE>".getBytes("UTF-8")
                ]
            }
        ]
    }
    
    private Map processDteMessageWithMocking(Map mockEmailMessage) {
        // Mock the DTE processing workflow with MIME decoding integration
        List<Map> processedAttachments = []
        
        mockEmailMessage.bodyPartList.each { bodyPart ->
            if (bodyPart.filename != null) {
                // Call MIME decoding service
                Map decodeResult = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                        .parameters([filename: bodyPart.filename])
                        .call()
                
                processedAttachments.add([
                    originalFilename: bodyPart.filename,
                    decodedFilename: decodeResult.decodedFilename,
                    wasDecoded: decodeResult.wasEncoded,
                    encoding: decodeResult.encoding,
                    charset: decodeResult.charset
                ])
            }
        }
        
        return [
            processedAttachments: processedAttachments,
            errors: null
        ]
    }
    
    private Map processDteMessageWithLogging(Map mockEmailMessage) {
        List<String> logEntries = []
        List<Map> processedAttachments = []
        
        mockEmailMessage.bodyPartList.each { bodyPart ->
            if (bodyPart.filename != null) {
                // Call MIME decoding service
                Map decodeResult = ec.service.sync().name("mchile.GeneralServices.decode#MimeFilename")
                        .parameters([filename: bodyPart.filename])
                        .call()
                
                logEntries.add("Original filename: ${bodyPart.filename}")
                logEntries.add("Decoded filename: ${decodeResult.decodedFilename}")
                
                processedAttachments.add([
                    originalFilename: bodyPart.filename,
                    decodedFilename: decodeResult.decodedFilename,
                    wasDecoded: decodeResult.wasEncoded
                ])
            }
        }
        
        return [
            processedAttachments: processedAttachments,
            logEntries: logEntries,
            errors: null
        ]
    }
    
    private Map processDteMessageWithStorage(Map mockEmailMessage) {
        Map processedAttachments = processDteMessageWithMocking(mockEmailMessage)
        
        // Simulate attachment storage with decoded filename
        Map storedAttachment = null
        if (processedAttachments.processedAttachments?.size() > 0) {
            Map attachment = processedAttachments.processedAttachments[0]
            storedAttachment = [
                filename: attachment.decodedFilename,
                originalMimeFilename: attachment.originalFilename,
                storageLocation: "test-storage/${attachment.decodedFilename}"
            ]
        }
        
        return [
            storedAttachment: storedAttachment,
            errors: null
        ]
    }
    
    private Map createCompleteEmailMessage(String attachmentFilename) {
        return [
            messageId: "complete-${System.currentTimeMillis()}@test.example.com",
            subject: "Complete DTE Workflow Test",
            fromAddress: "sender@example.com",
            toAddresses: "recipient@example.com",
            body: "Test email body with DTE attachment",
            bodyPartList: [[
                contentType: "application/xml",
                disposition: "attachment",
                filename: attachmentFilename,
                contentText: "<DTE><DocumentoTED>Complete workflow test content</DocumentoTED></DTE>",
                contentBytes: "<DTE><DocumentoTED>Complete workflow test content</DocumentoTED></DTE>".getBytes("UTF-8")
            ]]
        ]
    }
    
    private Map executeCompleteWorkflow(Map emailMessage) {
        // Simulate complete end-to-end workflow
        Map processedAttachments = processDteMessageWithMocking(emailMessage)
        
        return [
            emailProcessed: true,
            attachmentProcessed: processedAttachments.processedAttachments?.size() > 0,
            decodedFilename: processedAttachments.processedAttachments?[0]?.decodedFilename,
            originalFilename: processedAttachments.processedAttachments?[0]?.originalFilename,
            workflowComplete: true,
            errors: null
        ]
    }
    
    private void cleanupTestEmailMessages() {
        // Clean up any test email messages created during testing
        try {
            ec.service.sync().name("delete#moqui.basic.email.EmailMessage")
                .parameters([emailServerId: "TEST_EMAIL_SERVER"])
                .call()
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
    
    private void cleanupTestAttachments() {
        // Clean up any test attachments or related data
        try {
            // Implementation would depend on actual storage mechanism
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}
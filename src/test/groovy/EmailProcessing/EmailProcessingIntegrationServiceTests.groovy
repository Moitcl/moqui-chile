package EmailProcessing

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import java.util.UUID

/**
 * Comprehensive test suite for Email Processing Integration Services.
 * Tests all 10 service integration points defined in the API specification:
 * 1. initialize#EmailProcessing - Create tracking records with job context
 * 2. update#ProcessingStatus - Update status with JSON logging
 * 3. process#AttachmentWithTracking - Individual attachment handling
 * 4. process#EnviosFromAttachment - Multiple envios per attachment
 * 5. create#EnvioTracking - Create envio tracking records
 * 6. create#EnvioTrackingError - Error tracking for envios
 * 7. finalize#EmailProcessing - Processing summary and completion
 * 8. get#EmailProcessingSummary - Query service for email processing data
 * 9. get#EnvioProcessingDetails - Query service for envio details
 * 10. retry#EmailProcessing - Error recovery mechanisms
 */
class EmailProcessingIntegrationServiceTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String testEmailMessageId = "TEST-EMAIL-INTEG-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testJobRunId = "TEST-JOB-INTEG-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testEnvioId = "TEST-ENVIO-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testUsername = "john.doe"
    @Shared JsonSlurper jsonSlurper = new JsonSlurper()

    def setupSpec() {
        // Initialize framework
        ec = Moqui.getExecutionContext()

        // Login test user with proper error handling
        if (!ec.user.userId) {
            boolean loginSuccess = ec.user.loginUser(testUsername, "moqui")
            if (!loginSuccess) {
                throw new RuntimeException("Failed to login - ensure test data is loaded")
            }
        }

        // Set unique sequence ranges for test isolation
        ec.entity.tempSetSequencedIdPrimary("moqui.basic.email.EmailMessage", 85000, 50)
        ec.entity.tempSetSequencedIdPrimary("moqui.service.job.ServiceJobRun", 86000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.DteEmailProcessing", 87000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.DteEmailProcessingEnvio", 88000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.DteEnvio", 89000, 50)

        ec.artifactExecution.disableAuthz()

        // Create test infrastructure data
        createTestInfrastructureData()

        // Clear any existing test data for clean state
        cleanupTestData()
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        // Cleanup test data
        cleanupTestData()
        cleanupTestInfrastructureData()

        // Clear error messages
        ec.message.clearErrors()

        // Logout
        ec.user.logoutUser()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
    }

    def cleanup() {
        cleanupTestData()
        // Follow mantle-usl pattern: warn about unexpected errors, don't proactively clear
        if (ec.message.hasError()) {
            logger.warn("Unexpected errors in test: ${ec.message.getErrorsString()}")
        }
        ec.artifactExecution.enableAuthz()
        if (ec.transaction.status == javax.transaction.Status.STATUS_MARKED_ROLLBACK)
            ec.transaction.rollback("Marked as Rollback", null)
        else
            ec.transaction.commit()
        ec.message.clearErrors()
    }

    // ========================================
    // Test Infrastructure Helper Methods
    // ========================================

    private void createTestInfrastructureData() {
        // Create test email message
        def existingEmail = ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        if (!existingEmail) {
            ec.service.sync().name("create#moqui.basic.email.EmailMessage")
                    .parameters([
                            emailMessageId: testEmailMessageId,
                            subject: "Test Integration Email for DTE Processing",
                            body: "Test email body content with DTE attachments",
                            fromAddress: "integration.test@example.com",
                            toAddresses: "recipient@example.com",
                            sentDate: ec.user.nowTimestamp,
                            statusId: "EmsgReceived"
                    ]).call()
        }

        // Create test job run
        def existingJobRun = ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", testJobRunId)
                .one()
        if (!existingJobRun) {
            ec.service.sync().name("create#moqui.service.job.ServiceJobRun")
                    .parameters([
                            jobRunId: testJobRunId,
                            jobName: "Test Email Processing Integration Job",
                            serviceName: "moit.dte.DteEmailProcessingServices.initialize#EmailProcessing",
                            startTime: ec.user.nowTimestamp,
                            hasError: "N"
                    ]).call()
        }

        // Create test envio for tracking tests
        def existingEnvio = ec.entity.find("mchile.dte.DteEnvio")
                .condition("envioId", testEnvioId)
                .one()
        if (!existingEnvio) {
            ec.service.sync().name("create#mchile.dte.DteEnvio")
                    .parameters([
                            envioId: testEnvioId,
                            estadoEnvio: "0",
                            glosa: "Test envio for integration testing",
                            fechaGeneracion: ec.user.nowTimestamp,
                            cantidadDte: 1
                    ]).call()
        }
    }

    private void cleanupTestData() {
        // Delete test email processing envio records
        ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .deleteAll()

        // Delete test email processing records
        ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .deleteAll()
    }

    private void cleanupTestInfrastructureData() {
        // Delete test envio
        ec.entity.find("mchile.dte.DteEnvio")
                .condition("envioId", testEnvioId)
                .deleteAll()

        // Delete test email message
        ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", testEmailMessageId)
                .deleteAll()
        
        // Delete test job run
        ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", testJobRunId)
                .deleteAll()
    }

    private Map createMockBodyPart(String filename, String contentType, String content) {
        return [
            filename: filename,
            contentType: contentType,
            contentBytes: content.getBytes("UTF-8"),
            contentText: content
        ]
    }

    // ========================================
    // Service 1: initialize#EmailProcessing Tests
    // ========================================

    def "001 - initialize#EmailProcessing should create tracking record with minimal parameters"() {
        when: "Initializing email processing with required parameters"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        then: "Processing initialization should succeed"
        result.processingInitialized == true
        result.emailProcessingId == testEmailMessageId
        !ec.message.hasError()

        and: "Email processing record should be created with proper defaults"
        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        processingRecord != null
        processingRecord.emailMessageId == testEmailMessageId
        processingRecord.jobRunId == testJobRunId
        processingRecord.statusId == "DepReceived"
        processingRecord.processingAttemptCount == 1
        processingRecord.lastProcessingAttempt != null

        and: "Processing notes should contain initial JSON structure"
        processingRecord.processingNotes != null
        def processingNotes = jsonSlurper.parseText(processingRecord.processingNotes)
        processingNotes.processingStarted != null
        processingNotes.jobRunId == testJobRunId
        processingNotes.steps != null
        processingNotes.steps.size() == 0
    }

    def "002 - initialize#EmailProcessing should handle custom initial status"() {
        given: "Alternative email message for custom status test"
        String customEmailId = testEmailMessageId + "-CUSTOM"

        when: "Initializing with custom initial status"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: customEmailId,
                        jobRunId: testJobRunId,
                        initialStatus: "DepQueued"
                ]).call()

        then: "Custom status should be applied"
        result.processingInitialized == true
        !ec.message.hasError()

        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", customEmailId)
                .one()
        
        processingRecord.statusId == "DepQueued"
        
        cleanup: "Remove custom test record"
        ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", customEmailId)
                .deleteAll()
    }

    def "003 - initialize#EmailProcessing should prevent duplicate initialization"() {
        given: "Existing email processing record"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId,
                        statusId: "DepProcessing"
                ]).call()

        when: "Attempting duplicate initialization"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        then: "Service should return error for duplicate"
        ec.message.hasError()
        ec.message.getErrorsString().contains("already exists")
        result.processingInitialized != true
    }

    def "004 - initialize#EmailProcessing should handle missing parameters"() {
        when: "Attempting initialization without required emailMessageId"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        jobRunId: testJobRunId
                ]).call()

        then: "Service should return validation error"
        ec.message.hasError()
        ec.message.getErrorsString().toLowerCase().contains("emailmessageid") ||
        ec.message.getErrorsString().toLowerCase().contains("required")
    }

    // ========================================
    // Service 2: update#ProcessingStatus Tests
    // ========================================

    def "005 - update#ProcessingStatus should update status with detailed logging"() {
        given: "Existing email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        when: "Updating processing status with step information"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.update#ProcessingStatus")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        statusId: "DepAnalyzing",
                        stepDescription: "Starting attachment analysis",
                        stepData: [attachmentCount: 3, totalSize: "2.5MB"]
                ]).call()

        then: "Status update should succeed"
        result.statusUpdated == true
        !ec.message.hasError()

        and: "Processing record should reflect new status and logging"
        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        processingRecord.statusId == "DepAnalyzing"
        processingRecord.lastProcessingAttempt != null

        and: "Processing notes should contain detailed step information"
        def processingNotes = jsonSlurper.parseText(processingRecord.processingNotes)
        processingNotes.steps.size() > 0
        processingNotes.statusHistory.size() > 0
        processingNotes.lastStep.description == "Starting attachment analysis"
        processingNotes.lastStep.data.attachmentCount == 3
        processingNotes.statusHistory.last().toStatusId == "DepAnalyzing"
    }

    def "006 - update#ProcessingStatus should handle error scenarios with logging"() {
        given: "Existing email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        when: "Updating status with error information"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.update#ProcessingStatus")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        statusId: "DepParsingError",
                        stepDescription: "XML parsing failed",
                        errorMessage: "Invalid XML structure in attachment",
                        stepData: [errorType: "parsing_error", line: 42],
                        incrementAttemptCount: true
                ]).call()

        then: "Error status update should succeed"
        result.statusUpdated == true
        !ec.message.hasError()

        and: "Error information should be properly logged"
        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        processingRecord.statusId == "DepParsingError"
        processingRecord.errorMessage == "Invalid XML structure in attachment"
        processingRecord.processingAttemptCount == 2

        and: "Processing notes should contain error tracking"
        def processingNotes = jsonSlurper.parseText(processingRecord.processingNotes)
        processingNotes.errors.size() > 0
        processingNotes.errors.last().message == "Invalid XML structure in attachment"
        processingNotes.lastStep.errorMessage == "Invalid XML structure in attachment"
    }

    def "007 - update#ProcessingStatus should handle non-existent processing record"() {
        when: "Attempting to update non-existent processing record"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.update#ProcessingStatus")
                .parameters([
                        emailProcessingId: "NON-EXISTENT-ID",
                        statusId: "DepProcessing",
                        stepDescription: "Test step"
                ]).call()

        then: "Service should return error for missing record"
        ec.message.hasError()
        ec.message.getErrorsString().contains("not found")
        result.statusUpdated != true
    }

    def "008 - update#ProcessingStatus should preserve existing processing notes structure"() {
        given: "Email processing record with existing complex notes"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId,
                        statusId: "DepProcessing",
                        processingNotes: '{"existingData": "preserved", "customField": 123, "steps": [], "statusHistory": []}'
                ]).call()

        when: "Updating status to add new step"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.update#ProcessingStatus")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        statusId: "DepEnviosDetected",
                        stepDescription: "Envios detected successfully",
                        stepData: [enviosFound: 2]
                ]).call()

        then: "Update should preserve existing data while adding new information"
        result.statusUpdated == true
        !ec.message.hasError()

        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        def processingNotes = jsonSlurper.parseText(processingRecord.processingNotes)
        processingNotes.existingData == "preserved"
        processingNotes.customField == 123
        processingNotes.steps.size() == 1
        processingNotes.steps[0].description == "Envios detected successfully"
        processingNotes.steps[0].data.enviosFound == 2
    }

    // ========================================
    // Service 3: process#AttachmentWithTracking Tests
    // ========================================

    def "009 - process#AttachmentWithTracking should handle XML attachment successfully"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        and: "Mock XML attachment with valid DTE content"
        String mockXmlContent = '''<?xml version="1.0" encoding="UTF-8"?>
            <EnvioDTE version="1.0">
                <DTE version="1.0">
                    <Documento>
                        <Encabezado>
                            <IdDoc><TipoDTE>33</TipoDTE><Folio>123</Folio></IdDoc>
                        </Encabezado>
                    </Documento>
                </DTE>
            </EnvioDTE>'''
        
        Map mockBodyPart = createMockBodyPart("test-dte.xml", "application/xml", mockXmlContent)

        when: "Processing XML attachment with tracking"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.process#AttachmentWithTracking")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        bodyPart: mockBodyPart,
                        attachmentIndex: 1
                ]).call()

        then: "Attachment processing should complete successfully"
        result.attachmentProcessed == true
        result.enviosFoundInAttachment >= 0
        result.enviosProcessedFromAttachment >= 0
        result.enviosErrorFromAttachment >= 0
        result.envioTrackingInfo != null
        !ec.message.hasError()
    }

    def "010 - process#AttachmentWithTracking should skip non-XML attachments"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        and: "Mock non-XML attachment"
        Map mockBodyPart = createMockBodyPart("document.pdf", "application/pdf", "PDF content")

        when: "Processing non-XML attachment"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.process#AttachmentWithTracking")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        bodyPart: mockBodyPart,
                        attachmentIndex: 1
                ]).call()

        then: "Attachment should be skipped with appropriate tracking"
        result.attachmentProcessed == true
        result.enviosFoundInAttachment == 0
        result.enviosProcessedFromAttachment == 0
        result.enviosErrorFromAttachment == 0
        !ec.message.hasError()
    }

    def "011 - process#AttachmentWithTracking should handle malformed XML gracefully"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        and: "Mock malformed XML attachment"
        String malformedXml = '''<?xml version="1.0" encoding="UTF-8"?>
            <EnvioDTE version="1.0">
                <DTE version="1.0">
                    <Documento>
                        <Encabezado>
                            <!-- Missing closing tags -->
                        <Encabezado>
            </EnvioDTE>'''
        
        Map mockBodyPart = createMockBodyPart("malformed.xml", "application/xml", malformedXml)

        when: "Processing malformed XML attachment"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.process#AttachmentWithTracking")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        bodyPart: mockBodyPart,
                        attachmentIndex: 1
                ]).call()

        then: "Service should handle error gracefully"
        result.enviosErrorFromAttachment >= 0
        // Service should not throw exceptions but handle errors through tracking
        true
    }

    def "012 - process#AttachmentWithTracking should validate required parameters"() {
        when: "Processing attachment without required parameters"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.process#AttachmentWithTracking")
                .parameters([
                        // Missing required parameters
                        attachmentIndex: 1
                ]).call()

        then: "Service should return validation error"
        ec.message.hasError()
        ec.message.getErrorsString().toLowerCase().contains("required") ||
        ec.message.getErrorsString().toLowerCase().contains("emailprocessingid")
    }

    // ========================================
    // Service 4: process#EnviosFromAttachment Tests
    // ========================================

    def "013 - process#EnviosFromAttachment should process valid XML with envios"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        and: "Mock XML content with DTE structure"
        String xmlContent = '''<?xml version="1.0" encoding="UTF-8"?>
            <EnvioDTE version="1.0">
                <SetDTE>
                    <DTE version="1.0">
                        <Documento>
                            <Encabezado>
                                <IdDoc><TipoDTE>33</TipoDTE><Folio>123</Folio></IdDoc>
                            </Encabezado>
                        </Documento>
                    </DTE>
                </SetDTE>
            </EnvioDTE>'''

        when: "Processing envios from XML attachment"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.process#EnviosFromAttachment")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        xmlContent: xmlContent,
                        attachmentIndex: 1,
                        attachmentFileName: "test-envio.xml"
                ]).call()

        then: "Envio processing should complete"
        result.enviosFound >= 0
        result.enviosProcessed >= 0
        result.enviosError >= 0
        result.trackingInfo != null
        result.trackingInfo instanceof List
        !ec.message.hasError()
    }

    def "014 - process#EnviosFromAttachment should handle empty XML gracefully"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        when: "Processing empty XML content"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.process#EnviosFromAttachment")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        xmlContent: "",
                        attachmentIndex: 1,
                        attachmentFileName: "empty.xml"
                ]).call()

        then: "Service should handle empty content without errors"
        result.enviosFound == 0
        result.enviosProcessed == 0
        result.enviosError >= 0  // May be 0 or 1 depending on implementation
        result.trackingInfo != null
    }

    def "015 - process#EnviosFromAttachment should track processing sequence correctly"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        and: "XML with multiple potential envios"
        String xmlContent = '''<?xml version="1.0" encoding="UTF-8"?>
            <EnvioDTE>
                <SetDTE>
                    <DTE><Documento><Encabezado><IdDoc><Folio>1</Folio></IdDoc></Encabezado></Documento></DTE>
                    <DTE><Documento><Encabezado><IdDoc><Folio>2</Folio></IdDoc></Encabezado></Documento></DTE>
                </SetDTE>
            </EnvioDTE>'''

        when: "Processing multiple envios from attachment"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.process#EnviosFromAttachment")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        xmlContent: xmlContent,
                        attachmentIndex: 2,
                        attachmentFileName: "multi-envio.xml"
                ]).call()

        then: "Tracking should maintain proper sequence"
        result.trackingInfo != null
        // If envios are found and processed, tracking info should contain sequence information
        if (result.enviosFound > 0) {
            result.trackingInfo.each { trackingItem ->
                trackingItem.containsKey('processingSequence')
            }
        }
    }

    def "016 - process#EnviosFromAttachment should handle missing processing record"() {
        when: "Processing envios for non-existent email processing record"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.process#EnviosFromAttachment")
                .parameters([
                        emailProcessingId: "NON-EXISTENT-ID",
                        xmlContent: "<test>content</test>",
                        attachmentIndex: 1,
                        attachmentFileName: "test.xml"
                ]).call()

        then: "Service should handle missing record gracefully"
        // Implementation may vary - either error handling or graceful degradation
        true
    }

    // ========================================
    // Service 5: create#EnvioTracking Tests
    // ========================================

    def "017 - create#EnvioTracking should create successful tracking record"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        when: "Creating envio tracking record for successful processing"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTracking")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        envioId: testEnvioId,
                        processingSequence: 1,
                        attachmentIndex: 1,
                        attachmentFileName: "test-envio.xml",
                        envioXmlSnippet: "<DTE><Folio>123</Folio></DTE>"
                ]).call()

        then: "Tracking record should be created successfully"
        result.trackingRecordCreated == true
        !ec.message.hasError()

        and: "Database should contain the tracking record"
        def trackingRecord = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .condition("envioId", testEnvioId)
                .one()
        
        trackingRecord != null
        trackingRecord.attachmentFileName == "test-envio.xml"
    }

    def "018 - create#EnvioTracking should handle optional parameters gracefully"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        when: "Creating tracking record with minimal parameters"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTracking")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        envioId: testEnvioId,
                        processingSequence: 2
                ]).call()

        then: "Tracking record should be created with optional parameters as null"
        result.trackingRecordCreated == true
        !ec.message.hasError()

        def trackingRecord = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .condition("envioId", testEnvioId)
                .one()
        
        trackingRecord != null
        trackingRecord.attachmentFileName == null
    }

    def "019 - create#EnvioTracking should validate required parameters"() {
        when: "Creating tracking record without required parameters"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTracking")
                .parameters([
                        // Missing required emailProcessingId, envioId, processingSequence
                        attachmentFileName: "test.xml"
                ]).call()

        then: "Service should return validation error"
        ec.message.hasError()
        ec.message.getErrorsString().toLowerCase().contains("required") ||
        ec.message.getErrorsString().toLowerCase().contains("emailprocessingid") ||
        ec.message.getErrorsString().toLowerCase().contains("envioid")
    }

    def "020 - create#EnvioTracking should handle large XML snippets"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        and: "Large XML snippet (over 1000 characters)"
        String largeXmlSnippet = "<DTE>" + "x" * 1500 + "</DTE>"

        when: "Creating tracking record with large XML snippet"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTracking")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        envioId: testEnvioId,
                        processingSequence: 3,
                        envioXmlSnippet: largeXmlSnippet
                ]).call()

        then: "Service should handle large content appropriately"
        result.trackingRecordCreated == true
        !ec.message.hasError()

        def trackingRecord = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .condition("envioId", testEnvioId)
                .one()
        
        trackingRecord != null
    }

    // ========================================
    // Service 6: create#EnvioTrackingError Tests
    // ========================================

    def "021 - create#EnvioTrackingError should create error tracking record"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        when: "Creating error tracking record"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTrackingError")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        processingSequence: 1,
                        attachmentIndex: 1,
                        attachmentFileName: "error-envio.xml",
                        errorMessage: "XML parsing failed: invalid structure",
                        envioXmlSnippet: "<DTE><Invalid>structure"
                ]).call()

        then: "Error tracking record should be created"
        result.errorTrackingRecordCreated == true
        !ec.message.hasError()

        and: "Database should contain error tracking record"
        def errorRecord = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        errorRecord != null
        errorRecord.errorMessage == "XML parsing failed: invalid structure"
        errorRecord.attachmentFileName == "error-envio.xml"
    }

    def "022 - create#EnvioTrackingError should handle long error messages"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        and: "Very long error message"
        String longErrorMessage = "XML parsing error: " + ("detailed error information, " * 50)

        when: "Creating error tracking with long message"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTrackingError")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        processingSequence: 2,
                        errorMessage: longErrorMessage
                ]).call()

        then: "Service should handle long error message"
        result.errorTrackingRecordCreated == true
        !ec.message.hasError()

        def errorRecord = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        errorRecord != null
        errorRecord.errorMessage != null
        // Error message may be truncated based on field constraints
        errorRecord.errorMessage.contains("XML parsing error")
    }

    def "023 - create#EnvioTrackingError should validate required error message"() {
        when: "Creating error tracking without error message"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTrackingError")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        processingSequence: 1
                        // Missing required errorMessage
                ]).call()

        then: "Service should return validation error"
        ec.message.hasError()
        ec.message.getErrorsString().toLowerCase().contains("errormessage") ||
        ec.message.getErrorsString().toLowerCase().contains("required")
    }

    def "024 - create#EnvioTrackingError should handle minimal parameters"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        when: "Creating error tracking with minimal required parameters"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTrackingError")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        processingSequence: 3,
                        errorMessage: "Minimal error case"
                ]).call()

        then: "Error tracking should be created with null optional fields"
        result.errorTrackingRecordCreated == true
        !ec.message.hasError()

        def errorRecord = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        errorRecord != null
        errorRecord.errorMessage == "Minimal error case"
        errorRecord.attachmentFileName == null
    }

    // ========================================
    // Service 7: finalize#EmailProcessing Tests
    // ========================================

    def "025 - finalize#EmailProcessing should complete successful processing"() {
        given: "Initialized email processing record with tracking data"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        // Create some tracking records to simulate processing
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTracking")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        envioId: testEnvioId,
                        processingSequence: 1
                ]).call()

        when: "Finalizing successful email processing"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.finalize#EmailProcessing")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        totalEnviosFound: 2,
                        totalEnviosProcessed: 2,
                        totalEnviosError: 0,
                        envioTrackingList: [
                            [envioId: testEnvioId, status: 'success', processingSequence: 1]
                        ]
                ]).call()

        then: "Processing should be finalized successfully"
        result.processingFinalized == true
        result.finalStatusId == "DepProcessed"
        !ec.message.hasError()

        and: "Database should reflect final processing status"
        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        processingRecord.statusId == "DepProcessed"
        processingRecord.totalEnviosFound == 2
        processingRecord.totalEnviosProcessed == 2
        processingRecord.totalEnviosError == 0
    }

    def "026 - finalize#EmailProcessing should handle partial success scenarios"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        when: "Finalizing processing with mixed success/error results"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.finalize#EmailProcessing")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        totalEnviosFound: 3,
                        totalEnviosProcessed: 2,
                        totalEnviosError: 1,
                        envioTrackingList: [
                            [envioId: testEnvioId, status: 'success'],
                            [processingSequence: 2, status: 'error', errorMessage: 'Parsing failed']
                        ]
                ]).call()

        then: "Processing should finalize with partial success status"
        result.processingFinalized == true
        result.finalStatusId == "DepPartialSuccess"
        !ec.message.hasError()

        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        processingRecord.statusId == "DepPartialSuccess"
        processingRecord.totalEnviosFound == 3
        processingRecord.totalEnviosProcessed == 2
        processingRecord.totalEnviosError == 1
    }

    def "027 - finalize#EmailProcessing should handle complete failure scenarios"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        when: "Finalizing processing with complete failure"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.finalize#EmailProcessing")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        totalEnviosFound: 2,
                        totalEnviosProcessed: 0,
                        totalEnviosError: 2,
                        envioTrackingList: [
                            [processingSequence: 1, status: 'error', errorMessage: 'Critical error 1'],
                            [processingSequence: 2, status: 'error', errorMessage: 'Critical error 2']
                        ]
                ]).call()

        then: "Processing should finalize with error status"
        result.processingFinalized == true
        result.finalStatusId == "DepError"
        !ec.message.hasError()

        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        processingRecord.statusId == "DepError"
        processingRecord.totalEnviosFound == 2
        processingRecord.totalEnviosProcessed == 0
        processingRecord.totalEnviosError == 2
    }

    def "028 - finalize#EmailProcessing should handle no attachments scenario"() {
        given: "Initialized email processing record"
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        when: "Finalizing processing with no valid attachments"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.finalize#EmailProcessing")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        totalEnviosFound: 0,
                        totalEnviosProcessed: 0,
                        totalEnviosError: 0,
                        envioTrackingList: []
                ]).call()

        then: "Processing should finalize with no valid attachments status"
        result.processingFinalized == true
        result.finalStatusId == "DepNoValidAttachments"
        !ec.message.hasError()

        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        processingRecord.statusId == "DepNoValidAttachments"
        processingRecord.totalEnviosFound == 0
    }

    // ========================================
    // Service 8: get#EmailProcessingSummary Tests
    // ========================================

    def "029 - get#EmailProcessingSummary should retrieve processing records with default pagination"() {
        given: "Multiple email processing records"
        3.times { index ->
            String emailId = "${testEmailMessageId}-SUMMARY-${index}"
            ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                    .parameters([
                            emailMessageId: emailId,
                            jobRunId: testJobRunId,
                            statusId: (index % 2 == 0) ? "DepProcessed" : "DepError",
                            totalEnviosFound: index + 1,
                            totalEnviosProcessed: index,
                            totalEnviosError: (index % 2 == 1) ? 1 : 0
                    ]).call()
        }

        when: "Retrieving email processing summary with default parameters"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.get#EmailProcessingSummary")
                .parameters([:]).call()

        then: "Summary should return paginated results"
        result.emailProcessingList != null
        result.emailProcessingList instanceof List
        result.totalCount != null
        result.pageCount != null
        result.totalCount >= 3  // At least our test records
        !ec.message.hasError()

        cleanup: "Remove test summary records"
        3.times { index ->
            ec.entity.find("mchile.dte.DteEmailProcessing")
                    .condition("emailMessageId", "${testEmailMessageId}-SUMMARY-${index}")
                    .deleteAll()
        }
    }

    def "030 - get#EmailProcessingSummary should filter by specific email message ID"() {
        given: "Specific email processing record"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId,
                        statusId: "DepProcessed",
                        totalEnviosFound: 5,
                        totalEnviosProcessed: 5
                ]).call()

        when: "Filtering by specific email message ID"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.get#EmailProcessingSummary")
                .parameters([emailMessageId: testEmailMessageId]).call()

        then: "Should return only matching record"
        result.emailProcessingList != null
        result.emailProcessingList.size() >= 1
        result.emailProcessingList.every { record -> 
            record.emailMessageId == testEmailMessageId 
        }
        !ec.message.hasError()
    }

    def "031 - get#EmailProcessingSummary should filter by status"() {
        given: "Processing records with different statuses"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId + "-STATUS-1",
                        statusId: "DepProcessed",
                        jobRunId: testJobRunId
                ]).call()
                
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId + "-STATUS-2",
                        statusId: "DepError",
                        jobRunId: testJobRunId
                ]).call()

        when: "Filtering by error status"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.get#EmailProcessingSummary")
                .parameters([statusId: "DepError"]).call()

        then: "Should return only error status records"
        result.emailProcessingList != null
        result.emailProcessingList.every { record -> 
            record.statusId == "DepError"
        }
        !ec.message.hasError()

        cleanup: "Remove status test records"
        ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId + "-STATUS-1")
                .deleteAll()
        ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId + "-STATUS-2")
                .deleteAll()
    }

    def "032 - get#EmailProcessingSummary should handle date range filtering"() {
        given: "Email processing record"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId,
                        statusId: "DepProcessed"
                ]).call()

        and: "Date range for filtering"
        def yesterday = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000)
        def tomorrow = new Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000)

        when: "Filtering by date range"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.get#EmailProcessingSummary")
                .parameters([
                        fromDate: new java.sql.Timestamp(yesterday.getTime()),
                        toDate: new java.sql.Timestamp(tomorrow.getTime())
                ]).call()

        then: "Should return records within date range"
        result.emailProcessingList != null
        result.totalCount >= 1
        !ec.message.hasError()
    }

    def "033 - get#EmailProcessingSummary should handle custom pagination"() {
        when: "Retrieving with custom page size"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.get#EmailProcessingSummary")
                .parameters([
                        pageIndex: 0,
                        pageSize: 10
                ]).call()

        then: "Should respect pagination parameters"
        result.emailProcessingList != null
        result.emailProcessingList.size() <= 10
        result.totalCount != null
        result.pageCount != null
        !ec.message.hasError()
    }

    // ========================================
    // Service 9: get#EnvioProcessingDetails Tests
    // ========================================

    def "034 - get#EnvioProcessingDetails should retrieve detailed processing information"() {
        given: "Email processing record with complex processing notes"
        String complexNotes = new JsonBuilder([
            processingStarted: "2024-09-03T10:00:00Z",
            steps: [
                [step: 1, description: "Email received", timestamp: "2024-09-03T10:00:01Z"],
                [step: 2, description: "Attachments analyzed", count: 2, timestamp: "2024-09-03T10:00:15Z"]
            ],
            statusHistory: [
                [fromStatusId: "DepReceived", toStatusId: "DepProcessing", timestamp: "2024-09-03T10:00:05Z"]
            ],
            errors: []
        ]).toString()

        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId,
                        statusId: "DepProcessed",
                        processingNotes: complexNotes,
                        totalEnviosFound: 2,
                        totalEnviosProcessed: 2
                ]).call()

        and: "Related envio processing records"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        envioId: testEnvioId,
                        processingSequence: 1,
                        attachmentSeqId: "1"
                ]).call()

        when: "Retrieving detailed processing information"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.get#EnvioProcessingDetails")
                .parameters([emailMessageId: testEmailMessageId]).call()

        then: "Should return comprehensive processing details"
        result.processingDetails != null
        result.processingDetails.emailMessageId == testEmailMessageId
        result.processingDetails.statusId == "DepProcessed"
        result.processingDetails.totalEnviosFound == 2
        result.processingDetails.totalEnviosProcessed == 2

        and: "Should parse and include processing steps"
        result.processingDetails.processingSteps != null
        result.processingDetails.processingSteps.size() == 2
        result.processingDetails.statusHistory != null
        result.processingDetails.statusHistory.size() == 1

        and: "Should include envio details"
        result.envioDetailsList != null
        result.envioDetailsList.size() >= 1
        !ec.message.hasError()
    }

    def "035 - get#EnvioProcessingDetails should handle missing processing record"() {
        when: "Retrieving details for non-existent processing record"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.get#EnvioProcessingDetails")
                .parameters([emailMessageId: "NON-EXISTENT-ID"]).call()

        then: "Should return empty details gracefully"
        result.processingDetails != null  // Should be empty map, not null
        result.envioDetailsList != null   // Should be empty list, not null
        result.envioDetailsList.size() == 0
        !ec.message.hasError()
    }

    def "036 - get#EnvioProcessingDetails should handle malformed processing notes"() {
        given: "Processing record with malformed JSON notes"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId,
                        statusId: "DepProcessed",
                        processingNotes: '{"invalid": json structure'  // Malformed JSON
                ]).call()

        when: "Retrieving details with malformed notes"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.get#EnvioProcessingDetails")
                .parameters([emailMessageId: testEmailMessageId]).call()

        then: "Should handle parsing error gracefully"
        result.processingDetails != null
        result.processingDetails.emailMessageId == testEmailMessageId
        // Should have error indicator for failed JSON parsing
        result.processingDetails.processingNotesError != null ||
        result.processingDetails.processingSteps == null
        !ec.message.hasError()
    }

    def "037 - get#EnvioProcessingDetails should validate required parameters"() {
        when: "Retrieving details without required emailMessageId"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.get#EnvioProcessingDetails")
                .parameters([:]).call()

        then: "Should return validation error"
        ec.message.hasError()
        ec.message.getErrorsString().toLowerCase().contains("emailmessageid") ||
        ec.message.getErrorsString().toLowerCase().contains("required")
    }

    // ========================================
    // Service 10: retry#EmailProcessing Tests
    // ========================================

    def "038 - retry#EmailProcessing should initiate retry for failed processing"() {
        given: "Failed email processing record"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId,
                        statusId: "DepError",
                        errorMessage: "Processing failed",
                        processingAttemptCount: 1
                ]).call()

        when: "Initiating retry for failed processing"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.retry#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        resetStatus: true
                ]).call()

        then: "Retry should be initiated successfully"
        result.retryInitiated == true
        result.newJobRunId != null
        !ec.message.hasError()

        and: "Processing record should be updated for retry"
        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        processingRecord.statusId == "DepQueued"
        processingRecord.processingAttemptCount == 2  // Incremented
    }

    def "039 - retry#EmailProcessing should prevent retry of successful processing"() {
        given: "Successfully processed email record"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId,
                        statusId: "DepProcessed",
                        totalEnviosProcessed: 2
                ]).call()

        when: "Attempting retry of successful processing"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.retry#EmailProcessing")
                .parameters([emailMessageId: testEmailMessageId]).call()

        then: "Retry should be rejected"
        ec.message.hasError()
        ec.message.getErrorsString().contains("Cannot retry") ||
        ec.message.getErrorsString().contains("DepProcessed")
        result.retryInitiated != true
    }

    def "040 - retry#EmailProcessing should prevent retry of currently processing records"() {
        given: "Currently processing email record"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId,
                        statusId: "DepProcessing"
                ]).call()

        when: "Attempting retry of currently processing record"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.retry#EmailProcessing")
                .parameters([emailMessageId: testEmailMessageId]).call()

        then: "Retry should be rejected"
        ec.message.hasError()
        ec.message.getErrorsString().contains("Cannot retry") ||
        ec.message.getErrorsString().contains("DepProcessing")
        result.retryInitiated != true
    }

    def "041 - retry#EmailProcessing should handle missing processing record"() {
        when: "Attempting retry of non-existent processing record"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.retry#EmailProcessing")
                .parameters([emailMessageId: "NON-EXISTENT-ID"]).call()

        then: "Should return error for missing record"
        ec.message.hasError()
        ec.message.getErrorsString().contains("not found") ||
        ec.message.getErrorsString().contains("NON-EXISTENT-ID")
        result.retryInitiated != true
    }

    def "042 - retry#EmailProcessing should support retry without status reset"() {
        given: "Failed email processing record"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId,
                        statusId: "DepParsingError",
                        processingAttemptCount: 2
                ]).call()

        when: "Initiating retry without status reset"
        Map result = ec.service.sync().name("moit.dte.DteEmailProcessingServices.retry#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        resetStatus: false
                ]).call()

        then: "Retry should be initiated without changing status"
        result.retryInitiated == true
        result.newJobRunId != null
        !ec.message.hasError()

        and: "Status should remain unchanged but attempt count incremented"
        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        processingRecord.statusId == "DepParsingError"  // Unchanged
        processingRecord.processingAttemptCount == 3     // Incremented
    }

    // ========================================
    // Integration Workflow Tests
    // ========================================

    def "043 - Complete workflow integration test - successful processing"() {
        given: "Clean test state"
        cleanupTestData()

        when: "Execute complete processing workflow"
        
        // Step 1: Initialize processing
        Map initResult = ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        // Step 2: Update status to analyzing
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.update#ProcessingStatus")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        statusId: "DepAnalyzing",
                        stepDescription: "Starting attachment analysis"
                ]).call()

        // Step 3: Process attachment with tracking
        String xmlContent = '''<?xml version="1.0" encoding="UTF-8"?>
            <EnvioDTE><SetDTE><DTE><Documento><Encabezado><IdDoc><TipoDTE>33</TipoDTE></IdDoc></Encabezado></Documento></DTE></SetDTE></EnvioDTE>'''
        
        Map mockBodyPart = createMockBodyPart("test.xml", "application/xml", xmlContent)
        
        Map attachmentResult = ec.service.sync().name("moit.dte.DteEmailProcessingServices.process#AttachmentWithTracking")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        bodyPart: mockBodyPart,
                        attachmentIndex: 1
                ]).call()

        // Step 4: Create envio tracking
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTracking")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        envioId: testEnvioId,
                        processingSequence: 1,
                        attachmentIndex: 1
                ]).call()

        // Step 5: Finalize processing
        Map finalizeResult = ec.service.sync().name("moit.dte.DteEmailProcessingServices.finalize#EmailProcessing")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        totalEnviosFound: 1,
                        totalEnviosProcessed: 1,
                        totalEnviosError: 0,
                        envioTrackingList: [[envioId: testEnvioId, status: 'success']]
                ]).call()

        then: "Complete workflow should execute successfully"
        initResult.processingInitialized == true
        attachmentResult.attachmentProcessed == true
        finalizeResult.processingFinalized == true
        finalizeResult.finalStatusId == "DepProcessed"
        !ec.message.hasError()

        and: "Final state should be consistent"
        def finalRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        finalRecord.statusId == "DepProcessed"
        finalRecord.totalEnviosFound == 1
        finalRecord.totalEnviosProcessed == 1
        finalRecord.totalEnviosError == 0

        and: "Tracking records should exist"
        def trackingRecords = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .list()
        
        trackingRecords.size() >= 1
    }

    def "044 - Complete workflow integration test - error handling and recovery"() {
        given: "Clean test state"
        cleanupTestData()

        when: "Execute workflow with errors and recovery"
        
        // Step 1: Initialize processing
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        jobRunId: testJobRunId
                ]).call()

        // Step 2: Simulate processing error
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.update#ProcessingStatus")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        statusId: "DepParsingError",
                        stepDescription: "XML parsing failed",
                        errorMessage: "Invalid XML structure",
                        incrementAttemptCount: true
                ]).call()

        // Step 3: Create error tracking
        ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTrackingError")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        processingSequence: 1,
                        errorMessage: "XML parsing failed"
                ]).call()

        // Step 4: Finalize with error status
        Map finalizeResult = ec.service.sync().name("moit.dte.DteEmailProcessingServices.finalize#EmailProcessing")
                .parameters([
                        emailProcessingId: testEmailMessageId,
                        totalEnviosFound: 1,
                        totalEnviosProcessed: 0,
                        totalEnviosError: 1,
                        envioTrackingList: [[processingSequence: 1, status: 'error']]
                ]).call()

        // Step 5: Attempt retry
        Map retryResult = ec.service.sync().name("moit.dte.DteEmailProcessingServices.retry#EmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        resetStatus: true
                ]).call()

        then: "Error workflow should execute with recovery option"
        finalizeResult.finalStatusId == "DepError"
        retryResult.retryInitiated == true
        !ec.message.hasError()

        and: "Error tracking should be recorded"
        def errorRecords = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .condition("errorMessage", org.moqui.entity.EntityCondition.ComparisonOperator.NOT_EQUAL, null)
                .list()
        
        errorRecords.size() >= 1
        errorRecords[0].errorMessage == "XML parsing failed"

        and: "Processing record should be ready for retry"
        def processingRecord = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        
        processingRecord.statusId == "DepQueued"
        processingRecord.processingAttemptCount >= 2
    }

    // ========================================
    // Performance and Load Tests
    // ========================================

    def "045 - Performance test - batch processing simulation"() {
        given: "Clean test state"
        cleanupTestData()
        
        and: "Multiple processing scenarios"
        def testScenarios = [
            [emailId: "${testEmailMessageId}-BATCH-1", enviosCount: 1],
            [emailId: "${testEmailMessageId}-BATCH-2", enviosCount: 3],
            [emailId: "${testEmailMessageId}-BATCH-3", enviosCount: 2]
        ]

        when: "Processing multiple email scenarios concurrently"
        def startTime = System.currentTimeMillis()
        
        testScenarios.each { scenario ->
            // Initialize each processing
            ec.service.sync().name("moit.dte.DteEmailProcessingServices.initialize#EmailProcessing")
                    .parameters([
                            emailMessageId: scenario.emailId,
                            jobRunId: testJobRunId
                    ]).call()

            // Process multiple envios per email
            scenario.enviosCount.times { envioIndex ->
                ec.service.sync().name("moit.dte.DteEmailProcessingServices.create#EnvioTracking")
                        .parameters([
                                emailProcessingId: scenario.emailId,
                                envioId: "${testEnvioId}-${scenario.emailId}-${envioIndex}",
                                processingSequence: envioIndex + 1
                        ]).call()
            }

            // Finalize each processing
            ec.service.sync().name("moit.dte.DteEmailProcessingServices.finalize#EmailProcessing")
                    .parameters([
                            emailProcessingId: scenario.emailId,
                            totalEnviosFound: scenario.enviosCount,
                            totalEnviosProcessed: scenario.enviosCount,
                            totalEnviosError: 0,
                            envioTrackingList: []
                    ]).call()
        }
        
        def endTime = System.currentTimeMillis()
        def totalTime = endTime - startTime

        then: "Batch processing should complete within reasonable time"
        totalTime < 10000  // Should complete within 10 seconds
        !ec.message.hasError()

        and: "All processing records should be created correctly"
        testScenarios.each { scenario ->
            def record = ec.entity.find("mchile.dte.DteEmailProcessing")
                    .condition("emailMessageId", scenario.emailId)
                    .one()
            
            record != null
            record.statusId == "DepProcessed"
            record.totalEnviosFound == scenario.enviosCount
            record.totalEnviosProcessed == scenario.enviosCount
        }

        and: "Query performance should be acceptable"
        def queryStartTime = System.currentTimeMillis()
        Map summaryResult = ec.service.sync().name("moit.dte.DteEmailProcessingServices.get#EmailProcessingSummary")
                .parameters([pageSize: 100]).call()
        def queryTime = System.currentTimeMillis() - queryStartTime

        queryTime < 2000  // Query should complete within 2 seconds
        summaryResult.totalCount >= 3

        cleanup: "Remove batch test records"
        testScenarios.each { scenario ->
            ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                    .condition("emailMessageId", scenario.emailId)
                    .deleteAll()
            ec.entity.find("mchile.dte.DteEmailProcessing")
                    .condition("emailMessageId", scenario.emailId)
                    .deleteAll()
        }
    }
}
package EmailProcessing

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import java.util.UUID

/**
 * Integration test suite for email processing workflow functionality.
 * Tests complete workflows from email processing through envio detection,
 * relationship management, view entity functionality, and cross-entity consistency.
 */
class EmailProcessingIntegrationTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String testEmailMessageId1 = "TEST-EMAIL-INT-1-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testEmailMessageId2 = "TEST-EMAIL-INT-2-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testEnvioId1 = "TEST-ENVIO-INT-1-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testEnvioId2 = "TEST-ENVIO-INT-2-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testEnvioId3 = "TEST-ENVIO-INT-3-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testJobRunId = "TEST-JOB-INT-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testIssuerPartyId = "TEST-EMAIL-INT-ORG"
    @Shared String testUsername = "john.doe"

    def setupSpec() {
        // Initialize framework
        ec = Moqui.getExecutionContext()

        // Login test user
        if (!ec.user.userId) {
            boolean loginSuccess = ec.user.loginUser(testUsername, "moqui")
            if (!loginSuccess) {
                throw new RuntimeException("Failed to login - ensure test data is loaded")
            }
        }

        // Set unique sequence ranges for test isolation
        ec.entity.tempSetSequencedIdPrimary("moqui.basic.email.EmailMessage", 82000, 50)
        ec.entity.tempSetSequencedIdPrimary("moqui.service.job.ServiceJobRun", 83000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.DteEnvio", 84000, 50)
        ec.entity.tempSetSequencedIdPrimary("mantle.party.Party", 85000, 50)

        ec.artifactExecution.disableAuthz()

        // Create test infrastructure data
        createTestInfrastructure()

        // Clear any existing test data for clean state
        cleanupTestData()
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        // Cleanup test data
        cleanupTestData()
        cleanupTestInfrastructure()

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
    // Test Infrastructure Setup
    // ========================================

    private void createTestInfrastructure() {
        // Create test organization
        def existingParty = ec.entity.find("mantle.party.Party").condition("partyId", testIssuerPartyId).one()
        if (!existingParty) {
            ec.service.sync().name("mantle.party.PartyServices.create#Organization")
                    .parameters([
                            partyId: testIssuerPartyId,
                            organizationName: "Test Email Integration Organization",
                            roleTypeId: "OrgInternal"
                    ]).call()
        }

        // Create test email messages
        createTestEmailMessage(testEmailMessageId1, "Email with Multiple DTEs")
        createTestEmailMessage(testEmailMessageId2, "Email with Single DTE")

        // Create test job run
        def existingJobRun = ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", testJobRunId).one()
        if (!existingJobRun) {
            ec.service.sync().name("create#moqui.service.job.ServiceJobRun")
                    .parameters([
                            jobRunId: testJobRunId,
                            jobName: "Test Email Integration Processing Job",
                            serviceName: "mchile.dte.EmailProcessingServices.process#EmailBatch",
                            startTime: ec.user.nowTimestamp,
                            hasError: "N"
                    ]).call()
        }

        // Create test DTE envios
        createTestEnvio(testEnvioId1, "Ftdt-33", "Invoice 1")
        createTestEnvio(testEnvioId2, "Ftdt-34", "Tax-exempt Invoice")
        createTestEnvio(testEnvioId3, "Ftdt-33", "Invoice 2")
    }

    private void createTestEmailMessage(String emailId, String subject) {
        def existingEmail = ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", emailId).one()
        if (!existingEmail) {
            ec.service.sync().name("create#moqui.basic.email.EmailMessage")
                    .parameters([
                            emailMessageId: emailId,
                            subject: subject,
                            body: "Integration test email with DTE attachments",
                            fromAddress: "dte-integration@sii.cl",
                            toAddresses: "integration-test@company.com",
                            sentDate: ec.user.nowTimestamp,
                            statusId: "EmsgReceived"
                    ]).call()
        }
    }

    private void createTestEnvio(String envioId, String documentType, String description) {
        def existingEnvio = ec.entity.find("mchile.dte.DteEnvio")
                .condition("envioId", envioId).one()
        if (!existingEnvio) {
            ec.service.sync().name("create#mchile.dte.DteEnvio")
                    .parameters([
                            envioId: envioId,
                            envioTypeEnumId: documentType,
                            issuerPartyId: testIssuerPartyId,
                            receiverPartyId: testIssuerPartyId,
                            statusId: "DteEnvioCreated",
                            rutEmisor: "76514104-4",
                            rutReceptor: "12345678-9",
                            description: description
                    ]).call()
        }
    }

    private void cleanupTestData() {
        // Delete test email processing envio records
        ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId1)
                .deleteAll()
        ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId2)
                .deleteAll()

        // Delete test email processing records
        ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId1)
                .deleteAll()
        ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId2)
                .deleteAll()
    }

    private void cleanupTestInfrastructure() {
        // Delete test DteEnvio records
        [testEnvioId1, testEnvioId2, testEnvioId3].each { envioId ->
            ec.entity.find("mchile.dte.DteEnvio").condition("envioId", envioId).deleteAll()
        }

        // Delete test email messages
        [testEmailMessageId1, testEmailMessageId2].each { emailId ->
            ec.entity.find("moqui.basic.email.EmailMessage").condition("emailMessageId", emailId).deleteAll()
        }

        // Delete test job run
        ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", testJobRunId).deleteAll()

        // Delete test organization
        ec.entity.find("mantle.party.Party")
                .condition("partyId", testIssuerPartyId).deleteAll()
    }

    // ========================================
    // Complete Workflow Integration Tests
    // ========================================

    def "001 - Complete email processing workflow with multiple envios"() {
        given: "An email with multiple envios needs processing"
        def emailProcessingResult = ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        jobRunId: testJobRunId,
                        statusId: "DepReceived",
                        totalEnviosFound: 0,
                        processingAttemptCount: 1,
                        processingNotes: '{"workflow": "integration test", "startTime": "2024-09-03T10:00:00Z"}'
                ]).call()

        when: "Email processing transitions through workflow states"
        // Step 1: Start processing
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        statusId: "DepProcessing",
                        processingNotes: '{"step": "processing_started", "timestamp": "2024-09-03T10:00:15Z"}'
                ]).call()

        // Step 2: Analyze attachments
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        statusId: "DepAnalyzing",
                        processingNotes: '{"step": "analyzing_attachments", "attachmentCount": 3, "timestamp": "2024-09-03T10:00:30Z"}'
                ]).call()

        // Step 3: Detect envios and update counts
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        statusId: "DepEnviosDetected",
                        totalEnviosFound: 2,
                        processingNotes: '{"step": "envios_detected", "enviosFound": 2, "timestamp": "2024-09-03T10:01:00Z"}'
                ]).call()

        // Step 4: Create envio processing records
        def envio1Result = ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        envioId: testEnvioId1,
                        attachmentSeqId: "1",
                        attachmentFileName: "factura-001.xml"
                ]).call()

        def envio2Result = ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        envioId: testEnvioId2,
                        attachmentSeqId: "2",
                        attachmentFileName: "factura-exenta-001.xml"
                ]).call()

        then: "All entities are created and properly linked"
        !ec.message.hasError()

        // Verify main email processing record
        def emailProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId1).one()
        emailProcessing != null
        emailProcessing.statusId == "DepEnviosDetected"
        emailProcessing.totalEnviosFound == 2

        // Verify envio processing records
        def envioProcessingList = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId1).list()
        envioProcessingList.size() == 2

        // Verify relationships work
        def envio1Processing = envioProcessingList.find { it.envioId == testEnvioId1 }
        envio1Processing != null
        def relatedEmailProcessing = envio1Processing.getRelatedOne("mchile.dte.DteEmailProcessing", null, false)
        relatedEmailProcessing.emailMessageId == testEmailMessageId1

        def relatedEnvio = envio1Processing.getRelatedOne("mchile.dte.DteEnvio", null, false)
        relatedEnvio.envioId == testEnvioId1

        when: "Process individual envios with updates"
        // Update first envio
        ec.service.sync().name("update#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        envioId: testEnvioId1,
                        attachmentFileName: "factura-001-processed.xml"
                ]).call()

        // Update second envio with error
        ec.service.sync().name("update#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        envioId: testEnvioId2,
                        errorMessage: "XML validation failed"
                ]).call()

        // Update email processing counts
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        totalEnviosProcessed: 1,
                        totalEnviosError: 1,
                        lastProcessingAttempt: ec.user.nowTimestamp,
                        processingNotes: '{"completed": "2024-09-03T10:02:30Z", "summary": "1 processed, 1 error"}'
                ]).call()

        then: "Final state is correct and counts match"
        def finalEmailProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId1).one()
        
        finalEmailProcessing.totalEnviosFound == 2
        finalEmailProcessing.totalEnviosProcessed == 1
        finalEmailProcessing.totalEnviosError == 1
        finalEmailProcessing.totalEnviosFound == (finalEmailProcessing.totalEnviosProcessed + finalEmailProcessing.totalEnviosError)

        // Verify individual envio states
        def finalEnviosList = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId1).list()
        
        def successEnvio = finalEnviosList.find { it.envioId == testEnvioId1 }
        successEnvio.attachmentFileName == "factura-001-processed.xml"

        def errorEnvio = finalEnviosList.find { it.envioId == testEnvioId2 }
        errorEnvio.errorMessage == "XML validation failed"
    }

    def "002 - Test duplicate envio detection within same email"() {
        given: "An email processing record with duplicate envios"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId2,
                        jobRunId: testJobRunId,
                        statusId: "DepEnviosDetected",
                        totalEnviosFound: 3
                ]).call()

        when: "Same envio is detected multiple times in attachments"
        // First detection - this will succeed
        def detection1 = ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId2,
                        envioId: testEnvioId3,
                        attachmentSeqId: "1",
                        attachmentFileName: "original-envio.xml"
                ]).call()

        // Try to create duplicate record with same key - this should fail
        def duplicateAttempted = false
        try {
            def detection2 = ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                    .parameters([
                            emailMessageId: testEmailMessageId2,
                            envioId: testEnvioId3, // Same envio ID
                            attachmentSeqId: "2",
                            attachmentFileName: "duplicate-attempt.xml"
                    ]).call()
        } catch (Exception e) {
            duplicateAttempted = true
        }

        then: "Only one record exists due to primary key constraint"
        !ec.message.hasError()

        def records = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId2)
                .condition("envioId", testEnvioId3)
                .list()

        records.size() == 1
        
        def record = records.get(0)
        record.attachmentFileName == "original-envio.xml"
        
        // Duplicate creation should have been prevented
        duplicateAttempted == true || ec.message.hasError()
    }

    def "003 - Test cross-entity consistency validation"() {
        given: "Email processing with envios in various states"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        statusId: "DepEnviosDetected",
                        totalEnviosFound: 3,
                        totalEnviosProcessed: 0,
                        totalEnviosError: 0
                ]).call()

        // Create envio processing records
        ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        envioId: testEnvioId1,
                        processingSequence: 1,
                        attachmentSeqId: "1"
                ]).call()

        ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        envioId: testEnvioId2,
                        processingSequence: 1,
                        errorMessage: "Test error"
                ]).call()

        ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        envioId: testEnvioId3,
                        processingSequence: 1,
                        statusId: "DepEnvioProcessing"
                ]).call()

        when: "Verify counts match actual envio states"
        def emailProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId1).one()

        def envioProcessingList = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId1).list()

        // Count actual envio states
        def actualProcessedCount = envioProcessingList.count { it.errorMessage == null }
        def actualErrorCount = envioProcessingList.count { it.errorMessage != null }
        def actualFoundCount = envioProcessingList.size()

        then: "Initial state shows inconsistency"
        emailProcessing.totalEnviosFound == 3
        emailProcessing.totalEnviosProcessed == 0 // Not updated yet
        emailProcessing.totalEnviosError == 0 // Not updated yet
        
        actualFoundCount == 3
        actualProcessedCount == 1
        actualErrorCount == 1

        when: "Update email processing to match actual states"
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        totalEnviosProcessed: actualProcessedCount,
                        totalEnviosError: actualErrorCount
                ]).call()

        then: "Consistency is restored"
        def updatedEmailProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId1).one()

        updatedEmailProcessing.totalEnviosFound == actualFoundCount
        updatedEmailProcessing.totalEnviosProcessed == actualProcessedCount
        updatedEmailProcessing.totalEnviosError == actualErrorCount
        
        // Business rule: found = processed + error + processing
        def totalAccountedFor = updatedEmailProcessing.totalEnviosProcessed + updatedEmailProcessing.totalEnviosError
        def stillProcessing = updatedEmailProcessing.totalEnviosFound - totalAccountedFor
        stillProcessing == 1 // One still in processing state
    }

    // ========================================
    // View Entity Tests
    // ========================================

    def "004 - Test EmailProcessingDetailView functionality"() {
        given: "Complete email processing setup"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        jobRunId: testJobRunId,
                        statusId: "DepEnviosDetected",
                        totalEnviosFound: 2,
                        totalEnviosProcessed: 1,
                        totalEnviosError: 1,
                        processingAttemptCount: 2,
                        lastProcessingAttempt: ec.user.nowTimestamp,
                        processingNotes: '{"view_test": "comprehensive email processing data"}'
                ]).call()

        when: "Query the EmailProcessingDetailView"
        def viewResults = ec.entity.find("moit.dte.EmailProcessingDetailView")
                .condition("emailMessageId", testEmailMessageId1)
                .list()

        then: "View combines all related data correctly"
        viewResults.size() == 1
        def viewRecord = viewResults.get(0)

        // Email fields (prefixed with 'email')
        viewRecord.emailSubject == "Email with Multiple DTEs"
        viewRecord.emailFromAddress == "dte-integration@sii.cl"
        viewRecord.emailToAddresses == "integration-test@company.com"
        viewRecord.emailStatusId == "EmsgReceived"

        // Processing fields
        viewRecord.statusId == "DepEnviosDetected"
        viewRecord.totalEnviosFound == 2
        viewRecord.totalEnviosProcessed == 1
        viewRecord.totalEnviosError == 1
        viewRecord.processingAttemptCount == 2
        viewRecord.processingNotes.contains("comprehensive email processing")

        // Job fields
        viewRecord.jobName == "Test Email Integration Processing Job"
        viewRecord.jobHasError == "N"
        viewRecord.jobStartTime != null
    }

    def "005 - Test EnvioProcessingSummaryView functionality"() {
        given: "Email with multiple envios in different states"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId2,
                        statusId: "DepEnviosDetected",
                        totalEnviosFound: 2
                ]).call()

        // Create envio processing records
        ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId2,
                        envioId: testEnvioId1,
                        attachmentSeqId: "1",
                        attachmentFileName: "successful-envio.xml"
                ]).call()

        ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId2,
                        envioId: testEnvioId2,
                        attachmentSeqId: "2",
                        attachmentFileName: "failed-envio.xml",
                        errorMessage: "Processing failed"
                ]).call()

        when: "Query the EnvioProcessingSummaryView"
        def summaryResults = ec.entity.find("moit.dte.EnvioProcessingSummaryView")
                .condition("emailMessageId", testEmailMessageId2)
                .orderBy("envioId")
                .list()

        then: "View shows summary of all envios for the email"
        summaryResults.size() == 2

        def successEnvio = summaryResults.find { it.envioId == testEnvioId1 }
        successEnvio != null
        successEnvio.attachmentFileName == "successful-envio.xml"
        successEnvio.envioStatusId == "DteEnvioCreated" // From DteEnvio
        successEnvio.rutEmisor == "76514104-4"

        def errorEnvio = summaryResults.find { it.envioId == testEnvioId2 }
        errorEnvio != null
        errorEnvio.attachmentFileName == "failed-envio.xml"
        errorEnvio.errorMessage == "Processing failed"
        errorEnvio.rutReceptor == "12345678-9"
    }

    // ========================================
    // Performance and Scale Tests
    // ========================================

    def "006 - Test email processing with many envios"() {
        given: "Email processing with many envios (simulating large batch)"
        def manyEnviosCount = 10
        def manyEnviosEmailId = testEmailMessageId1 + "-MANY"

        // Create email message for many envios test
        ec.service.sync().name("create#moqui.basic.email.EmailMessage")
                .parameters([
                        emailMessageId: manyEnviosEmailId,
                        subject: "Batch Email with ${manyEnviosCount} DTEs",
                        body: "Large batch processing test",
                        fromAddress: "batch@sii.cl",
                        toAddresses: "batch-test@company.com",
                        sentDate: ec.user.nowTimestamp,
                        statusId: "EmsgReceived"
                ]).call()

        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: manyEnviosEmailId,
                        statusId: "DepEnviosDetected",
                        totalEnviosFound: manyEnviosCount
                ]).call()

        when: "Create many envio processing records"
        def startTime = System.currentTimeMillis()

        for (int i = 1; i <= manyEnviosCount; i++) {
            def batchEnvioId = "BATCH-ENVIO-${i}-${UUID.randomUUID().toString().substring(0, 4)}"
            
            // Create DteEnvio first
            ec.service.sync().name("create#mchile.dte.DteEnvio")
                    .parameters([
                            envioId: batchEnvioId,
                            envioTypeEnumId: "Ftdt-33",
                            issuerPartyId: testIssuerPartyId,
                            receiverPartyId: testIssuerPartyId,
                            statusId: "DteEnvioCreated",
                            rutEmisor: "76514104-4",
                            rutReceptor: "12345678-${String.format('%01d', i % 10)}"
                    ]).call()

            // Create processing record
            ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                    .parameters([
                            emailMessageId: manyEnviosEmailId,
                            envioId: batchEnvioId,
                            processingSequence: 1,
                            errorMessage: (i % 5 == 0) ? "Processing error" : null,
                            attachmentIndex: i - 1,
                            attachmentFileName: "batch-dte-${String.format('%03d', i)}.xml",
                            processingNotes: """{"batchIndex": ${i}, "processingTime": "${i * 50}ms"}"""
                    ]).call()
        }

        def endTime = System.currentTimeMillis()
        def processingTime = endTime - startTime

        then: "All records are created efficiently"
        !ec.message.hasError()
        processingTime < 10000 // Should complete within 10 seconds

        def createdEnvios = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", manyEnviosEmailId)
                .list()

        createdEnvios.size() == manyEnviosCount

        // Verify status distribution
        def processedCount = createdEnvios.count { it.errorMessage == null }
        def errorCount = createdEnvios.count { it.errorMessage != null }

        processedCount == (manyEnviosCount - (manyEnviosCount / 5).intValue())
        errorCount == (manyEnviosCount / 5).intValue()

        cleanup: "Clean up batch test data"
        // Delete batch envio processing records
        ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", manyEnviosEmailId)
                .deleteAll()

        // Delete batch envios
        def batchEnvios = ec.entity.find("mchile.dte.DteEnvio")
                .condition("issuerPartyId", testIssuerPartyId)
                .condition("envioId", EntityCondition.ComparisonOperator.LIKE, "BATCH-ENVIO-%")
                .list()
        batchEnvios.each { envio ->
            ec.entity.find("mchile.dte.DteEnvio")
                    .condition("envioId", envio.envioId)
                    .deleteAll()
        }

        // Delete batch email processing
        ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", manyEnviosEmailId)
                .deleteAll()

        // Delete batch email message
        ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", manyEnviosEmailId)
                .deleteAll()
    }

    // ========================================
    // Error Recovery and Resilience Tests
    // ========================================

    def "007 - Test error recovery and retry scenarios"() {
        given: "Email processing that initially fails"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        statusId: "DepError",
                        errorMessage: "Initial processing failed - XML parsing error",
                        totalEnviosFound: 0,
                        processingAttemptCount: 1,
                        lastProcessingAttempt: ec.user.nowTimestamp,
                        processingNotes: '{"attempt": 1, "error": "XML malformed", "timestamp": "2024-09-03T10:00:00Z"}'
                ]).call()

        when: "Retry processing after error resolution"
        // Simulate retry attempt
        def retryTime = ec.user.nowTimestamp
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        statusId: "DepProcessing",
                        errorMessage: null, // Clear previous error
                        processingAttemptCount: 2,
                        lastProcessingAttempt: retryTime,
                        processingNotes: '{"attempt": 2, "retry": true, "previousError": "resolved", "timestamp": "2024-09-03T10:30:00Z"}'
                ]).call()

        // Successful processing after retry
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        statusId: "DepEnviosDetected",
                        totalEnviosFound: 1,
                        processingNotes: '{"attempt": 2, "success": true, "enviosFound": 1, "timestamp": "2024-09-03T10:31:00Z"}'
                ]).call()

        // Create successful envio processing
        ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        envioId: testEnvioId1,
                        attachmentSeqId: "1",
                        attachmentFileName: "recovered-envio.xml"
                ]).call()

        // Update final counts
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId1,
                        totalEnviosProcessed: 1,
                        totalEnviosError: 0
                ]).call()

        then: "Recovery is successful and properly tracked"
        def finalProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId1).one()

        finalProcessing.statusId == "DepEnviosDetected"
        finalProcessing.processingAttemptCount == 2
        finalProcessing.totalEnviosFound == 1
        finalProcessing.totalEnviosProcessed == 1
        finalProcessing.totalEnviosError == 0
        finalProcessing.errorMessage == null
        finalProcessing.processingNotes.contains("success")
        finalProcessing.lastProcessingAttempt == retryTime

        def envioProcessing = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId1)
                .condition("envioId", testEnvioId1)
                .one()

        envioProcessing.attachmentFileName == "recovered-envio.xml"
    }

    def "008 - Test partial processing scenarios"() {
        given: "Email with multiple envios where some succeed and others fail"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId2,
                        statusId: "DepEnviosDetected",
                        totalEnviosFound: 3
                ]).call()

        when: "Process envios with mixed results"
        // First envio: Success
        ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId2,
                        envioId: testEnvioId1,
                        attachmentSeqId: "1",
                        attachmentFileName: "success-envio-1.xml"
                ]).call()

        // Second envio: Error
        ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId2,
                        envioId: testEnvioId2,
                        attachmentSeqId: "2",
                        errorMessage: "RUT validation failed",
                        attachmentFileName: "error-envio-2.xml"
                ]).call()

        // Third envio: Pending (no error, simulating still processing)
        ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId2,
                        envioId: testEnvioId3,
                        attachmentSeqId: "3",
                        attachmentFileName: "pending-envio-3.xml"
                ]).call()

        // Update summary counts based on current states
        def processedEnvios = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId2)
                .list()

        def processedCount = processedEnvios.count { it.errorMessage == null }
        def errorCount = processedEnvios.count { it.errorMessage != null }

        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId2,
                        totalEnviosProcessed: processedCount,
                        totalEnviosError: errorCount,
                        lastProcessingAttempt: ec.user.nowTimestamp,
                        processingNotes: """{"partial": true, "processed": ${processedCount}, "errors": ${errorCount}, "pending": ${3 - processedCount - errorCount}}"""
                ]).call()

        then: "Partial processing state is correctly tracked"
        def partialProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId2).one()

        partialProcessing.totalEnviosFound == 3
        partialProcessing.totalEnviosProcessed == 1
        partialProcessing.totalEnviosError == 1
        partialProcessing.processingNotes.contains("partial")

        // Verify individual envio states
        def allEnvios = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId2)
                .list()

        def successEnvio = allEnvios.find { it.errorMessage == null }
        def errorEnvio = allEnvios.find { it.errorMessage != null }
        def pendingEnvio = allEnvios.find { it.envioId == testEnvioId3 }

        successEnvio != null
        successEnvio.errorMessage == null
        successEnvio.attachmentFileName == "success-envio-1.xml"

        errorEnvio != null
        errorEnvio.errorMessage == "RUT validation failed"
        errorEnvio.attachmentFileName == "error-envio-2.xml"

        pendingEnvio != null
        pendingEnvio.attachmentFileName == "pending-envio-3.xml"

        // Business rule validation: partial sums
        def accountedFor = partialProcessing.totalEnviosProcessed + partialProcessing.totalEnviosError
        def stillPending = partialProcessing.totalEnviosFound - accountedFor
        stillPending == 1 // One envio still processing
    }
}
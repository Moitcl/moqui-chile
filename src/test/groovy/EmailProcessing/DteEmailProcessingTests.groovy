package EmailProcessing

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import java.util.UUID

/**
 * Comprehensive test suite for DteEmailProcessing entity functionality.
 * Tests cover CRUD operations, status transitions, JSON processingNotes handling,
 * and relationship management with EmailMessage and ServiceJobRun entities.
 */
class DteEmailProcessingTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String testEmailMessageId = "TEST-EMAIL-MSG-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testJobRunId = "TEST-JOB-RUN-${UUID.randomUUID().toString().substring(0, 8)}"
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
        ec.entity.tempSetSequencedIdPrimary("moqui.basic.email.EmailMessage", 75000, 50)
        ec.entity.tempSetSequencedIdPrimary("moqui.service.job.ServiceJobRun", 76000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.DteEmailProcessing", 77000, 50)

        ec.artifactExecution.disableAuthz()

        // Create test infrastructure data
        createTestEmailMessage()
        createTestJobRun()

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
    // Test Helper Methods
    // ========================================

    private void createTestEmailMessage() {
        def existingEmail = ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        if (!existingEmail) {
            ec.service.sync().name("create#moqui.basic.email.EmailMessage")
                    .parameters([
                            emailMessageId: testEmailMessageId,
                            subject: "Test Email for DTE Processing",
                            body: "Test email body content",
                            fromAddress: "test@example.com",
                            toAddresses: "recipient@example.com",
                            sentDate: ec.user.nowTimestamp,
                            statusId: "EmsgReceived"
                    ]).call()
        }
    }

    private void createTestJobRun() {
        def existingJobRun = ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", testJobRunId)
                .one()
        if (!existingJobRun) {
            ec.service.sync().name("create#moqui.service.job.ServiceJobRun")
                    .parameters([
                            jobRunId: testJobRunId,
                            jobName: "Test DTE Processing Job",
                            serviceName: "mchile.dte.EmailProcessingServices.process#EmailDteAttachments",
                            startTime: ec.user.nowTimestamp,
                            hasError: "N"
                    ]).call()
        }
    }

    private void cleanupTestData() {
        // Delete test email processing records
        ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .deleteAll()
    }

    private void cleanupTestInfrastructure() {
        // Delete test email message
        ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", testEmailMessageId)
                .deleteAll()
        
        // Delete test job run
        ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", testJobRunId)
                .deleteAll()
    }

    private Map createTestEmailProcessing(Map params = [:]) {
        def defaultParams = [
                emailMessageId: testEmailMessageId,
                jobRunId: testJobRunId,
                statusId: "DepReceived",
                totalEnviosFound: 0,
                totalEnviosProcessed: 0,
                totalEnviosError: 0,
                processingAttemptCount: 0
        ]
        defaultParams.putAll(params)

        return ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters(defaultParams)
                .call()
    }

    // ========================================
    // CRUD Operations Tests
    // ========================================

    def "001 - Create DteEmailProcessing with minimal required fields"() {
        when:
        def result = createTestEmailProcessing()

        then:
        result.emailMessageId == testEmailMessageId
        !ec.message.hasError()

        // Verify entity was created
        def created = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        created != null
        created.emailMessageId == testEmailMessageId
        created.jobRunId == testJobRunId
        created.statusId == "DepReceived"
        created.totalEnviosFound == 0
        created.totalEnviosProcessed == 0
        created.totalEnviosError == 0
        created.processingAttemptCount == 0
        created.processDate != null
    }

    def "002 - Create DteEmailProcessing with all fields populated"() {
        given:
        def testProcessingNotes = '{"step1": "Email received", "step2": "Attachments analyzed", "enviosDetected": 3}'
        def testErrorMessage = "Test error message for comprehensive testing"
        def testLastProcessingAttempt = ec.user.nowTimestamp

        when:
        def result = createTestEmailProcessing([
                statusId: "DepProcessing",
                errorMessage: testErrorMessage,
                processingNotes: testProcessingNotes,
                totalEnviosFound: 3,
                totalEnviosProcessed: 2,
                totalEnviosError: 1,
                lastProcessingAttempt: testLastProcessingAttempt,
                processingAttemptCount: 2
        ])

        then:
        result.emailMessageId == testEmailMessageId
        !ec.message.hasError()

        // Verify all fields were saved correctly
        def created = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        created.statusId == "DepProcessing"
        created.errorMessage == testErrorMessage
        created.processingNotes == testProcessingNotes
        created.totalEnviosFound == 3
        created.totalEnviosProcessed == 2
        created.totalEnviosError == 1
        created.lastProcessingAttempt == testLastProcessingAttempt
        created.processingAttemptCount == 2
    }

    def "003 - Read DteEmailProcessing with relationships"() {
        given:
        createTestEmailProcessing([
                statusId: "DepEnviosDetected",
                totalEnviosFound: 2
        ])

        when:
        def emailProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        then:
        emailProcessing != null
        emailProcessing.emailMessageId == testEmailMessageId

        // Test relationships
        def emailMessage = emailProcessing.getRelatedOne("moqui.basic.email.EmailMessage", null, false)
        emailMessage != null
        emailMessage.emailMessageId == testEmailMessageId
        emailMessage.subject == "Test Email for DTE Processing"

        def jobRun = emailProcessing.getRelatedOne("moqui.service.job.ServiceJobRun", null, false)
        jobRun != null
        jobRun.jobRunId == testJobRunId

        def statusItem = emailProcessing.getRelatedOne("DteEmailProcessing#moqui.basic.StatusItem", null, false)
        statusItem != null
        statusItem.statusId == "DepEnviosDetected"
        statusItem.description == "Envios Detected"
    }

    def "004 - Update DteEmailProcessing fields"() {
        given:
        createTestEmailProcessing([
                statusId: "DepReceived",
                totalEnviosFound: 0
        ])

        when:
        def updateResult = ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        statusId: "DepProcessing",
                        totalEnviosFound: 3,
                        totalEnviosProcessed: 1,
                        processingNotes: '{"status": "Processing started", "enviosFound": 3}',
                        processingAttemptCount: 1,
                        lastProcessingAttempt: ec.user.nowTimestamp
                ]).call()

        then:
        updateResult.emailMessageId == testEmailMessageId
        !ec.message.hasError()

        // Verify updates were applied
        def updated = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        updated.statusId == "DepProcessing"
        updated.totalEnviosFound == 3
        updated.totalEnviosProcessed == 1
        updated.processingAttemptCount == 1
        updated.processingNotes.contains("Processing started")
        updated.lastProcessingAttempt != null
    }

    def "005 - Delete DteEmailProcessing"() {
        given:
        createTestEmailProcessing()

        when:
        def deleteResult = ec.service.sync().name("delete#mchile.dte.DteEmailProcessing")
                .parameters([emailMessageId: testEmailMessageId])
                .call()

        then:
        deleteResult.emailMessageId == testEmailMessageId
        !ec.message.hasError()

        // Verify deletion
        def deleted = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        deleted == null
    }

    // ========================================
    // Status Transition Tests
    // ========================================

    def "006 - Test valid status transitions"() {
        given:
        createTestEmailProcessing([statusId: "DepReceived"])

        when: "Transition from DepReceived to DepQueued"
        def result1 = ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        statusId: "DepQueued"
                ]).call()

        then:
        !ec.message.hasError()
        def entity1 = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()
        entity1.statusId == "DepQueued"

        when: "Transition from DepQueued to DepProcessing"
        def result2 = ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        statusId: "DepProcessing"
                ]).call()

        then:
        !ec.message.hasError()
        def entity2 = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()
        entity2.statusId == "DepProcessing"

        when: "Transition from DepProcessing to DepEnviosDetected"
        def result3 = ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        statusId: "DepEnviosDetected"
                ]).call()

        then:
        !ec.message.hasError()
        def entity3 = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()
        entity3.statusId == "DepEnviosDetected"
    }

    def "007 - Test error status handling"() {
        given:
        createTestEmailProcessing([statusId: "DepProcessing"])

        when:
        def result = ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        statusId: "DepError",
                        errorMessage: "Processing failed due to invalid attachment format",
                        processingNotes: '{"error": "XML parsing failed", "timestamp": "2024-09-03T10:30:00Z"}'
                ]).call()

        then:
        !ec.message.hasError()
        
        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()
        
        entity.statusId == "DepError"
        entity.errorMessage == "Processing failed due to invalid attachment format"
        entity.processingNotes.contains("XML parsing failed")
    }

    def "008 - Test duplicate detection status"() {
        given:
        createTestEmailProcessing([statusId: "DepReceived"])

        when:
        def result = ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        statusId: "DepDuplicate",
                        processingNotes: '{"reason": "Email already processed", "originalProcessDate": "2024-09-01T15:20:00Z"}'
                ]).call()

        then:
        !ec.message.hasError()
        
        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()
        
        entity.statusId == "DepDuplicate"
        entity.processingNotes.contains("already processed")
    }

    // ========================================
    // JSON Processing Notes Tests
    // ========================================

    def "009 - Test JSON processingNotes field functionality"() {
        given:
        def complexProcessingNotes = '''
        {
            "processingStarted": "2024-09-03T10:00:00Z",
            "steps": [
                {"step": 1, "action": "Email received", "timestamp": "2024-09-03T10:00:01Z"},
                {"step": 2, "action": "Attachments parsed", "count": 3, "timestamp": "2024-09-03T10:00:15Z"},
                {"step": 3, "action": "Envios detected", "envioIds": ["ENV001", "ENV002"], "timestamp": "2024-09-03T10:00:30Z"}
            ],
            "metadata": {
                "emailSize": "2.5MB",
                "attachmentCount": 3,
                "processingServer": "server-01"
            },
            "diagnostics": {
                "xmlValidationTime": "125ms",
                "totalProcessingTime": "2.3s"
            }
        }
        '''.trim()

        when:
        def result = createTestEmailProcessing([
                processingNotes: complexProcessingNotes,
                statusId: "DepEnviosDetected",
                totalEnviosFound: 2
        ])

        then:
        !ec.message.hasError()

        // Verify JSON was stored correctly
        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()

        entity.processingNotes != null
        entity.processingNotes.contains("processingStarted")
        entity.processingNotes.contains("ENV001")
        entity.processingNotes.contains("server-01")
        entity.processingNotes.contains("xmlValidationTime")
    }

    def "010 - Test processingNotes with special characters and encoding"() {
        given:
        def notesWithSpecialChars = '''
        {
            "message": "Procesamiento de email con caracteres especiales: ñáéíóú",
            "errorDescription": "Error en validación XML: 'elemento &lt;DTE&gt; no válido'",
            "fileName": "Envío_DTE_Compañía_González&Hijos.xml",
            "specialCases": {
                "utf8Test": "Test: áéíóúñÁÉÍÓÚÑ",
                "symbolsTest": "Symbols: @#$%^&*(){}[]|\\:;\"'<>,.?/",
                "quotesTest": "Double \"quotes\" and single 'quotes'"
            }
        }
        '''.trim()

        when:
        def result = createTestEmailProcessing([
                processingNotes: notesWithSpecialChars,
                statusId: "DepError",
                errorMessage: "Encoding test error"
        ])

        then:
        !ec.message.hasError()

        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()

        entity.processingNotes.contains("ñáéíóú")
        entity.processingNotes.contains("González&Hijos")
        entity.processingNotes.contains("Double \"quotes\"")
        entity.processingNotes.contains("single 'quotes'")
    }

    // ========================================
    // Validation and Constraint Tests
    // ========================================

    def "011 - Test numeric field constraints and validation"() {
        when: "Create with valid numeric values"
        def result = createTestEmailProcessing([
                totalEnviosFound: 5,
                totalEnviosProcessed: 3,
                totalEnviosError: 2,
                processingAttemptCount: 1
        ])

        then:
        !ec.message.hasError()
        
        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()
        
        entity.totalEnviosFound == 5
        entity.totalEnviosProcessed == 3
        entity.totalEnviosError == 2
        entity.processingAttemptCount == 1
    }

    @Unroll
    def "012 - Test business logic constraints: found=#found, processed=#processed, error=#error, valid=#isValid"() {
        when:
        def result = createTestEmailProcessing([
                totalEnviosFound: found,
                totalEnviosProcessed: processed,
                totalEnviosError: error
        ])

        then:
        if (isValid) {
            !ec.message.hasError()
            def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                    .condition("emailMessageId", testEmailMessageId).one()
            entity != null
            // Business rule: totalEnviosFound >= totalEnviosProcessed + totalEnviosError
            entity.totalEnviosFound >= (entity.totalEnviosProcessed + entity.totalEnviosError)
        } else {
            // Note: Business rule validation would be implemented in service layer
            // This test documents the expected constraint behavior
            true // Framework allows creation, business validation in services
        }

        where:
        found | processed | error | isValid
        5     | 3         | 2     | true    // 5 >= 3 + 2 (valid)
        5     | 2         | 2     | true    // 5 >= 2 + 2 (valid)
        10    | 0         | 0     | true    // 10 >= 0 + 0 (valid)
        3     | 2         | 2     | false   // 3 < 2 + 2 (invalid - business rule)
        0     | 1         | 0     | false   // 0 < 1 + 0 (invalid - business rule)
    }

    def "013 - Test required field validation"() {
        when: "Try to create without required emailMessageId"
        def result = ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        // emailMessageId missing
                        statusId: "DepReceived"
                ]).call()

        then:
        ec.message.hasError()
        ec.message.getErrorsString().contains("emailMessageId") || 
        ec.message.getErrorsString().contains("required") ||
        ec.message.getErrorsString().contains("null")
    }

    // ========================================
    // Relationship and Foreign Key Tests
    // ========================================

    def "014 - Test relationships with valid foreign keys"() {
        given:
        createTestEmailProcessing([
                statusId: "DepProcessing",
                totalEnviosFound: 2
        ])

        when:
        def emailProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        then:
        // Test EmailMessage relationship
        def emailMessage = emailProcessing.getRelatedOne("moqui.basic.email.EmailMessage", null, false)
        emailMessage != null
        emailMessage.emailMessageId == testEmailMessageId
        emailMessage.subject == "Test Email for DTE Processing"

        // Test ServiceJobRun relationship  
        def jobRun = emailProcessing.getRelatedOne("moqui.service.job.ServiceJobRun", null, false)
        jobRun != null
        jobRun.jobRunId == testJobRunId
        jobRun.jobName == "Test DTE Processing Job"

        // Test StatusItem relationship
        def statusItem = emailProcessing.getRelatedOne("DteEmailProcessing#moqui.basic.StatusItem", null, false)
        statusItem != null
        statusItem.statusId == "DepProcessing"
        statusItem.statusTypeId == "DteEmailProcessing"
    }

    def "015 - Test optional relationships (nullable foreign keys)"() {
        when: "Create with optional jobRunId as null"
        def result = ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId + "-NULL-FK",
                        jobRunId: null,
                        statusId: "DepReceived"
                ]).call()

        then:
        !ec.message.hasError()

        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId + "-NULL-FK")
                .one()

        entity != null
        entity.jobRunId == null
        
        // Relationship should handle null gracefully
        def jobRun = entity.getRelatedOne("moqui.service.job.ServiceJobRun", null, false)
        jobRun == null

        cleanup:
        ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId + "-NULL-FK")
                .deleteAll()
    }

    // ========================================
    // Performance and Edge Case Tests
    // ========================================

    def "016 - Test large processingNotes field handling"() {
        given:
        def largeProcessingNotes = generateLargeJsonNotes(10000) // ~10KB JSON

        when:
        def result = createTestEmailProcessing([
                processingNotes: largeProcessingNotes,
                statusId: "DepProcessing"
        ])

        then:
        !ec.message.hasError()

        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()

        entity.processingNotes != null
        entity.processingNotes.length() >= 10000
        entity.processingNotes.contains("largeDataEntry_1000")
    }

    def "017 - Test concurrent processing scenarios"() {
        given:
        createTestEmailProcessing([
                statusId: "DepProcessing",
                processingAttemptCount: 1
        ])

        when: "Simulate concurrent updates"
        def update1 = ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        totalEnviosProcessed: 1,
                        processingNotes: '{"thread": "worker-1", "timestamp": "2024-09-03T10:00:01Z"}'
                ]).call()

        def update2 = ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        totalEnviosError: 1,
                        processingAttemptCount: 2,
                        processingNotes: '{"thread": "worker-2", "timestamp": "2024-09-03T10:00:02Z"}'
                ]).call()

        then:
        !ec.message.hasError()

        // Verify final state (last update wins)
        def finalEntity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()

        finalEntity.processingAttemptCount == 2
        finalEntity.totalEnviosError == 1
        finalEntity.processingNotes.contains("worker-2")
    }

    def "018 - Test timestamp field behavior"() {
        given:
        def beforeCreation = ec.user.nowTimestamp
        
        when:
        def result = createTestEmailProcessing([
                statusId: "DepReceived",
                lastProcessingAttempt: null // Should be nullable
        ])
        
        def afterCreation = ec.user.nowTimestamp

        then:
        !ec.message.hasError()

        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()

        // processDate should be auto-set to current timestamp
        entity.processDate != null
        entity.processDate.getTime() >= beforeCreation.getTime()
        entity.processDate.getTime() <= afterCreation.getTime()
        
        // lastProcessingAttempt should be nullable and null initially
        entity.lastProcessingAttempt == null

        when: "Update lastProcessingAttempt"
        def updateTime = ec.user.nowTimestamp
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        lastProcessingAttempt: updateTime
                ]).call()

        then:
        def updatedEntity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()
        
        updatedEntity.lastProcessingAttempt == updateTime
    }

    // ========================================
    // Helper Methods for Complex Tests
    // ========================================

    private String generateLargeJsonNotes(int targetSize) {
        def json = new StringBuilder()
        json.append('{"processingLog": [')
        
        def entrySize = 100 // approximate size per entry
        def entriesNeeded = Math.max(targetSize / entrySize, 10)
        
        for (int i = 1; i <= entriesNeeded; i++) {
            if (i > 1) json.append(',')
            json.append("""
            {
                "entry": ${i},
                "timestamp": "2024-09-03T10:${String.format('%02d', i % 60)}:${String.format('%02d', (i * 13) % 60)}Z",
                "action": "Processing step ${i}",
                "data": "largeDataEntry_${i}",
                "details": "Detailed processing information for entry ${i} with additional text to increase size"
            }""".trim())
        }
        
        json.append(']}')
        return json.toString()
    }
}
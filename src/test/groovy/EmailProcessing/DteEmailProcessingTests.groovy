package EmailProcessing

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import java.util.UUID
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Simplified test suite for DteEmailProcessing entity functionality.
 * Tests cover CRUD operations, status transitions, and relationship management
 * with EmailMessage and ServiceJobRun entities.
 *
 * Updated to match actual entity definition with fields:
 * - emailMessageId (id, pk)
 * - jobRunId (id)
 * - statusId (id)
 * - processDate (date-time)
 * - errorMessage (text-long)
 * - processingNotes (text-very-long)
 */
class DteEmailProcessingTests extends Specification {
    @Shared ExecutionContext ec
    @Shared ServiceJobRunMockHelper jobMockHelper
    @Shared Logger logger = LoggerFactory.getLogger(DteEmailProcessingTests.class)
    @Shared String testEmailMessageId = "TEST-EMAIL-MSG-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testJobRunId = "TEST-JOB-RUN-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testUsername = "john.doe"

    def setupSpec() {
        // Initialize framework
        ec = Moqui.getExecutionContext()
        jobMockHelper = new ServiceJobRunMockHelper()

        // Login test user
        if (!ec.user.userId) {
            boolean loginSuccess = ec.user.loginUser(testUsername, "moqui")
            if (!loginSuccess) {
                throw new RuntimeException("Failed to login - ensure test data is loaded")
            }
        }

        // Disable authorization for test execution
        ec.artifactExecution.disableAuthz()

        // Set unique sequence ranges for test isolation
        ec.entity.tempSetSequencedIdPrimary("moqui.basic.email.EmailMessage", 75000, 50)
        ec.entity.tempSetSequencedIdPrimary("moqui.service.job.ServiceJobRun", 76000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.DteEmailProcessing", 77000, 50)

        // NOTE: Test infrastructure (EmailMessage, JobRun) is created per-test in createTestEmailProcessing()
        // to ensure proper test isolation and avoid cross-test contamination
    }

    def cleanupSpec() {
        // Final cleanup in case any tests failed to clean up properly
        try {
            ec.artifactExecution.disableAuthz()
            cleanupTestData()
        } catch (Exception e) {
            // Ignore cleanup errors during teardown
            logger.warn("Error during final cleanup: ${e.message}")
        }
        ec.artifactExecution.enableAuthz()
        ec.destroy()
    }

    def setup() {
        ec.message.clearErrors()
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
    }

    def cleanup() {
        // Handle transaction rollback first, then cleanup data only if transaction is valid
        if (ec.transaction.status == javax.transaction.Status.STATUS_MARKED_ROLLBACK) {
            ec.transaction.rollback("Marked as Rollback", null)
        } else {
            // Only clean up data if transaction is not marked for rollback
            cleanupTestData()
            ec.transaction.commit()
        }

        // Follow mantle-usl pattern: warn about unexpected errors, don't proactively clear
        if (ec.message.hasError()) {
            logger.warn("Unexpected errors in test: ${ec.message.getErrorsString()}")
            // DO NOT clear errors here - let tests fail if unexpected errors occur
        }
        ec.artifactExecution.enableAuthz()
    }

    private void createTestEmailMessage() {
        def existingEmail = ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        if (!existingEmail) {
            ec.service.sync().name("create#moqui.basic.email.EmailMessage")
                    .parameters([
                            emailMessageId: testEmailMessageId,
                            subject: "Test Email Subject",
                            body: "Test email body content",
                            fromAddress: "test@example.com",
                            toAddresses: "recipient@example.com",
                            sentDate: ec.user.nowTimestamp,
                            statusId: "ES_RECEIVED"
                    ]).call()
        }
    }

    private void createTestJobRun() {
        def existingJobRun = ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", testJobRunId)
                .one()
        if (!existingJobRun) {
            jobMockHelper.createAndStartJobRun(ec, [
                    jobRunId: testJobRunId,
                    jobName: "Test DTE Processing Job",
                    serviceName: "mchile.dte.EmailProcessingServices.process#EmailDteAttachments",
                    startTime: ec.user.nowTimestamp,
                    hasError: "N"
            ])
        }
    }

    private void cleanupTestData() {
        // Clean up test data in proper dependency order to avoid foreign key violations
        try {
            // 1. Delete child records that reference parent entities first
            ec.entity.find("mchile.dte.DteEmailProcessing")
                    .condition("emailMessageId", testEmailMessageId)
                    .deleteAll()

            // 2. Delete parent entities in correct order
            ec.entity.find("moqui.basic.email.EmailMessage")
                    .condition("emailMessageId", testEmailMessageId)
                    .deleteAll()

            ec.entity.find("moqui.service.job.ServiceJobRun")
                    .condition("jobRunId", testJobRunId)
                    .deleteAll()

            logger.info("Deleted test data in dependency order: ${testEmailMessageId}, ${testJobRunId}")
        } catch (Exception e) {
            logger.warn("Error during test data cleanup: ${e.message}")
        }
    }

    private Map createTestEmailProcessing(Map params = [:]) {
        // BEST PRACTICE: Create test infrastructure per-test for proper isolation
        // This ensures each test has its own EmailMessage and JobRun records
        // avoiding cross-test contamination and dependencies
        createTestEmailMessage()
        createTestJobRun()

        def defaultParams = [
                emailMessageId: testEmailMessageId,
                jobRunId: testJobRunId,
                statusId: "DepReceived",
                processDate: ec.user.nowTimestamp
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

        // Verify record exists
        def created = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        created != null
        created.emailMessageId == testEmailMessageId
        created.jobRunId == testJobRunId
        created.statusId == "DepReceived"
        created.errorMessage == null
        created.processingNotes == null
        created.processDate != null
    }

    def "002 - Create DteEmailProcessing with all fields populated"() {
        given:
        def testErrorMessage = "Test error message"
        def testProcessingNotes = "Test processing notes with detailed information"

        when: "Create record with default status and then transition to DepProcessed"
        // First create with default status (DepReceived)
        def result = createTestEmailProcessing([
                errorMessage: testErrorMessage,
                processingNotes: testProcessingNotes
        ])

        // Then progress to processed status: DepReceived -> DepProcessed
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([emailMessageId: testEmailMessageId, statusId: "DepProcessed"])
                .call()

        then:
        result.emailMessageId == testEmailMessageId
        !ec.message.hasError()

        // Verify all fields were saved correctly
        def created = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        created.statusId == "DepProcessed"
        created.errorMessage == testErrorMessage
        created.processingNotes == testProcessingNotes
        created.processDate != null
    }

    def "003 - Read DteEmailProcessing with relationships"() {
        given: "Create record and progress to DepProcessed status"
        createTestEmailProcessing()

        // Progress to processed status: DepReceived -> DepProcessed
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([emailMessageId: testEmailMessageId, statusId: "DepProcessed"])
                .call()

        when:
        def emailProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        then:
        emailProcessing != null

        // Test relationship to EmailMessage
        def relatedEmail = emailProcessing.findRelatedOne("moqui.basic.email.EmailMessage", null, false)
        relatedEmail != null
        relatedEmail.emailMessageId == testEmailMessageId
        relatedEmail.subject == "Test Email Subject"

        // Test relationship to ServiceJobRun
        def relatedJobRun = emailProcessing.findRelatedOne("moqui.service.job.ServiceJobRun", null, false)
        relatedJobRun != null
        relatedJobRun.jobRunId == testJobRunId
        relatedJobRun.jobName == "Test DTE Processing Job"

        // Test relationship to StatusItem
        def statusItem = emailProcessing.findRelatedOne("DteEmailProcessing#moqui.basic.StatusItem", null, false)
        statusItem != null
        statusItem.statusId == "DepProcessed"
    }

    def "004 - Update DteEmailProcessing fields"() {
        given:
        createTestEmailProcessing()

        when: "Update to DepProcessed with field updates"
        // Update to processed status with field changes
        def updateResult = ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        statusId: "DepProcessed",
                        errorMessage: "Updated error message",
                        processingNotes: "Updated processing notes"
                ]).call()

        then:
        !ec.message.hasError()

        def updated = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        updated.statusId == "DepProcessed"
        updated.errorMessage == "Updated error message"
        updated.processingNotes == "Updated processing notes"
    }

    def "005 - Delete DteEmailProcessing"() {
        given:
        createTestEmailProcessing()

        when:
        def deleteResult = ec.service.sync().name("delete#mchile.dte.DteEmailProcessing")
                .parameters([emailMessageId: testEmailMessageId])
                .call()

        then:
        !ec.message.hasError()

        def deleted = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        deleted == null
    }

    // ========================================
    // Status Management Tests
    // ========================================

    @Unroll
    def "006 - Test status transitions: #fromStatus to #toStatus"() {
        given: "Create record and progress to the required starting status"
        createTestEmailProcessing() // Always starts with DepReceived

        // Set up the fromStatus if not already DepReceived
        if (fromStatus == "DepProcessed") {
            ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                    .parameters([emailMessageId: testEmailMessageId, statusId: "DepProcessed"])
                    .call()
        }

        when:
        def result = ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        statusId: toStatus
                ]).call()

        then:
        !ec.message.hasError()
        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        entity.statusId == toStatus

        where:
        fromStatus     | toStatus
        "DepReceived"  | "DepProcessed"
        "DepReceived"  | "DepError"
    }

    def "007 - Test error status handling"() {
        given:
        def errorMessage = "Critical processing error occurred"

        when: "Create record and transition to DepError"
        // Create with default status and transition to error
        createTestEmailProcessing([errorMessage: errorMessage])

        // Transition to error status: DepReceived -> DepError
        ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                .parameters([emailMessageId: testEmailMessageId, statusId: "DepError"])
                .call()

        then:
        !ec.message.hasError()

        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        entity.statusId == "DepError"
        entity.errorMessage == errorMessage
        entity.errorMessage.contains("Critical processing error")
    }

    // ========================================
    // Field Validation Tests
    // ========================================

    def "008 - Test processingNotes field functionality"() {
        given:
        def longNotes = "This is a very long processing note that contains detailed information " * 10

        when:
        createTestEmailProcessing([
                processingNotes: longNotes
        ])

        then:
        !ec.message.hasError()

        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        entity.processingNotes == longNotes
        entity.processingNotes.length() > 500 // Verify it can handle long text
    }

    def "009 - Test required field validation"() {
        given: "Create supporting infrastructure but don't create the DteEmailProcessing record"
        createTestEmailMessage()
        createTestJobRun()

        when: "Try to create without emailMessageId (PK)"
        ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                .parameters([
                        jobRunId: testJobRunId,
                        statusId: "DepReceived"
                ]).call()

        then: "Should fail validation"
        ec.message.hasError()
        ec.message.getErrorsString().contains("emailMessageId")

        cleanup: "Clear expected errors from this error-testing test"
        // This test intentionally triggers errors to test validation
        // Clear them so they don't affect subsequent tests
        ec.message.clearErrors()
    }

    def "010 - Test relationships with valid foreign keys"() {
        given:
        createTestEmailProcessing()

        when:
        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        def jobRun = entity.findRelatedOne("moqui.service.job.ServiceJobRun", null, false)

        then:
        jobRun != null
        jobRun.jobRunId == testJobRunId
        jobRun.jobName == "Test DTE Processing Job"
    }

    def "011 - Test timestamp field behavior"() {
        given:
        def beforeTime = ec.user.nowTimestamp

        when:
        createTestEmailProcessing()

        def afterTime = ec.user.nowTimestamp

        then:
        def entity = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()

        entity.processDate != null
        entity.processDate.time >= beforeTime.time
        entity.processDate.time <= afterTime.time
    }
}
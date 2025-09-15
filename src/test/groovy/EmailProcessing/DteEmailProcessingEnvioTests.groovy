package EmailProcessing

import org.moqui.BaseException
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Comprehensive test suite for DteEmailProcessingEnvio entity functionality.
 * Tests cover CRUD operations, composite primary key handling, status transitions,
 * attachment metadata, and relationships with DteEmailProcessing and DteEnvio entities.
 */
class DteEmailProcessingEnvioTests extends Specification {
    @Shared ExecutionContext ec
    @Shared Logger logger = LoggerFactory.getLogger(DteEmailProcessingEnvioTests.class)

    // Shared infrastructure IDs (used across all tests for setup)
    @Shared String testEmailMessageId = "TEST-EMAIL-ENVIO-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testEnvioId = "TEST-ENVIO-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testEnvioId2 = "TEST-ENVIO-2-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testJobRunId = "TEST-JOB-ENVIO-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testIssuerPartyId = "TEST-ENVIO-ORG-${UUID.randomUUID().toString().substring(0, 8)}"
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

        ec.artifactExecution.disableAuthz()

        // CRITICAL: Clean up any stale test data from previous failed runs first
        try {
            cleanupTestInfrastructure()
            cleanupTestData()
        } catch (Exception e) {
            logger.warn("Error during initial cleanup (expected if no previous data): ${e.message}")
        }

        // Set unique sequence ranges for test isolation AFTER cleanup
        ec.entity.tempSetSequencedIdPrimary("moqui.basic.email.EmailMessage", 78000, 50)
        ec.entity.tempSetSequencedIdPrimary("moqui.service.job.ServiceJobRun", 79000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.DteEnvio", 80000, 50)
        ec.entity.tempSetSequencedIdPrimary("mantle.party.Party", 81000, 50)

        // Create test infrastructure data
        createTestInfrastructure()
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
        // CRITICAL: Clear errors from previous tests for test independence
        ec.message.clearErrors()

        // Start fresh transaction for each test
        ec.transaction.begin(null)

        // Disable authorization for test operations
        ec.artifactExecution.disableAuthz()

        logger.debug("Test setup completed - using shared infrastructure with per-test cleanup")
    }

    def cleanup() {
        try {
            // Clean up test-specific data first (before transaction handling)
            cleanupPerTestData()

            // Handle transaction properly based on status
            if (ec.transaction.status == javax.transaction.Status.STATUS_MARKED_ROLLBACK) {
                ec.transaction.rollback("Test marked for rollback", null)
                logger.debug("Test transaction rolled back as expected")
            } else {
                ec.transaction.commit()
                logger.debug("Test transaction committed successfully")
            }
        } catch (Exception e) {
            logger.warn("Error during test cleanup: ${e.message}")
            try {
                if (ec.transaction.isTransactionInPlace()) {
                    ec.transaction.rollback("Cleanup error", e)
                }
            } catch (Exception rollbackError) {
                logger.error("Failed to rollback transaction during cleanup: ${rollbackError.message}")
            }
        } finally {
            // Always re-enable authorization
            ec.artifactExecution.enableAuthz()

            // Follow Agent OS pattern: warn about unexpected errors but don't clear them
            // This allows tests to fail if there are genuine issues
            if (ec.message.hasError()) {
                logger.warn("Unexpected errors after test execution: ${ec.message.getErrorsString()}")
                // DO NOT clear errors - let them bubble up to identify real issues
            }
        }
    }

    // ========================================
    // Per-Test Data Management
    // ========================================

    private void cleanupPerTestData() {
        try {
            // Clean up any DteEmailProcessingEnvio records created during the test
            // Use shared infrastructure IDs since that's what tests actually use
            int deletedCount = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                    .condition("emailMessageId", testEmailMessageId)
                    .deleteAll()

            if (deletedCount > 0) {
                logger.debug("Cleaned up ${deletedCount} DteEmailProcessingEnvio records for test")
            }
        } catch (Exception e) {
            logger.warn("Error cleaning up per-test data: ${e.message}")
            // Continue with cleanup - don't fail test due to cleanup issues
        }
    }

    // ========================================
    // Test Helper Methods
    // ========================================

    private void createTestInfrastructure() {
        // Create test organization
        def existingParty = ec.entity.find("mantle.party.Party").condition("partyId", testIssuerPartyId).one()
        if (!existingParty) {
            ec.service.sync().name("mantle.party.PartyServices.create#Organization")
                    .parameters([
                            partyId: testIssuerPartyId,
                            organizationName: "Test Email Processing Envio Organization",
                            roleTypeId: "OrgInternal"
                    ]).call()
        }

        // Create test email message
        def existingEmail = ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", testEmailMessageId).one()
        if (!existingEmail) {
            ec.service.sync().name("create#moqui.basic.email.EmailMessage")
                    .parameters([
                            emailMessageId: testEmailMessageId,
                            subject: "Test Email for Envio Processing",
                            body: "Test email with multiple DTE attachments",
                            fromAddress: "dte@sii.cl",
                            toAddresses: "test@company.com",
                            sentDate: ec.user.nowTimestamp,
                            statusId: "ES_RECEIVED"
                    ]).call()
        }

        // Create test service job first (required for ServiceJobRun foreign key)
        def testJobName = "Test Envio Processing Job"
        def existingJob = ec.entity.find("moqui.service.job.ServiceJob")
                .condition("jobName", testJobName).one()
        if (!existingJob) {
            ec.service.sync().name("create#moqui.service.job.ServiceJob")
                    .parameters([
                            jobName: testJobName,
                            serviceName: "mchile.dte.EmailProcessingServices.process#EmailDteEnvios",
                            description: "Test job for email DTE envio processing",
                            cronExpression: "0 0 0 * * ?" // Dummy cron expression for test
                    ]).call()
        }

        // Create test job run
        def existingJobRun = ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", testJobRunId).one()
        if (!existingJobRun) {
            ec.service.sync().name("create#moqui.service.job.ServiceJobRun")
                    .parameters([
                            jobRunId: testJobRunId,
                            jobName: testJobName,
                            serviceName: "mchile.dte.EmailProcessingServices.process#EmailDteEnvios",
                            startTime: ec.user.nowTimestamp,
                            hasError: "N"
                    ]).call()
        }

        // Create parent DteEmailProcessing record following proper status flow
        def existingProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()
        if (!existingProcessing) {
            // Create with initial status and progress through valid status flow
            ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                    .parameters([
                            emailMessageId: testEmailMessageId,
                            jobRunId: testJobRunId,
                            statusId: "DepReceived",
                            processDate: ec.user.nowTimestamp
                    ]).call()

            // Progress through valid status flow: DepReceived -> DepProcessed
            ec.service.sync().name("update#mchile.dte.DteEmailProcessing")
                    .parameters([emailMessageId: testEmailMessageId, statusId: "DepProcessed"])
                    .call()
        }

        // Create test DteEnvio records
        createTestEnvio(testEnvioId, "Ftdt-33")
        createTestEnvio(testEnvioId2, "Ftdt-34")
    }

    private void createTestEnvio(String envioId, String documentType) {
        def existingEnvio = ec.entity.find("mchile.dte.DteEnvio")
                .condition("envioId", envioId).one()
        if (!existingEnvio) {
            ec.service.sync().name("create#mchile.dte.DteEnvio")
                    .parameters([
                            envioId: envioId,
                            envioTypeEnumId: documentType,
                            issuerPartyId: testIssuerPartyId,
                            receiverPartyId: testIssuerPartyId, // Self for test
                            statusId: "Ftde-Created",
                            emailMessageId: testEmailMessageId,
                            rutEmisor: "76514104-4",
                            rutReceptor: "76514104-4"
                    ]).call()
        }
    }

    private void cleanupTestData() {
        // Delete test email processing envio records only (child records)
        // DO NOT delete parent DteEmailProcessing records - they're needed for subsequent tests
        ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .deleteAll()
    }

    private void cleanupTestInfrastructure() {
        // Ensure transaction is available for cleanup operations
        boolean transactionWasActive = ec.transaction.isTransactionInPlace()

        try {
            // Start transaction if none exists
            if (!transactionWasActive) {
                ec.transaction.begin(null)
            }

            // BRUTE FORCE CLEANUP: Delete ALL test data that could interfere, not just current test IDs
            // This handles stale data from previous failed test runs

            // 1. Delete ALL DteEmailProcessingEnvio records for ANY test email message
            ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                    .condition("emailMessageId", "like", "TEST-EMAIL-ENVIO-%")
                    .deleteAll()

            // 2. Delete ALL DteEmailProcessing records for ANY test email message
            ec.entity.find("mchile.dte.DteEmailProcessing")
                    .condition("emailMessageId", "like", "TEST-EMAIL-ENVIO-%")
                    .deleteAll()

            // 3. Delete ALL test ServiceJobRun records (by job name pattern)
            ec.entity.find("moqui.service.job.ServiceJobRun")
                    .condition("jobName", "Test Envio Processing Job")
                    .deleteAll()

            // 4. Delete ALL test ServiceJob records
            ec.entity.find("moqui.service.job.ServiceJob")
                    .condition("jobName", "Test Envio Processing Job")
                    .deleteAll()

            // 5. Delete ALL test DteEnvio records first (they reference EmailMessage)
            ec.entity.find("mchile.dte.DteEnvio")
                    .condition("envioId", "like", "TEST-ENVIO-%")
                    .deleteAll()

            // 6. Now delete ALL test email messages (no longer referenced)
            ec.entity.find("moqui.basic.email.EmailMessage")
                    .condition("emailMessageId", "like", "TEST-EMAIL-ENVIO-%")
                    .deleteAll()

            // Delete test organization with proper cleanup of related records
            // First delete PartyRole records that reference ANY test Party
            ec.entity.find("mantle.party.PartyRole")
                    .condition("partyId", "like", "TEST-ENVIO-ORG-%")
                    .deleteAll()

            // Delete Organization record that references ANY test Party
            ec.entity.find("mantle.party.Organization")
                    .condition("partyId", "like", "TEST-ENVIO-ORG-%")
                    .deleteAll()

            // Then delete ANY test Party
            ec.entity.find("mantle.party.Party")
                    .condition("partyId", "like", "TEST-ENVIO-ORG-%")
                    .deleteAll()

            // Commit transaction if we started it
            if (!transactionWasActive) {
                ec.transaction.commit()
            }

        } catch (Exception e) {
            // Handle transaction rollback if we started the transaction
            if (!transactionWasActive && ec.transaction.isTransactionInPlace()) {
                try {
                    ec.transaction.rollback("Cleanup error", e)
                } catch (Exception rollbackError) {
                    logger.error("Failed to rollback cleanup transaction: ${rollbackError.message}")
                }
            }

            // Log cleanup errors but don't fail the test suite for cleanup issues
            logger.warn("Error during test infrastructure cleanup: ${e.message}")
            // Don't throw exception for cleanup issues to prevent test suite failures
        }
    }

    private Map createTestEmailProcessingEnvio(Map params = [:]) {
        // Use shared infrastructure IDs to maintain foreign key relationships
        def defaultParams = [
                emailMessageId: testEmailMessageId,
                envioId: testEnvioId,
                attachmentSeqId: "1",
                attachmentFileName: "test-dte-envio.xml"
        ]
        defaultParams.putAll(params)

        def result = ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters(defaultParams)
                .call()

        // Log detailed error information if service call fails
        if (ec.message.hasError()) {
            logger.error("Service call failed - Errors: ${ec.message.getErrorsString()}")
            logger.error("Service call parameters: ${defaultParams}")
        }

        return result
    }

    // ========================================
    // CRUD Operations Tests
    // ========================================

    def "001 - Create DteEmailProcessingEnvio with minimal required fields"() {
        when:
        // Verify required dependencies exist
        def emailMsg = ec.entity.find("moqui.basic.email.EmailMessage").condition("emailMessageId", testEmailMessageId).one()
        def jobRun = ec.entity.find("moqui.service.job.ServiceJobRun").condition("jobRunId", testJobRunId).one()
        def envio = ec.entity.find("mchile.dte.DteEnvio").condition("envioId", testEnvioId).one()
        def processing = ec.entity.find("mchile.dte.DteEmailProcessing").condition("emailMessageId", testEmailMessageId).one()

        logger.debug("Dependencies check: EmailMessage=${emailMsg != null}, JobRun=${jobRun != null}, Envio=${envio != null}, Processing=${processing != null}")
        if (processing) logger.debug("Processing status: ${processing.statusId}")

        def result = createTestEmailProcessingEnvio()

        logger.debug("Service result: ${result}")
        logger.debug("Has errors: ${ec.message.hasError()}")
        if (ec.message.hasError()) logger.error("Service errors: ${ec.message.getErrorsString()}")

        then:
        !ec.message.hasError()
        // NOTE: Auto-services may not return created record fields, so check the database directly

        // Verify entity was created with shared infrastructure IDs
        def created = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .condition("envioId", testEnvioId)
                .one()

        created != null
        created.emailMessageId == testEmailMessageId
        created.envioId == testEnvioId
        created.attachmentFileName == "test-dte-envio.xml"
    }

    def "002 - Create DteEmailProcessingEnvio with all fields populated"() {
        given:
        def testErrorMessage = "Test error for comprehensive validation"

        when:
        def result = createTestEmailProcessingEnvio([
                envioId: testEnvioId2,
                attachmentSeqId: "2",
                errorMessage: testErrorMessage,
                attachmentFileName: "segundo-envio-dte.xml"
        ])

        then:
        !ec.message.hasError()
        // NOTE: Auto-services may not return created record fields, so check the database directly

        // Verify all fields were saved correctly using shared infrastructure IDs
        def created = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .condition("envioId", testEnvioId2)
                .one()

        created != null
        created.emailMessageId == testEmailMessageId
        created.envioId == testEnvioId2
        created.errorMessage == testErrorMessage
        created.attachmentSeqId == "2"
        created.attachmentFileName == "segundo-envio-dte.xml"
    }

    def "003 - Read DteEmailProcessingEnvio with relationships"() {
        given:
        createTestEmailProcessingEnvio([
                attachmentFileName: "validated-dte.xml"
        ])

        when:
        def envioProcessing = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .condition("envioId", testEnvioId)
                .one()

        then:
        envioProcessing != null

        // Test relationship to DteEmailProcessing using shared infrastructure data
        def emailProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .one()
        emailProcessing != null
        emailProcessing.emailMessageId == testEmailMessageId
        // Note: DteEmailProcessing no longer has envioId field - it's now in DteEmailProcessingEnvio
        emailProcessing.statusId == "DepProcessed"

        // Test relationship to DteEnvio using shared infrastructure data
        def dteEnvio = ec.entity.find("mchile.dte.DteEnvio")
                .condition("envioId", testEnvioId)
                .one()
        dteEnvio != null
        dteEnvio.envioId == testEnvioId
        dteEnvio.issuerPartyId == testIssuerPartyId
    }

    def "004 - Update DteEmailProcessingEnvio fields"() {
        given:
        createTestEmailProcessingEnvio()

        when:
        def updateResult = ec.service.sync().name("update#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        envioId: testEnvioId,
                        errorMessage: "Updated error message for testing"
                ]).call()

        then:
        !ec.message.hasError()

        // Verify updates were applied using shared infrastructure IDs
        def updated = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .condition("envioId", testEnvioId)
                .one()

        updated.errorMessage == "Updated error message for testing"
    }

    def "005 - Delete DteEmailProcessingEnvio using primary key"() {
        given:
        createTestEmailProcessingEnvio()

        when:
        def deleteResult = ec.service.sync().name("delete#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        envioId: testEnvioId
                ]).call()

        then:
        !ec.message.hasError()

        // Verify deletion using shared infrastructure IDs
        def deleted = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .condition("envioId", testEnvioId)
                .one()

        deleted == null
    }

    // ========================================
    // Basic Entity Tests
    // ========================================

    def "006 - Test multiple envios for same email with different attachment sequences"() {
        when: "Create multiple envios with different attachment sequences"
        def result1 = createTestEmailProcessingEnvio([
                envioId: testEnvioId,
                attachmentSeqId: "1",
                attachmentFileName: "first-attachment.xml"
        ])

        def result2 = createTestEmailProcessingEnvio([
                envioId: testEnvioId2,
                attachmentSeqId: "2",
                attachmentFileName: "second-attachment.xml"
        ])

        then:
        !ec.message.hasError()

        // Verify both records exist using shared infrastructure IDs
        def envio1 = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition([emailMessageId: testEmailMessageId, envioId: testEnvioId])
                .one()

        def envio2 = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition([emailMessageId: testEmailMessageId, envioId: testEnvioId2])
                .one()

        envio1 != null
        envio2 != null
        envio1.attachmentFileName != envio2.attachmentFileName
    }

    def "007 - Test multiple envios in same email"() {
        when: "Create processing records for multiple envios from same email"
        def result1 = createTestEmailProcessingEnvio([
                envioId: testEnvioId,
                attachmentSeqId: "1",
                attachmentFileName: "factura-electronica.xml"
        ])

        def result2 = createTestEmailProcessingEnvio([
                envioId: testEnvioId2,
                attachmentSeqId: "2",
                attachmentFileName: "factura-exenta.xml"
        ])

        then:
        !ec.message.hasError()

        // Verify both envios are tracked separately using shared infrastructure IDs
        def envio1Processing = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition([emailMessageId: testEmailMessageId, envioId: testEnvioId])
                .one()

        def envio2Processing = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition([emailMessageId: testEmailMessageId, envioId: testEnvioId2])
                .one()

        envio1Processing != null
        envio2Processing != null
        envio1Processing.attachmentSeqId == "1"
        envio2Processing.attachmentSeqId == "2"
    }

    def "008 - Test primary key uniqueness constraint"() {
        given:
        createTestEmailProcessingEnvio()

        when: "Try to create duplicate with same primary key"
        def duplicateResult = ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        envioId: testEnvioId, // Same key
                        attachmentSeqId: "2"
                ]).call()

        then:
        ec.message.hasError()
        ec.message.getErrorsString().contains("Unique index or primary key violation")
        ec.message.clearErrors()
    }

    // ========================================
    // Error Message Tests
    // ========================================

    def "009 - Test error message handling"() {
        given:
        createTestEmailProcessingEnvio()

        when: "Update with error message"
        def result = ec.service.sync().name("update#mchile.dte.DteEmailProcessingEnvio")
                .parameters([
                        emailMessageId: testEmailMessageId,
                        envioId: testEnvioId,
                        errorMessage: "Test error message for validation"
                ]).call()

        then:
        !ec.message.hasError()
        def entity = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition([emailMessageId: testEmailMessageId, envioId: testEnvioId]).one()
        entity.errorMessage == "Test error message for validation"
    }

    def "010 - Test attachment filename handling"() {
        given:
        def longFilename = "very-long-attachment-filename-that-tests-field-constraints.xml"

        when:
        def result = createTestEmailProcessingEnvio([
                attachmentFileName: longFilename
        ])

        then:
        !ec.message.hasError()

        def entity = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition([emailMessageId: testEmailMessageId, envioId: testEnvioId]).one()

        entity.attachmentFileName != null
        entity.attachmentFileName.length() > 0
    }
}
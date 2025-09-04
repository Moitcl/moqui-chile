package EmailProcessing

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import java.util.UUID

/**
 * Comprehensive test suite for DteEmailProcessingEnvio entity functionality.
 * Tests cover CRUD operations, composite primary key handling, status transitions,
 * attachment metadata, and relationships with DteEmailProcessing and DteEnvio entities.
 */
class DteEmailProcessingEnvioTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String testEmailMessageId = "TEST-EMAIL-ENVIO-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testEnvioId = "TEST-ENVIO-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testEnvioId2 = "TEST-ENVIO-2-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testJobRunId = "TEST-JOB-ENVIO-${UUID.randomUUID().toString().substring(0, 8)}"
    @Shared String testIssuerPartyId = "TEST-ENVIO-ORG"
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
        ec.entity.tempSetSequencedIdPrimary("moqui.basic.email.EmailMessage", 78000, 50)
        ec.entity.tempSetSequencedIdPrimary("moqui.service.job.ServiceJobRun", 79000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.DteEnvio", 80000, 50)
        ec.entity.tempSetSequencedIdPrimary("mantle.party.Party", 81000, 50)

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
                            statusId: "EmsgReceived"
                    ]).call()
        }

        // Create test job run
        def existingJobRun = ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", testJobRunId).one()
        if (!existingJobRun) {
            ec.service.sync().name("create#moqui.service.job.ServiceJobRun")
                    .parameters([
                            jobRunId: testJobRunId,
                            jobName: "Test Envio Processing Job",
                            serviceName: "mchile.dte.EmailProcessingServices.process#EmailDteEnvios",
                            startTime: ec.user.nowTimestamp,
                            hasError: "N"
                    ]).call()
        }

        // Create parent DteEmailProcessing record
        def existingProcessing = ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId).one()
        if (!existingProcessing) {
            ec.service.sync().name("create#mchile.dte.DteEmailProcessing")
                    .parameters([
                            emailMessageId: testEmailMessageId,
                            jobRunId: testJobRunId,
                            statusId: "DepEnviosDetected",
                            totalEnviosFound: 2,
                            totalEnviosProcessed: 0,
                            totalEnviosError: 0,
                            processingAttemptCount: 1
                    ]).call()
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
                            statusId: "DteEnvioCreated",
                            emailMessageId: testEmailMessageId,
                            rutEmisor: "76514104-4",
                            rutReceptor: "76514104-4"
                    ]).call()
        }
    }

    private void cleanupTestData() {
        // Delete test email processing envio records
        ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .deleteAll()
    }

    private void cleanupTestInfrastructure() {
        // Delete test DteEnvio records
        ec.entity.find("mchile.dte.DteEnvio")
                .condition("envioId", testEnvioId)
                .deleteAll()
        ec.entity.find("mchile.dte.DteEnvio")
                .condition("envioId", testEnvioId2)
                .deleteAll()

        // Delete parent processing record
        ec.entity.find("mchile.dte.DteEmailProcessing")
                .condition("emailMessageId", testEmailMessageId)
                .deleteAll()

        // Delete test email message
        ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", testEmailMessageId)
                .deleteAll()
        
        // Delete test job run
        ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", testJobRunId)
                .deleteAll()

        // Delete test organization
        ec.entity.find("mantle.party.Party")
                .condition("partyId", testIssuerPartyId)
                .deleteAll()
    }

    private Map createTestEmailProcessingEnvio(Map params = [:]) {
        def defaultParams = [
                emailMessageId: testEmailMessageId,
                envioId: testEnvioId,
                attachmentSeqId: "1",
                attachmentFileName: "test-dte-envio.xml"
        ]
        defaultParams.putAll(params)

        return ec.service.sync().name("create#mchile.dte.DteEmailProcessingEnvio")
                .parameters(defaultParams)
                .call()
    }

    // ========================================
    // CRUD Operations Tests
    // ========================================

    def "001 - Create DteEmailProcessingEnvio with minimal required fields"() {
        when:
        def result = createTestEmailProcessingEnvio()

        then:
        result.emailMessageId == testEmailMessageId
        result.envioId == testEnvioId
        !ec.message.hasError()

        // Verify entity was created with primary key
        def created = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .condition("envioId", testEnvioId)
                .one()

        created != null
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
        result.emailMessageId == testEmailMessageId
        result.envioId == testEnvioId2
        !ec.message.hasError()

        // Verify all fields were saved correctly
        def created = ec.entity.find("mchile.dte.DteEmailProcessingEnvio")
                .condition("emailMessageId", testEmailMessageId)
                .condition("envioId", testEnvioId2)
                .one()

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

        // Test relationship to DteEmailProcessing
        def emailProcessing = envioProcessing.getRelatedOne("mchile.dte.DteEmailProcessing", null, false)
        emailProcessing != null
        emailProcessing.emailMessageId == testEmailMessageId
        emailProcessing.statusId == "DepEnviosDetected"

        // Test relationship to DteEnvio
        def dteEnvio = envioProcessing.getRelatedOne("mchile.dte.DteEnvio", null, false)
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

        // Verify updates were applied
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

        // Verify deletion
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

        // Verify both records exist
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

        // Verify both envios are tracked separately
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
        ec.message.getErrorsString().contains("duplicate") || 
        ec.message.getErrorsString().contains("unique") ||
        ec.message.getErrorsString().contains("constraint")
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
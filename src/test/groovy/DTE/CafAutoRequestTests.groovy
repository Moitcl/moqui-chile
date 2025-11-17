package DTE

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import java.util.UUID

/**
 * Comprehensive test suite for CAF Auto Request functionality.
 * Tests cover folio availability calculation, auto-request triggering,
 * rate limiting, and edge cases with different CAF states.
 */
class CafAutoRequestTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String testIssuerPartyId = "TEST-CAF-AUTO-ORG"
    @Shared String testDocumentType = "Ftdt-33" // Electronic Invoice
    @Shared String testUsername = "john.doe"

    def setupSpec() {
        // Initialize framework
        ec = Moqui.getExecutionContext()

        // Login test user
        ec.user.loginUser(testUsername, "moqui")

        // Set unique sequence ranges for test isolation
        ec.entity.tempSetSequencedIdPrimary("mantle.party.Party", 70000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.Caf", 80000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.FiscalTaxDocument", 90000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.CafAutoRequest", 95000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.CafAutoRequestLog", 96000, 50)

        ec.artifactExecution.disableAuthz()

        // Create test organization
        createTestOrganization()

        // Clear any existing configurations for clean test state
        cleanupTestData()
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        // Cleanup test data
        cleanupTestData()

        // Clear error messages
        ec.message.clearErrors()

        // Logout
        ec.user.logoutUser()
    }

    def setup() {
        ec.message.clearAll()  // Clear any messages from previous tests
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)  // Begin new transaction for each test
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

    private void createTestOrganization() {
        // Create test organization if it doesn't exist
        def existingParty = ec.entity.find("mantle.party.Party").condition("partyId", testIssuerPartyId).one()
        if (!existingParty) {
            ec.service.sync().name("mantle.party.PartyServices.create#Organization")
                    .parameters([
                            partyId: testIssuerPartyId,
                            organizationName: "Test CAF Auto Request Organization",
                            roleTypeId: "OrgInternal"
                    ]).call()
        }
    }

    private void cleanupTestData() {
        // Delete test configurations
        ec.entity.find("mchile.dte.CafAutoRequest")
                .condition("partyId", testIssuerPartyId)
                .deleteAll()

        // Delete test logs
        ec.entity.find("mchile.dte.CafAutoRequestLog")
                .condition("partyId", testIssuerPartyId)
                .deleteAll()

        // Delete test FiscalTaxDocuments
        ec.entity.find("mchile.dte.FiscalTaxDocument")
                .condition("issuerPartyId", testIssuerPartyId)
                .deleteAll()

        // Delete test CAFs
        ec.entity.find("mchile.dte.Caf")
                .condition("issuerPartyId", testIssuerPartyId)
                .deleteAll()
    }

    private String createTestCaf(int desde, int hasta, boolean activo = true, Date fechaAutorizacion = null) {
        def cafId = "TEST-CAF-${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 8)}"

        ec.service.sync().name("create#mchile.dte.Caf")
                .parameters([
                        cafId: cafId,
                        issuerPartyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType,
                        desde: desde,
                        hasta: hasta,
                        activo: activo,
                        fechaAutorizacion: fechaAutorizacion ?: new java.sql.Timestamp(System.currentTimeMillis()),
                        cafFragment: "<CAF>test</CAF>",
                        publicKey: "test-public-key",
                        privateKey: "test-private-key"
                ]).call()
        for(int i = desde; i <= hasta; i++) {
            storeTestFiscalTaxDocument(i)
        }

        return cafId
    }

    private void updateTestFiscalTaxDocument(int folioNumber, String statusId = "Ftd-NotIssued") {
        def documentId = "${testDocumentType.split('-')[1]}-${testIssuerPartyId.replace('-', '')}-${folioNumber}"
        ec.service.sync().name("store#mchile.dte.FiscalTaxDocument")
                .parameters([
                        fiscalTaxDocumentId: documentId,
                        statusId: statusId
                ]).call()
    }
    private void storeTestFiscalTaxDocument(int folioNumber, String statusId = "Ftd-NotIssued") {
        def documentId = "${testDocumentType.split('-')[1]}-${testIssuerPartyId.replace('-', '')}-${folioNumber}"

        ec.service.sync().name("store#mchile.dte.FiscalTaxDocument")
                .parameters([
                        fiscalTaxDocumentId: documentId,
                        issuerPartyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType,
                        fiscalTaxDocumentNumber: folioNumber,
                        statusId: statusId,
                        issuerPartyIdValue: "13477571-8",
                        sentAuthStatusId: "Ftd-NotSentAuth",
                        sentRecStatusId: "Ftd-NotSentRec",
                        issuerPartyIdTypeEnumId: "PtidNationalTaxId"
                ]).call()
    }

    private String createTestAutoRequestConfig(int threshold = 10, int fetchQuantity = 50, int minimumHours = 6) {
        ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.create#CafAutoRequestConfig")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType,
                        minimumThreshold: threshold,
                        fetchQuantity: fetchQuantity,
                        minimumHoursBetweenFetches: minimumHours
                ]).call()

        return "${testIssuerPartyId}-${testDocumentType}"
    }

    // ========================================
    // Folio Availability Calculation Tests
    // ========================================

    def "001 Calculate availability with no CAFs"() {
        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.check#FolioAvailability")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        then:
        result.totalAvailableFolios == 0
        result.activeCafCount == 0
        result.folioBreakdownList.size() == 0
        result.hasExpiredCafs == false
        !result.cacheHit
    }

    def "002 Calculate availability with single active CAF and no documents"() {
        given:
        createTestCaf(1, 100, true)

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.check#FolioAvailability")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        then:
        result.totalAvailableFolios == 100
        result.activeCafCount == 1
        result.folioBreakdownList.size() == 1
        result.folioBreakdownList.get(0).totalAmount == 100
        result.folioBreakdownList.get(0).usedFolios == 0
        result.folioBreakdownList.get(0).availableFolios == 100
        result.folioBreakdownList.get(0).isActive == true
        result.folioBreakdownList.get(0).isExpired == false
    }

    def "003 Calculate availability with partially used CAF"() {
        given:
        createTestCaf(1, 100, true)

        // Create some used folios
        updateTestFiscalTaxDocument(1, "Ftd-Issued")
        updateTestFiscalTaxDocument(2, "Ftd-Issued")
        updateTestFiscalTaxDocument(3, "Ftd-Blocked")
        // Folios 4-100 remain available

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.check#FolioAvailability")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        then:
        result.totalAvailableFolios == 97 // 100 - 3 used
        result.activeCafCount == 1
        result.folioBreakdownList.get(0).totalAmount == 100
        result.folioBreakdownList.get(0).usedFolios == 3
        result.folioBreakdownList.get(0).availableFolios == 97
    }

    def "004 Calculate availability with multiple active CAFs"() {
        given:
        createTestCaf(1, 50, true)    // 50 folios
        createTestCaf(51, 100, true)  // 50 folios
        createTestCaf(101, 200, true) // 100 folios

        // Use some folios from different CAFs
        updateTestFiscalTaxDocument(25, "Ftd-Issued")   // From first CAF
        updateTestFiscalTaxDocument(75, "Ftd-Issued")   // From second CAF
        updateTestFiscalTaxDocument(150, "Ftd-Blocked") // From third CAF

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.check#FolioAvailability")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        then:
        result.totalAvailableFolios == 197 // 200 - 3 used
        result.activeCafCount == 3
        result.folioBreakdownList.size() == 3

        // Verify each CAF breakdown
        def breakdown1 = result.folioBreakdownList.find { it.rangeStart == 1 }
        breakdown1.totalAmount == 50
        breakdown1.usedFolios == 1
        breakdown1.availableFolios == 49

        def breakdown2 = result.folioBreakdownList.find { it.rangeStart == 51 }
        breakdown2.totalAmount == 50
        breakdown2.usedFolios == 1
        breakdown2.availableFolios == 49

        def breakdown3 = result.folioBreakdownList.find { it.rangeStart == 101 }
        breakdown3.totalAmount == 100
        breakdown3.usedFolios == 1
        breakdown3.availableFolios == 99
    }

    def "005 Calculate availability excluding inactive CAFs"() {
        given:
        createTestCaf(1, 50, true)   // Active CAF
        createTestCaf(51, 100, false) // Inactive CAF

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.check#FolioAvailability")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        then:
        result.totalAvailableFolios == 50 // Only from active CAF
        result.activeCafCount == 1
        result.folioBreakdownList.size() == 2 // Both CAFs in breakdown but only active counted

        def activeBreakdown = result.folioBreakdownList.find { it.isActive }
        activeBreakdown.availableFolios == 50

        def inactiveBreakdown = result.folioBreakdownList.find { !it.isActive }
        inactiveBreakdown.availableFolios == 0 // Not counted as available
    }

    def "006 Calculate availability excluding expired CAFs"() {
        given:
        // Create active CAF
        createTestCaf(1, 50, true)

        // Create expired CAF (older than 6 months)
        def eightMonthsAgo = new Date(System.currentTimeMillis() - (8L * 30L * 24L * 60L * 60L * 1000L))
        createTestCaf(51, 100, true, eightMonthsAgo)

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.check#FolioAvailability")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        then:
        result.totalAvailableFolios == 50 // Only from non-expired CAF
        result.activeCafCount == 1
        result.hasExpiredCafs == true
        result.folioBreakdownList.size() == 2

        def expiredBreakdown = result.folioBreakdownList.find { it.isExpired }
        expiredBreakdown != null
        expiredBreakdown.availableFolios == 0 // Not counted as available
    }

    def "007 Test consistent calculation results"() {
        given:
        createTestCaf(1, 100, true)

        when: "First call"
        def result1 = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.check#FolioAvailability")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        and: "Second call should return consistent results (Moqui entity caching optimizes performance)"
        def result2 = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.check#FolioAvailability")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        then:
        result1.totalAvailableFolios == result2.totalAvailableFolios
        result1.activeCafCount == result2.activeCafCount
        // Both calls should return the same data (Moqui framework handles caching)
    }

    // ========================================
    // CAF Auto Request Configuration Tests
    // ========================================

    def "008 Create CAF auto request configuration"() {
        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.create#CafAutoRequestConfig")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType,
                        minimumThreshold: 20,
                        fetchQuantity: 100,
                        minimumHoursBetweenFetches: 6
                ]).call()

        then:
        result.configCreated == true

        // Verify configuration was created
        def config = ec.entity.find("mchile.dte.CafAutoRequest")
                .condition("partyId", testIssuerPartyId)
                .condition("fiscalTaxDocumentTypeEnumId", testDocumentType)
                .one()

        config != null
        config.minimumThreshold == 20
        config.fetchQuantity == 100
        config.minimumHoursBetweenFetches == 6
    }

    def "009 Update CAF auto request configuration"() {
        given:
        createTestAutoRequestConfig(10, 50, 6)

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.update#CafAutoRequestConfig")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType,
                        minimumThreshold: 30,
                        fetchQuantity: 75,
                        isActive: "N"
                ]).call()

        then:
        result.configUpdated == true

        // Verify configuration was updated
        def config = ec.entity.find("mchile.dte.CafAutoRequest")
                .condition("partyId", testIssuerPartyId)
                .condition("fiscalTaxDocumentTypeEnumId", testDocumentType)
                .one()

        config.minimumThreshold == 30
        config.fetchQuantity == 75
        config.isActive == "N"
        config.minimumHoursBetweenFetches == 6 // Unchanged
    }

    def "010 Delete CAF auto request configuration"() {
        given:
        createTestAutoRequestConfig()

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.delete#CafAutoRequestConfig")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        then:
        result.configDeleted == true

        // Verify configuration was deleted
        def config = ec.entity.find("mchile.dte.CafAutoRequest")
                .condition("partyId", testIssuerPartyId)
                .condition("fiscalTaxDocumentTypeEnumId", testDocumentType)
                .one()

        config == null
    }

    // ========================================
    // CAF Auto Request Execution Tests
    // ========================================

    def "011 Auto request execution with no configurations"() {
        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.run#CafAutoRequest")
                .parameters([
                        dryRun: true
                ]).call()

        then:
        result.processedConfigs == 0
        result.requestsTriggered == 0
        result.errorList.size() == 0
        result.configResultList.size() == 0
    }

    def "012 Auto request execution with threshold not breached"() {
        given:
        createTestCaf(1, 100, true) // 100 available folios
        createTestAutoRequestConfig(50, 100, 6) // Threshold 50, fetch 100

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.run#CafAutoRequest")
                .parameters([
                        specificPartyId: testIssuerPartyId,
                        dryRun: true
                ]).call()

        then:
        result.processedConfigs == 1
        result.requestsTriggered == 0
        result.errorList.size() == 0
        result.configResultList.size() == 1
        result.configResultList.get(0).actionTaken == "NO_ACTION_NEEDED"
        result.configResultList.get(0).currentAvailable == 100
        result.configResultList.get(0).canFetch == true
    }

    def "013 Auto request execution with threshold breached (dry run)"() {
        given:
        createTestCaf(1, 100, true) // 100 total folios

        // Use up most folios, leaving only 5 available
        (1..95).each { folioNum ->
            updateTestFiscalTaxDocument(folioNum, "Ftd-Issued")
        }

        createTestAutoRequestConfig(10, 50, 6) // Threshold 10, fetch 50

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.run#CafAutoRequest")
                .parameters([
                        specificPartyId: testIssuerPartyId,
                        dryRun: true
                ]).call()

        then:
        result.processedConfigs == 1
        result.requestsTriggered == 0 // Dry run doesn't trigger
        result.errorList.size() == 0
        result.configResultList.size() == 1
        result.configResultList.get(0).actionTaken == "FETCH_TRIGGERED"
        result.configResultList.get(0).currentAvailable == 5
        result.configResultList.get(0).fetchSuccessful == true
        result.configResultList.get(0).dryRunNote != null
    }

    def "014 Auto request execution with rate limiting"() {
        given:
        createTestCaf(1, 100, true)

        // Use up most folios
        (1..95).each { folioNum ->
            updateTestFiscalTaxDocument(folioNum, "Ftd-Issued")
        }

        createTestAutoRequestConfig(10, 50, 6) // Threshold 10, fetch 50, 6 hours minimum

        // Set lastAutoFetchDate to 2 hours ago (within 6-hour limit)
        def twoHoursAgo = new java.sql.Timestamp(System.currentTimeMillis() - (2L * 60L * 60L * 1000L))
        ec.service.sync().name("update#mchile.dte.CafAutoRequest")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType,
                        lastAutoFetchDate: twoHoursAgo
                ]).call()

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.run#CafAutoRequest")
                .parameters([
                        specificPartyId: testIssuerPartyId,
                        dryRun: true
                ]).call()

        then:
        result.processedConfigs == 1
        result.requestsTriggered == 0
        result.configResultList.size() == 1
        result.configResultList.get(0).actionTaken == "RATE_LIMITED"
        result.configResultList.get(0).canFetch == false
        result.configResultList.get(0).rateLimitReason != null
        result.configResultList.get(0).rateLimitReason.contains("hours remaining")
    }

    def "015 Auto request execution with inactive configuration"() {
        given:
        createTestCaf(1, 100, true)

        // Use up most folios
        (1..95).each { folioNum ->
            updateTestFiscalTaxDocument(folioNum, "Ftd-Issued")
        }

        createTestAutoRequestConfig(10, 50, 6) // Threshold 10, fetch 50

        // Deactivate the configuration
        ec.service.sync().name("update#mchile.dte.CafAutoRequest")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType,
                        isActive: "N"
                ]).call()

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.run#CafAutoRequest")
                .parameters([
                        specificPartyId: testIssuerPartyId,
                        dryRun: true
                ]).call()

        then:
        result.processedConfigs == 0 // Inactive configs not processed
        result.requestsTriggered == 0
        result.configResultList.size() == 0
    }

    def "016 Auto request execution with multiple configurations"() {
        given:
        // Create CAFs for different document types
        createTestCaf(1, 50, true) // For Ftdt-33

        def testDocumentType2 = "Ftdt-34" // Tax-exempt invoice
        ec.service.sync().name("create#mchile.dte.Caf")
                .parameters([
                        cafId: "TEST-CAF-34-${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 8)}",
                        issuerPartyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType2,
                        desde: 1,
                        hasta: 50,
                        activo: "true",
                        fechaAutorizacion: new java.sql.Timestamp(System.currentTimeMillis()),
                        cafFragment: "<CAF>test</CAF>",
                        publicKey: "test-public-key",
                        privateKey: "test-private-key"
                ]).call()

        // Use up folios for Ftdt-33 to trigger auto-request
        (1..45).each { folioNum ->
            updateTestFiscalTaxDocument(folioNum, "Ftd-Issued")
        }

        // Create configurations for both document types
        createTestAutoRequestConfig(10, 50, 6) // For Ftdt-33

        ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.create#CafAutoRequestConfig")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType2,
                        minimumThreshold: 40,
                        fetchQuantity: 50,
                        minimumHoursBetweenFetches: 6
                ]).call()

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.run#CafAutoRequest")
                .parameters([
                        specificPartyId: testIssuerPartyId,
                        dryRun: true
                ]).call()

        then:
        result.processedConfigs == 2
        result.configResultList.size() == 2

        // Ftdt-33 should trigger (5 available < 10 threshold)
        def config33Result = result.configResultList.find { it.fiscalTaxDocumentTypeEnumId == testDocumentType }
        config33Result.actionTaken == "FETCH_TRIGGERED"
        config33Result.currentAvailable == 5

        // Ftdt-34 should not trigger (50 available > 40 threshold)
        def config34Result = result.configResultList.find { it.fiscalTaxDocumentTypeEnumId == testDocumentType2 }
        config34Result.actionTaken == "NO_ACTION_NEEDED"
        config34Result.currentAvailable == 50
    }

    // ========================================
    // Edge Case and Error Handling Tests
    // ========================================

    def "017 Handle negative available folios (data inconsistency)"() {
        given:
        createTestCaf(1, 10, true) // Small CAF with 10 folios

        // Create more documents than CAF range (data inconsistency scenario)
        (1..10).each { folioNum ->
            updateTestFiscalTaxDocument(folioNum, "Ftd-Issued")
        }
        (11..15).each { folioNum ->
            storeTestFiscalTaxDocument(folioNum, "Ftd-Issued")
        }

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.check#FolioAvailability")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        then:
        result.totalAvailableFolios == 0 // Should not go negative
        result.folioBreakdownList.get(0).availableFolios == 0
        result.folioBreakdownList.get(0).usedFolios == 10 // Only counts folios within CAF range
    }

    def "018 Handle mixed document statuses correctly"() {
        given:
        createTestCaf(1, 100, true)

        // Create documents with various statuses
        updateTestFiscalTaxDocument(1, "Ftd-Issued")        // Used
        updateTestFiscalTaxDocument(2, "Ftd-Blocked")       // Used (blocked)
        updateTestFiscalTaxDocument(3, "Ftd-Cancelled")     // Used (cancelled)
        updateTestFiscalTaxDocument(5, "Ftd-Cancelled")     // Used (cancelled)
        // Folios 6-100 are implicitly available (no documents created)

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.check#FolioAvailability")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        then:
        result.totalAvailableFolios == 96 // 100 - 4 used (excludes Ftd-NotIssued)
        result.folioBreakdownList.get(0).usedFolios == 4 // Issued + Blocked + Cancelled + LocallyUnknown
        result.folioBreakdownList.get(0).availableFolios == 96
    }

    @Unroll
    def "019 Test auto request with various threshold scenarios: available=#available, threshold=#threshold, shouldTrigger=#shouldTrigger"() {
        given:
        createTestCaf(1, 100, true)

        // Create used documents to achieve desired available count
        def usedCount = 100 - available
        if (usedCount > 0) {
            (1..usedCount).each { folioNum ->
                updateTestFiscalTaxDocument(folioNum, "Ftd-Issued")
            }
        }

        createTestAutoRequestConfig(threshold, 50, 6)

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.run#CafAutoRequest")
                .parameters([
                        specificPartyId: testIssuerPartyId,
                        dryRun: true
                ]).call()

        then:
        result.processedConfigs == 1
        result.configResultList.get(0).currentAvailable == available
        result.configResultList.get(0).actionTaken == (shouldTrigger ? "FETCH_TRIGGERED" : "NO_ACTION_NEEDED")

        where:
        available | threshold | shouldTrigger
        50        | 20        | false          // 50 >= 20, no action needed
        20        | 20        | false          // 20 >= 20, no action needed
        19        | 20        | true           // 19 < 20, should trigger
        5         | 10        | true           // 5 < 10, should trigger
        0         | 5         | true           // 0 < 5, should trigger
        100       | 150       | true           // 100 < 150, should trigger
    }

    def "020 Test CAF expiration edge cases"() {
        given:
        // Create CAF that expires exactly 6 months ago (boundary condition)
        def sixMonthsAgo = new Date(System.currentTimeMillis() - (6L * 30L * 24L * 60L * 60L * 1000L))
        def almostExpired = new Date(System.currentTimeMillis() - (6L * 30L * 24L * 60L * 60L * 1000L) + (24L * 60L * 60L * 1000L)) // 6 months minus 1 day

        createTestCaf(1, 50, true, sixMonthsAgo)    // Exactly 6 months old (should be expired)
        createTestCaf(51, 100, true, almostExpired) // Almost 6 months old (should be active)

        when:
        def result = ec.service.sync().name("mchile.sii.dte.DteCafAutoRequestServices.check#FolioAvailability")
                .parameters([
                        partyId: testIssuerPartyId,
                        fiscalTaxDocumentTypeEnumId: testDocumentType
                ]).call()

        then:
        result.totalAvailableFolios == 50 // Only from non-expired CAF
        result.activeCafCount == 1
        result.hasExpiredCafs == true

        def expiredCaf = result.folioBreakdownList.find { it.isExpired }
        expiredCaf != null
        expiredCaf.availableFolios == 0

        def activeCaf = result.folioBreakdownList.find { !it.isExpired }
        activeCaf != null
        activeCaf.availableFolios == 50
    }
}

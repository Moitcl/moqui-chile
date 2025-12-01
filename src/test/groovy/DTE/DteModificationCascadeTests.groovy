package DTE

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Timestamp

/**
 * Tests for DTE Modification Cascade Logic
 *
 * Validates that when a ND (Nota de Débito) cancels a NC (Nota de Crédito),
 * the original DTE's modificationAmount and modificationStatusId are properly updated.
 *
 * Test Chain: Original Invoice (Ftdt-33) -> NC (Ftdt-61) -> ND (Ftdt-56)
 *
 * Following Moqui test patterns with proper isolation and cleanup.
 */
class DteModificationCascadeTests extends Specification {
    @Shared protected final static Logger logger = LoggerFactory.getLogger(DteModificationCascadeTests.class)
    @Shared ExecutionContext ec
    @Shared long startTime = System.currentTimeMillis()
    @Shared int testCount = 0

    // Test data IDs for cleanup
    @Shared String testIssuerPartyId
    @Shared String testReceiverPartyId
    @Shared String testReceiverRut  // Unique RUT for receiver party
    @Shared List<String> createdFiscalTaxDocumentIds = []

    def setupSpec() {
        logger.info("=== Starting DTE Modification Cascade Tests ===")

        // Initialize framework
        ec = Moqui.getExecutionContext()

        // Login with standard john.doe test user from framework
        // When running in isolation, the user might not exist yet - handle gracefully
        try {
            ec.user.loginUser("john.doe", "moqui")
            logger.info("Running tests with test user: john.doe")
        } catch (Exception e) {
            logger.warn("Could not login as john.doe, running without user context: ${e.message}")
            ec.message.clearErrors()
        }
        ec.user.setEffectiveTime(new Timestamp(startTime))

        // Set up test sequence IDs to avoid conflicts (using unique range for modification tests)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.FiscalTaxDocument", 90000, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.party.Party", 90000, 10)

        // Initialize test data
        initializeTestData()
        logger.info("Test data initialization completed")
    }

    def cleanupSpec() {
        long totalTime = System.currentTimeMillis() - startTime
        logger.info("=== DTE Modification Cascade Tests Complete ===")
        logger.info("Ran ${testCount} tests in ${totalTime/1000}s")

        // Clean up test data
        cleanupTestData()

        // Reset sequence IDs
        ec.entity.tempResetSequencedIdPrimary("mchile.dte.FiscalTaxDocument")
        ec.entity.tempResetSequencedIdPrimary("mantle.party.Party")

        ec.destroy()
    }

    def setup() {
        ec.message.clearAll()  // Clear any errors from previous tests
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)  // Begin new transaction for each test
        testCount++
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
        ec.transaction.commit()
    }

    private void initializeTestData() {
        ec.artifactExecution.disableAuthz()
        // Clear any errors from login failure before proceeding
        ec.message.clearErrors()
        try {
            // Create test issuer organization
            logger.info("Creating test issuer organization")
            String testPartyId = "TEST_MOD_PARTY_${System.currentTimeMillis()}"

            ec.service.sync().name("create#mantle.party.Party")
                .parameters([
                    partyId: testPartyId,
                    partyTypeEnumId: "PtyOrganization",
                    disabled: "N"
                ]).disableAuthz().call()

            ec.service.sync().name("create#mantle.party.Organization")
                .parameters([
                    partyId: testPartyId,
                    organizationName: "Test Modification Organization"
                ]).disableAuthz().call()

            if (ec.message.hasError()) {
                logger.error("Service errors during party creation: ${ec.message.getErrorsString()}")
                ec.message.clearErrors()
                throw new Exception("Could not create test party")
            }

            testIssuerPartyId = testPartyId

            // Add RUT identification
            ec.service.sync().name("create#mantle.party.PartyIdentification")
                .parameters([
                    partyId: testIssuerPartyId,
                    partyIdTypeEnumId: "PtidNationalTaxId",
                    idValue: "76514104-4"
                ]).disableAuthz().call()

            logger.info("Created test issuer: ${testIssuerPartyId}")

            // Create test receiver organization
            String receiverPartyId = "TEST_MOD_RECV_${System.currentTimeMillis()}"

            ec.service.sync().name("create#mantle.party.Party")
                .parameters([
                    partyId: receiverPartyId,
                    partyTypeEnumId: "PtyOrganization",
                    disabled: "N"
                ]).disableAuthz().call()

            ec.service.sync().name("create#mantle.party.Organization")
                .parameters([
                    partyId: receiverPartyId,
                    organizationName: "Test Receiver Organization"
                ]).disableAuthz().call()

            testReceiverPartyId = receiverPartyId

            // Add RUT identification for receiver - use a valid test RUT
            // Using a known valid test RUT that doesn't conflict with existing data
            testReceiverRut = "66666666-6"  // Valid test RUT with correct check digit
            ec.service.sync().name("create#mantle.party.PartyIdentification")
                .parameters([
                    partyId: testReceiverPartyId,
                    partyIdTypeEnumId: "PtidNationalTaxId",
                    idValue: testReceiverRut
                ]).disableAuthz().call()

            logger.info("Created test receiver: ${testReceiverPartyId}")

        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    private void cleanupTestData() {
        ec.artifactExecution.disableAuthz()
        try {
            // Clean up fiscal tax documents
            createdFiscalTaxDocumentIds.each { fiscalTaxDocumentId ->
                try {
                    // Delete references first
                    def references = ec.entity.find("mchile.dte.ReferenciaDte")
                        .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                        .list()
                    references.each { ref ->
                        ec.service.sync().name("delete#mchile.dte.ReferenciaDte")
                            .parameters([referenciaId: ref.referenciaId])
                            .call()
                    }

                    // Delete attributes
                    def attributes = ec.entity.find("mchile.dte.FiscalTaxDocumentAttributes")
                        .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                        .one()
                    if (attributes) {
                        ec.service.sync().name("delete#mchile.dte.FiscalTaxDocumentAttributes")
                            .parameters([fiscalTaxDocumentId: fiscalTaxDocumentId])
                            .call()
                    }

                    // Delete document
                    ec.service.sync().name("delete#mchile.dte.FiscalTaxDocument")
                        .parameters([fiscalTaxDocumentId: fiscalTaxDocumentId])
                        .call()
                } catch (Exception e) {
                    logger.warn("Cleanup warning for DTE ${fiscalTaxDocumentId}: ${e.message}")
                }
            }

            // Clean up parties
            [testIssuerPartyId, testReceiverPartyId].each { partyId ->
                if (partyId) {
                    try {
                        // Delete party identifications
                        def partyIds = ec.entity.find("mantle.party.PartyIdentification")
                            .condition("partyId", partyId)
                            .list()
                        partyIds.each { pid ->
                            ec.service.sync().name("delete#mantle.party.PartyIdentification")
                                .parameters([
                                    partyId: pid.partyId,
                                    partyIdTypeEnumId: pid.partyIdTypeEnumId,
                                    idValue: pid.idValue
                                ])
                                .call()
                        }

                        // Delete organization and party
                        ec.service.sync().name("delete#mantle.party.Organization")
                            .parameters([partyId: partyId])
                            .call()
                        ec.service.sync().name("delete#mantle.party.Party")
                            .parameters([partyId: partyId])
                            .call()
                    } catch (Exception e) {
                        logger.warn("Cleanup warning for party ${partyId}: ${e.message}")
                    }
                }
            }
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    /**
     * Helper method to create a test DTE with attributes
     * @param params Map containing:
     *   - fiscalTaxDocumentTypeEnumId (required)
     *   - fiscalTaxDocumentNumber (required)
     *   - amount (required)
     *   - issuerPartyIdValue (defaults to test issuer)
     *   - receiverPartyIdValue (defaults to test receiver)
     * @return fiscalTaxDocumentId
     */
    private String createTestDte(Map params) {
        def issuerRut = params.issuerPartyIdValue ?: "76514104-4"
        def receiverRut = params.receiverPartyIdValue ?: testReceiverRut  // Use unique test receiver RUT
        def dteType = params.fiscalTaxDocumentTypeEnumId
        def folio = params.fiscalTaxDocumentNumber
        def amount = params.amount

        // Create FiscalTaxDocument - include all required fields
        def dteResult = ec.service.sync().name("create#mchile.dte.FiscalTaxDocument")
            .parameters([
                issuerPartyId: testIssuerPartyId,
                issuerPartyIdValue: issuerRut,
                issuerPartyIdTypeEnumId: "PtidNationalTaxId",
                fiscalTaxDocumentTypeEnumId: dteType,
                fiscalTaxDocumentNumber: folio,
                receiverPartyId: testReceiverPartyId,
                receiverPartyIdValue: receiverRut,
                receiverPartyIdTypeEnumId: "PtidNationalTaxId",
                statusId: "Ftd-Issued",
                date: new Timestamp(System.currentTimeMillis())
            ]).disableAuthz().call()

        if (ec.message.hasError()) {
            logger.error("Error creating DTE: ${ec.message.getErrorsString()}")
            ec.message.clearErrors()
            throw new RuntimeException("Failed to create DTE: ${dteType} folio ${folio}")
        }

        String fiscalTaxDocumentId = dteResult?.fiscalTaxDocumentId
        if (!fiscalTaxDocumentId) {
            throw new RuntimeException("DTE creation returned null ID for ${dteType} folio ${folio}")
        }
        createdFiscalTaxDocumentIds.add(fiscalTaxDocumentId)

        // Create FiscalTaxDocumentAttributes
        ec.service.sync().name("create#mchile.dte.FiscalTaxDocumentAttributes")
            .parameters([
                fiscalTaxDocumentId: fiscalTaxDocumentId,
                amount: amount,
                modificationStatusId: "FtdmsUnmodified",
                modificationAmount: 0.0
            ]).disableAuthz().call()

        logger.info("Created test DTE: ${dteType} folio ${folio}, ID: ${fiscalTaxDocumentId}, amount: ${amount}")
        return fiscalTaxDocumentId
    }

    /**
     * Helper method to create a DTE reference
     * @param fromDteId The DTE that references another
     * @param toDteId The referenced DTE ID (used for lookup)
     * @param toTypeEnumId The type of the referenced DTE
     * @param toFolio The folio of the referenced DTE
     */
    private void createDteReference(String fromDteId, String toDteId, String toTypeEnumId, Integer toFolio) {
        // Get the issuer RUT from the source DTE
        def fromDte = ec.entity.find("mchile.dte.FiscalTaxDocument")
            .condition("fiscalTaxDocumentId", fromDteId)
            .one()

        ec.service.sync().name("create#mchile.dte.ReferenciaDte")
            .parameters([
                fiscalTaxDocumentId: fromDteId,
                referenciaTypeEnumId: "RefDteTypeFiscalTaxDocument",
                fiscalTaxDocumentTypeEnumId: toTypeEnumId,
                rutEmisorFolio: fromDte.issuerPartyIdValue,
                folio: toFolio.toString(),
                codigoReferenciaEnumId: "MCHRefDteCodeCorrigeMonto"
            ]).disableAuthz().call()

        logger.info("Created reference from DTE ${fromDteId} to ${toTypeEnumId} folio ${toFolio}")
    }

    /**
     * Helper method to call the modification processing service
     */
    private void callProcessModificationReferences(String fiscalTaxDocumentId) {
        ec.service.sync().name("mchile.sii.dte.DteModificationRegistrationServices.process#ModificationReferencesForDte")
            .parameters([fiscalTaxDocumentId: fiscalTaxDocumentId])
            .call()
    }

    /**
     * Helper method to get DTE attributes
     */
    private EntityValue getDteAttributes(String fiscalTaxDocumentId) {
        return ec.entity.find("mchile.dte.FiscalTaxDocumentAttributes")
            .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
            .one()
    }

    def "001 - ND cancelling NC should cascade update to original Invoice"() {
        given: "original Invoice with amount 100000"
        String invoiceDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-33",
            fiscalTaxDocumentNumber: 1001,
            amount: 100000.0
        ])

        and: "NC referencing Invoice with amount 100000 (full cancellation)"
        String ncDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-61",
            fiscalTaxDocumentNumber: 2001,
            amount: 100000.0
        ])
        createDteReference(ncDteId, invoiceDteId, "Ftdt-33", 1001)

        when: "processing NC modification references"
        callProcessModificationReferences(ncDteId)

        then: "Invoice should be FtdmsRevoked with modificationAmount -100000"
        def invoiceAttrs = getDteAttributes(invoiceDteId)
        invoiceAttrs.modificationStatusId == "FtdmsRevoked"
        invoiceAttrs.modificationAmount == -100000.0

        when: "ND is created referencing NC with amount 100000 (cancels the NC)"
        String ndDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-56",
            fiscalTaxDocumentNumber: 3001,
            amount: 100000.0
        ])
        createDteReference(ndDteId, ncDteId, "Ftdt-61", 2001)
        callProcessModificationReferences(ndDteId)

        then: "NC should become FtdmsRevoked"
        def ncAttrs = getDteAttributes(ncDteId)
        ncAttrs.modificationStatusId == "FtdmsRevoked"

        and: "Invoice modificationStatusId should change back to FtdmsUnmodified (NC was cancelled)"
        def updatedInvoiceAttrs = getDteAttributes(invoiceDteId)
        updatedInvoiceAttrs.modificationStatusId == "FtdmsUnmodified"
        updatedInvoiceAttrs.modificationAmount == null  // null when unmodified

        cleanup:
        logger.info("Test 001 completed: Invoice ${invoiceDteId}, NC ${ncDteId}, ND ${ndDteId}")
    }

    def "002 - ND partially cancelling NC should cascade update to original Invoice"() {
        given: "original Invoice with amount 100000"
        String invoiceDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-33",
            fiscalTaxDocumentNumber: 1002,
            amount: 100000.0
        ])

        and: "NC referencing Invoice with amount 50000 (partial modification)"
        String ncDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-61",
            fiscalTaxDocumentNumber: 2002,
            amount: 50000.0
        ])
        createDteReference(ncDteId, invoiceDteId, "Ftdt-33", 1002)

        when: "processing NC modification references"
        callProcessModificationReferences(ncDteId)

        then: "Invoice should be FtdmsModified with modificationAmount -50000"
        def invoiceAttrs = getDteAttributes(invoiceDteId)
        invoiceAttrs.modificationStatusId == "FtdmsModified"
        invoiceAttrs.modificationAmount == -50000.0

        when: "ND is created referencing NC with amount 50000 (cancels the NC)"
        String ndDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-56",
            fiscalTaxDocumentNumber: 3002,
            amount: 50000.0
        ])
        createDteReference(ndDteId, ncDteId, "Ftdt-61", 2002)
        callProcessModificationReferences(ndDteId)

        then: "NC should become FtdmsRevoked"
        def ncAttrs = getDteAttributes(ncDteId)
        ncAttrs.modificationStatusId == "FtdmsRevoked"

        and: "Invoice modificationAmount should be null (NC was cancelled, now unmodified)"
        def updatedInvoiceAttrs = getDteAttributes(invoiceDteId)
        updatedInvoiceAttrs.modificationStatusId == "FtdmsUnmodified"
        updatedInvoiceAttrs.modificationAmount == null

        cleanup:
        logger.info("Test 002 completed: Invoice ${invoiceDteId}, NC ${ncDteId}, ND ${ndDteId}")
    }

    def "003 - Multiple NCs with one cancelled should correctly calculate modification"() {
        given: "original Invoice with amount 100000"
        String invoiceDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-33",
            fiscalTaxDocumentNumber: 1003,
            amount: 100000.0
        ])

        and: "NC1 referencing Invoice with amount 30000"
        String nc1DteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-61",
            fiscalTaxDocumentNumber: 2003,
            amount: 30000.0
        ])
        createDteReference(nc1DteId, invoiceDteId, "Ftdt-33", 1003)
        callProcessModificationReferences(nc1DteId)

        and: "NC2 referencing Invoice with amount 20000"
        String nc2DteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-61",
            fiscalTaxDocumentNumber: 2004,
            amount: 20000.0
        ])
        createDteReference(nc2DteId, invoiceDteId, "Ftdt-33", 1003)
        callProcessModificationReferences(nc2DteId)

        when: "checking Invoice after both NCs"
        def invoiceAttrs = getDteAttributes(invoiceDteId)

        then: "Invoice should be FtdmsModified with modificationAmount -50000 (sum of both NCs)"
        invoiceAttrs.modificationStatusId == "FtdmsModified"
        invoiceAttrs.modificationAmount == -50000.0

        when: "ND is created referencing NC1 with amount 30000 (cancels NC1)"
        String ndDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-56",
            fiscalTaxDocumentNumber: 3003,
            amount: 30000.0
        ])
        createDteReference(ndDteId, nc1DteId, "Ftdt-61", 2003)
        callProcessModificationReferences(ndDteId)

        then: "NC1 should become FtdmsRevoked"
        def nc1Attrs = getDteAttributes(nc1DteId)
        nc1Attrs.modificationStatusId == "FtdmsRevoked"

        and: "Invoice modificationAmount should become -20000 (only NC2 counts)"
        def updatedInvoiceAttrs = getDteAttributes(invoiceDteId)
        updatedInvoiceAttrs.modificationStatusId == "FtdmsModified"
        updatedInvoiceAttrs.modificationAmount == -20000.0

        and: "NC2 should still be active (not revoked)"
        def nc2Attrs = getDteAttributes(nc2DteId)
        nc2Attrs.modificationStatusId == "FtdmsUnmodified"

        cleanup:
        logger.info("Test 003 completed: Invoice ${invoiceDteId}, NC1 ${nc1DteId}, NC2 ${nc2DteId}, ND ${ndDteId}")
    }

    def "004 - ND partially cancelling NC should leave partial modification on original Invoice"() {
        given: "original Invoice with amount 100000"
        String invoiceDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-33",
            fiscalTaxDocumentNumber: 1004,
            amount: 100000.0
        ])

        and: "NC referencing Invoice with amount 60000"
        String ncDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-61",
            fiscalTaxDocumentNumber: 2005,
            amount: 60000.0
        ])
        createDteReference(ncDteId, invoiceDteId, "Ftdt-33", 1004)
        callProcessModificationReferences(ncDteId)

        when: "Invoice attributes after NC"
        def invoiceAttrs = getDteAttributes(invoiceDteId)

        then: "Invoice should be FtdmsModified with modificationAmount -60000"
        invoiceAttrs.modificationStatusId == "FtdmsModified"
        invoiceAttrs.modificationAmount == -60000.0

        when: "ND is created referencing NC with amount 40000 (partially cancels NC)"
        String ndDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-56",
            fiscalTaxDocumentNumber: 3004,
            amount: 40000.0
        ])
        createDteReference(ndDteId, ncDteId, "Ftdt-61", 2005)
        callProcessModificationReferences(ndDteId)

        then: "NC should be FtdmsModified (partially cancelled)"
        def ncAttrs = getDteAttributes(ncDteId)
        ncAttrs.modificationStatusId == "FtdmsModified"
        ncAttrs.modificationAmount == -40000.0

        and: "Invoice modificationAmount should be -20000 (60000 NC - 40000 ND = 20000 remaining NC effect)"
        def updatedInvoiceAttrs = getDteAttributes(invoiceDteId)
        updatedInvoiceAttrs.modificationStatusId == "FtdmsModified"
        updatedInvoiceAttrs.modificationAmount == -20000.0

        cleanup:
        logger.info("Test 004 completed: Invoice ${invoiceDteId}, NC ${ncDteId}, ND ${ndDteId}")
    }

    def "005 - Cascade chain: Invoice -> NC -> ND -> NC2 (ND gets cancelled)"() {
        given: "original Invoice with amount 100000"
        String invoiceDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-33",
            fiscalTaxDocumentNumber: 1005,
            amount: 100000.0
        ])

        and: "NC referencing Invoice with amount 80000"
        String nc1DteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-61",
            fiscalTaxDocumentNumber: 2006,
            amount: 80000.0
        ])
        createDteReference(nc1DteId, invoiceDteId, "Ftdt-33", 1005)
        callProcessModificationReferences(nc1DteId)

        and: "ND referencing NC with amount 80000 (cancels the NC)"
        String ndDteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-56",
            fiscalTaxDocumentNumber: 3005,
            amount: 80000.0
        ])
        createDteReference(ndDteId, nc1DteId, "Ftdt-61", 2006)
        callProcessModificationReferences(ndDteId)

        when: "checking Invoice and NC after ND"
        def invoiceAttrs = getDteAttributes(invoiceDteId)
        def nc1Attrs = getDteAttributes(nc1DteId)

        then: "Invoice should be FtdmsUnmodified (NC was cancelled by ND)"
        invoiceAttrs.modificationStatusId == "FtdmsUnmodified"
        invoiceAttrs.modificationAmount == null

        and: "NC should be FtdmsRevoked"
        nc1Attrs.modificationStatusId == "FtdmsRevoked"

        when: "NC2 is created referencing ND with amount 80000 (cancels the ND)"
        String nc2DteId = createTestDte([
            fiscalTaxDocumentTypeEnumId: "Ftdt-61",
            fiscalTaxDocumentNumber: 2007,
            amount: 80000.0
        ])
        createDteReference(nc2DteId, ndDteId, "Ftdt-56", 3005)
        callProcessModificationReferences(nc2DteId)

        then: "ND should become FtdmsRevoked"
        def ndAttrs = getDteAttributes(ndDteId)
        ndAttrs.modificationStatusId == "FtdmsRevoked"

        and: "NC1 should become Unmodified (ND that cancelled it is now void, so NC1's effect is restored)"
        def finalNc1Attrs = getDteAttributes(nc1DteId)
        finalNc1Attrs.modificationStatusId == "FtdmsUnmodified"

        and: "Invoice should now be Modified by NC1 (NC1's credit is restored since ND is cancelled)"
        def finalInvoiceAttrs = getDteAttributes(invoiceDteId)
        finalInvoiceAttrs.modificationStatusId == "FtdmsModified"
        finalInvoiceAttrs.modificationAmount == -80000.0

        cleanup:
        logger.info("Test 005 completed: Invoice ${invoiceDteId}, NC1 ${nc1DteId}, ND ${ndDteId}, NC2 ${nc2DteId}")
    }
}

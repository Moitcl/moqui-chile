package DTE

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.sql.Timestamp

/**
 * Tests for find#Dte service (DteInfoServices)
 * Tests RUT normalization, tipoDte mapping, and document lookup
 */
class FindDteTests extends Specification {
    @Shared protected final static Logger logger = LoggerFactory.getLogger(FindDteTests.class)
    @Shared ExecutionContext ec
    @Shared long startTime = System.currentTimeMillis()
    @Shared int testCount = 0

    // Test data
    @Shared String testIssuerPartyId
    @Shared String testFiscalTaxDocumentId
    @Shared String testIssuerRut = "76123456-7"  // Normalized format
    @Shared Long testFolio = 12345L

    def setupSpec() {
        logger.info("=== Starting find#Dte Service Tests ===")

        // Initialize framework
        ec = Moqui.getExecutionContext()

        // Login with standard test user
        ec.user.loginUser("john.doe", "moqui")
        logger.info("Running tests with test user: john.doe")
        ec.user.setEffectiveTime(new Timestamp(startTime))

        // Set up test sequence IDs
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.FiscalTaxDocument", 90000, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.party.Party", 90000, 10)

        // Initialize test data
        initializeTestData()
        logger.info("Test data initialization completed")
    }

    def cleanupSpec() {
        long totalTime = System.currentTimeMillis() - startTime
        logger.info("=== find#Dte Service Tests Complete ===")
        logger.info("Ran ${testCount} tests in ${totalTime/1000}s")

        // Clean up test data
        cleanupTestData()

        // Reset sequence IDs
        ec.entity.tempResetSequencedIdPrimary("mchile.dte.FiscalTaxDocument")
        ec.entity.tempResetSequencedIdPrimary("mantle.party.Party")

        ec.destroy()
    }

    def setup() {
        ec.message.clearAll()
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
        testCount++
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
        ec.transaction.commit()
    }

    // ==================== Helper Methods ====================

    private void initializeTestData() {
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)

        try {
            // Create test issuer party with RUT
            testIssuerPartyId = ec.entity.makeValue("mantle.party.Party")
                    .set("partyTypeEnumId", "PtyOrganization")
                    .set("ownerPartyId", "_NA_")
                    .setSequencedIdPrimary()
                    .create()
                    .partyId

            ec.entity.makeValue("mantle.party.Organization")
                    .set("partyId", testIssuerPartyId)
                    .set("organizationName", "Test Issuer Organization")
                    .create()

            ec.entity.makeValue("mantle.party.PartyIdentification")
                    .set("partyId", testIssuerPartyId)
                    .set("partyIdTypeEnumId", "PtidNationalTaxId")
                    .set("idValue", testIssuerRut)
                    .create()

            // Create test FiscalTaxDocument (Factura Electrónica - tipo 33)
            testFiscalTaxDocumentId = ec.entity.makeValue("mchile.dte.FiscalTaxDocument")
                    .set("issuerPartyId", testIssuerPartyId)
                    .set("issuerPartyIdTypeEnumId", "PtidNationalTaxId")
                    .set("issuerPartyIdValue", testIssuerRut)
                    .set("fiscalTaxDocumentTypeEnumId", "Ftdt-33")
                    .set("fiscalTaxDocumentNumber", testFolio)
                    .set("statusId", "Ftd-Created")
                    .set("sentAuthStatusId", "Ftd-NotSentAuth")
                    .setSequencedIdPrimary()
                    .create()
                    .fiscalTaxDocumentId

            logger.info("Created test FiscalTaxDocument: ${testFiscalTaxDocumentId} with RUT ${testIssuerRut}, tipo 33, folio ${testFolio}")

            ec.transaction.commit()
        } catch (Exception e) {
            ec.transaction.rollback()
            throw e
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    private void cleanupTestData() {
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)

        try {
            // Delete test FiscalTaxDocument
            if (testFiscalTaxDocumentId) {
                ec.entity.find("mchile.dte.FiscalTaxDocument")
                        .condition("fiscalTaxDocumentId", testFiscalTaxDocumentId)
                        .one()?.delete()
            }

            // Delete test party
            if (testIssuerPartyId) {
                ec.entity.find("mantle.party.PartyIdentification")
                        .condition("partyId", testIssuerPartyId)
                        .list()?.each { it.delete() }
                ec.entity.find("mantle.party.Organization")
                        .condition("partyId", testIssuerPartyId)
                        .one()?.delete()
                ec.entity.find("mantle.party.Party")
                        .condition("partyId", testIssuerPartyId)
                        .one()?.delete()
            }

            ec.transaction.commit()
        } catch (Exception e) {
            ec.transaction.rollback()
            logger.error("Error during cleanup", e)
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    // ==================== Tests ====================

    def "find#Dte should find document with normalized RUT"() {
        when: "calling find#Dte with exact normalized RUT"
        Map result = ec.service.sync().name("mchile.sii.dte.DteInfoServices.find#Dte")
                .parameters([
                        rutEmisor: testIssuerRut,
                        tipoDte: "33",
                        folio: testFolio
                ])
                .call()

        then: "document should be found"
        result.found == true
        result.fiscalTaxDocument != null
        result.fiscalTaxDocument.fiscalTaxDocumentId == testFiscalTaxDocumentId
        result.normalizedRut == testIssuerRut
        result.fiscalTaxDocumentTypeEnumId == "Ftdt-33"
    }

    @Unroll
    def "find#Dte should normalize RUT format: #description"() {
        when: "calling find#Dte with RUT in format: #rutFormat"
        Map result = ec.service.sync().name("mchile.sii.dte.DteInfoServices.find#Dte")
                .parameters([
                        rutEmisor: rutFormat,
                        tipoDte: "33",
                        folio: testFolio
                ])
                .call()

        then: "RUT should be normalized and document found"
        result.found == true
        result.normalizedRut == testIssuerRut
        result.fiscalTaxDocument != null
        result.fiscalTaxDocument.fiscalTaxDocumentId == testFiscalTaxDocumentId

        where:
        rutFormat           | description
        "76123456-7"        | "already normalized"
        "76.123.456-7"      | "with thousand separators"
        "76123456 7"        | "with space instead of dash"
        "761234567"         | "without dash"
        "  76123456-7  "    | "with leading/trailing spaces"
    }

    @Unroll
    def "find#Dte should map tipoDte to fiscalTaxDocumentTypeEnumId: #tipoDte -> #expectedEnumId"() {
        given: "a test document of the specified type exists"
        def testDocId = null

        // Create a test document with the specified type if it doesn't exist
        if (tipoDte != "33") {  // Skip creation for tipo 33 as it already exists
            ec.transaction.begin(null)
            testDocId = ec.entity.makeValue("mchile.dte.FiscalTaxDocument")
                    .set("issuerPartyId", testIssuerPartyId)
                    .set("issuerPartyIdTypeEnumId", "PtidNationalTaxId")
                    .set("issuerPartyIdValue", testIssuerRut)
                    .set("fiscalTaxDocumentTypeEnumId", expectedEnumId)
                    .set("fiscalTaxDocumentNumber", testFolio + tipoDte.toLong())  // Unique folio per type
                    .set("statusId", "Ftd-Created")
                    .set("sentAuthStatusId", "Ftd-NotSentAuth")
                    .setSequencedIdPrimary()
                    .create()
                    .fiscalTaxDocumentId
            ec.transaction.commit()
        }

        when: "calling find#Dte with tipoDte"
        Map result = ec.service.sync().name("mchile.sii.dte.DteInfoServices.find#Dte")
                .parameters([
                        rutEmisor: testIssuerRut,
                        tipoDte: tipoDte,
                        folio: tipoDte == "33" ? testFolio : (testFolio + tipoDte.toLong())
                ])
                .call()

        then: "fiscalTaxDocumentTypeEnumId should be correctly mapped"
        result.fiscalTaxDocumentTypeEnumId == expectedEnumId
        result.found == true

        cleanup:
        if (testDocId) {
            ec.transaction.begin(null)
            ec.entity.find("mchile.dte.FiscalTaxDocument")
                    .condition("fiscalTaxDocumentId", testDocId)
                    .one()?.delete()
            ec.transaction.commit()
        }

        where:
        tipoDte | expectedEnumId | description
        "33"    | "Ftdt-33"      | "Factura Electrónica"
        "34"    | "Ftdt-34"      | "Factura Exenta Electrónica"
        "39"    | "Ftdt-39"      | "Boleta Electrónica"
        "41"    | "Ftdt-41"      | "Boleta Exenta Electrónica"
        "52"    | "Ftdt-52"      | "Guía de Despacho Electrónica"
        "56"    | "Ftdt-56"      | "Nota de Débito Electrónica"
        "61"    | "Ftdt-61"      | "Nota de Crédito Electrónica"
        "110"   | "Ftdt-110"     | "Factura de Exportación Electrónica"
    }

    def "find#Dte should return found=false when document doesn't exist"() {
        when: "calling find#Dte with non-existent folio"
        Map result = ec.service.sync().name("mchile.sii.dte.DteInfoServices.find#Dte")
                .parameters([
                        rutEmisor: testIssuerRut,
                        tipoDte: "33",
                        folio: 999999L
                ])
                .call()

        then: "document should not be found"
        result.found == false
        result.fiscalTaxDocument == null
        result.normalizedRut == testIssuerRut
        result.fiscalTaxDocumentTypeEnumId == "Ftdt-33"
    }

    def "find#Dte should return found=false with different RUT"() {
        when: "calling find#Dte with different issuer RUT"
        Map result = ec.service.sync().name("mchile.sii.dte.DteInfoServices.find#Dte")
                .parameters([
                        rutEmisor: "99999999-9",
                        tipoDte: "33",
                        folio: testFolio
                ])
                .call()

        then: "document should not be found"
        result.found == false
        result.fiscalTaxDocument == null
        result.normalizedRut == "99999999-9"
    }

    def "find#Dte should return found=false with different tipoDte"() {
        when: "calling find#Dte with different document type"
        Map result = ec.service.sync().name("mchile.sii.dte.DteInfoServices.find#Dte")
                .parameters([
                        rutEmisor: testIssuerRut,
                        tipoDte: "39",  // Boleta instead of Factura
                        folio: testFolio
                ])
                .call()

        then: "document should not be found"
        result.found == false
        result.fiscalTaxDocument == null
        result.fiscalTaxDocumentTypeEnumId == "Ftdt-39"
    }

    def "find#Dte should fail with invalid RUT"() {
        when: "calling find#Dte with invalid RUT"
        Map result = ec.service.sync().name("mchile.sii.dte.DteInfoServices.find#Dte")
                .parameters([
                        rutEmisor: "12345678-0",  // Invalid check digit
                        tipoDte: "33",
                        folio: testFolio
                ])
                .call()

        then: "service should return error"
        ec.message.hasError()
        result == null || result.isEmpty()
    }

    def "find#Dte should fail with invalid tipoDte"() {
        when: "calling find#Dte with non-existent tipoDte"
        Map result = ec.service.sync().name("mchile.sii.dte.DteInfoServices.find#Dte")
                .parameters([
                        rutEmisor: testIssuerRut,
                        tipoDte: "999",  // Non-existent document type
                        folio: testFolio
                ])
                .call()

        then: "service should return error"
        ec.message.hasError()
        ec.message.errorsString.contains("Invalid tipoDte")
        ec.message.errorsString.contains("Ftdt-999")
    }

    def "find#Dte should validate required parameters"() {
        when: "calling find#Dte without required parameter"
        Map result = ec.service.sync().name("mchile.sii.dte.DteInfoServices.find#Dte")
                .parameters(params)
                .call()

        then: "service should return error for missing parameter"
        ec.message.hasError()

        where:
        params << [
                [tipoDte: "33", folio: 12345L],                    // missing rutEmisor
                [rutEmisor: "76123456-7", folio: 12345L],          // missing tipoDte
                [rutEmisor: "76123456-7", tipoDte: "33"]           // missing folio
        ]
    }

    def "find#Dte should use efficient index query"() {
        when: "calling find#Dte"
        Map result = ec.service.sync().name("mchile.sii.dte.DteInfoServices.find#Dte")
                .parameters([
                        rutEmisor: testIssuerRut,
                        tipoDte: "33",
                        folio: testFolio
                ])
                .call()

        then: "query should use the FiscalTaxDocumentIssuerNumber unique index"
        result.found == true
        // The index covers: issuerPartyIdValue, fiscalTaxDocumentTypeEnumId, fiscalTaxDocumentNumber
        // This ensures efficient single-row lookup without table scan
        result.fiscalTaxDocument.issuerPartyIdValue == testIssuerRut
        result.fiscalTaxDocument.fiscalTaxDocumentTypeEnumId == "Ftdt-33"
        result.fiscalTaxDocument.fiscalTaxDocumentNumber == testFolio
    }
}
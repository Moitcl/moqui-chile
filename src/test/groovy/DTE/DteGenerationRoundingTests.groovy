package DTE

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import mchile.test.DteCertificateGenerator

import java.math.RoundingMode
import java.sql.Timestamp
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import javax.xml.xpath.XPathFactory
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants

/**
 * Integration tests to detect rounding inconsistencies during DTE generation.
 *
 * Problem: When generating DTEs (specifically Boletas), sometimes the sum of individual
 * MontoItem values doesn't match the MntTotal in the header, causing SII validation warnings.
 *
 * Real Example:
 * - Item 1: 2 × 23512.000000 = 47024
 * - Item 2: 6 × 1224.833333 = 7349
 * - Sum of MontoItems: 47024 + 7349 = 54373
 * - MntTotal: 54374
 * - Discrepancy: 1 peso
 *
 * SII Error: "Total -> [54374] <> [54373] <- suma de detalles"
 *
 * This test suite is designed to EXPOSE THE BUG. Tests with problematic rounding
 * are EXPECTED TO FAIL until the rounding issue is fixed in the generation logic.
 */
class DteGenerationRoundingTests extends Specification {
    @Shared protected final static Logger logger = LoggerFactory.getLogger(DteGenerationRoundingTests.class)
    @Shared ExecutionContext ec
    @Shared long startTime = System.currentTimeMillis()
    @Shared int testCount = 0

    // Test data IDs for cleanup
    @Shared String testIssuerPartyId
    @Shared String testReceiverPartyId
    @Shared List createdFiscalTaxDocumentIds = []

    def setupSpec() {
        logger.info("=" * 80)
        logger.info("=== Starting DTE Generation Rounding Tests ===")
        logger.info("=" * 80)
        logger.info("These tests are designed to detect rounding inconsistencies in DTE generation.")
        logger.info("=" * 80)

        // Initialize framework
        ec = Moqui.getExecutionContext()

        // Login with standard test user
        ec.user.loginUser("john.doe", "moqui")
        logger.info("Running tests with test user: john.doe")
        ec.user.setEffectiveTime(new Timestamp(startTime))

        // Set up test sequence IDs to avoid conflicts
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.FiscalTaxDocument", 90000, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.party.Party", 90000, 10)

        // Initialize test data
        initializeTestData()
        logger.info("Test data initialization completed")
    }

    def cleanupSpec() {
        long totalTime = System.currentTimeMillis() - startTime
        logger.info("=" * 80)
        logger.info("=== DTE Generation Rounding Tests Complete ===")
        logger.info("Ran ${testCount} tests in ${totalTime / 1000}s")
        logger.info("=" * 80)

        // Clean up test data
        cleanupTestData()

        // Reset sequence IDs
        ec.entity.tempResetSequencedIdPrimary("mchile.dte.FiscalTaxDocument")
        ec.entity.tempResetSequencedIdPrimary("mantle.party.Party")

        ec.destroy()
    }

    def setup() {
        ec.message.clearAll()  // Clear any messages from previous tests
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
        testCount++
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
        ec.transaction.commit()
    }

    private void initializeTestData() {
        ec.artifactExecution.disableAuthz()
        try {
            // Create test issuer organization with unique party ID
            String testPartyId = "TEST_ROUNDING_ISSUER_${System.currentTimeMillis()}"

            ec.service.sync().name("create#mantle.party.Party")
                .parameters([
                    partyId: testPartyId,
                    partyTypeEnumId: "PtyOrganization",
                    disabled: "N"
                ]).call()

            if (ec.message.hasError()) {
                logger.error("Service errors during party creation: ${ec.message.getErrorsString()}")
                ec.message.clearErrors()
                throw new Exception("Could not create test party")
            }

            testIssuerPartyId = testPartyId
            logger.info("Created test issuer organization: ${testIssuerPartyId}")

            // Add OrgInternal role
            ec.service.sync().name("create#mantle.party.PartyRole")
                .parameters([
                    partyId: testIssuerPartyId,
                    roleTypeId: "OrgInternal"
                ]).call()

            // Add RUT identification (using valid test RUT)
            String issuerRut = "77777777-7"  // Valid test RUT
            ec.service.sync().name("create#mantle.party.PartyIdentification")
                .parameters([
                    partyId: testIssuerPartyId,
                    partyIdTypeEnumId: "PtidNationalTaxId",
                    idValue: issuerRut
                ]).call()

            // Create DTE parameters for issuer
            ec.service.sync().name("create#mchile.dte.PartyDteParams")
                .parameters([
                    partyId: testIssuerPartyId,
                    razonSocialOrganizacion: "Test Rounding Organization",
                    direccionOrigen: "Av. Test 123",
                    comunaOrigen: "Santiago",
                    ciudadOrigen: "Santiago",
                    numeroResolucionSii: "999",
                    fechaResolucionSii: "2024-01-01",
                    oficinaSII: "SANTIAGO CENTRO",
                    codigosActividadEconomica: "999999",
                    certData: createTestPkcs12Certificate(),
                    passCert: "test123"
                ]).call()

            // Create Party Giro for issuer
            ec.service.sync().name("create#mchile.dte.PartyGiro")
                .parameters([
                    giroId: testIssuerPartyId + "-GIRO",
                    partyId: testIssuerPartyId,
                    isPrimary: "Y",
                    description: "Giro de Organización Prueba"
                ]).call()

            logger.info("Created DTE configuration for issuer ${testIssuerPartyId}")

            // Clear any errors before receiver creation
            if (ec.message.hasError()) {
                logger.warn("Clearing errors before receiver creation: ${ec.message.getErrorsString()}")
                ec.message.clearErrors()
            }

            // Create test receiver organization
            String testReceiverPartyId = "TEST_ROUNDING_RECEIVER_${System.currentTimeMillis()}"

            ec.service.sync().name("create#mantle.party.Party")
                .parameters([
                    partyId: testReceiverPartyId,
                    partyTypeEnumId: "PtyOrganization",
                    disabled: "N"
                ]).call()

            if (ec.message.hasError()) {
                logger.error("Service errors during receiver party creation: ${ec.message.getErrorsString()}")
                String errorDetails = ec.message.getErrorsString()
                ec.message.clearErrors()
                throw new Exception("Could not create test receiver party: ${errorDetails}")
            }

            // Add Customer role to receiver
            ec.service.sync().name("create#mantle.party.PartyRole")
                .parameters([
                    partyId: testReceiverPartyId,
                    roleTypeId: "Customer"
                ]).call()

            this.testReceiverPartyId = testReceiverPartyId
            logger.info("Created test receiver organization: ${testReceiverPartyId}")

            // Add RUT to receiver
            String receiverRut = "88888888-8"  // Valid test RUT
            ec.service.sync().name("create#mantle.party.PartyIdentification")
                .parameters([
                    partyId: testReceiverPartyId,
                    partyIdTypeEnumId: "PtidNationalTaxId",
                    idValue: receiverRut
                ]).call()

            if (ec.message.hasError()) {
                logger.error("Service errors during party identification creation: ${ec.message.getErrorsString()}")
                ec.message.clearErrors()
            }

            // Generate test certificates and CAFs
            generateTestCertificateAndCaf()
            generateTestCafForFactura()
            generateTestCafForBoletaExenta()
            generateTestCafForFacturaExenta()

            logger.info("Test data initialized: issuer=${testIssuerPartyId}, receiver=${testReceiverPartyId}")
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    private void generateTestCertificateAndCaf() {
        // Create test CAF for Boleta Electrónica (tipo 39)
        String cafId = "${testIssuerPartyId}-39-1"

        // Check if CAF already exists
        EntityValue existingCaf = ec.entity.find("mchile.dte.Caf")
            .condition("cafId", cafId)
            .one()

        if (existingCaf) {
            logger.info("CAF already exists: ${cafId}")
            return
        }

        Timestamp currentTimestamp = ec.user.nowTimestamp
        String xmlDate = new java.text.SimpleDateFormat("yyyy-MM-dd").format(currentTimestamp)

        String cafXml = """<CAF version="1.0">
    <DA>
        <RE>77777777-7</RE>
        <RS>Test Rounding Organization</RS>
        <TD>39</TD>
        <RNG>
            <D>1</D>
            <H>1000</H>
        </RNG>
        <FA>${xmlDate}</FA>
        <RSAPK>
            <M>uX02i4mqmJ8hwWPwrRBJTBls5y3tylaSdXc9WQnkmUbnlHyGD2GH/zwSb5IYvOwGlChHtz0aKSITiPOiHTFfmw==</M>
            <E>Aw==</E>
        </RSAPK>
        <IDK>100</IDK>
    </DA>
    <FRMA algoritmo="SHA1withRSA">Fsh0/AFUH8dCcSgvXjiIT2wfO9QLYp94KNvGbeKc46jEMj2XgLxq7tBv83rVJ2bIgwytLoJCHaUOi4OnpkVjyg==</FRMA>
</CAF>"""

        String publicKey = '''-----BEGIN PUBLIC KEY-----
MFowDQYJKoZIhvcNAQEBBQADSQAwRgJBALl9NouJqpifIcFj8K0QSUwZbOct7cpW
knV3PVkJ5JlG55R8hQ9ih/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQM=
-----END PUBLIC KEY-----'''

        String privateKey = '''-----BEGIN RSA PRIVATE KEY-----
MIIBOgIBAAJBALl9NouJqpifIcFj8K0QSUwZbOct7cpWknV3PVkJ5JlG55R8hQ9i
h/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQMCQHuozwexHGW/a9ZCoHNgMN1m
SJoenobkYaOk05CxQxDYy64cThXXFJA5wsn/MTxMBUGpvx4fq3rJEB/6v3J8NXsC
IQDxJBA2u1nUvzOjBm+h79bC0fGGA0RcJ6xUyQnXAtuE5QIhAMTrQdkzRhRnscs6
I6zyo2HfuCMGyTzJSCaP8avum4p/AiEAoMK1edI74yoibK71Fp/kgeFLrqzYPW/I
OIYGj1c9A0MCIQCDR4E7d4QNmnaHfBfIocJBP9AXWdt924VvCqEdSb0G/wIhAJ+3
q4+htPABTvIWzZcF4LILEDnaZS791SWJYxbbE72D
-----END RSA PRIVATE KEY-----'''

        try {
            ec.service.sync().name("create#mchile.dte.Caf")
                .parameters([
                    cafId: cafId,
                    fiscalTaxDocumentTypeEnumId: "Ftdt-39",
                    fechaAutorizacion: new java.sql.Date(currentTimestamp.time),
                    issuerPartyId: testIssuerPartyId,
                    activo: "true",
                    desde: "1",
                    hasta: "1000",
                    cafFragment: cafXml,
                    publicKey: publicKey,
                    privateKey: privateKey
                ]).call()

            logger.info("Created test CAF for Boleta Electrónica: ${cafId}")

            // Create FiscalTaxDocument entries for the CAF range
            createFiscalTaxDocumentsForCaf("39", "1", "1000")

        } catch (Exception e) {
            logger.warn("Could not create CAF for Boleta: ${e.message}")
        }
    }

    private void createFiscalTaxDocumentsForCaf(String tipoDte, String desde, String hasta) {
        String rutNoDv = "77777777"  // Test RUT without check digit
        String rut = "77777777-7"    // Full RUT with check digit

        int inicio = Integer.parseInt(desde)
        int fin = Integer.parseInt(hasta)

        logger.info("Creating FiscalTaxDocument entries for CAF range ${inicio}-${fin} for DTE type ${tipoDte}")

        for (int folio = inicio; folio <= fin; folio++) {
            String fiscalTaxDocumentId = "${tipoDte}-${rutNoDv}-${folio}"

            try {
                ec.service.sync().name("create#mchile.dte.FiscalTaxDocument")
                    .parameters([
                        fiscalTaxDocumentId: fiscalTaxDocumentId,
                        fiscalTaxDocumentTypeEnumId: "Ftdt-${tipoDte}",
                        fiscalTaxDocumentNumber: folio,
                        issuerPartyId: testIssuerPartyId,
                        issuerPartyIdTypeEnumId: 'PtidNationalTaxId',
                        issuerPartyIdValue: rut,
                        statusId: 'Ftd-NotIssued',
                        sentAuthStatusId: 'Ftd-NotSentAuth',
                        sentRecStatusId: 'Ftd-NotSentRec'
                    ]).call()

            } catch (Exception e) {
                // Log but don't fail if document already exists
                logger.warn("Could not create FiscalTaxDocument ${fiscalTaxDocumentId}: ${e.message}")
            }
        }

        logger.info("Created ${fin - inicio + 1} FiscalTaxDocument entries for CAF range")
    }

    private void generateTestCafForFactura() {
        // Create test CAF for Factura Electrónica (tipo 33)
        String cafId = "${testIssuerPartyId}-33-1"

        // Check if CAF already exists
        EntityValue existingCaf = ec.entity.find("mchile.dte.Caf")
            .condition("cafId", cafId)
            .one()

        if (existingCaf) {
            logger.info("CAF already exists for Factura: ${cafId}")
            return
        }

        Timestamp currentTimestamp = ec.user.nowTimestamp
        String xmlDate = new java.text.SimpleDateFormat("yyyy-MM-dd").format(currentTimestamp)

        String cafXml = """<CAF version="1.0">
    <DA>
        <RE>77777777-7</RE>
        <RS>Test Rounding Organization</RS>
        <TD>33</TD>
        <RNG>
            <D>1</D>
            <H>1000</H>
        </RNG>
        <FA>${xmlDate}</FA>
        <RSAPK>
            <M>uX02i4mqmJ8hwWPwrRBJTBls5y3tylaSdXc9WQnkmUbnlHyGD2GH/zwSb5IYvOwGlChHtz0aKSITiPOiHTFfmw==</M>
            <E>Aw==</E>
        </RSAPK>
        <IDK>100</IDK>
    </DA>
    <FRMA algoritmo="SHA1withRSA">Fsh0/AFUH8dCcSgvXjiIT2wfO9QLYp94KNvGbeKc46jEMj2XgLxq7tBv83rVJ2bIgwytLoJCHaUOi4OnpkVjyg==</FRMA>
</CAF>"""

        String publicKey = '''-----BEGIN PUBLIC KEY-----
MFowDQYJKoZIhvcNAQEBBQADSQAwRgJBALl9NouJqpifIcFj8K0QSUwZbOct7cpW
knV3PVkJ5JlG55R8hQ9ih/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQM=
-----END PUBLIC KEY-----'''

        String privateKey = '''-----BEGIN RSA PRIVATE KEY-----
MIIBOgIBAAJBALl9NouJqpifIcFj8K0QSUwZbOct7cpWknV3PVkJ5JlG55R8hQ9i
h/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQMCQHuozwexHGW/a9ZCoHNgMN1m
SJoenobkYaOk05CxQxDYy64cThXXFJA5wsn/MTxMBUGpvx4fq3rJEB/6v3J8NXsC
IQDxJBA2u1nUvzOjBm+h79bC0fGGA0RcJ6xUyQnXAtuE5QIhAMTrQdkzRhRnscs6
I6zyo2HfuCMGyTzJSCaP8avum4p/AiEAoMK1edI74yoibK71Fp/kgeFLrqzYPW/I
OIYGj1c9A0MCIQCDR4E7d4QNmnaHfBfIocJBP9AXWdt924VvCqEdSb0G/wIhAJ+3
q4+htPABTvIWzZcF4LILEDnaZS791SWJYxbbE72D
-----END RSA PRIVATE KEY-----'''

        try {
            ec.service.sync().name("create#mchile.dte.Caf")
                .parameters([
                    cafId: cafId,
                    fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                    fechaAutorizacion: new java.sql.Date(currentTimestamp.time),
                    issuerPartyId: testIssuerPartyId,
                    activo: "true",
                    desde: "1",
                    hasta: "1000",
                    cafFragment: cafXml,
                    publicKey: publicKey,
                    privateKey: privateKey
                ]).call()

            logger.info("Created test CAF for Factura Electrónica: ${cafId}")

            // Create FiscalTaxDocument entries for the CAF range
            createFiscalTaxDocumentsForCaf("33", "1", "1000")

        } catch (Exception e) {
            logger.warn("Could not create CAF for Factura: ${e.message}")
        }
    }

    private void generateTestCafForBoletaExenta() {
        // Create test CAF for Boleta Electrónica Exenta (tipo 41)
        String cafId = "${testIssuerPartyId}-41-1"

        EntityValue existingCaf = ec.entity.find("mchile.dte.Caf")
            .condition("cafId", cafId)
            .one()

        if (existingCaf) {
            logger.info("CAF already exists for Boleta Exenta: ${cafId}")
            return
        }

        Timestamp currentTimestamp = ec.user.nowTimestamp
        String xmlDate = new java.text.SimpleDateFormat("yyyy-MM-dd").format(currentTimestamp)

        String cafXml = """<CAF version="1.0">
    <DA>
        <RE>77777777-7</RE>
        <RS>Test Rounding Organization</RS>
        <TD>41</TD>
        <RNG>
            <D>1</D>
            <H>1000</H>
        </RNG>
        <FA>${xmlDate}</FA>
        <RSAPK>
            <M>uX02i4mqmJ8hwWPwrRBJTBls5y3tylaSdXc9WQnkmUbnlHyGD2GH/zwSb5IYvOwGlChHtz0aKSITiPOiHTFfmw==</M>
            <E>Aw==</E>
        </RSAPK>
        <IDK>100</IDK>
    </DA>
    <FRMA algoritmo="SHA1withRSA">Fsh0/AFUH8dCcSgvXjiIT2wfO9QLYp94KNvGbeKc46jEMj2XgLxq7tBv83rVJ2bIgwytLoJCHaUOi4OnpkVjyg==</FRMA>
</CAF>"""

        String publicKey = '''-----BEGIN PUBLIC KEY-----
MFowDQYJKoZIhvcNAQEBBQADSQAwRgJBALl9NouJqpifIcFj8K0QSUwZbOct7cpW
knV3PVkJ5JlG55R8hQ9ih/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQM=
-----END PUBLIC KEY-----'''

        String privateKey = '''-----BEGIN RSA PRIVATE KEY-----
MIIBOgIBAAJBALl9NouJqpifIcFj8K0QSUwZbOct7cpWknV3PVkJ5JlG55R8hQ9i
h/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQMCQHuozwexHGW/a9ZCoHNgMN1m
SJoenobkYaOk05CxQxDYy64cThXXFJA5wsn/MTxMBUGpvx4fq3rJEB/6v3J8NXsC
IQDxJBA2u1nUvzOjBm+h79bC0fGGA0RcJ6xUyQnXAtuE5QIhAMTrQdkzRhRnscs6
I6zyo2HfuCMGyTzJSCaP8avum4p/AiEAoMK1edI74yoibK71Fp/kgeFLrqzYPW/I
OIYGj1c9A0MCIQCDR4E7d4QNmnaHfBfIocJBP9AXWdt924VvCqEdSb0G/wIhAJ+3
q4+htPABTvIWzZcF4LILEDnaZS791SWJYxbbE72D
-----END RSA PRIVATE KEY-----'''

        try {
            ec.service.sync().name("create#mchile.dte.Caf")
                .parameters([
                    cafId: cafId,
                    fiscalTaxDocumentTypeEnumId: "Ftdt-41",
                    fechaAutorizacion: new java.sql.Date(currentTimestamp.time),
                    issuerPartyId: testIssuerPartyId,
                    activo: "true",
                    desde: "1",
                    hasta: "1000",
                    cafFragment: cafXml,
                    publicKey: publicKey,
                    privateKey: privateKey
                ]).call()

            logger.info("Created test CAF for Boleta Electrónica Exenta: ${cafId}")
            createFiscalTaxDocumentsForCaf("41", "1", "1000")

        } catch (Exception e) {
            logger.warn("Could not create CAF for Boleta Exenta: ${e.message}")
        }
    }

    private void generateTestCafForFacturaExenta() {
        // Create test CAF for Factura Electrónica Exenta (tipo 34)
        String cafId = "${testIssuerPartyId}-34-1"

        EntityValue existingCaf = ec.entity.find("mchile.dte.Caf")
            .condition("cafId", cafId)
            .one()

        if (existingCaf) {
            logger.info("CAF already exists for Factura Exenta: ${cafId}")
            return
        }

        Timestamp currentTimestamp = ec.user.nowTimestamp
        String xmlDate = new java.text.SimpleDateFormat("yyyy-MM-dd").format(currentTimestamp)

        String cafXml = """<CAF version="1.0">
    <DA>
        <RE>77777777-7</RE>
        <RS>Test Rounding Organization</RS>
        <TD>34</TD>
        <RNG>
            <D>1</D>
            <H>1000</H>
        </RNG>
        <FA>${xmlDate}</FA>
        <RSAPK>
            <M>uX02i4mqmJ8hwWPwrRBJTBls5y3tylaSdXc9WQnkmUbnlHyGD2GH/zwSb5IYvOwGlChHtz0aKSITiPOiHTFfmw==</M>
            <E>Aw==</E>
        </RSAPK>
        <IDK>100</IDK>
    </DA>
    <FRMA algoritmo="SHA1withRSA">Fsh0/AFUH8dCcSgvXjiIT2wfO9QLYp94KNvGbeKc46jEMj2XgLxq7tBv83rVJ2bIgwytLoJCHaUOi4OnpkVjyg==</FRMA>
</CAF>"""

        String publicKey = '''-----BEGIN PUBLIC KEY-----
MFowDQYJKoZIhvcNAQEBBQADSQAwRgJBALl9NouJqpifIcFj8K0QSUwZbOct7cpW
knV3PVkJ5JlG55R8hQ9ih/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQM=
-----END PUBLIC KEY-----'''

        String privateKey = '''-----BEGIN RSA PRIVATE KEY-----
MIIBOgIBAAJBALl9NouJqpifIcFj8K0QSUwZbOct7cpWknV3PVkJ5JlG55R8hQ9i
h/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQMCQHuozwexHGW/a9ZCoHNgMN1m
SJoenobkYaOk05CxQxDYy64cThXXFJA5wsn/MTxMBUGpvx4fq3rJEB/6v3J8NXsC
IQDxJBA2u1nUvzOjBm+h79bC0fGGA0RcJ6xUyQnXAtuE5QIhAMTrQdkzRhRnscs6
I6zyo2HfuCMGyTzJSCaP8avum4p/AiEAoMK1edI74yoibK71Fp/kgeFLrqzYPW/I
OIYGj1c9A0MCIQCDR4E7d4QNmnaHfBfIocJBP9AXWdt924VvCqEdSb0G/wIhAJ+3
q4+htPABTvIWzZcF4LILEDnaZS791SWJYxbbE72D
-----END RSA PRIVATE KEY-----'''

        try {
            ec.service.sync().name("create#mchile.dte.Caf")
                .parameters([
                    cafId: cafId,
                    fiscalTaxDocumentTypeEnumId: "Ftdt-34",
                    fechaAutorizacion: new java.sql.Date(currentTimestamp.time),
                    issuerPartyId: testIssuerPartyId,
                    activo: "true",
                    desde: "1",
                    hasta: "1000",
                    cafFragment: cafXml,
                    publicKey: publicKey,
                    privateKey: privateKey
                ]).call()

            logger.info("Created test CAF for Factura Electrónica Exenta: ${cafId}")
            createFiscalTaxDocumentsForCaf("34", "1", "1000")

        } catch (Exception e) {
            logger.warn("Could not create CAF for Factura Exenta: ${e.message}")
        }
    }

    private String createTestPkcs12Certificate() {
        try {
            // Use the proper certificate generator utility with BouncyCastle
            // Using valid Chilean RUTs with correct check digits
            Map certData = DteCertificateGenerator.generateTestCertificateData([
                subjectDN: "CN=Test DTE Certificate,O=Test Rounding Organization,C=CL",
                personRut: "11111111-1",  // Valid test RUT with correct check digit
                issuerRut: "77777777-7",  // Valid test RUT with correct check digit
                password: "test123",
                alias: "test-dte-cert"
            ])

            logger.info("Successfully created test PKCS12 certificate")
            return certData.certData

        } catch (Exception e) {
            logger.error("Failed to create test PKCS12 certificate: ${e.message}", e)
            throw new RuntimeException("Certificate generation failed - tests cannot proceed without valid certificate: ${e.message}", e)
        }
    }

    private void cleanupTestData() {
        // Create a new transaction for cleanup to isolate any errors
        ec.transaction.begin(null)
        ec.artifactExecution.disableAuthz()

        try {
            // Delete fiscal tax documents
            for (String fiscalTaxDocumentId in createdFiscalTaxDocumentIds) {
                ec.entity.find("mchile.dte.FiscalTaxDocument")
                    .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                    .deleteAll()
            }

            // Clean up party dependencies
            if (testIssuerPartyId) {
                cleanupPartyDependencies(testIssuerPartyId)
            }

            if (testReceiverPartyId) {
                cleanupPartyDependencies(testReceiverPartyId)
            }

            logger.info("Test data cleanup completed")
            ec.transaction.commit()

        } catch (Exception e) {
            logger.error("Critical error during test data cleanup: ${e.message}", e)
            ec.transaction.rollback("Critical error during test data cleanup", e)
        } finally {
            ec.artifactExecution.enableAuthz()
            ec.message.clearErrors()
        }
    }

    private void cleanupPartyDependencies(String partyId) {
        // Delete party dependencies in correct order

        // Delete DTE-specific dependencies first
        ec.entity.find("mchile.dte.PartyGiro")
            .condition("partyId", partyId)
            .deleteAll()

        ec.entity.find("mchile.dte.PartyDteParams")
            .condition("partyId", partyId)
            .deleteAll()

        ec.entity.find("mchile.dte.Caf")
            .condition("issuerPartyId", partyId)
            .deleteAll()

        // Delete party contact mechanisms
        ec.entity.find("mantle.party.PartyContactMech")
            .condition("partyId", partyId)
            .deleteAll()

        // Delete party identifications
        ec.entity.find("mantle.party.PartyIdentification")
            .condition("partyId", partyId)
            .deleteAll()

        // Delete party roles
        ec.entity.find("mantle.party.PartyRole")
            .condition("partyId", partyId)
            .deleteAll()

        // Delete organization record
        ec.entity.find("mantle.party.Organization")
            .condition("partyId", partyId)
            .deleteAll()

        // Delete base party record last
        ec.entity.find("mantle.party.Party")
            .condition("partyId", partyId)
            .deleteAll()

        logger.info("Cleaned up party dependencies for: ${partyId}")
    }

    /**
     * Helper method to parse DTE XML and extract totals and item amounts
     */
    private Map parseDteXml(String fiscalTaxDocumentId) {
        // Get DTE content
        Map contentResult = ec.service.sync().name("mchile.sii.dte.DteContentServices.get#DteContent")
            .parameters([
                fiscalTaxDocumentId: fiscalTaxDocumentId,
                fiscalTaxDocumentContentTypeEnumId: "Ftdct-Xml"
            ]).call()

        if (!contentResult.contentRef) {
            return [valid: false, error: "No XML found for document ${fiscalTaxDocumentId}"]
        }

        try {
            // Get XML text from resource reference
            String xmlText = contentResult.contentRef.getText()

            if (!xmlText) {
                return [valid: false, error: "XML content is empty"]
            }

            // Parse XML
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
            factory.setNamespaceAware(false)
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xmlText.getBytes("UTF-8")))

            // Create XPath
            XPath xpath = XPathFactory.newInstance().newXPath()

            // Extract MntTotal
            String mntTotalStr = xpath.evaluate("//Totales/MntTotal", doc)
            BigDecimal mntTotal = mntTotalStr ? new BigDecimal(mntTotalStr) : null

            // Extract all MontoItem values and item details with rounding validation
            List<BigDecimal> montoItems = []
            List<Map> itemDetails = []
            NodeList detalleNodes = (NodeList) xpath.evaluate("//Detalle", doc, XPathConstants.NODESET)

            for (int i = 0; i < detalleNodes.getLength(); i++) {
                Element detalleElement = (Element) detalleNodes.item(i)

                String montoItemStr = xpath.evaluate("MontoItem", detalleElement)
                String prcItemStr = xpath.evaluate("PrcItem", detalleElement)
                String qtyItemStr = xpath.evaluate("QtyItem", detalleElement)

                if (montoItemStr) {
                    montoItems.add(new BigDecimal(montoItemStr))
                }

                // Collect item details with rounding validation
                if (prcItemStr && qtyItemStr && montoItemStr) {
                    BigDecimal prcItem = new BigDecimal(prcItemStr)
                    BigDecimal qtyItem = new BigDecimal(qtyItemStr)
                    BigDecimal montoItem = new BigDecimal(montoItemStr)

                    // Calculate expected MontoItem (rounded)
                    BigDecimal calculatedMonto = (prcItem * qtyItem).setScale(0, RoundingMode.HALF_UP)
                    BigDecimal itemDiscrepancy = montoItem - calculatedMonto

                    itemDetails.add([
                        prcItem: prcItem,
                        qtyItem: qtyItem,
                        montoItem: montoItem,
                        calculatedMonto: calculatedMonto,
                        discrepancy: itemDiscrepancy
                    ])
                }
            }

            // Calculate sum of MontoItems
            BigDecimal sumMontoItems = montoItems.sum(BigDecimal.ZERO)

            // Extract other totals for additional validation
            String mntNetoStr = xpath.evaluate("//Totales/MntNeto", doc)
            String ivaStr = xpath.evaluate("//Totales/IVA", doc)

            BigDecimal mntNeto = mntNetoStr ? new BigDecimal(mntNetoStr) : null
            BigDecimal iva = ivaStr ? new BigDecimal(ivaStr) : null

            // Extract MntExe (exempt amount)
            String mntExeStr = xpath.evaluate("//Totales/MntExe", doc)
            BigDecimal mntExe = mntExeStr ? new BigDecimal(mntExeStr) : null

            // Extract TipoDTE (document type)
            String tipoDteStr = xpath.evaluate("//IdDoc/TipoDTE", doc)

            // Return parsed data
            return [
                valid: true,
                xmlText: xmlText,
                mntTotal: mntTotal,
                montoItems: montoItems,
                itemDetails: itemDetails,
                sumMontoItems: sumMontoItems,
                mntNeto: mntNeto,
                iva: iva,
                mntExe: mntExe,
                tipoDte: tipoDteStr,
                discrepancy: mntTotal ? (sumMontoItems - mntTotal) : null
            ]

        } catch (Exception e) {
            logger.error("Error parsing XML: ${e.message}", e)
            return [valid: false, error: "XML parsing error: ${e.message}"]
        }
    }

    /**
     * Test 1: Known Problematic Case from Production
     *
     * This test uses the EXACT values from the production bug report.
     * It is EXPECTED TO FAIL until the rounding issue is fixed.
     *
     * Item 1: 2 × 23512.000000 = 47024
     * Item 2: 6 × 1224.833333 = 7349 (problematic rounding)
     * Expected sum: 54373
     * Actual MntTotal: 54374
     * Discrepancy: 1 peso
     */
    def "001 - EXPECTED FAILURE: Production rounding bug -sum(MontoItems) != MntTotal"() {
        given: "Test data with known problematic rounding from production"
        // These are the EXACT values from the production bug
        // Item 1: JARDIANCE 10 MG - 2 units at price that results in MontoItem 47024
        // Item 2: LEVETIRACETAM 500 MG (F) - 6 units at 1224.833333 = 7349
        BigDecimal item1Quantity = 2
        BigDecimal item1UnitPrice = 23512.000000  // Results in MontoItem 47024

        BigDecimal item2Quantity = 6
        BigDecimal item2UnitPrice = 1224.833333   // Problematic: results in MontoItem 7349

        // Calculate expected values
        BigDecimal item1Total = item1Quantity * item1UnitPrice  // 47024
        BigDecimal item2Total = item2Quantity * item2UnitPrice  // 7349 (due to rounding)

        BigDecimal expectedSumMontoItems = item1Total + item2Total  // 54373

        when: "Generate Boleta with problematic items"
        logger.info("=" * 80)
        logger.info("TEST 001: Production Rounding Bug")
        logger.info("Item 1: ${item1Quantity} × ${item1UnitPrice} = ${item1Total}")
        logger.info("Item 2: ${item2Quantity} × ${item2UnitPrice} = ${item2Total}")
        logger.info("Expected sum of MontoItems: ${expectedSumMontoItems}")
        logger.info("=" * 80)

        // Generate Boleta using the same service that has the bug
        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Boleta")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-39",  // Boleta Electrónica
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "88888888-8",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Receiver SPA",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Street 123",
                comunaReceptor: "Santiago",
                preciosIncluyenIva: true,  // Prices include VAT (typical for Boletas)
                detailList: [
                    [
                        description: "Product 1",
                        quantity: item1Quantity,
                        amount: item1Total  // Gross amount including VAT
                    ],
                    [
                        description: "Product 2",
                        quantity: item2Quantity,
                        amount: item2Total  // Gross amount including VAT
                    ]
                ]
            ]).call()

        // Track for cleanup
        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete without errors"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the generated XML to extract totals"
        Map xmlData = parseDteXml(result.fiscalTaxDocumentId)

        logger.info("XML Parsing Results:")
        logger.info("  MntTotal (header): ${xmlData.mntTotal}")
        logger.info("  MontoItems: ${xmlData.montoItems}")
        logger.info("  Sum of MontoItems: ${xmlData.sumMontoItems}")
        logger.info("  Discrepancy: ${xmlData.discrepancy} pesos")

        if (xmlData.discrepancy && xmlData.discrepancy != 0) {
            logger.error("=" * 80)
            logger.error("ROUNDING DISCREPANCY DETECTED:")
            logger.error("  Sum of MontoItems: ${xmlData.sumMontoItems}")
            logger.error("  MntTotal (header): ${xmlData.mntTotal}")
            logger.error("  Difference: ${Math.abs(xmlData.discrepancy)} pesos")
            logger.error("")
            logger.error("This will cause SII warning:")
            logger.error('  "REPARO- Monto Total No Cuadra con Parciales"')
            logger.error("  Total -> [${xmlData.mntTotal}] <> [${xmlData.sumMontoItems}] <- suma de detalles")
            logger.error("=" * 80)
        }

        then: "CRITICAL ASSERTION: Sum of MontoItems MUST equal MntTotal"
        xmlData.valid
        assert xmlData.sumMontoItems == xmlData.mntTotal : """
ROUNDING DISCREPANCY DETECTED:
Sum of MontoItems: ${xmlData.sumMontoItems}
MntTotal (header):  ${xmlData.mntTotal}
Difference:         ${Math.abs(xmlData.discrepancy ?: 0)} pesos

This will cause SII warning: "REPARO- Monto Total No Cuadra con Parciales"

Item details:
  Item 1: ${item1Quantity} × ${item1UnitPrice} = ${item1Total}
  Item 2: ${item2Quantity} × ${item2UnitPrice} = ${item2Total}
  Sum: ${item1Total} + ${item2Total} = ${expectedSumMontoItems}

Expected: ${xmlData.mntTotal}
Actual:   ${xmlData.sumMontoItems}
""".trim()
    }

    /**
     * Test 2: Clean Case (Should Pass)
     *
     * This test uses round numbers that should NOT cause rounding issues.
     * It validates that the test doesn't produce false positives.
     */
    def "002 - Clean case: Round numbers without rounding issues"() {
        given: "Test data with clean round numbers"
        BigDecimal item1Quantity = 2
        BigDecimal item1UnitPrice = 10000  // Clean round number

        BigDecimal item2Quantity = 3
        BigDecimal item2UnitPrice = 5000   // Clean round number

        BigDecimal expectedSumMontoItems = (item1Quantity * item1UnitPrice) + (item2Quantity * item2UnitPrice)

        when: "Generate Boleta with clean numbers"
        logger.info("=" * 80)
        logger.info("TEST 002: Clean Case (EXPECTED TO PASS)")
        logger.info("Item 1: ${item1Quantity} × ${item1UnitPrice} = ${item1Quantity * item1UnitPrice}")
        logger.info("Item 2: ${item2Quantity} × ${item2UnitPrice} = ${item2Quantity * item2UnitPrice}")
        logger.info("Expected sum of MontoItems: ${expectedSumMontoItems}")
        logger.info("=" * 80)

        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Boleta")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-39",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "88888888-8",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Receiver SPA",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Street 123",
                comunaReceptor: "Santiago",
                preciosIncluyenIva: true,
                detailList: [
                    [
                        description: "Clean Product 1",
                        quantity: item1Quantity,
                        amount: item1Quantity * item1UnitPrice
                    ],
                    [
                        description: "Clean Product 2",
                        quantity: item2Quantity,
                        amount: item2Quantity * item2UnitPrice
                    ]
                ]
            ]).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete without errors"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the generated XML"
        Map xmlData = parseDteXml(result.fiscalTaxDocumentId)

        logger.info("XML Parsing Results:")
        logger.info("  MntTotal (header): ${xmlData.mntTotal}")
        logger.info("  Sum of MontoItems: ${xmlData.sumMontoItems}")
        logger.info("  Discrepancy: ${xmlData.discrepancy} pesos")

        then: "Sum of MontoItems should equal MntTotal (no rounding issues)"
        xmlData.valid
        xmlData.sumMontoItems == xmlData.mntTotal
        xmlData.discrepancy == 0

        logger.info("✓ Clean case test PASSED - No rounding discrepancy")
    }

    /**
     * Test 3: Another Problematic Case
     *
     * This test uses different quantities/prices that also cause rounding issues.
     * Example: 7 × 1428.571429 should equal 10000 but might round to 9999 or 10001
     *
     * This test may also FAIL depending on how the rounding is handled.
     */
    @Unroll
    def "003 - Problematic rounding with #description, montosBrutos #preciosIncluyenIva and generarXmlMontosNetos #generarXmlMontosNetos"() {
        given: "Test data with problematic decimal prices"
        logger.info("=" * 80)
        logger.info("TEST 003: Problematic Rounding - ${description}")
        logger.info("Item 1: ${item1Qty} × ${item1Price}")
        logger.info("Item 2: ${item2Qty} × ${item2Price}")
        logger.info("=" * 80)

        when: "Generate Boleta with problematic rounding"

        // Calculate item amounts
        BigDecimal item1Total = (item1Qty as BigDecimal) * (item1Price as BigDecimal)
        BigDecimal item2Total = (item2Qty as BigDecimal) * (item2Price as BigDecimal)

        // For gross XML: expectedTotal = sum of rounded items (matches MontoItem calculation)
        // For net XML: expectedTotal = round(sum of items) then add IVA
        BigDecimal expectedTotal
        if (preciosIncluyenIva && !generarXmlMontosNetos) {
            // Gross input + Gross XML: sum of individually rounded MontoItems
            expectedTotal = item1Total.setScale(0, RoundingMode.HALF_UP) + item2Total.setScale(0, RoundingMode.HALF_UP)
        } else {
            // All other cases: sum then round (or add IVA then round)
            expectedTotal = item1Total + item2Total
        }

        // Build detail list
        List detailList = [
                [
                        description: "Product A",
                        quantity: item1Qty,
                        amount: item1Price
                ],
                [
                        description: "Product B",
                        quantity: item2Qty,
                        amount: item2Price
                ]
        ]

        // If prices don't include IVA (net prices), calculate and add IVA as a separate item
        // This matches the behavior in IssuingRestApiServicesV1_1_3.generate#Boleta:289-291
        if (!preciosIncluyenIva) {
            // Calculate total net amount
            BigDecimal totalNetAmount = item1Total + item2Total
            // Calculate IVA (19% in Chile)
            BigDecimal ivaAmount = (totalNetAmount * 0.19).setScale(0, java.math.RoundingMode.HALF_UP)
            expectedTotal += ivaAmount
            // Add IVA as an item with ItemVatTax type
            detailList.add([itemTypeEnumId:'ItemVatTax', quantity:1, amount:ivaAmount])

            logger.info("Net prices mode: totalNetAmount=${totalNetAmount}, ivaAmount=${ivaAmount}, total with IVA=${totalNetAmount + ivaAmount}")
        }

        // Apply final rounding only if NOT already rounded (gross input + gross XML case)
        if (!(preciosIncluyenIva && !generarXmlMontosNetos)) {
            expectedTotal = expectedTotal.setScale(0, RoundingMode.HALF_UP)
        }

        Map parameters = [
                fiscalTaxDocumentTypeEnumId: "Ftdt-39",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "88888888-8",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Receiver SPA",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Street 123",
                comunaReceptor: "Santiago",
                preciosIncluyenIva: preciosIncluyenIva,
                detailList: detailList,
                generarXmlMontosNetos: generarXmlMontosNetos
        ]

        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Boleta").parameters(parameters).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the generated XML"
        Map xmlData = parseDteXml(result.fiscalTaxDocumentId)

        logger.info("XML Parsing Results:")
        logger.info("  MntNeto: ${xmlData.mntNeto}")
        logger.info("  IVA: ${xmlData.iva}")
        logger.info("  MntTotal: ${xmlData.mntTotal}")
        logger.info("  Sum of MontoItems: ${xmlData.sumMontoItems}")
        logger.info("  generarXmlMontosNetos: ${generarXmlMontosNetos}")

        then: "Validate amounts based on generarXmlMontosNetos flag"
        xmlData.valid

        // When generarXmlMontosNetos=true, MontoItems are net amounts, so compare to MntNeto
        // When generarXmlMontosNetos=false, MontoItems are gross amounts, so compare to MntTotal
        if (generarXmlMontosNetos) {
            // MontoItems should equal MntNeto
            BigDecimal netoDiscrepancy = xmlData.sumMontoItems - xmlData.mntNeto
            logger.info("  Neto Discrepancy: ${netoDiscrepancy} pesos")

            if (netoDiscrepancy != 0) {
                logger.warn("ROUNDING DISCREPANCY IN NETO: ${netoDiscrepancy} pesos")
            }

            assert xmlData.sumMontoItems == xmlData.mntNeto : """
Rounding discrepancy detected in test case: ${description} (generarXmlMontosNetos=true)
Sum of MontoItems: ${xmlData.sumMontoItems}
MntNeto: ${xmlData.mntNeto}
Difference: ${Math.abs(netoDiscrepancy)} pesos
""".trim()
        } else {
            // MontoItems should equal MntTotal
            BigDecimal totalDiscrepancy = xmlData.sumMontoItems - xmlData.mntTotal
            logger.info("  Total Discrepancy: ${totalDiscrepancy} pesos")

            if (totalDiscrepancy != 0) {
                logger.warn("ROUNDING DISCREPANCY IN TOTAL: ${totalDiscrepancy} pesos")
            }

            assert xmlData.sumMontoItems == xmlData.mntTotal : """
Rounding discrepancy detected in test case: ${description} (generarXmlMontosNetos=false)
Sum of MontoItems: ${xmlData.sumMontoItems}
MntTotal: ${xmlData.mntTotal}
Difference: ${Math.abs(totalDiscrepancy)} pesos
""".trim()
        }

        // Always validate IVA calculation and totals relationship
        when: "Validate IVA and totals"
        BigDecimal expectedIva = (xmlData.mntNeto * 0.19).setScale(0, java.math.RoundingMode.HALF_UP)
        BigDecimal calculatedTotal = xmlData.mntNeto + xmlData.iva

        logger.info("IVA Validation:")
        logger.info("  Expected IVA (19% of MntNeto): ${expectedIva}")
        logger.info("  Actual IVA: ${xmlData.iva}")
        logger.info("  MntNeto + IVA: ${calculatedTotal}")
        logger.info("  Expected Total: ${expectedTotal}")
        logger.info("  MntTotal: ${xmlData.mntTotal}")

        then: "IVA should be 19% of MntNeto (rounded) and MntNeto + IVA should equal MntTotal"
        assert xmlData.iva == expectedIva : """
IVA calculation error in test case: ${description}
Expected IVA (19% of ${xmlData.mntNeto}): ${expectedIva}
Actual IVA: ${xmlData.iva}
Difference: ${Math.abs(xmlData.iva - expectedIva)} pesos
""".trim()

        assert expectedTotal == xmlData.mntTotal : """
Totals relationship validation failed in test case: ${description}
MntNeto: ${xmlData.mntNeto}
IVA: ${xmlData.iva}
MntNeto + IVA: ${calculatedTotal}
MntTotal: ${xmlData.mntTotal}
Difference: ${Math.abs(calculatedTotal - xmlData.mntTotal)} pesos
""".trim()

        // Validate individual item rounding: MontoItem = round(PrcItem × QtyItem)
        when: "Validate each item's MontoItem equals rounded PrcItem × QtyItem"
        logger.info("Item Rounding Validation:")
        boolean allItemsValid = true
        for (int i = 0; i < xmlData.itemDetails.size(); i++) {
            Map itemDetail = xmlData.itemDetails[i]
            logger.info("  Item ${i+1}:")
            logger.info("    PrcItem: ${itemDetail.prcItem}")
            logger.info("    QtyItem: ${itemDetail.qtyItem}")
            logger.info("    Expected MontoItem (rounded): ${itemDetail.calculatedMonto}")
            logger.info("    Actual MontoItem: ${itemDetail.montoItem}")
            logger.info("    Discrepancy: ${itemDetail.discrepancy}")

            if (itemDetail.discrepancy != 0) {
                logger.error("    ERROR: Item ${i+1} has rounding discrepancy of ${itemDetail.discrepancy} pesos")
                allItemsValid = false
            }
        }

        then: "Each MontoItem must equal round(PrcItem × QtyItem)"
        for (int i = 0; i < xmlData.itemDetails.size(); i++) {
            Map itemDetail = xmlData.itemDetails[i]
            assert itemDetail.montoItem == itemDetail.calculatedMonto : """
Item ${i+1} rounding error in Boleta test case: ${description}
PrcItem: ${itemDetail.prcItem}
QtyItem: ${itemDetail.qtyItem}
Expected MontoItem (rounded): ${itemDetail.calculatedMonto}
Actual MontoItem: ${itemDetail.montoItem}
Discrepancy: ${itemDetail.discrepancy}
""".trim()
        }

        where:
        description                    | item1Qty | item1Price    | item2Qty | item2Price | preciosIncluyenIva | generarXmlMontosNetos
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000       | true               | false
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000       | true               | true
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000       | false              | false
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000       | false              | true
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000       | true               | false
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000       | true               | true
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000       | false              | false
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000       | false              | true
        "13 × 769.230769"              | 13       | 769.230769    | 1        | 10000      | true               | false
        "13 × 769.230769"              | 13       | 769.230769    | 1        | 10000      | true               | true
        "13 × 769.230769"              | 13       | 769.230769    | 1        | 10000      | false              | false
        "13 × 769.230769"              | 13       | 769.230769    | 1        | 10000      | false              | true
        "5159.663865 × 2, 836.134453 × 2" | 2     | 5159.663865   | 2        | 836.134453   | true               | false
        "5159.663865 × 2, 836.134453 × 2" | 2     | 5159.663865   | 2        | 836.134453   | true               | true
        "5159.663865 × 2, 836.134453 × 2" | 2     | 5159.663865   | 2        | 836.134453   | false              | false
        "5159.663865 × 2, 836.134453 × 2" | 2     | 5159.663865   | 2        | 836.134453   | false              | true
    }

    /**
     * Test 4: Validation of MntNeto + IVA = MntTotal
     *
     * Additional validation to ensure that net amount plus VAT equals total.
     * This catches a different class of calculation errors.
     */
    def "004 - Validate MntNeto + IVA = MntTotal relationship"() {
        given: "Test data for totals validation"
        BigDecimal netAmount = 100000
        BigDecimal vatAmount = 19000
        BigDecimal totalAmount = 119000

        when: "Generate Boleta"
        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Boleta")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-39",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "88888888-8",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Receiver SPA",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Street 123",
                comunaReceptor: "Santiago",
                preciosIncluyenIva: true,
                generarXmlMontosNetos: true,  // Generate with net amounts to test MntNeto
                detailList: [
                    [
                        description: "Test Product",
                        quantity: 1,
                        amount: totalAmount
                    ]
                ]
            ]).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the XML and validate totals relationship"
        Map xmlData = parseDteXml(result.fiscalTaxDocumentId)

        logger.info("Totals Validation:")
        logger.info("  MntNeto: ${xmlData.mntNeto}")
        logger.info("  IVA: ${xmlData.iva}")
        logger.info("  MntTotal: ${xmlData.mntTotal}")
        logger.info("  MntNeto + IVA: ${xmlData.mntNeto + xmlData.iva}")

        BigDecimal calculatedTotal = xmlData.mntNeto + xmlData.iva

        then: "MntNeto + IVA should equal MntTotal"
        xmlData.valid
        assert calculatedTotal == xmlData.mntTotal : """
Totals relationship validation failed:
MntNeto: ${xmlData.mntNeto}
IVA: ${xmlData.iva}
MntNeto + IVA: ${calculatedTotal}
MntTotal: ${xmlData.mntTotal}
Difference: ${Math.abs(calculatedTotal - xmlData.mntTotal)}
""".trim()

        logger.info("✓ Totals relationship validation PASSED")
    }

    /**
     * Test 5: Factura Rounding Tests
     *
     * This test replicates test 003 but for Facturas (DTE tipo 33).
     * Facturas use the same rounding logic as Boletas, so they should exhibit the same issues.
     */
    @Unroll
    def "005 - Factura: Problematic rounding with #description, montosBrutos #preciosIncluyenIva and generarXmlMontosNetos #generarXmlMontosNetos"() {
        given: "Test data with problematic decimal prices for Factura"
        logger.info("=" * 80)
        logger.info("TEST 005: Factura Problematic Rounding - ${description}")
        logger.info("Item 1: ${item1Qty} × ${item1Price}")
        logger.info("Item 2: ${item2Qty} × ${item2Price}")
        logger.info("=" * 80)

        when: "Generate Factura with problematic rounding"

        // Calculate item amounts
        BigDecimal item1Total = (item1Qty as BigDecimal) * (item1Price as BigDecimal)
        BigDecimal item2Total = (item2Qty as BigDecimal) * (item2Price as BigDecimal)

        // For gross XML: expectedTotal = sum of rounded items (matches MontoItem calculation)
        // For net XML: expectedTotal = round(sum of items) then add IVA
        BigDecimal expectedTotal
        if (preciosIncluyenIva && !generarXmlMontosNetos) {
            // Gross input + Gross XML: sum of individually rounded MontoItems
            expectedTotal = item1Total.setScale(0, RoundingMode.HALF_UP) + item2Total.setScale(0, RoundingMode.HALF_UP)
        } else {
            // All other cases: sum then round (or add IVA then round)
            expectedTotal = item1Total + item2Total
        }

        // Build detail list
        List detailList = [
                [
                        description: "Product A",
                        quantity: item1Qty,
                        amount: item1Price,
                        afecto: true
                ],
                [
                        description: "Product B",
                        quantity: item2Qty,
                        amount: item2Price,
                        afecto: true
                ]
        ]

        // If prices don't include IVA (net prices), calculate and add IVA as a separate item
        if (!preciosIncluyenIva) {
            // Calculate total net amount
            BigDecimal totalNetAmount = item1Total + item2Total
            // Calculate IVA (19% in Chile)
            BigDecimal ivaAmount = (totalNetAmount * 0.19).setScale(0, RoundingMode.HALF_UP)
            expectedTotal += ivaAmount
            // Add IVA as an item with ItemVatTax type
            detailList.add([itemTypeEnumId:'ItemVatTax', quantity:1, amount:ivaAmount])

            logger.info("Net prices mode: totalNetAmount=${totalNetAmount}, ivaAmount=${ivaAmount}, total with IVA=${totalNetAmount + ivaAmount}")
        }

        // Apply final rounding only if NOT already rounded (gross input + gross XML case)
        if (!(preciosIncluyenIva && !generarXmlMontosNetos)) {
            expectedTotal = expectedTotal.setScale(0, RoundingMode.HALF_UP)
        }

        Map parameters = [
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",  // Factura Electrónica
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "88888888-8",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Receiver SPA",
                giroReceptor: "Actividades de prueba",  // Required for Facturas
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Street 123",
                comunaReceptor: "Santiago",
                preciosIncluyenIva: preciosIncluyenIva,
                detailList: detailList,
                generarXmlMontosBrutos: !generarXmlMontosNetos
        ]

        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura").parameters(parameters).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the generated XML"
        Map xmlData = parseDteXml(result.fiscalTaxDocumentId)

        logger.info("XML Parsing Results:")
        logger.info("  MntNeto: ${xmlData.mntNeto}")
        logger.info("  IVA: ${xmlData.iva}")
        logger.info("  MntTotal: ${xmlData.mntTotal}")
        logger.info("  Sum of MontoItems: ${xmlData.sumMontoItems}")
        logger.info("  generarXmlMontosNetos: ${generarXmlMontosNetos}")

        then: "Validate amounts based on generarXmlMontosNetos flag"
        xmlData.valid

        // When generarXmlMontosNetos=true, MontoItems are net amounts, so compare to MntNeto
        // When generarXmlMontosNetos=false, MontoItems are gross amounts, so compare to MntTotal
        if (generarXmlMontosNetos) {
            // MontoItems should equal MntNeto
            BigDecimal netoDiscrepancy = xmlData.sumMontoItems - xmlData.mntNeto
            logger.info("  Neto Discrepancy: ${netoDiscrepancy} pesos")

            if (netoDiscrepancy != 0) {
                logger.warn("ROUNDING DISCREPANCY IN NETO: ${netoDiscrepancy} pesos")
            }

            assert xmlData.sumMontoItems == xmlData.mntNeto : """
Rounding discrepancy detected in test case: ${description} (generarXmlMontosNetos=true)
Sum of MontoItems: ${xmlData.sumMontoItems}
MntNeto: ${xmlData.mntNeto}
Difference: ${Math.abs(netoDiscrepancy)} pesos
""".trim()
        } else {
            // MontoItems should equal MntTotal
            BigDecimal totalDiscrepancy = xmlData.sumMontoItems - xmlData.mntTotal
            logger.info("  Total Discrepancy: ${totalDiscrepancy} pesos")

            if (totalDiscrepancy != 0) {
                logger.warn("ROUNDING DISCREPANCY IN TOTAL: ${totalDiscrepancy} pesos")
            }

            assert xmlData.sumMontoItems == xmlData.mntTotal : """
Rounding discrepancy detected in test case: ${description} (generarXmlMontosNetos=false)
Sum of MontoItems: ${xmlData.sumMontoItems}
MntTotal: ${xmlData.mntTotal}
Difference: ${Math.abs(totalDiscrepancy)} pesos
""".trim()
        }

        // Always validate IVA calculation and totals relationship
        when: "Validate IVA and totals"
        BigDecimal expectedIva = (xmlData.mntNeto * 0.19).setScale(0, java.math.RoundingMode.HALF_UP)
        BigDecimal calculatedTotal = xmlData.mntNeto + xmlData.iva

        logger.info("IVA Validation:")
        logger.info("  Expected IVA (19% of MntNeto): ${expectedIva}")
        logger.info("  Actual IVA: ${xmlData.iva}")
        logger.info("  MntNeto + IVA: ${calculatedTotal}")
        logger.info("  Expected Total: ${expectedTotal}")
        logger.info("  MntTotal: ${xmlData.mntTotal}")

        then: "IVA should be 19% of MntNeto (rounded) and MntNeto + IVA should equal MntTotal"
        assert xmlData.iva == expectedIva : """
IVA calculation error in Factura test case: ${description}
Expected IVA (19% of ${xmlData.mntNeto}): ${expectedIva}
Actual IVA: ${xmlData.iva}
Difference: ${Math.abs(xmlData.iva - expectedIva)} pesos
""".trim()

        assert expectedTotal == xmlData.mntTotal : """
Totals relationship validation failed in Factura test case: ${description}
MntNeto: ${xmlData.mntNeto}
IVA: ${xmlData.iva}
MntNeto + IVA: ${calculatedTotal}
MntTotal: ${xmlData.mntTotal}
Difference: ${Math.abs(calculatedTotal - xmlData.mntTotal)} pesos
""".trim()

        // Validate individual item rounding: MontoItem = round(PrcItem × QtyItem)
        when: "Validate each item's MontoItem equals rounded PrcItem × QtyItem"
        logger.info("Item Rounding Validation:")
        boolean allItemsValid = true
        for (int i = 0; i < xmlData.itemDetails.size(); i++) {
            Map itemDetail = xmlData.itemDetails[i]
            logger.info("  Item ${i+1}:")
            logger.info("    PrcItem: ${itemDetail.prcItem}")
            logger.info("    QtyItem: ${itemDetail.qtyItem}")
            logger.info("    Expected MontoItem (rounded): ${itemDetail.calculatedMonto}")
            logger.info("    Actual MontoItem: ${itemDetail.montoItem}")
            logger.info("    Discrepancy: ${itemDetail.discrepancy}")

            if (itemDetail.discrepancy != 0) {
                logger.error("    ERROR: Item ${i+1} has rounding discrepancy of ${itemDetail.discrepancy} pesos")
                allItemsValid = false
            }
        }

        then: "Each MontoItem must equal round(PrcItem × QtyItem)"
        for (int i = 0; i < xmlData.itemDetails.size(); i++) {
            Map itemDetail = xmlData.itemDetails[i]
            assert itemDetail.montoItem == itemDetail.calculatedMonto : """
Item ${i+1} rounding error in Factura test case: ${description}
PrcItem: ${itemDetail.prcItem}
QtyItem: ${itemDetail.qtyItem}
Expected MontoItem (rounded): ${itemDetail.calculatedMonto}
Actual MontoItem: ${itemDetail.montoItem}
Discrepancy: ${itemDetail.discrepancy}
""".trim()
        }

        where:
        description                    | item1Qty | item1Price    | item2Qty | item2Price   | preciosIncluyenIva | generarXmlMontosNetos
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000         | true               | false
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000         | true               | true
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000         | false              | false
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000         | false              | true
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000         | true               | false
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000         | true               | true
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000         | false              | false
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000         | false              | true
        "13 × 769.230769"              | 13       | 769.230769    | 1        | 10000        | true               | false
        "13 × 769.230769"              | 13       | 769.230769    | 1        | 10000        | true               | true
        "13 × 769.230769"              | 13       | 769.230769    | 1        | 10000        | false              | false
        "13 × 769.230769"              | 13       | 769.230769    | 1        | 10000        | false              | true
        "5159.663865 × 2, 836.134453 × 2" | 2     | 5159.663865   | 2        | 836.134453   | true               | false
        "5159.663865 × 2, 836.134453 × 2" | 2     | 5159.663865   | 2        | 836.134453   | true               | true
        "5159.663865 × 2, 836.134453 × 2" | 2     | 5159.663865   | 2        | 836.134453   | false              | false
        "5159.663865 × 2, 836.134453 × 2" | 2     | 5159.663865   | 2        | 836.134453   | false              | true
    }

    /**
     * Test 6: Specific Case with Parameterized Variations
     *
     * This test validates a specific case with three items:
     * - Item 1: 1 × 673 = 673 (net)
     * - Item 2: 1 × 4034 = 4034 (net)
     * - Item 3: 1 × 9960 = 9960 (net)
     * - Total Net: 14667
     * - IVA (19%): 2787
     * - Total with IVA: 17454
     *
     * Tests all combinations of:
     * - Document type: Boleta (39) and Factura (33)
     * - Input prices: net (preciosIncluyenIva=false) or gross (preciosIncluyenIva=true)
     * - XML format: net amounts (generarXmlMontosNetos=true) or gross amounts (generarXmlMontosNetos=false)
     */
    @Unroll
    def "006 - Specific case (#docType): Input #inputFormat, XML #xmlFormat"() {
        given: "Test data with specific prices"
        // Base net prices
        BigDecimal item1NetPrice = 673
        BigDecimal item2NetPrice = 4034
        BigDecimal item3NetPrice = 9960
        BigDecimal totalNetBase = item1NetPrice + item2NetPrice + item3NetPrice  // 14667
        BigDecimal ivaBase = 2787  // 19% of 14667

        logger.info("=" * 80)
        logger.info("TEST 006: ${docType} - Input: ${inputFormat}, XML: ${xmlFormat}")
        logger.info("Base net prices: ${item1NetPrice}, ${item2NetPrice}, ${item3NetPrice}")
        logger.info("=" * 80)

        when: "Generate DTE with test prices"

        // Convert prices based on input format
        BigDecimal item1Price, item2Price, item3Price
        if (preciosIncluyenIva) {
            // Convert net to gross for input
            item1Price = (item1NetPrice * 1.19).setScale(0, RoundingMode.HALF_UP)
            item2Price = (item2NetPrice * 1.19).setScale(0, RoundingMode.HALF_UP)
            item3Price = (item3NetPrice * 1.19).setScale(0, RoundingMode.HALF_UP)
            logger.info("Input prices (gross): ${item1Price}, ${item2Price}, ${item3Price}")
        } else {
            item1Price = item1NetPrice
            item2Price = item2NetPrice
            item3Price = item3NetPrice
            logger.info("Input prices (net): ${item1Price}, ${item2Price}, ${item3Price}")
        }

        // Build detail list
        List detailList = [
                [description: "Product A", quantity: 1, amount: item1Price, afecto: true],
                [description: "Product B", quantity: 1, amount: item2Price, afecto: true],
                [description: "Product C", quantity: 1, amount: item3Price, afecto: true]
        ]

        // If input prices are net, add IVA as separate item
        if (!preciosIncluyenIva) {
            BigDecimal totalNet = item1Price + item2Price + item3Price
            BigDecimal calculatedIva = (totalNet * 0.19).setScale(0, RoundingMode.HALF_UP)
            detailList.add([itemTypeEnumId:'ItemVatTax', quantity:1, amount:calculatedIva])
            logger.info("Added IVA item: ${calculatedIva}")
        }

        // Build service parameters
        Map parameters = [
                fiscalTaxDocumentTypeEnumId: fiscalTaxDocumentTypeEnumId,
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "88888888-8",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Receiver SPA",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Street 123",
                comunaReceptor: "Santiago",
                preciosIncluyenIva: preciosIncluyenIva,
                detailList: detailList
        ]

        // Add XML format parameter (name differs by document type)
        if (docType == "Boleta") {
            parameters.generarXmlMontosNetos = generarXmlMontosNetos
        } else {  // Factura
            parameters.generarXmlMontosBrutos = !generarXmlMontosNetos
            parameters.giroReceptor = "Actividades de prueba"  // Required for Facturas
        }

        // Call appropriate service
        String serviceName = docType == "Boleta" ?
            "mchile.sii.dte.DteGenerationServices.generate#Boleta" :
            "mchile.sii.dte.DteGenerationServices.generate#Factura"

        Map result = ec.service.sync().name(serviceName).parameters(parameters).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete without errors"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the generated XML"
        Map xmlData = parseDteXml(result.fiscalTaxDocumentId)

        logger.info("XML Parsing Results:")
        logger.info("  MntNeto: ${xmlData.mntNeto}")
        logger.info("  IVA: ${xmlData.iva}")
        logger.info("  MntTotal: ${xmlData.mntTotal}")
        logger.info("  Sum of MontoItems: ${xmlData.sumMontoItems}")

        then: "Validate XML amounts based on XML format"
        xmlData.valid

        // Validate sum based on XML format
        if (generarXmlMontosNetos) {
            // XML has net amounts - compare to MntNeto
            BigDecimal netoDiscrepancy = xmlData.sumMontoItems - xmlData.mntNeto
            logger.info("  Neto Discrepancy: ${netoDiscrepancy} pesos")

            assert xmlData.sumMontoItems == xmlData.mntNeto : """
Rounding discrepancy detected: ${docType} with ${inputFormat} input and ${xmlFormat} XML
Sum of MontoItems: ${xmlData.sumMontoItems}
MntNeto: ${xmlData.mntNeto}
Difference: ${Math.abs(netoDiscrepancy)} pesos
""".trim()
        } else {
            // XML has gross amounts - compare to MntTotal
            BigDecimal totalDiscrepancy = xmlData.sumMontoItems - xmlData.mntTotal
            logger.info("  Total Discrepancy: ${totalDiscrepancy} pesos")

            assert xmlData.sumMontoItems == xmlData.mntTotal : """
Rounding discrepancy detected: ${docType} with ${inputFormat} input and ${xmlFormat} XML
Sum of MontoItems: ${xmlData.sumMontoItems}
MntTotal: ${xmlData.mntTotal}
Difference: ${Math.abs(totalDiscrepancy)} pesos
""".trim()
        }

        // Validate IVA and totals relationship
        when: "Validate IVA and totals"
        BigDecimal expectedIva = (xmlData.mntNeto * 0.19).setScale(0, RoundingMode.HALF_UP)
        BigDecimal calculatedTotal = xmlData.mntNeto + xmlData.iva

        logger.info("IVA Validation:")
        logger.info("  Expected IVA (19% of MntNeto): ${expectedIva}")
        logger.info("  Actual IVA: ${xmlData.iva}")
        logger.info("  MntNeto + IVA: ${calculatedTotal}")
        logger.info("  MntTotal: ${xmlData.mntTotal}")

        then: "IVA should be 19% of MntNeto and MntNeto + IVA should equal MntTotal (within tolerance)"
        assert xmlData.iva == expectedIva : """
IVA calculation error in ${docType} test
Expected IVA (19% of ${xmlData.mntNeto}): ${expectedIva}
Actual IVA: ${xmlData.iva}
Difference: ${Math.abs(xmlData.iva - expectedIva)} pesos
""".trim()

        // Allow 1 peso tolerance for MntNeto + IVA = MntTotal due to rounding
        BigDecimal totalsDifference = Math.abs(calculatedTotal - xmlData.mntTotal)
        assert totalsDifference <= 1 : """
Totals relationship validation failed in ${docType} test
MntNeto: ${xmlData.mntNeto}
IVA: ${xmlData.iva}
MntNeto + IVA: ${calculatedTotal}
MntTotal: ${xmlData.mntTotal}
Difference: ${totalsDifference} pesos (tolerance: 1 peso)
""".trim()

        // Validate individual item rounding: MontoItem = round(PrcItem × QtyItem)
        when: "Validate each item's MontoItem equals rounded PrcItem × QtyItem"
        logger.info("Item Rounding Validation:")
        boolean allItemsValid = true
        for (int i = 0; i < xmlData.itemDetails.size(); i++) {
            Map itemDetail = xmlData.itemDetails[i]
            logger.info("  Item ${i+1}:")
            logger.info("    PrcItem: ${itemDetail.prcItem}")
            logger.info("    QtyItem: ${itemDetail.qtyItem}")
            logger.info("    Expected MontoItem (rounded): ${itemDetail.calculatedMonto}")
            logger.info("    Actual MontoItem: ${itemDetail.montoItem}")
            logger.info("    Discrepancy: ${itemDetail.discrepancy}")

            if (itemDetail.discrepancy != 0) {
                logger.error("    ERROR: Item ${i+1} has rounding discrepancy of ${itemDetail.discrepancy} pesos")
                allItemsValid = false
            }
        }

        then: "Each MontoItem must equal round(PrcItem × QtyItem)"
        for (int i = 0; i < xmlData.itemDetails.size(); i++) {
            Map itemDetail = xmlData.itemDetails[i]
            assert itemDetail.montoItem == itemDetail.calculatedMonto : """
Item ${i+1} rounding error in ${docType} test
PrcItem: ${itemDetail.prcItem}
QtyItem: ${itemDetail.qtyItem}
Expected MontoItem (rounded): ${itemDetail.calculatedMonto}
Actual MontoItem: ${itemDetail.montoItem}
Discrepancy: ${itemDetail.discrepancy}
""".trim()
        }

        logger.info("✓ ${docType} test PASSED - ${inputFormat} input, ${xmlFormat} XML")

        where:
        docType    | fiscalTaxDocumentTypeEnumId | preciosIncluyenIva | generarXmlMontosNetos | inputFormat | xmlFormat
        "Boleta"   | "Ftdt-39"                  | false              | false                 | "Net"       | "Gross"
        "Boleta"   | "Ftdt-39"                  | false              | true                  | "Net"       | "Net"
        "Boleta"   | "Ftdt-39"                  | true               | false                 | "Gross"     | "Gross"
        "Boleta"   | "Ftdt-39"                  | true               | true                  | "Gross"     | "Net"
        "Factura"  | "Ftdt-33"                  | false              | false                 | "Net"       | "Gross"
        "Factura"  | "Ftdt-33"                  | false              | true                  | "Net"       | "Net"
        "Factura"  | "Ftdt-33"                  | true               | false                 | "Gross"     | "Gross"
        "Factura"  | "Ftdt-33"                  | true               | true                  | "Gross"     | "Net"
    }

    /**
     * Test 7: Boleta with exento items rounding
     *
     * This test validates that when exento (exempt) items are used in Boletas,
     * the sum of MontoItems equals MntExe, and there is no IVA or MntNeto.
     */
    @Unroll
    def "007 - Boleta with exento items: #description, input #inputFormat, XML #xmlFormat"() {
        given: "Test data with exento (exempt) items"
        logger.info("=" * 80)
        logger.info("TEST 007: Boleta with EXENTO items - ${description}")
        logger.info("Item 1: ${item1Qty} × ${item1Price}")
        logger.info("Item 2: ${item2Qty} × ${item2Price}")
        logger.info("=" * 80)

        when: "Generate Boleta with exento items"
        BigDecimal item1Total = (item1Qty as BigDecimal) * (item1Price as BigDecimal)
        BigDecimal item2Total = (item2Qty as BigDecimal) * (item2Price as BigDecimal)

        // Build detail list with afecto: false for exempt items
        List detailList = [
            [description: "Exento Product A", quantity: item1Qty, amount: item1Price, afecto: false],
            [description: "Exento Product B", quantity: item2Qty, amount: item2Price, afecto: false]
        ]

        Map parameters = [
            fiscalTaxDocumentTypeEnumId: "Ftdt-39",
            issuerPartyId: testIssuerPartyId,
            rutReceptor: "88888888-8",
            receiverPartyId: testReceiverPartyId,
            razonSocialReceptor: "Test Receiver SPA",
            contactoReceptor: "Test Contact",
            direccionReceptor: "Test Street 123",
            comunaReceptor: "Santiago",
            preciosIncluyenIva: preciosIncluyenIva,
            detailList: detailList,
            generarXmlMontosNetos: generarXmlMontosNetos
        ]

        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Boleta").parameters(parameters).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the generated XML"
        Map xmlData = parseDteXml(result.fiscalTaxDocumentId)

        logger.info("XML Parsing Results:")
        logger.info("  MntExe: ${xmlData.mntExe}")
        logger.info("  MntNeto: ${xmlData.mntNeto}")
        logger.info("  IVA: ${xmlData.iva}")
        logger.info("  MntTotal: ${xmlData.mntTotal}")
        logger.info("  Sum of MontoItems: ${xmlData.sumMontoItems}")

        then: "Validate exento amounts"
        xmlData.valid

        // For exento items, sum should equal MntExe
        assert xmlData.sumMontoItems == (xmlData.mntExe ?: 0) : """
Exento rounding discrepancy in Boleta: ${description}
Sum of MontoItems: ${xmlData.sumMontoItems}
MntExe: ${xmlData.mntExe}
Difference: ${Math.abs(xmlData.sumMontoItems - (xmlData.mntExe ?: 0))} pesos
""".trim()

        // Should have no IVA for exento items
        assert (xmlData.iva ?: 0) == 0 : "Exento items should not have IVA"

        // MntNeto should be null or 0
        assert (xmlData.mntNeto ?: 0) == 0 : "Exento-only Boleta should have no MntNeto"

        where:
        description                    | item1Qty | item1Price    | item2Qty | item2Price   | preciosIncluyenIva | generarXmlMontosNetos | inputFormat | xmlFormat
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000         | false              | false                 | "Net"       | "Gross"
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000         | false              | true                  | "Net"       | "Net"
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000         | false              | false                 | "Net"       | "Gross"
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000         | false              | true                  | "Net"       | "Net"
    }

    /**
     * Test 8: Factura with exento items rounding
     *
     * This test validates that when exento (exempt) items are used in Facturas,
     * the sum of MontoItems equals MntExe, and there is no IVA or MntNeto.
     */
    @Unroll
    def "008 - Factura with exento items: #description, input #inputFormat, XML #xmlFormat"() {
        given: "Test data with exento (exempt) items for Factura"
        logger.info("=" * 80)
        logger.info("TEST 008: Factura with EXENTO items - ${description}")
        logger.info("Item 1: ${item1Qty} × ${item1Price}")
        logger.info("Item 2: ${item2Qty} × ${item2Price}")
        logger.info("=" * 80)

        when: "Generate Factura with exento items"
        BigDecimal item1Total = (item1Qty as BigDecimal) * (item1Price as BigDecimal)
        BigDecimal item2Total = (item2Qty as BigDecimal) * (item2Price as BigDecimal)

        // Build detail list with afecto: false for exempt items
        List detailList = [
            [description: "Exento Product A", quantity: item1Qty, amount: item1Price, afecto: false],
            [description: "Exento Product B", quantity: item2Qty, amount: item2Price, afecto: false]
        ]

        Map parameters = [
            fiscalTaxDocumentTypeEnumId: "Ftdt-33",
            issuerPartyId: testIssuerPartyId,
            rutReceptor: "88888888-8",
            receiverPartyId: testReceiverPartyId,
            razonSocialReceptor: "Test Receiver SPA",
            giroReceptor: "Actividades de prueba",
            contactoReceptor: "Test Contact",
            direccionReceptor: "Test Street 123",
            comunaReceptor: "Santiago",
            preciosIncluyenIva: preciosIncluyenIva,
            detailList: detailList,
            generarXmlMontosBrutos: !generarXmlMontosNetos
        ]

        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura").parameters(parameters).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the generated XML"
        Map xmlData = parseDteXml(result.fiscalTaxDocumentId)

        logger.info("XML Parsing Results:")
        logger.info("  MntExe: ${xmlData.mntExe}")
        logger.info("  MntNeto: ${xmlData.mntNeto}")
        logger.info("  IVA: ${xmlData.iva}")
        logger.info("  MntTotal: ${xmlData.mntTotal}")
        logger.info("  Sum of MontoItems: ${xmlData.sumMontoItems}")

        then: "Validate exento amounts"
        xmlData.valid

        // For exento items, sum should equal MntExe
        assert xmlData.sumMontoItems == (xmlData.mntExe ?: 0) : """
Exento rounding discrepancy in Factura: ${description}
Sum of MontoItems: ${xmlData.sumMontoItems}
MntExe: ${xmlData.mntExe}
Difference: ${Math.abs(xmlData.sumMontoItems - (xmlData.mntExe ?: 0))} pesos
""".trim()

        // Should have no IVA for exento items
        assert (xmlData.iva ?: 0) == 0 : "Exento items should not have IVA"

        // MntNeto should be null or 0
        assert (xmlData.mntNeto ?: 0) == 0 : "Exento-only Factura should have no MntNeto"

        where:
        description                    | item1Qty | item1Price    | item2Qty | item2Price   | preciosIncluyenIva | generarXmlMontosNetos | inputFormat | xmlFormat
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000         | false              | false                 | "Net"       | "Gross"
        "7 × 1428.571429"              | 7        | 1428.571429   | 3        | 5000         | false              | true                  | "Net"       | "Net"
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000         | false              | false                 | "Net"       | "Gross"
        "11 × 909.090909"              | 11       | 909.090909    | 2        | 8000         | false              | true                  | "Net"       | "Net"
    }

    /**
     * Test 9: Mixed afecto and exento items
     *
     * This test validates documents with both taxable (afecto) and exempt (exento) items.
     * It ensures that MntNeto, MntExe, IVA, and MntTotal are all calculated correctly.
     */
    @Unroll
    def "009 - Mixed afecto and exento items (#docType)"() {
        given: "Test data with both afecto and exento items"
        BigDecimal afectoItem1Price = 10000
        BigDecimal afectoItem2Price = 5000
        BigDecimal exentoItem1Price = 3000
        BigDecimal exentoItem2Price = 2000

        logger.info("=" * 80)
        logger.info("TEST 009: ${docType} with MIXED afecto and exento items")
        logger.info("Afecto items: ${afectoItem1Price} + ${afectoItem2Price} = ${afectoItem1Price + afectoItem2Price}")
        logger.info("Exento items: ${exentoItem1Price} + ${exentoItem2Price} = ${exentoItem1Price + exentoItem2Price}")
        logger.info("=" * 80)

        when: "Generate DTE with mixed items"
        List detailList = [
            [description: "Afecto Product 1", quantity: 1, amount: afectoItem1Price, afecto: true],
            [description: "Afecto Product 2", quantity: 1, amount: afectoItem2Price, afecto: true],
            [description: "Exento Product 1", quantity: 1, amount: exentoItem1Price, afecto: false],
            [description: "Exento Product 2", quantity: 1, amount: exentoItem2Price, afecto: false]
        ]

        Map parameters = [
            fiscalTaxDocumentTypeEnumId: fiscalTaxDocumentTypeEnumId,
            issuerPartyId: testIssuerPartyId,
            rutReceptor: "88888888-8",
            receiverPartyId: testReceiverPartyId,
            razonSocialReceptor: "Test Receiver SPA",
            contactoReceptor: "Test Contact",
            direccionReceptor: "Test Street 123",
            comunaReceptor: "Santiago",
            preciosIncluyenIva: false,  // Net prices
            detailList: detailList
        ]

        if (docType == "Factura") {
            parameters.giroReceptor = "Actividades de prueba"
            parameters.generarXmlMontosBrutos = false  // Net XML
        } else {  // Boleta
            parameters.generarXmlMontosNetos = true  // Net XML
        }

        String serviceName = docType == "Boleta" ?
            "mchile.sii.dte.DteGenerationServices.generate#Boleta" :
            "mchile.sii.dte.DteGenerationServices.generate#Factura"

        Map result = ec.service.sync().name(serviceName).parameters(parameters).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the generated XML and validate mixed amounts"
        Map xmlData = parseDteXml(result.fiscalTaxDocumentId)

        BigDecimal expectedMntNeto = afectoItem1Price + afectoItem2Price
        BigDecimal expectedMntExe = exentoItem1Price + exentoItem2Price
        BigDecimal expectedIva = (expectedMntNeto * 0.19).setScale(0, RoundingMode.HALF_UP)
        BigDecimal expectedTotal = expectedMntNeto + expectedIva + expectedMntExe

        logger.info("XML Parsing Results:")
        logger.info("  MntNeto: ${xmlData.mntNeto} (expected: ${expectedMntNeto})")
        logger.info("  MntExe: ${xmlData.mntExe} (expected: ${expectedMntExe})")
        logger.info("  IVA: ${xmlData.iva} (expected: ${expectedIva})")
        logger.info("  MntTotal: ${xmlData.mntTotal} (expected: ${expectedTotal})")

        then: "Validate mixed amounts"
        xmlData.valid
        assert xmlData.mntNeto == expectedMntNeto : "MntNeto mismatch"
        assert xmlData.mntExe == expectedMntExe : "MntExe mismatch"
        assert xmlData.iva == expectedIva : "IVA mismatch"
        assert xmlData.mntTotal == expectedTotal : "MntTotal mismatch"

        where:
        docType    | fiscalTaxDocumentTypeEnumId
        "Boleta"   | "Ftdt-39"
        "Factura"  | "Ftdt-33"
    }

    /**
     * Test 10: All items exento validation
     *
     * This test validates that when ALL items are exento (exempt),
     * the document has only MntExe and MntTotal (which should be equal),
     * with no MntNeto or IVA.
     */
    @Unroll
    def "010 - All items exento (#docType)"() {
        given: "Test data with ALL exento items"
        BigDecimal item1Price = 5000
        BigDecimal item2Price = 3000

        logger.info("=" * 80)
        logger.info("TEST 010: ${docType} with ALL exento items")
        logger.info("Item 1: ${item1Price}")
        logger.info("Item 2: ${item2Price}")
        logger.info("Total Exento: ${item1Price + item2Price}")
        logger.info("=" * 80)

        when: "Generate DTE with all exento items"
        List detailList = [
            [description: "Exento Product 1", quantity: 1, amount: item1Price, afecto: false],
            [description: "Exento Product 2", quantity: 1, amount: item2Price, afecto: false]
        ]

        Map parameters = [
            fiscalTaxDocumentTypeEnumId: fiscalTaxDocumentTypeEnumId,
            issuerPartyId: testIssuerPartyId,
            rutReceptor: "88888888-8",
            receiverPartyId: testReceiverPartyId,
            razonSocialReceptor: "Test Receiver SPA",
            contactoReceptor: "Test Contact",
            direccionReceptor: "Test Street 123",
            comunaReceptor: "Santiago",
            preciosIncluyenIva: false,
            detailList: detailList
        ]

        if (docType == "Factura") {
            parameters.giroReceptor = "Actividades de prueba"
            parameters.generarXmlMontosBrutos = false
        } else {  // Boleta
            parameters.generarXmlMontosNetos = true
        }

        String serviceName = docType == "Boleta" ?
            "mchile.sii.dte.DteGenerationServices.generate#Boleta" :
            "mchile.sii.dte.DteGenerationServices.generate#Factura"

        Map result = ec.service.sync().name(serviceName).parameters(parameters).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the generated XML"
        Map xmlData = parseDteXml(result.fiscalTaxDocumentId)

        BigDecimal expectedMntExe = item1Price + item2Price

        logger.info("XML Parsing Results:")
        logger.info("  TipoDTE: ${xmlData.tipoDte}")
        logger.info("  MntExe: ${xmlData.mntExe} (expected: ${expectedMntExe})")
        logger.info("  MntNeto: ${xmlData.mntNeto}")
        logger.info("  IVA: ${xmlData.iva}")
        logger.info("  MntTotal: ${xmlData.mntTotal}")

        then: "Validate all-exento case"
        xmlData.valid

        // MntExe should equal sum of items
        assert xmlData.mntExe == expectedMntExe : "MntExe should equal sum of exento items"

        // MntNeto and IVA should be 0 or null
        assert (xmlData.mntNeto ?: 0) == 0 : "All-exento document should have no MntNeto"
        assert (xmlData.iva ?: 0) == 0 : "All-exento document should have no IVA"

        // MntTotal should equal MntExe
        assert xmlData.mntTotal == expectedMntExe : "MntTotal should equal MntExe for all-exento document"

        where:
        docType    | fiscalTaxDocumentTypeEnumId
        "Boleta"   | "Ftdt-39"
        "Factura"  | "Ftdt-33"
    }

    /**
     * Test 11: DTE type change detection
     *
     * This test validates that when a Boleta (39) or Factura (33) contains only exento items,
     * the document type automatically changes to Boleta Exenta (41) or Factura Exenta (34).
     */
    @Unroll
    def "011 - DTE type change detection: #initialType → #expectedType"() {
        given: "Test data for DTE type change detection"
        logger.info("=" * 80)
        logger.info("TEST 011: DTE Type Change - ${initialType} → ${expectedType}")
        logger.info("Starting with: ${initialTypeEnumId}")
        logger.info("Expected TipoDTE in XML: ${expectedType}")
        logger.info("=" * 80)

        when: "Generate DTE with all exento items (should trigger type change)"
        List detailList = [
            [description: "Exento Product 1", quantity: 1, amount: 5000, afecto: false],
            [description: "Exento Product 2", quantity: 1, amount: 3000, afecto: false]
        ]

        Map parameters = [
            fiscalTaxDocumentTypeEnumId: initialTypeEnumId,
            issuerPartyId: testIssuerPartyId,
            rutReceptor: "88888888-8",
            receiverPartyId: testReceiverPartyId,
            razonSocialReceptor: "Test Receiver SPA",
            contactoReceptor: "Test Contact",
            direccionReceptor: "Test Street 123",
            comunaReceptor: "Santiago",
            preciosIncluyenIva: false,
            detailList: detailList
        ]

        if (docType == "Factura") {
            parameters.giroReceptor = "Actividades de prueba"
            parameters.generarXmlMontosBrutos = false
        } else {  // Boleta
            parameters.generarXmlMontosNetos = true
        }

        String serviceName = docType == "Boleta" ?
            "mchile.sii.dte.DteGenerationServices.generate#Boleta" :
            "mchile.sii.dte.DteGenerationServices.generate#Factura"

        Map result = ec.service.sync().name(serviceName).parameters(parameters).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the generated XML to verify type change"
        Map xmlData = parseDteXml(result.fiscalTaxDocumentId)

        logger.info("XML Parsing Results:")
        logger.info("  TipoDTE in XML: ${xmlData.tipoDte}")
        logger.info("  Expected: ${expectedType}")

        then: "Validate DTE type changed correctly"
        xmlData.valid
        assert xmlData.tipoDte == expectedType : """
DTE type change validation failed for ${docType}
Started with: ${initialType} (${initialTypeEnumId})
Expected TipoDTE in XML: ${expectedType}
Actual TipoDTE in XML: ${xmlData.tipoDte}

This test verifies that when all items are exento:
- Boleta Electrónica (39) changes to Boleta Electrónica Exenta (41)
- Factura Electrónica (33) changes to Factura Electrónica Exenta (34)
""".trim()

        logger.info("✓ DTE type change test PASSED - ${initialType} → ${expectedType}")

        where:
        docType    | initialTypeEnumId | initialType | expectedType
        "Boleta"   | "Ftdt-39"        | "39"        | "41"
        "Factura"  | "Ftdt-33"        | "33"        | "34"
    }
}
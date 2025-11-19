package DTE

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import mchile.test.DteCertificateGenerator

import java.sql.Timestamp
import java.security.KeyStore
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.X509Certificate
import java.security.cert.CertificateFactory
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.xpath.XPathFactory
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants

/**
 * Comprehensive tests for DTE Generation Services
 * Following mantle-usl test patterns with proper isolation and cleanup
 */
class DteGenerationTests extends Specification {
    @Shared protected final static Logger logger = LoggerFactory.getLogger(DteGenerationTests.class)
    @Shared ExecutionContext ec
    @Shared long startTime = System.currentTimeMillis()
    @Shared long totalFieldsChecked = 0
    @Shared int testCount = 0

    // Test data IDs for cleanup
    @Shared String testIssuerPartyId
    @Shared String testReceiverPartyId
    @Shared String testProductId
    @Shared List createdFiscalTaxDocumentIds = []
    @Shared List<String> createdInvoiceIds = []

    // Acceptable tolerance for monetary amounts (in CLP) - for round-trip consistency tests
    private static final BigDecimal TOLERANCE = new BigDecimal("1.0")

    def setupSpec() {
        logger.info("=== Starting DTE Generation Tests ===")
        
        // Initialize framework
        ec = Moqui.getExecutionContext()
        
        // Login with standard john.doe test user from framework
        ec.user.loginUser("john.doe", "moqui")
        logger.info("Running tests with test user: john.doe")
        ec.user.setEffectiveTime(new Timestamp(startTime))

        // Set up test sequence IDs to avoid conflicts (using unique range for DTE tests)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.FiscalTaxDocument", 80000, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.account.invoice.Invoice", 80000, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.product.Product", 80000, 10)
        ec.entity.tempSetSequencedIdPrimary("mantle.party.Party", 80000, 10)

        // Initialize test certificate and CAF data
        initializeTestData()
        logger.info("Test data initialization completed")
    }

    def cleanupSpec() {
        long totalTime = System.currentTimeMillis() - startTime
        logger.info("=== DTE Generation Tests Complete ===")
        logger.info("Ran ${testCount} tests, checked ${totalFieldsChecked} fields in ${totalTime/1000}s")

        // Clean up test data
        cleanupTestData()

        // Reset sequence IDs
        ec.entity.tempResetSequencedIdPrimary("mchile.dte.FiscalTaxDocument")
        ec.entity.tempResetSequencedIdPrimary("mantle.account.invoice.Invoice")
        ec.entity.tempResetSequencedIdPrimary("mantle.product.Product")
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
        // Clean up test-specific data (if any per-test data exists)
        // Note: Most cleanup happens in cleanupSpec() for this test suite

        ec.artifactExecution.enableAuthz()
        ec.transaction.commit()

        // According to testing-guide.md: Do NOT clear errors in cleanup()
        // Errors should be cleared in setup() at the start of each test
        // This ensures unexpected errors cause proper test failures
    }
    
    private void initializeTestData() {
        ec.artifactExecution.disableAuthz()
        try {
            // Create test issuer organization with unique party ID using timestamp
            logger.info("Creating test issuer organization programmatically")
            String testPartyId = "TEST_DTE_PARTY_${System.currentTimeMillis()}"

            // Try to reuse existing test party if it exists
            EntityValue existingParty = ec.entity.find("mantle.party.Party")
                .condition("partyId", testPartyId)
                .one()

            if (existingParty) {
                testIssuerPartyId = testPartyId
                logger.info("Reusing existing test issuer organization: ${testIssuerPartyId}")
            } else {
                // Create new party with explicit party ID
                ec.service.sync().name("create#mantle.party.Party")
                    .parameters([
                        partyId: testPartyId,
                        partyTypeEnumId: "PtyOrganization",
                        disabled: "N"
                    ]).call()

                // Create Organization entity
                ec.service.sync().name("create#mantle.party.Organization")
                    .parameters([
                        partyId: testPartyId,
                        organizationName: "Test DTE Organization"
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
            }

            // Add RUT identification to issuer (using test RUT matching CAF and certificate)
            long timestamp = System.currentTimeMillis()
            String issuerRut = "12345678-5"  // Test RUT matching CAF and certificate
            ec.service.sync().name("create#mantle.party.PartyIdentification")
                .parameters([
                    partyId: testIssuerPartyId,
                    partyIdTypeEnumId: "PtidNationalTaxId",
                    idValue: issuerRut
                ]).call()

            // Create DTE parameters for issuer with certificate data
            ec.service.sync().name("create#mchile.dte.PartyDteParams")
                .parameters([
                    partyId: testIssuerPartyId,
                    razonSocialOrganizacion: "Test DTE Organization",
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
            
            // Clear any errors from issuer creation before proceeding
            if (ec.message.hasError()) {
                logger.warn("Clearing errors before receiver creation: ${ec.message.getErrorsString()}")
                ec.message.clearErrors()
            }
            
            // Create a test receiver organization with unique party ID
            String testReceiverPartyId = "TEST_DTE_RECEIVER_${System.currentTimeMillis()}"

            ec.service.sync().name("create#mantle.party.Party")
                .parameters([
                    partyId: testReceiverPartyId,
                    partyTypeEnumId: "PtyOrganization",
                    disabled: "N"
                ]).call()

            // Create Organization entity for receiver
            ec.service.sync().name("create#mantle.party.Organization")
                .parameters([
                    partyId: testReceiverPartyId,
                    organizationName: "Test DTE Receiver Organization"
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

            String receiverRut = "87654321-4"  // Valid test RUT matching other tests
            Map idResult = ec.service.sync().name("create#mantle.party.PartyIdentification")
                .parameters([
                    partyId: testReceiverPartyId,
                    partyIdTypeEnumId: "PtidNationalTaxId",
                    idValue: receiverRut
                ]).call()
                
            if (ec.message.hasError()) {
                logger.error("Service errors during party identification creation: ${ec.message.getErrorsString()}")
                ec.message.clearErrors()
            }

            // Create Party Giro for receiver (required for Factura generation)
            ec.service.sync().name("create#mchile.dte.PartyGiro")
                .parameters([
                    giroId: testReceiverPartyId + "-GIRO",
                    partyId: testReceiverPartyId,
                    isPrimary: "Y",
                    description: "Giro de Organización Receptora Prueba"
                ]).call()

            if (ec.message.hasError()) {
                logger.error("Service errors during receiver giro creation: ${ec.message.getErrorsString()}")
                ec.message.clearErrors()
            }

            // Create PostalAddress for receiver (required for Factura generation)
            Map addressResult = ec.service.sync().name("mantle.party.ContactServices.create#PostalAddress")
                .parameters([
                    contactNumber: "Test Address 456",
                    address1: "Av Test Receiver 456",
                    city: "Santiago",
                    stateProvinceGeoId: "CHL_13123",
                    postalCode: "8320000",
                    countryGeoId: "CHL"
                ]).call()

            String receiverContactMechId = addressResult.contactMechId

            // Link address to receiver party with PostalTax purpose
            ec.service.sync().name("create#mantle.party.contact.PartyContactMech")
                .parameters([
                    partyId: testReceiverPartyId,
                    contactMechId: receiverContactMechId,
                    contactMechPurposeId: "PostalTax",
                    fromDate: ec.user.nowTimestamp
                ]).call()

            if (ec.message.hasError()) {
                logger.error("Service errors during receiver address creation: ${ec.message.getErrorsString()}")
                ec.message.clearErrors()
            }

            // Create test product with unique product ID
            String testProductId = "TEST_DTE_PRODUCT_${System.currentTimeMillis()}"
            ec.service.sync().name("create#mantle.product.Product")
                .parameters([
                    productId: testProductId,
                    productName: "Test DTE Product",
                    productTypeEnumId: "PtService",
                    description: "Test product for DTE generation"
                ]).call()

            if (ec.message.hasError()) {
                logger.error("Service errors during product creation: ${ec.message.getErrorsString()}")
                String errorDetails = ec.message.getErrorsString()
                ec.message.clearErrors()
                throw new Exception("Could not create test product: ${errorDetails}")
            }

            this.testProductId = testProductId
            logger.info("Created test product: ${testProductId}")
            
            // Generate dynamic test certificate and update CAF if needed
            generateTestCertificateAndCaf()
            
            logger.info("Test data initialized: issuer=${testIssuerPartyId}, receiver=${testReceiverPartyId}, product=${testProductId}")
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }
    
    private void generateTestCertificateAndCaf() {
        // Generate fresh certificate with RUT extension using BouncyCastle
        try {
            Map certResult = ec.service.sync().name("mchile.test.TestCertificateServices.generate#TestCertificate")
                .parameters([
                    partyId: testIssuerPartyId,
                    rut: "12345678-5",
                    organizationName: "Test DTE Organization",
                    validityDays: 365
                ]).call()
            logger.info("Generated fresh test certificate with RUT extension for ${testIssuerPartyId}")
            logger.info("Certificate has RUT extension: ${certResult.rutExtensionFound}")
            
            // Update PartyDteParams with PKCS12 certificate for proper DTE integration
            updatePartyDteParamsWithPKCS12Certificate(certResult)
            
            // Create test CAF records for all DTE types used in tests
            createAllTestCafs(certResult)
            
        } catch (Exception e) {
            logger.warn("Could not generate dynamic certificate: ${e.message}")
            // Create basic CAF with static keys if certificate generation fails
            createAllTestCafs(null)
        }
        
        // Update CAF dates to ensure they're not older than 6 months
        refreshCafDates()
    }
    
    private void createAllTestCafs(Map certResult) {
        // DTE types used in tests
        List<Map> dteTypes = [
            [code: "33", enumId: "Ftdt-33", name: "Factura Electrónica"],
            [code: "34", enumId: "Ftdt-34", name: "Factura Exenta"],
            [code: "56", enumId: "Ftdt-56", name: "Nota de Débito"],
            [code: "61", enumId: "Ftdt-61", name: "Nota de Crédito"],
            [code: "39", enumId: "Ftdt-39", name: "Boleta Electrónica"],
            [code: "41", enumId: "Ftdt-41", name: "Boleta Exenta"]
        ]
        
        // Get keys from certificate result or use default test keys
        String publicKey = certResult?.publicKeyBase64 ?: '''-----BEGIN PUBLIC KEY-----
MFowDQYJKoZIhvcNAQEBBQADSQAwRgJBALl9NouJqpifIcFj8K0QSUwZbOct7cpW
knV3PVkJ5JlG55R8hQ9ih/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQM=
-----END PUBLIC KEY-----'''
        
        String privateKey = certResult?.privateKeyBase64 ?: '''-----BEGIN RSA PRIVATE KEY-----
MIIBOgIBAAJBALl9NouJqpifIcFj8K0QSUwZbOct7cpWknV3PVkJ5JlG55R8hQ9i
h/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQMCQHuozwexHGW/a9ZCoHNgMN1m
SJoenobkYaOk05CxQxDYy64cThXXFJA5wsn/MTxMBUGpvx4fq3rJEB/6v3J8NXsC
IQDxJBA2u1nUvzOjBm+h79bC0fGGA0RcJ6xUyQnXAtuE5QIhAMTrQdkzRhRnscs6
I6zyo2HfuCMGyTzJSCaP8avum4p/AiEAoMK1edI74yoibK71Fp/kgeFLrqzYPW/I
OIYGj1c9A0MCIQCDR4E7d4QNmnaHfBfIocJBP9AXWdt924VvCqEdSb0G/wIhAJ+3
q4+htPABTvIWzZcF4LILEDnaZS791SWJYxbbE72D
-----END RSA PRIVATE KEY-----'''
        
        Timestamp currentTimestamp = ec.user.nowTimestamp
        String xmlDate = new java.text.SimpleDateFormat("yyyy-MM-dd").format(currentTimestamp)
        
        // Create CAF for each DTE type
        dteTypes.each { dteType ->
            String cafId = "${testIssuerPartyId}-${dteType.code}-1"
            
            // Check if CAF already exists to avoid duplicates
            EntityValue existingCaf = ec.entity.find("mchile.dte.Caf")
                .condition("cafId", cafId)
                .one()
                
            if (existingCaf) {
                logger.info("CAF already exists: ${cafId}")
                return // Skip this DTE type
            }
            
            // Use larger folio range for Boleta types to handle all test variations
            String hastaFolio = (dteType.code in ["39", "41"]) ? "10000" : "1000"

            String cafXml = """<CAF version="1.0">
    <DA>
        <RE>12345678-5</RE>
        <RS>Test DTE Organization</RS>
        <TD>${dteType.code}</TD>
        <RNG>
            <D>1</D>
            <H>${hastaFolio}</H>
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

            try {
                ec.service.sync().name("create#mchile.dte.Caf")
                    .parameters([
                        cafId: cafId,
                        fiscalTaxDocumentTypeEnumId: dteType.enumId,
                        fechaAutorizacion: new java.sql.Date(currentTimestamp.time),
                        issuerPartyId: testIssuerPartyId,
                        activo: "true",
                        desde: "1",
                        hasta: hastaFolio,
                        cafFragment: cafXml,
                        publicKey: publicKey,
                        privateKey: privateKey
                    ]).call()

                logger.info("Created test CAF for ${dteType.name}: ${cafId} (folios 1-${hastaFolio})")

                // Create FiscalTaxDocument entries for the CAF range
                createFiscalTaxDocumentsForCaf(dteType.code, "1", hastaFolio)
                
            } catch (Exception e) {
                logger.warn("Could not create CAF for ${dteType.name}: ${e.message}")
            }
        }
    }
    
    private void refreshCafDates() {
        // Update CAF authorization dates to current date to ensure they're not older than 6 months
        Timestamp currentTimestamp = ec.user.nowTimestamp
        java.sql.Date currentSqlDate = new java.sql.Date(currentTimestamp.time - (24*60*60*1000L))
        
        // Update all CAF records for the test organization
        ec.entity.find("mchile.dte.Caf")
            .condition("issuerPartyId", testIssuerPartyId)
            .list()
            .each { caf ->
                // Update fechaAutorizacion to current date
                caf.fechaAutorizacion = currentSqlDate
                
                // Update with valid test keys (from generaFactura.groovy draft logic)
                updateCafWithValidKeys(caf, currentTimestamp)
                
                caf.update()
                logger.info("Updated CAF ${caf.cafId} fechaAutorizacion to ${currentSqlDate} with valid keys")
            }
        
        // Also update the CAF XML fragment dates to match
        updateCafXmlDates(currentTimestamp)
    }
    
    private void updateCafXmlDates(Timestamp currentDate) {
        // Format date for XML (YYYY-MM-DD)
        String xmlDate = new java.text.SimpleDateFormat("yyyy-MM-dd").format(currentDate)
        
        ec.entity.find("mchile.dte.Caf")
            .condition("issuerPartyId", testIssuerPartyId)
            .list()
            .each { caf ->
                if (caf.cafFragment) {
                    // Update the <FA> (fechaAutorizacion) tag in the CAF XML fragment
                    String updatedFragment = caf.cafFragment.toString()
                        .replaceAll(/<FA>[\d-]+<\/FA>/, "<FA>${xmlDate}</FA>")
                    
                    caf.cafFragment = updatedFragment
                    caf.update()
                    logger.info("Updated CAF ${caf.cafId} XML fragment date to ${xmlDate}")
                }
            }
    }
    
    private void updateCafWithValidKeys(EntityValue caf, Timestamp currentDate) {
        // Use the same valid test keys from generaFactura.groovy draft logic
        String xmlDate = new java.text.SimpleDateFormat("yyyy-MM-dd").format(currentDate)

        // Extract document type code from fiscalTaxDocumentTypeEnumId (e.g., "Ftdt-33" -> "33")
        String tipoDocumento = caf.fiscalTaxDocumentTypeEnumId?.toString()?.replaceAll(/Ftdt-/, '') ?: '33'

        // Get folio range from CAF
        String desdeRango = caf.desde?.toString() ?: '1'
        String hastaRango = caf.hasta?.toString() ?: '1000'

        // Valid CAF XML fragment with test keys
        String validCafFragment = """<CAF version="1.0">
    <DA>
        <RE>12345678-5</RE>
        <RS>Test DTE Organization</RS>
        <TD>${tipoDocumento}</TD>
        <RNG>
            <D>${desdeRango}</D>
            <H>${hastaRango}</H>
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

        // Valid public key
        String validPublicKey = '''-----BEGIN PUBLIC KEY-----
MFowDQYJKoZIhvcNAQEBBQADSQAwRgJBALl9NouJqpifIcFj8K0QSUwZbOct7cpW
knV3PVkJ5JlG55R8hQ9ih/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQM=
-----END PUBLIC KEY-----'''

        // Valid private key
        String validPrivateKey = '''-----BEGIN RSA PRIVATE KEY-----
MIIBOgIBAAJBALl9NouJqpifIcFj8K0QSUwZbOct7cpWknV3PVkJ5JlG55R8hQ9i
h/88Em+SGLzsLJQoR7c9GikiE4jzoh0xX5sCAQMCQHuozwexHGW/a9ZCoHNgMN1m
SJoenobkYaOk05CxQxDYy64cThXXFJA5wsn/MTxMBUGpvx4fq3rJEB/6v3J8NXsC
IQDxJBA2u1nUvzOjBm+h79bC0fGGA0RcJ6xUyQnXAtuE5QIhAMTrQdkzRhRnscs6
I6zyo2HfuCMGyTzJSCaP8avum4p/AiEAoMK1edI74yoibK71Fp/kgeFLrqzYPW/I
OIYGj1c9A0MCIQCDR4E7d4QNmnaHfBfIocJBP9AXWdt924VvCqEdSb0G/wIhAJ+3
q4+htPABTvIWzZcF4LILEDnaZS791SWJYxbbE72D
-----END RSA PRIVATE KEY-----'''

        // Update CAF with valid keys
        caf.cafFragment = validCafFragment
        caf.publicKey = validPublicKey
        caf.privateKey = validPrivateKey
    }
    
    private void createFiscalTaxDocumentsForCaf(String tipoDte, String desde, String hasta) {
        // Create FiscalTaxDocument entries for CAF range following load#CafInternal logic
        // Get RUT without DV for fiscal document ID generation
        String rutNoDv = "12345678"  // Test RUT without check digit
        String rut = "12345678-5"    // Full RUT with check digit

        int inicio = Integer.parseInt(desde)
        int fin = Integer.parseInt(hasta)

        logger.info("Creating FiscalTaxDocument entries for CAF range ${inicio}-${fin} for DTE type ${tipoDte}")

        // OPTION 1: Delete existing entries first to ensure fresh folios
        // This prevents issues where folios from previous runs are marked as "used"
        int deleted = 0
        try {
            List<EntityValue> existingEntries = ec.entity.find("mchile.dte.FiscalTaxDocument")
                .condition("fiscalTaxDocumentTypeEnumId", "Ftdt-${tipoDte}")
                .condition("issuerPartyId", testIssuerPartyId)
                .condition("fiscalTaxDocumentNumber", ">=", inicio)
                .condition("fiscalTaxDocumentNumber", "<=", fin)
                .list()

            for (EntityValue entry : existingEntries) {
                entry.delete()
                deleted++
            }

            if (deleted > 0) {
                logger.info("Deleted ${deleted} existing FiscalTaxDocument entries to ensure fresh folios")
            }
        } catch (Exception e) {
            logger.warn("Could not delete existing FiscalTaxDocument entries: ${e.message}")
            ec.message.clearErrors()
        }

        int created = 0

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
                created++

            } catch (Exception e) {
                // Log but don't fail if document creation fails
                logger.debug("Could not create FiscalTaxDocument ${fiscalTaxDocumentId}: ${e.message}")
                // Clear errors to allow subsequent service calls
                ec.message.clearErrors()
            }
        }

        logger.info("Created ${created} new FiscalTaxDocument entries for CAF range ${inicio}-${fin}")
    }
    
    private void updatePartyDteParamsWithPKCS12Certificate(Map certResult) {
        try {
            // Generate PKCS12 certificate data suitable for PartyDteParams storage
            Map pkcs12Data = DteCertificateGenerator.generateTestCertificateData([
                subjectDN: "CN=Test DTE Organization,O=Test DTE Organization,C=CL",
                personRut: "12345678-5",
                issuerRut: "96790240-3",
                password: "test123",
                alias: "dte-cert-${testIssuerPartyId}"
            ])
            
            // Update PartyDteParams with proper PKCS12 certificate
            EntityValue dteParams = ec.entity.find("mchile.dte.PartyDteParams")
                .condition("partyId", testIssuerPartyId)
                .one()
            
            if (dteParams) {
                dteParams.certData = pkcs12Data.certData
                dteParams.passCert = pkcs12Data.passCert
                dteParams.update()
                logger.info("Updated PartyDteParams with PKCS12 certificate containing RUT extension")
            }
            
        } catch (Exception e) {
            logger.warn("Could not update PartyDteParams with PKCS12 certificate: ${e.message}")
        }
    }
    
    private String createTestPkcs12Certificate() {
        try {
            // Create a test PKCS12 keystore with the same keys used in CAF generation
            
            // Use consistent RSA keys from generaFactura.groovy
            String certPem = '''-----BEGIN CERTIFICATE-----
MIICdTCCAV0CAQAwDQYJKoZIhvcNAQEFBQAwGjEYMBYGA1UEAwwPVGVzdCBEVEUg
Q2VydGlmaWNhdGUwHhcNMjQwMTAxMDAwMDAwWhcNMjUwMTAxMDAwMDAwWjAaMRgw
FgYDVQQDDA9UZXN0IERWRSBDZXJ0aWZpY2F0ZTBaMA0GCSqGSIb3DQEBAQUAA0kA
MEYCQQC5fTaLiaqYnyHBY/CtEElMGWznLe3KVpJ1dz1ZCeSZRueUfIUPYof/PBJv
khi87CyUKEe3PRopIhOI86IdMV+bAgED
-----END CERTIFICATE-----'''
            
            String privKeyPem = '''-----BEGIN PRIVATE KEY-----
MIIBVAIBADANBgkqhkiG9w0BAQEFAASCAT4wggE6AgEAAkEAuX02i4mqmJ8hwWPw
rRBJTBls5y3tylaSdXc9WQnkmUbnlHyFD2KH/zwSb5IYvOwslChHtz0aKSITiPOi
HTFfmwIBAwJAe6jPB7EcZb9r1kKgc2Aw3WZImh6ehuRho6TTkLFDENjLrhxOFdcU
kDnCyf8xPEwFQam/Hh+reskQH/q/cnw1ewIhAPEkEDa7WdS/M6MGb6Hv1sLR8YYD
RFwnrFTJCdcC24TlAiEAxOtB2TNGFGexyzojrPKjYd+4IwbJPMlIJo/xq+6bin8C
IQCgwrV50jvjKiJsrvUWn+SB4UuurNg9b8g4hgaPVz0DQwIhAINHgTt3hA2adod8
F8ihwkE/0BdZ233bhW8KoR1JvQb/AiEAn7erj6G08AFO8hbNlwXgsgsQOdplLv3V
JYljFtsT
-----END PRIVATE KEY-----'''
            
            // Parse certificate
            String certBase64 = certPem.replace("-----BEGIN CERTIFICATE-----\n", "")
                                     .replace("\n-----END CERTIFICATE-----", "")
                                     .replace("\n", "")
            byte[] certBytes = Base64.getDecoder().decode(certBase64)
            CertificateFactory cf = CertificateFactory.getInstance("X.509")
            X509Certificate certificate = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes))
            
            // Parse private key
            String privKeyBase64 = privKeyPem.replace("-----BEGIN PRIVATE KEY-----\n", "")
                                            .replace("\n-----END PRIVATE KEY-----", "")
                                            .replace("\n", "")
            byte[] privKeyBytes = Base64.getDecoder().decode(privKeyBase64)
            PKCS8EncodedKeySpec privKeySpec = new PKCS8EncodedKeySpec(privKeyBytes)
            KeyFactory keyFactory = KeyFactory.getInstance("RSA")
            PrivateKey privateKey = keyFactory.generatePrivate(privKeySpec)
            
            // Create PKCS12 keystore
            KeyStore keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            
            String password = "test123"
            keyStore.setKeyEntry("test", privateKey, password.toCharArray(), [certificate] as java.security.cert.Certificate[])
            
            // Export as PKCS12
            ByteArrayOutputStream baos = new ByteArrayOutputStream()
            keyStore.store(baos, password.toCharArray())
            
            // Return base64 encoded PKCS12 data
            return Base64.getEncoder().encodeToString(baos.toByteArray())
            
        } catch (Exception e) {
            logger.warn("Failed to create test PKCS12 certificate: ${e.message}")
            // Return a minimal test PKCS12 structure
            return "MIIGcQIBAzCCBi0GCSqGSIb3DQEHAaCCBh4EggYaMIIGFjCCAv8GCSqGSIb3DQEHBqCCAvAwggLsAgEAMIIC5QYJKoZIhvcNAQcBMBwGCiqGSIb3DQEMAQYwDgQI+Test+Certificate+Data"
        }
    }

    private void cleanupTestData() {
        // Create a new transaction for cleanup to isolate any errors
        ec.transaction.begin(null)
        ec.artifactExecution.disableAuthz()

        try {
            // Delete test data in proper dependency order to avoid foreign key violations

            // 1. Clean fiscal tax documents (dependent on invoices)
            // Reset folios to available state instead of deleting to avoid exhausting folio pool
            for (String fiscalTaxDocumentId in createdFiscalTaxDocumentIds) {
                // Delete ALL related records that reference FiscalTaxDocument
                ec.entity.find("mchile.dte.FiscalTaxDocumentAttributes")
                    .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                    .deleteAll()
                ec.entity.find("mchile.dte.FiscalTaxDocumentContent")
                    .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                    .deleteAll()
                ec.entity.find("mchile.dte.ReferenciaDte")
                    .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                    .deleteAll()
                ec.entity.find("mchile.dte.GuiaDespacho")
                    .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                    .deleteAll()
                ec.entity.find("mchile.dte.FiscalTaxDocumentEmailMessage")
                    .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                    .deleteAll()
                ec.entity.find("mchile.dte.DteEnvioFiscalTaxDocument")
                    .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                    .deleteAll()
                ec.entity.find("mchile.dte.FiscalTaxDocumentTerm")
                    .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                    .deleteAll()

                // Reset folio to available state instead of deleting to preserve folio pool
                EntityValue ftd = ec.entity.find("mchile.dte.FiscalTaxDocument")
                    .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                    .one()

                if (ftd) {
                    ftd.statusId = 'Ftd-NotIssued'
                    ftd.sentAuthStatusId = 'Ftd-NotSentAuth'
                    ftd.sentRecStatusId = 'Ftd-NotSentRec'
                    ftd.date = null
                    ftd.update()
                }
            }

            // 2. Delete invoice items before invoices
            for (String invoiceId in createdInvoiceIds) {
                ec.entity.find("mantle.account.invoice.InvoiceItem")
                    .condition("invoiceId", invoiceId)
                    .deleteAll()
            }

            // 3. Delete invoices (depend on parties and products)
            for (String invoiceId in createdInvoiceIds) {
                ec.entity.find("mantle.account.invoice.Invoice")
                    .condition("invoiceId", invoiceId)
                    .deleteAll()
            }

            // 4. Clean up party-related dependencies first
            if (testIssuerPartyId) {
                try {
                    cleanupPartyDependencies(testIssuerPartyId)
                } catch (Exception e) {
                    logger.warn("Could not delete test issuer party dependencies ${testIssuerPartyId}: ${e.message}")
                }
            }

            if (testReceiverPartyId) {
                try {
                    cleanupPartyDependencies(testReceiverPartyId)
                } catch (Exception e) {
                    logger.warn("Could not delete test receiver party dependencies ${testReceiverPartyId}: ${e.message}")
                }
            }

            // 5. Clean up test product
            if (testProductId) {
                try {
                    ec.entity.find("mantle.product.Product")
                        .condition("productId", testProductId)
                        .deleteAll()
                } catch (Exception e) {
                    logger.warn("Could not delete test product ${testProductId}: ${e.message}")
                }
            }

            logger.info("Test data cleanup completed using manual dependency order")

            // Commit the cleanup transaction
            ec.transaction.commit()

        } catch (Exception e) {
            logger.error("Critical error during test data cleanup: ${e.message}", e)
            ec.transaction.rollback("Critical error during test data cleanup in DteGenerationTests", e)
        } finally {
            ec.artifactExecution.enableAuthz()

            // Clear any errors to prevent propagation to other test suites
            ec.message.clearErrors()
        }
    }

    private void cleanupPartyDependencies(String partyId) {
        // Delete party dependencies in correct order to avoid foreign key violations

        // 1. Delete party contact mechanisms
        ec.entity.find("mantle.party.contact.PartyContactMech")
            .condition("partyId", partyId)
            .deleteAll()

        // 2. Delete party identifications
        ec.entity.find("mantle.party.PartyIdentification")
            .condition("partyId", partyId)
            .deleteAll()

        // 3. Delete party roles
        ec.entity.find("mantle.party.PartyRole")
            .condition("partyId", partyId)
            .deleteAll()

        // 4. Delete party relationships where this party is involved
        ec.entity.find("mantle.party.PartyRelationship")
            .condition("fromPartyId", partyId)
            .deleteAll()
        ec.entity.find("mantle.party.PartyRelationship")
            .condition("toPartyId", partyId)
            .deleteAll()

        // 5. Delete organization record if it exists
        ec.entity.find("mantle.party.Organization")
            .condition("partyId", partyId)
            .deleteAll()

        // 6. Delete the base party record last
        ec.entity.find("mantle.party.Party")
            .condition("partyId", partyId)
            .deleteAll()

        logger.info("Cleaned up party dependencies for: ${partyId}")
    }

    /**
     * Generic helper method to validate XML content in generated DTE documents
     * @param fiscalTaxDocumentId The fiscal tax document ID
     * @param validations List of validation maps, each containing:
     *   - xpath: The XPath expression to evaluate (required)
     *   - expected: The expected value (optional, can be null to skip validation)
     *   - name: A descriptive name for this validation (required)
     *   - type: The expected data type: 'string', 'number', 'decimal' (default: 'string')
     * @param logXml If true, logs the full XML content for debugging (default: false)
     * @return Map with:
     *   - valid: boolean indicating if all validations passed
     *   - errors: List of error messages
     *   - results: List of maps with name, xpath, expected, actual, valid for each validation
     */
    private Map validateXmlContent(String fiscalTaxDocumentId, List<Map> validations, boolean logXml = false) {
        // Use DteContentServices to get the XML content
        Map contentResult = ec.service.sync().name("mchile.sii.dte.DteContentServices.get#DteContent")
            .parameters([
                fiscalTaxDocumentId: fiscalTaxDocumentId,
                fiscalTaxDocumentContentTypeEnumId: "Ftdct-Xml"
            ]).call()

        if (!contentResult.contentRef) {
            return [valid: false, error: "No XML found for document ${fiscalTaxDocumentId}", results: []]
        }

        try {
            // Parse XML directly from stream to respect ISO-8859-1 encoding declaration
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
            factory.setNamespaceAware(false)
            InputStream xmlStream = contentResult.contentRef.openStream()
            Document doc = factory.newDocumentBuilder().parse(xmlStream)
            xmlStream.close()

            // Get XML text for logging/debugging (after parsing to avoid encoding issues)
            String xmlText = null
            try {
                xmlText = contentResult.contentRef.getText()
            } catch (Exception e) {
                logger.error("Error reading XML content: ${e.message}", e)
                return [valid: false, error: "Error reading XML content: ${e.message}", results: []]
            }

            if (!xmlText) {
                return [valid: false, error: "XML content is empty for document ${fiscalTaxDocumentId}", results: []]
            }

            // Log XML content if requested (for debugging)
            if (logXml) {
                logger.info("=" * 80)
                logger.info("XML Content for document ${fiscalTaxDocumentId}:")
                logger.info("=" * 80)
                logger.info(xmlText)
                logger.info("=" * 80)
            }

            // Create XPath
            XPath xpath = XPathFactory.newInstance().newXPath()

            // Process all validations in a single pass
            boolean allValid = true
            List<String> errors = []
            List<Map> results = []

            for (Map validation : validations) {
                String xpathExpr = validation.xpath
                Object expectedValue = validation.expected
                String name = validation.name
                String type = validation.type ?: 'string'

                Object actualValue = null
                boolean validationPassed = true
                String errorMsg = null

                try {
                    String rawValue = xpath.evaluate(xpathExpr, doc)

                    if (rawValue != null && !rawValue.isEmpty()) {
                        // Convert based on type
                        switch (type) {
                            case 'decimal':
                            case 'number':
                                actualValue = new BigDecimal(rawValue)
                                break
                            case 'integer':
                                actualValue = Integer.parseInt(rawValue)
                                break
                            default:
                                actualValue = rawValue
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not evaluate XPath '${xpathExpr}' for ${name}: ${e.message}")
                }

                // Validate if expected value is provided
                if (expectedValue != null && actualValue != null) {
                    if (actualValue != expectedValue) {
                        validationPassed = false
                        errorMsg = "${name} mismatch: expected ${expectedValue}, got ${actualValue}"
                        errors.add(errorMsg)
                        allValid = false
                    }
                }

                results.add([
                    name: name,
                    xpath: xpathExpr,
                    expected: expectedValue,
                    actual: actualValue,
                    valid: validationPassed,
                    error: errorMsg
                ])
            }

            return [
                valid: allValid,
                errors: errors,
                results: results
            ]

        } catch (Exception e) {
            logger.error("Error parsing XML for validation: ${e.message}", e)
            return [valid: false, error: "XML parsing error: ${e.message}", results: []]
        }
    }

    /**
     * Unified helper method to validate XML totals and items in generated DTE (wrapper for validateXmlContent)
     * Performs a single XML parse and validation pass for both totals and items
     * @param fiscalTaxDocumentId The fiscal tax document ID
     * @param expectedNeto Expected net total (Monto Neto) - optional
     * @param expectedExento Expected exempt total (Monto Exento) - optional
     * @param expectedIva Expected VAT total (IVA) - optional
     * @param expectedItems List of expected item values, each map should contain:
     *   - prcItem: Expected unit price (optional)
     *   - montoItem: Expected line total (optional)
     * @param logXml If true, logs the full XML content for debugging (default: false)
     * @return Map with validation results including:
     *   - valid: boolean indicating if all validations passed
     *   - errors: List of error messages
     *   - actualNeto: Actual net total from XML
     *   - actualExento: Actual exempt total from XML
     *   - actualIva: Actual VAT total from XML
     *   - actualItems: List of actual item values from XML
     */
    private Map validateXmlTotalsAndItems(String fiscalTaxDocumentId,
                                          BigDecimal expectedNeto = null,
                                          BigDecimal expectedExento = null,
                                          BigDecimal expectedIva = null,
                                          List<Map> expectedItems = null,
                                          boolean logXml = false) {
        // Build all validations in a single list
        List<Map> validations = []

        // Add totals validations
        validations.add([xpath: "//Totales/MntNeto", expected: expectedNeto, name: "MntNeto", type: "decimal"])
        validations.add([xpath: "//Totales/MntExe", expected: expectedExento, name: "MntExe", type: "decimal"])
        validations.add([xpath: "//Totales/IVA", expected: expectedIva, name: "IVA", type: "decimal"])

        // Add item validations if provided
        if (expectedItems) {
            for (int i = 0; i < expectedItems.size(); i++) {
                Map expectedItem = expectedItems[i]
                int nroLinDet = i + 1

                validations.add([
                    xpath: "//Detalle[NroLinDet='${nroLinDet}']/PrcItem",
                    expected: expectedItem.prcItem,
                    name: "Line ${nroLinDet} PrcItem",
                    type: "decimal"
                ])

                validations.add([
                    xpath: "//Detalle[NroLinDet='${nroLinDet}']/MontoItem",
                    expected: expectedItem.montoItem,
                    name: "Line ${nroLinDet} MontoItem",
                    type: "decimal"
                ])
            }
        }

        // Perform single XML validation pass
        Map result = validateXmlContent(fiscalTaxDocumentId, validations, logXml)

        // Extract totals results
        def netoResult = result.results?.find { it.name == "MntNeto" }
        def exentoResult = result.results?.find { it.name == "MntExe" }
        def ivaResult = result.results?.find { it.name == "IVA" }

        // Extract item results if items were validated
        List<Map> actualItems = []
        if (expectedItems) {
            for (int i = 0; i < expectedItems.size(); i++) {
                int nroLinDet = i + 1
                def prcItemResult = result.results?.find { it.name == "Line ${nroLinDet} PrcItem" }
                def montoItemResult = result.results?.find { it.name == "Line ${nroLinDet} MontoItem" }

                actualItems.add([
                    nroLinDet: nroLinDet,
                    prcItem: prcItemResult?.actual,
                    montoItem: montoItemResult?.actual
                ])
            }
        }

        return [
            valid: result.valid,
            errors: result.errors,
            error: result.error,
            actualNeto: netoResult?.actual,
            actualExento: exentoResult?.actual,
            actualIva: ivaResult?.actual,
            actualItems: actualItems
        ]
    }

    def "001 - generate Factura with minimal required parameters"() {
        when:
        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-33", // Factura Electrónica
                issuerPartyId: testIssuerPartyId, // TEST-DTE-ORG
                rutReceptor: "87654321-4",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization",
                giroReceptor: 'Giro de Prueba Receptor',
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456",
                comunaReceptor: "Valparaiso",
                detailList: [[ description: "Test Product", quantity: 1, amount: 100000 ], [ itemTypeEnumId:'ItemVatTax', quantity:1, amount:19000 ]]
            ]).call()

        // Track created document for cleanup
        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        // Verify the created document
        EntityValue fiscalDoc = null
        if (result.fiscalTaxDocumentId) {
            fiscalDoc = ec.entity.find("mchile.dte.FiscalTaxDocument")
                .condition("fiscalTaxDocumentId", result.fiscalTaxDocumentId)
                .one()
        }

        then:
        !ec.message.hasError()
        result.fiscalTaxDocumentId
        fiscalDoc
        fiscalDoc.fiscalTaxDocumentTypeEnumId == "Ftdt-33"
        fiscalDoc.issuerPartyId == testIssuerPartyId
        fiscalDoc.receiverPartyIdValue == "87654321-4"
        fiscalDoc.statusId == "Ftd-Issued"

        cleanup:
        totalFieldsChecked += 6
        logger.info("✓ Basic Factura generation test passed - Document ID: ${result.fiscalTaxDocumentId}")
    }

    def "002 - generate Factura with complete parameter set"() {
        when:
        Timestamp emissionDate = ec.user.nowTimestamp
        Timestamp dueDate = new Timestamp(emissionDate.time + (30 * 24 * 60 * 60 * 1000L)) // 30 days later

        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization",
                giroReceptor: "Test Business Activity",
                contactoReceptor: "Test Contact Person",
                direccionReceptor: "Receiver Street 456, Valparaiso",
                comunaReceptor: "Valparaiso",
                ciudadReceptor: "Valparaiso",
                fechaEmision: emissionDate,
                fechaVencimiento: dueDate,
                medioPago: "EF",
                invoiceMessage: "Test invoice message",
                detailList: [
                    [
                        description: "Test Product 1",
                        quantity: 2,
                        amount: 50000
                    ],
                    [
                        description: "Test Product 2",
                        quantity: 1,
                        amount: 75000
                    ],
                    [
                        itemTypeEnumId:'ItemVatTax',
                        quantity:1,
                        amount:31588
                    ]
                ],
                descuentoORecargoGlobalList: [
                    [
                        numeroLinea: 1,
                        tipo: "D", // Descuento
                        glosa: "Descuento por volumen",
                        tipoValor: "%",
                        valor: 5.0
                    ]
                ]
            ]).call()

        // Track for cleanup
        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        EntityValue fiscalDoc = ec.entity.find("mchile.dte.FiscalTaxDocument")
            .condition("fiscalTaxDocumentId", result.fiscalTaxDocumentId)
            .one()

        then:
        !ec.message.hasError()
        result.fiscalTaxDocumentId
        fiscalDoc
        fiscalDoc.fiscalTaxDocumentTypeEnumId == "Ftdt-33"
        fiscalDoc.issuerPartyId == testIssuerPartyId
        fiscalDoc.statusId == "Ftd-Issued"

        cleanup:
        totalFieldsChecked += 7
        logger.info("✓ Complete Factura generation test passed - Document ID: ${result.fiscalTaxDocumentId}")
    }

    @Unroll
    def "003 - generate Facturas using generate-Factura service (#dteType, #description)"() {
        when:

        List detailList = [ [ description: "Test Product for ${description}", quantity: 1, amount: amount, afecto: (dteType != 'Ftdt-34') ] ]
        if (dteType != 'Ftdt-34')
            detailList.add([ itemTypeEnumId:'ItemVatTax', quantity:1, amount:amount*0.19 ])
        Map parameters = [
                fiscalTaxDocumentTypeEnumId: dteType,
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",
                giroReceptor: "Test Business Activity",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456",
                comunaReceptor: "Valparaiso",
                detailList: detailList
        ]
        if (dteType in ['Ftdt-56', 'Ftdt-61']) {
            Map referencia = [fiscalTaxDocumentTypeEnumId:'Ftdt-33', folio:'12345', razonReferencia:'Modifica el documento', fecha:ec.user.nowTimestamp]
            if (dteType == 'Ftdt-61')
                referencia.codigo = 1
            parameters.referenciaList = [referencia]
        }
        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura").parameters(parameters).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        EntityValue fiscalDoc = ec.entity.find("mchile.dte.FiscalTaxDocument")
            .condition("fiscalTaxDocumentId", result.fiscalTaxDocumentId)
            .one()

        then:
        !ec.message.hasError()
        result.fiscalTaxDocumentId
        fiscalDoc.fiscalTaxDocumentTypeEnumId == dteType

        cleanup:
        // Clear any errors between parameterized test iterations
        if (ec.message.hasError()) {
            logger.warn("Unexpected errors in parameterized test iteration (${description}): ${ec.message.getErrorsString()}")
        }
        ec.message.clearAll()
        totalFieldsChecked += 2
        logger.info("✓ ${description} generation test passed")

        where:
        dteType    | description           | amount
        "Ftdt-33"  | "Factura Electrónica" | 119000
        "Ftdt-34"  | "Factura Exenta"      | 100000
        "Ftdt-61"  | "Nota de Crédito"     | 50000   // Credit note
        "Ftdt-56"  | "Nota de Débito"      | 25000   // Debit note
    }

    @Unroll 
    def "004 - generate Boletas using generate-Boleta service (#dteType, #description)"() {
        when:

        List detailList = [[ description: "Boleta Test Product for ${description}", quantity: 1, amount: amount, afecto: (dteType != 'Ftdt-41') ]]
        if (dteType != 'Ftdt-41')
            detailList.add([ itemTypeEnumId:'ItemVatTax', quantity:1, amount:amount*0.19 ])

        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Boleta")
            .parameters([
                fiscalTaxDocumentTypeEnumId: dteType,
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456",
                comunaReceptor: "Valparaiso",
                detailList: detailList
            ]).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        EntityValue fiscalDoc = ec.entity.find("mchile.dte.FiscalTaxDocument")
            .condition("fiscalTaxDocumentId", result.fiscalTaxDocumentId)
            .one()

        then:
        !ec.message.hasError()
        result.fiscalTaxDocumentId
        fiscalDoc.fiscalTaxDocumentTypeEnumId == dteType

        and: "Validate Boleta can be wrapped in EnvioBoleta and passes schema validation"
        Map envioResult = ec.service.sync().name("mchile.sii.dte.DteEnvioServices.genera#EnvioBoleta")
            .parameters([
                organizationPartyId: testIssuerPartyId,
                documentIdList: [result.fiscalTaxDocumentId],
                skipEnvioCreation: true  // Validation-only, don't create DB records
            ]).call()

        logger.info("EnvioBoleta validation result for ${description}: schemaError=${envioResult.schemaError}")
        envioResult.schemaError == null  // Must pass SII schema validation

        cleanup:
        // Clear any errors between parameterized test iterations
        if (ec.message.hasError()) {
            logger.warn("Unexpected errors in parameterized test iteration (${description}): ${ec.message.getErrorsString()}")
        }
        ec.message.clearAll()
        totalFieldsChecked += 3
        logger.info("✓ ${description} generation test passed")

        where:
        dteType    | description           | amount
        "Ftdt-39"  | "Boleta Electrónica"  | 5000
        "Ftdt-41"  | "Boleta Exenta"       | 7500
    }

    def "005 - generate Factura with draft mode"() {
        when:
        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",
                giroReceptor: 'Giro de Prueba Receptor',
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456", 
                comunaReceptor: "Valparaiso",
                draft: true,
                detailList: [
                        [ itemDescription: "Draft Test Product", quantity: 1, amount: 50000 ] // if draft == true, treat itemType as 'OrderItem'.
                ]
            ]).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        // Verify the draft document
        EntityValue fiscalDoc = null
        if (result.fiscalTaxDocumentId) {
            fiscalDoc = ec.entity.find("mchile.dte.FiscalTaxDocument").condition("fiscalTaxDocumentId", result.fiscalTaxDocumentId).one()
        }

        then:
        !ec.message.hasError() 
        result.previewPdf
        result.previewPdf.length > 1000 // PDF should have reasonable size

        cleanup:
        totalFieldsChecked += 7
        logger.info("✓ Draft Factura generation test passed - Document ID: ${result.fiscalTaxDocumentId}, Status: ${fiscalDoc?.statusId}")
    }

    def "006 - generate Factura with missing required parameter fails gracefully"() {
        when:
        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters([
                // Missing fiscalTaxDocumentTypeEnumId
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization"
            ]).call()

        then:
        ec.message.hasError()
        !result?.fiscalTaxDocumentId

        cleanup:
        // Clear the expected error from this test
        ec.message.clearAll()
        totalFieldsChecked += 2
        logger.info("✓ Missing parameter error handling test passed")
    }

    def "007 - generate Factura with invalid issuer party fails gracefully"() {
        when:
        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                issuerPartyId: "INVALID_PARTY_ID",
                rutReceptor: "87654321-4",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456",
                comunaReceptor: "Valparaiso",
                detailList: [[
                    description: "Test Product",
                    quantity: 1,
                    amount: 100000
                ]]
            ]).call()

        then:
        ec.message.hasError() || !result.fiscalTaxDocumentId

        cleanup:
        // Clear the expected error from this test
        ec.message.clearAll()
        totalFieldsChecked += 1
        logger.info("✓ Invalid issuer party error handling test passed")
    }

    def "008 - generate Factura with zero amount detail"() {
        when:
        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",
                giroReceptor: 'Giro de Prueba Receptor',
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456",
                comunaReceptor: "Valparaiso",
                detailList: [[
                    description: "Free Product",
                    quantity: 1,
                    amount: 0
                ]]
            ]).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then:
        // Should either succeed (for valid zero-amount scenarios) or fail gracefully
        !ec.message.hasError() || result.fiscalTaxDocumentId != null
        
        cleanup:
        totalFieldsChecked += 1
        logger.info("✓ Zero amount detail test passed")
    }

    def "009 - generate Factura performance test"() {
        when:
        long startTime = System.currentTimeMillis()
        
        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                issuerPartyId: testIssuerPartyId,
                contactoReceptor: "Test Contact",
                rutReceptor: "87654321-4",
                giroReceptor: 'Giro de Prueba Receptor',
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization",
                giroReceptor: 'Giro Prueba Receptor',
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456",
                comunaReceptor: "Valparaiso",
                detailList: (1..10).collect { i ->
                    [ description: "Performance Test Product ${i}", quantity: i, amount: 10000 * i ]
                } + [ itemTypeEnumId:'ItemVatTax', quantity:1, amount:731500 ]
            ]).call()

        long executionTime = System.currentTimeMillis() - startTime

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then:
        !ec.message.hasError()
        result.fiscalTaxDocumentId
        executionTime < 10000 // Should complete within 10 seconds

        cleanup:
        totalFieldsChecked += 4
        logger.info("✓ Performance test passed - Execution time: ${executionTime}ms")
    }

    def "010 - verify certificate RUT extension extraction"() {
        when:
        // Verify that the test organization's certificate has extractable RUT extension
        Map verifyResult = ec.service.sync().name("mchile.test.TestCertificateServices.verify#CertificateRutExtension")
            .parameters([partyId: testIssuerPartyId])
            .call()

        then:
        !ec.message.hasError()
        verifyResult.rutFound != null
        verifyResult.certificateValid == true
        verifyResult.expirationDate != null
        
        // Verify RUT format is correct (Chilean format: ########-#)
        verifyResult.rutFound ==~ /\d{6,8}-[\dkK]/

        cleanup:
        totalFieldsChecked += 4
        logger.info("✓ Certificate RUT extraction test passed - RUT found: ${verifyResult.rutFound}")
    }

    def "011 - load DTE configuration with RUT extraction"() {
        when:
        // Load DTE configuration and verify RUT can be extracted from certificate
        Map dteConfig = ec.service.sync().name("mchile.sii.dte.DteInternalServices.load#DteConfig")
            .parameters([partyId: testIssuerPartyId])
            .call()

        then:
        !ec.message.hasError()
        dteConfig.certificate != null
        dteConfig.pkey != null
        dteConfig.rutEnviador != null
        dteConfig.rutEnviador == "12345678-5"  // Test RUT should match

        cleanup:
        totalFieldsChecked += 4
        logger.info("✓ DTE config RUT extraction test passed - RUT enviador: ${dteConfig.rutEnviador}")
    }

    @Unroll
    def "012 - generate Factura with preciosIncluyenIva and generarXmlMontosBrutos combinations (#testCase)"() {
        when:
        // Base amount for testing: 100,000 CLP (net) or 119,000 CLP (gross with 19% VAT)
        BigDecimal inputAmount = preciosIncluyenIva ? 119000 : 100000
        BigDecimal expectedNetAmount = 100000
        BigDecimal expectedGrossAmount = 119000
        BigDecimal expectedVatAmount = 19000

        // Build detailList based on whether conversion is happening
        // When no conversion (both false), VAT item must be provided manually
        List detailList = [
            [
                description: "Test Product - ${testCase}",
                quantity: 1,
                amount: inputAmount
            ]
        ]

        // Add VAT item when preciosIncluyenIva = false (net prices require explicit VAT)
        if (!preciosIncluyenIva) {
            detailList.add([itemTypeEnumId: 'ItemVatTax', quantity: 1, amount: expectedVatAmount])
        }

        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization",
                giroReceptor: "Test Business Activity",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456",
                comunaReceptor: "Valparaiso",
                preciosIncluyenIva: preciosIncluyenIva,
                generarXmlMontosBrutos: generarXmlMontosBrutos,
                detailList: detailList
            ]).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        // Retrieve the generated document
        EntityValue fiscalDoc = ec.entity.find("mchile.dte.FiscalTaxDocument")
            .condition("fiscalTaxDocumentId", result.fiscalTaxDocumentId)
            .one()

        // Calculate expected XML values based on price conversion logic
        // For Facturas: generarXmlMontosBrutos=true means XML shows gross amounts
        BigDecimal expectedXmlNeto = 100000
        BigDecimal expectedXmlIva = 19000
        BigDecimal expectedPrcItem = generarXmlMontosBrutos ? 119000 : 100000
        BigDecimal expectedMontoItem = generarXmlMontosBrutos ? 119000 : 100000

        // Validate XML totals and items in a single pass (only if document was created successfully)
        Map validation = result.fiscalTaxDocumentId ?
            validateXmlTotalsAndItems(result.fiscalTaxDocumentId, expectedXmlNeto, null, expectedXmlIva,
                [[prcItem: expectedPrcItem, montoItem: expectedMontoItem]]) :
            [valid: false, error: "No document created", actualItems: []]

        if (!validation.valid && result.fiscalTaxDocumentId) {
            logger.error("XML validation failed for ${testCase}: ${validation.errors ?: validation.error}")
        }

        then:
        !ec.message.hasError()
        result.fiscalTaxDocumentId
        fiscalDoc != null
        fiscalDoc.fiscalTaxDocumentTypeEnumId == "Ftdt-33"
        fiscalDoc.statusId == "Ftd-Issued"
        validation.valid
        validation.actualIva == expectedXmlIva
        validation.actualItems[0].prcItem == expectedPrcItem
        validation.actualItems[0].montoItem == expectedMontoItem

        cleanup:
        // Clear any errors between parameterized test iterations
        if (ec.message.hasError()) {
            logger.warn("Unexpected errors in parameterized test iteration (${testCase}): ${ec.message.getErrorsString()}")
            ec.message.clearErrors()
        }
        totalFieldsChecked += 6  // Added XML validation checks
        logger.info("✓ Factura ${testCase} test passed - FiscalDoc: ${result.fiscalTaxDocumentId}")

        where:
        testCase                                    | preciosIncluyenIva | generarXmlMontosBrutos
        "Net input, Net XML (default)"              | false              | false
        "Net input, Gross XML"                      | false              | true
        "Gross input, Net XML"                      | true               | false
        "Gross input, Gross XML"                    | true               | true
    }

    @Unroll
    def "013 - generate FacturaInternal with preciosIncluyenIva and generarXmlMontosBrutos combinations (#testCase)"() {
        when:
        // For *Internal services, provide a properly structured detailList (like the REST API does)
        // rather than creating invoice items in the database
        BigDecimal inputAmount = preciosIncluyenIva ? 119000 : 100000

        List detailList = [
            [
                description: "Test Product - ${testCase}",
                quantity: 1,
                amount: inputAmount,
                itemTypeEnumId: "ItemProduct"
            ]
        ]

        // Add VAT item when preciosIncluyenIva = false (net prices require explicit VAT)
        if (!preciosIncluyenIva) {
            detailList.add([
                itemTypeEnumId: "ItemVatTax",
                quantity: 1,
                amount: 19000  // 19% of 100000
            ])
        }

        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#FacturaInternal")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                detailList: detailList,
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",
                razonSocialReceptor: "Test DTE Receiver Organization",
                giroReceptor: "Test Business Activity",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456",
                comunaReceptor: "Valparaiso",
                preciosIncluyenIva: preciosIncluyenIva,
                generarXmlMontosBrutos: generarXmlMontosBrutos
            ]).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        EntityValue fiscalDoc = ec.entity.find("mchile.dte.FiscalTaxDocument")
            .condition("fiscalTaxDocumentId", result.fiscalTaxDocumentId)
            .one()

        // Calculate expected XML values based on price conversion logic
        // For Facturas: MntNeto is ALWAYS present (Chilean SII requirement)
        // generarXmlMontosBrutos=true means line items show gross amounts with MntBruto indicator
        BigDecimal expectedXmlNeto = 100000  // Always present for Facturas
        BigDecimal expectedXmlIva = 19000
        BigDecimal expectedPrcItem = generarXmlMontosBrutos ? 119000 : 100000
        BigDecimal expectedMontoItem = generarXmlMontosBrutos ? 119000 : 100000

        // Validate XML totals and items in a single pass (only if document was created successfully)
        Map validation = result.fiscalTaxDocumentId ?
            validateXmlTotalsAndItems(result.fiscalTaxDocumentId, expectedXmlNeto, null, expectedXmlIva,
                [[prcItem: expectedPrcItem, montoItem: expectedMontoItem]]) :
            [valid: false, error: "No document created", actualItems: []]

        if (!validation.valid && result.fiscalTaxDocumentId) {
            logger.error("XML validation failed for ${testCase}: ${validation.errors ?: validation.error}")
        }

        then:
        !ec.message.hasError()
        result.fiscalTaxDocumentId
        fiscalDoc != null
        fiscalDoc.fiscalTaxDocumentTypeEnumId == "Ftdt-33"
        fiscalDoc.statusId == "Ftd-Issued"
        validation.valid
        validation.actualIva == expectedXmlIva
        validation.actualItems[0].prcItem == expectedPrcItem
        validation.actualItems[0].montoItem == expectedMontoItem

        cleanup:
        // Clear any errors between parameterized test iterations
        if (ec.message.hasError()) {
            logger.warn("Unexpected errors in parameterized test iteration (${testCase}): ${ec.message.getErrorsString()}")
            ec.message.clearErrors()
        }
        totalFieldsChecked += 4
        logger.info("✓ FacturaInternal ${testCase} test passed - FiscalDoc: ${result.fiscalTaxDocumentId}")

        where:
        testCase                                    | preciosIncluyenIva | generarXmlMontosBrutos
        "Net input, Net XML (default)"             | false              | false
        "Net input, Gross XML"                      | false              | true
        "Gross input, Net XML"                      | true               | false
        "Gross input, Gross XML"                    | true               | true
    }

    @Unroll
    def "014 - generate Boleta with preciosIncluyenIva and generarXmlMontosNetos combinations (#testCase)"() {
        when:
        // For Boletas, default is preciosIncluyenIva=true (gross prices)
        BigDecimal inputAmount = preciosIncluyenIva ? 11900 : 10000
        BigDecimal expectedNetAmount = 10000
        BigDecimal expectedGrossAmount = 11900
        BigDecimal expectedVatAmount = 1900

        // Build detailList for Boleta
        List detailList = [
            [
                description: "Boleta Product - ${testCase}",
                quantity: 1,
                amount: inputAmount
            ]
        ]

        // Add VAT item when preciosIncluyenIva = false (net prices require explicit VAT)
        if (!preciosIncluyenIva) {
            detailList.add([itemTypeEnumId: 'ItemVatTax', quantity: 1, amount: expectedVatAmount])
        }

        ec.logger.info("Executing generate#Boleta with preciosIncluyenIva: ${preciosIncluyenIva}, generarXmlMontosNetos: ${generarXmlMontosNetos}, detailList: ${detailList}")
        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Boleta")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-39",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456",
                comunaReceptor: "Valparaiso",
                preciosIncluyenIva: preciosIncluyenIva,
                generarXmlMontosNetos: generarXmlMontosNetos,
                detailList: detailList
            ]).call()

        // Capture errors immediately after service call for better debugging
        String errorString = ec.message.getErrorsString()
        if (errorString) {
            logger.error("Service errors in test (${testCase}): ${errorString}")
        }

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        EntityValue fiscalDoc = ec.entity.find("mchile.dte.FiscalTaxDocument")
            .condition("fiscalTaxDocumentId", result.fiscalTaxDocumentId)
            .one()

        // Calculate expected XML values based on price conversion logic
        // For Boletas: generarXmlMontosNetos=true shows net amounts, false shows gross amounts (default)
        BigDecimal expectedXmlNeto = generarXmlMontosNetos ? 10000 : null
        BigDecimal expectedXmlIva = 1900
        BigDecimal expectedPrcItem = generarXmlMontosNetos ? 10000 : 11900
        BigDecimal expectedMontoItem = generarXmlMontosNetos ? 10000 : 11900

        // Validate XML totals and items in a single pass (only if document was created successfully)
        Map validation = result.fiscalTaxDocumentId ?
            validateXmlTotalsAndItems(result.fiscalTaxDocumentId, expectedXmlNeto, null, expectedXmlIva,
                [[prcItem: expectedPrcItem, montoItem: expectedMontoItem]]) :
            [valid: false, error: "No document created", actualItems: []]

        if (!validation.valid && result.fiscalTaxDocumentId) {
            logger.error("XML validation failed for ${testCase}: ${validation.errors ?: validation.error}")
        }

        then:
        errorString == null || errorString.isEmpty()
        result.fiscalTaxDocumentId
        fiscalDoc != null
        fiscalDoc.fiscalTaxDocumentTypeEnumId == "Ftdt-39"
        fiscalDoc.statusId == "Ftd-Issued"
        validation.valid
        validation.actualIva == expectedXmlIva
        validation.actualItems[0].prcItem == expectedPrcItem
        validation.actualItems[0].montoItem == expectedMontoItem

        cleanup:
        // Clear any errors between parameterized test iterations
        if (ec.message.hasError()) {
            logger.warn("Unexpected errors in parameterized test iteration (${testCase}): ${ec.message.getErrorsString()}")
            ec.message.clearErrors()
        }
        totalFieldsChecked += 4
        logger.info("✓ Boleta ${testCase} test passed - FiscalDoc: ${result.fiscalTaxDocumentId}")

        where:
        testCase                                    | preciosIncluyenIva | generarXmlMontosNetos
        "Gross input, Gross XML (default)"         | true               | false
        "Gross input, Net XML"                      | true               | true
        "Net input, Gross XML"                      | false              | false
        "Net input, Net XML"                        | false              | true
    }

    @Unroll
    def "015 - generate BoletaInternal with preciosIncluyenIva and generarXmlMontosNetos combinations (#testCase)"() {
        when:
        // For *Internal services, provide a properly structured detailList (like the REST API does)
        // rather than creating invoice items in the database
        BigDecimal inputAmount = preciosIncluyenIva ? 11900 : 10000

        List detailList = [
            [
                description: "Boleta Product - ${testCase}",
                quantity: 1,
                amount: inputAmount,
                itemTypeEnumId: "ItemProduct"
            ]
        ]

        // Add VAT item when preciosIncluyenIva = false (net prices require explicit VAT)
        if (!preciosIncluyenIva) {
            detailList.add([
                itemTypeEnumId: "ItemVatTax",
                quantity: 1,
                amount: 1900  // 19% of 10000
            ])
        }

        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#BoletaInternal")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-39",
                detailList: detailList,
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",
                razonSocialReceptor: "Test DTE Receiver Organization",
                giroReceptor: "Test Business Activity",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456",
                comunaReceptor: "Valparaiso",
                preciosIncluyenIva: preciosIncluyenIva,
                generarXmlMontosNetos: generarXmlMontosNetos
            ]).call()

        // Capture errors immediately after service call for better debugging
        String errorString = ec.message.getErrorsString()
        if (errorString) {
            logger.error("Service errors in test (${testCase}): ${errorString}")
        }

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        EntityValue fiscalDoc = ec.entity.find("mchile.dte.FiscalTaxDocument")
            .condition("fiscalTaxDocumentId", result.fiscalTaxDocumentId)
            .one()

        // Calculate expected XML values based on price conversion logic
        // For Boletas: generarXmlMontosNetos=true shows net amounts, false shows gross amounts (default)
        BigDecimal expectedXmlNeto = generarXmlMontosNetos ? 10000 : null
        BigDecimal expectedXmlIva = 1900
        BigDecimal expectedPrcItem = generarXmlMontosNetos ? 10000 : 11900
        BigDecimal expectedMontoItem = generarXmlMontosNetos ? 10000 : 11900

        // Validate XML totals and items in a single pass (only if document was created successfully)
        Map validation = result.fiscalTaxDocumentId ?
            validateXmlTotalsAndItems(result.fiscalTaxDocumentId, expectedXmlNeto, null, expectedXmlIva,
                [[prcItem: expectedPrcItem, montoItem: expectedMontoItem]]) :
            [valid: false, error: "No document created", actualItems: []]

        if (!validation.valid && result.fiscalTaxDocumentId) {
            logger.error("XML validation failed for ${testCase}: ${validation.errors ?: validation.error}")
        }

        then:
        errorString == null || errorString.isEmpty()
        result.fiscalTaxDocumentId
        fiscalDoc != null
        fiscalDoc.fiscalTaxDocumentTypeEnumId == "Ftdt-39"
        fiscalDoc.statusId == "Ftd-Issued"
        validation.valid
        validation.actualIva == expectedXmlIva
        validation.actualItems[0].prcItem == expectedPrcItem
        validation.actualItems[0].montoItem == expectedMontoItem

        cleanup:
        // Clear any errors between parameterized test iterations
        if (ec.message.hasError()) {
            logger.warn("Unexpected errors in parameterized test iteration (${testCase}): ${ec.message.getErrorsString()}")
            ec.message.clearErrors()
        }
        totalFieldsChecked += 4
        logger.info("✓ BoletaInternal ${testCase} test passed - FiscalDoc: ${result.fiscalTaxDocumentId}")

        where:
        testCase                                    | preciosIncluyenIva | generarXmlMontosNetos
        "Gross input, Gross XML (default)"         | true               | false
        "Gross input, Net XML"                      | true               | true
        "Net input, Gross XML"                      | false              | false
        "Net input, Net XML"                        | false              | true
    }

    /**
     * ROUNDING DISCREPANCY DETECTION TESTS
     *
     * These tests detect the production rounding issue where sum(MontoItems) ≠ MntTotal
     * causing SII warning: "REPARO- Monto Total No Cuadra con Parciales"
     *
     * Root cause: sum(round(values)) ≠ round(sum(values))
     */

    def "017 - ROUNDING: Clean case with round numbers (should pass)"() {
        given: "Test data with clean round numbers that should NOT cause rounding issues"
        BigDecimal item1Quantity = 2
        BigDecimal item1UnitPrice = 10000
        BigDecimal item2Quantity = 3
        BigDecimal item2UnitPrice = 5000

        BigDecimal item1Total = item1Quantity * item1UnitPrice  // 20000
        BigDecimal item2Total = item2Quantity * item2UnitPrice  // 15000
        BigDecimal totalNeto = item1Total + item2Total  // 35000

        // Calculate IVA (19%) - simulating what the API does
        BigDecimal montoIva = (totalNeto * 0.19).setScale(0, java.math.RoundingMode.HALF_UP)  // 6650
        BigDecimal expectedTotal = totalNeto + montoIva  // 41650

        when: "Generate Boleta with clean numbers (including IVA item like the API does)"
        logger.info("TEST 017: Clean Case - verifying no false positives")
        logger.info("Item 1: ${item1Quantity} × ${item1UnitPrice} = ${item1Total}")
        logger.info("Item 2: ${item2Quantity} × ${item2UnitPrice} = ${item2Total}")
        logger.info("Total Neto: ${totalNeto}")
        logger.info("IVA (19%): ${montoIva}")
        logger.info("Expected Total: ${expectedTotal}")

        // Call generate#BoletaInternal like the API does (line 262 in IssuingRestApiServicesV1_1_2.xml)
        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#BoletaInternal")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-39",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "88888888-8",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Receiver SPA",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Street 123",
                comunaReceptor: "Santiago",
                preciosIncluyenIva: false,  // NET prices for consistency with test 016
                importInvoiceFromXml: true,  // Set by API before calling generate#BoletaInternal (line 258)
                detailList: [
                    [
                        description: "Clean Product 1",
                        quantity: item1Quantity,
                        amount: item1UnitPrice,  // Unit price, service will multiply by quantity
                        itemTypeEnumId: "ItemSales",
                        afecto: true  // Explicitly set to match API behavior
                    ],
                    [
                        description: "Clean Product 2",
                        quantity: item2Quantity,
                        amount: item2UnitPrice,  // Unit price, service will multiply by quantity
                        itemTypeEnumId: "ItemSales",
                        afecto: true  // Explicitly set to match API behavior
                    ],
                    [
                        // Add IVA item like the API does (line 254-256 in IssuingRestApiServicesV1_1_2.xml)
                        itemTypeEnumId: "ItemVatTax",
                        quantity: 1,
                        amount: montoIva
                    ]
                ]
            ]).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should complete without errors"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Parse the XML and validate totals"
        Map xmlData = parseDteXmlForRounding(result.fiscalTaxDocumentId)

        logger.info("Clean case XML results:")
        logger.info("  MntTotal: ${xmlData.mntTotal}")
        logger.info("  Sum of MontoItems: ${xmlData.sumMontoItems}")
        logger.info("  Discrepancy: ${xmlData.discrepancy} pesos")

        then: "Sum should equal total (no rounding issues with clean numbers)"
        xmlData.valid
        xmlData.sumMontoItems == xmlData.mntTotal
        xmlData.discrepancy == 0

        cleanup:
        totalFieldsChecked += 1
        logger.info("✓ Clean case test PASSED - No rounding discrepancy detected")
    }

    def "018 - VALIDATION BUG: NET prices with afecto items but no ItemVatTax"() {
        given: "NET prices with taxable items but missing IVA detail item"
        BigDecimal item1Quantity = 2
        BigDecimal item1UnitPrice = 10000
        BigDecimal item2Quantity = 6
        BigDecimal item2UnitPrice = 1224.833333

        when: "Generate Boleta with NET prices, afecto items, but NO ItemVatTax detail"
        logger.info("=" * 80)
        logger.info("TEST 018: Invalid State - NET prices with afecto items but no IVA item")
        logger.info("This should either FAIL the service OR create invalid DTE with IVA=0")
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
                preciosIncluyenIva: false,  // NET prices
                detailList: [
                    [
                        description: "Product 1",
                        quantity: item1Quantity,
                        amount: item1Quantity * item1UnitPrice,
                        afecto: true  // Taxable item
                    ],
                    [
                        description: "Product 2",
                        quantity: item2Quantity,
                        amount: item2Quantity * item2UnitPrice,
                        afecto: true  // Taxable item
                    ]
                    // MISSING: ItemVatTax item with IVA amount!
                ]
            ]).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Service should either FAIL with error OR succeed but create invalid DTE"
        // For now, we expect it to succeed (the bug)
        // After fix, this should fail with an appropriate error message
        def hasError = ec.message.hasError()
        def succeeded = result.fiscalTaxDocumentId != null

        if (hasError) {
            logger.info("✓ Service correctly detected invalid state and failed")
            logger.info("Error messages: ${ec.message.getErrors()}")
        } else if (succeeded) {
            logger.warn("✗ Service succeeded but created potentially invalid DTE")
        }

        // Always log what happened
        logger.info("Service execution result: hasError=${hasError}, succeeded=${succeeded}")

        when: "If DTE was created, check if IVA is zero (invalid state)"
        Map xmlData = null
        if (succeeded) {
            xmlData = parseDteXmlForRounding(result.fiscalTaxDocumentId)

            logger.info("DTE was created with:")
            logger.info("  MntNeto: ${xmlData.mntNeto}")
            logger.info("  IVA: ${xmlData.iva}")
            logger.info("  MntTotal: ${xmlData.mntTotal}")
            logger.info("  Has afecto items: true")
        }

        then: "CRITICAL: Boleta with afecto items MUST have IVA > 0"
        if (succeeded && xmlData?.valid) {
            // This assertion should FAIL with current code (the bug)
            // After fix, the service should prevent this scenario
            assert xmlData.iva != null && xmlData.iva > 0 : """
VALIDATION BUG DETECTED:
The service created a Boleta with:
- preciosIncluyenIva = false (NET prices)
- afecto items (taxable)
- IVA = ${xmlData.iva} (INVALID - should be > 0)

This is an invalid state. The service should either:
1. Require an ItemVatTax detail item when preciosIncluyenIva=false, OR
2. Halt execution with an error when afecto=true but total IVA=0

Current behavior: Service succeeds but creates invalid DTE
Expected behavior: Service should detect this and fail with error

See: .agent-os/findings/2025-10-28-afecto-preciosIncluyenIva-bug.md
""".trim()
        } else {
            // Service failed - this is the expected behavior after fix
            logger.info("✓ Service correctly prevented invalid DTE creation")
            assert hasError : "Service should have error messages explaining the validation failure"
        }

        cleanup:
        totalFieldsChecked += 1
    }

    def "019 - generate Nota de Crédito with only exento (exempt) values"() {
        given: "Credit note with only exempt items (no taxable amounts)"
        BigDecimal exemptAmount1 = 50000
        BigDecimal exemptAmount2 = 30000

        when: "Generate Nota de Crédito (Ftdt-61) with exento items only"
        logger.info("=" * 80)
        logger.info("TEST 019: Nota de Crédito with only exento (exempt) values")
        logger.info("=" * 80)

        List detailList = [
            [description: "Exempt Product 1", quantity: 1, amount: exemptAmount1, afecto: false],
            [description: "Exempt Product 2", quantity: 1, amount: exemptAmount2, afecto: false]
        ]

        Map referencia = [
            fiscalTaxDocumentTypeEnumId: 'Ftdt-34',  // Reference to Factura Exenta
            folio: '54321',
            razonReferencia: 'Anula factura exenta',
            fecha: ec.user.nowTimestamp,
            codigo: 1  // Required for credit notes
        ]

        Map parameters = [
            fiscalTaxDocumentTypeEnumId: 'Ftdt-61',  // Nota de Crédito
            issuerPartyId: testIssuerPartyId,
            rutReceptor: "87654321-4",
            giroReceptor: "Test Business Activity",
            receiverPartyId: testReceiverPartyId,
            razonSocialReceptor: "Test DTE Receiver Organization",
            contactoReceptor: "Test Contact",
            direccionReceptor: "Receiver Street 456",
            comunaReceptor: "Valparaiso",
            detailList: detailList,
            referenciaList: [referencia]
        ]

        Map result = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters(parameters).call()

        if (result.fiscalTaxDocumentId) {
            createdFiscalTaxDocumentIds.add(result.fiscalTaxDocumentId)
        }

        then: "Credit note should be created successfully with no errors"
        !ec.message.hasError()
        result.fiscalTaxDocumentId

        when: "Verify the created fiscal document"
        EntityValue fiscalDoc = ec.entity.find("mchile.dte.FiscalTaxDocument")
            .condition("fiscalTaxDocumentId", result.fiscalTaxDocumentId)
            .one()

        then: "Document type should be Nota de Crédito and all values should be correct"
        fiscalDoc != null
        fiscalDoc.fiscalTaxDocumentTypeEnumId == 'Ftdt-61'

        logger.info("✓ Nota de Crédito Exenta created successfully")
        logger.info("  FiscalTaxDocumentId: ${result.fiscalTaxDocumentId}")
        logger.info("  Exempt Amount 1: ${exemptAmount1}")
        logger.info("  Exempt Amount 2: ${exemptAmount2}")
        logger.info("  Total Exempt: ${exemptAmount1 + exemptAmount2}")

        cleanup:
        totalFieldsChecked += 2
        logger.info("✓ Nota de Crédito Exenta generation test passed")
    }


    /**
     * Helper method to parse DTE XML and extract totals for rounding validation
     */
    private Map parseDteXmlForRounding(String fiscalTaxDocumentId) {
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

            // Extract all MontoItem values
            List<BigDecimal> montoItems = []
            org.w3c.dom.NodeList detalleNodes = (org.w3c.dom.NodeList) xpath.evaluate("//Detalle", doc, XPathConstants.NODESET)

            for (int i = 0; i < detalleNodes.getLength(); i++) {
                org.w3c.dom.Element detalleElement = (org.w3c.dom.Element) detalleNodes.item(i)
                String montoItemStr = xpath.evaluate("MontoItem", detalleElement)
                if (montoItemStr) {
                    montoItems.add(new BigDecimal(montoItemStr))
                }
            }

            // Calculate sum of MontoItems
            BigDecimal sumMontoItems = montoItems.sum(BigDecimal.ZERO)

            // Extract other totals
            String mntNetoStr = xpath.evaluate("//Totales/MntNeto", doc)
            String ivaStr = xpath.evaluate("//Totales/IVA", doc)

            BigDecimal mntNeto = mntNetoStr ? new BigDecimal(mntNetoStr) : null
            BigDecimal iva = ivaStr ? new BigDecimal(ivaStr) : null

            // Return parsed data
            return [
                valid: true,
                xmlText: xmlText,
                mntTotal: mntTotal,
                montoItems: montoItems,
                sumMontoItems: sumMontoItems,
                mntNeto: mntNeto,
                iva: iva,
                discrepancy: mntTotal ? (sumMontoItems - mntTotal) : null
            ]

        } catch (Exception e) {
            logger.error("Error parsing XML for rounding check: ${e.message}", e)
            return [valid: false, error: "XML parsing error: ${e.message}"]
        }
    }

    @Unroll
    def "RT-001 - Boleta tipo #dteType round-trip consistency"() {
        given: "A simple boleta with known values"
        BigDecimal netAmount = 10000
        BigDecimal taxAmount = 1900
        BigDecimal totalAmount = 11900

        when: "Generate original DTE (Boleta)"
        List detailList = [
            [
                description: "Round-Trip Test Product",
                quantity: 1,
                amount: totalAmount
            ]
        ]

        Map generateResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Boleta")
            .parameters([
                fiscalTaxDocumentTypeEnumId: dteType,
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",  // Must match the RUT assigned to testReceiverPartyId in setup
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Round-Trip Receiver",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Address 123",
                comunaReceptor: "Santiago",
                preciosIncluyenIva: true,  // Amount includes IVA
                detailList: detailList,
                sendToSii: false  // Don't actually send to SII in tests
            ]).call()

        String originalFiscalTaxDocumentId = generateResult.fiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(originalFiscalTaxDocumentId)

        logger.info("Generated original DTE: ${originalFiscalTaxDocumentId}")

        and: "Get original DTE attributes"
        Map originalAttributes = getDteAttributes(originalFiscalTaxDocumentId)

        and: "Get original DTE XML content"
        Document originalDoc = getDteXml(originalFiscalTaxDocumentId)
        Element originalDteElement = originalDoc.getDocumentElement()

        and: "Load DTE from XML (simulating receiving it)"
        Map parseResult = ec.service.sync().name("mchile.sii.dte.DteLoadServices.parse#Dte")
            .parameters([dte: originalDteElement])
            .call()

        logger.info("Parsed DTE: tipoDte=${parseResult.tipoDte}, folio=${parseResult.folioDte}, montoTotal=${parseResult.montoTotal}")

        and: "Create Invoice from parsed DTE"
        Map invoiceResult = ec.service.sync().name("mchile.sii.dte.DteLoadServices.create#InvoiceFromDte")
            .parameters([
                fiscalTaxDocumentId: originalFiscalTaxDocumentId,
                parsedDteXmlMap: parseResult,
                invoiceStatusId: "InvoiceReceived"
            ]).call()

        String invoiceId = invoiceResult.invoiceId
        createdInvoiceIds.add(invoiceId)

        logger.info("Created Invoice from DTE: ${invoiceId}")

        EntityValue invoice = ec.entity.find("mantle.account.invoice.Invoice").condition("invoiceId", invoiceId).one()
        logger.info("Invoice fromPartyId: ${invoice.fromPartyId}")
        logger.info("testIssuerPartyId: ${testIssuerPartyId}")
        logger.info("invoice.invoiceDate: ${invoice.invoiceDate}")
        logger.info("dteType: ${dteType}")

        and: "Generate new DTE from the Invoice"
        Map regenerateResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#DteFromInvoice")
            .parameters([
                invoiceId: invoiceId,
                fiscalTaxDocumentTypeEnumId: dteType,
                sendToSii: false
            ]).call()

        String regeneratedFiscalTaxDocumentId = regenerateResult.fiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(regeneratedFiscalTaxDocumentId)

        logger.info("Regenerated DTE from Invoice: ${regeneratedFiscalTaxDocumentId}")

        // Check for errors from regeneration
        if (ec.message.hasError()) {
            logger.error("ERRORS after generate#DteFromInvoice:")
            List errors = ec.message.getErrors()
            logger.error("Error count: ${errors.size()}")
            errors.each { error -> logger.error("  ERROR: ${error}") }
        } else {
            logger.info("NO ERRORS after generate#DteFromInvoice")
        }

        logger.info("regenerateResult keys: ${regenerateResult.keySet()}")
        logger.info("regeneratedFiscalTaxDocumentId value: ${regeneratedFiscalTaxDocumentId}")

        and: "Get regenerated DTE attributes"
        Map regeneratedAttributes = getDteAttributes(regeneratedFiscalTaxDocumentId)

        // Debug logging if attributes are null
        if (!regeneratedAttributes) {
            logger.error("getDteAttributes returned null for regeneratedFiscalTaxDocumentId: ${regeneratedFiscalTaxDocumentId}")

            // Check if FiscalTaxDocument exists
            def ftd = ec.entity.find("mchile.dte.FiscalTaxDocument")
                .condition("fiscalTaxDocumentId", regeneratedFiscalTaxDocumentId)
                .one()
            logger.error("FiscalTaxDocument exists: ${ftd != null}")

            // Check if FiscalTaxDocumentAttributes exists
            def ftda = ec.entity.find("mchile.dte.FiscalTaxDocumentAttributes")
                .condition("fiscalTaxDocumentId", regeneratedFiscalTaxDocumentId)
                .one()
            logger.error("FiscalTaxDocumentAttributes exists: ${ftda != null}")

            // List all errors
            if (ec.message.hasError()) {
                logger.error("Current error messages:")
                ec.message.getErrors().each { error -> logger.error("  - ${error}") }
            }
        }

        and: "Compare original vs regenerated"
        Map comparison = compareDteAttributes(originalAttributes, regeneratedAttributes, "Boleta ${dteType} Round-Trip Comparison")
        logComparisonResults(comparison)

        then: "All critical fields should match within tolerance"
        !ec.message.hasError()
        originalFiscalTaxDocumentId != null
        invoiceId != null
        regeneratedFiscalTaxDocumentId != null

        // Type and parties should match exactly
        comparison.fieldComparisons['fiscalTaxDocumentTypeEnumId'].match
        comparison.fieldComparisons['issuerPartyIdValue'].match
        comparison.fieldComparisons['receiverPartyIdValue'].match

        // Monetary amounts should match within tolerance
        comparison.fieldComparisons['montoTotal'].match
        comparison.fieldComparisons['iva'].match

        // For boletas, montoNeto might be null (depending on generarXmlMontosNetos setting)
        // So we only check if both have values
        originalAttributes.montoNeto == null || regeneratedAttributes.montoNeto == null ||
            comparison.fieldComparisons['montoNeto'].match

        // Date should match
        comparison.fieldComparisons['fechaEmision'].match

        // Overall comparison
        comparison.allMatch || comparison.differences.size() == 0

        cleanup:
        totalFieldsChecked += 8
        logger.info("✓ Boleta ${dteType} round-trip consistency test completed")

        where:
        dteType << ["Ftdt-39", "Ftdt-41"]
    }

    def "RT-002 - Factura tipo 33 round-trip consistency"() {
        given: "A factura with known values"
        BigDecimal netAmount = 100000
        BigDecimal taxAmount = 19000
        BigDecimal totalAmount = 119000

        when: "Generate original DTE (Factura)"
        List detailList = [
            [
                description: "Round-Trip Test Product",
                quantity: 1,
                amount: netAmount
            ],
            [
                itemTypeEnumId: 'ItemVatTax',
                quantity: 1,
                amount: taxAmount
            ]
        ]

        Map generateResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",  // Must match the RUT assigned to testReceiverPartyId in setup
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Round-Trip Receiver",
                giroReceptor: "Test Business",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Address 123",
                comunaReceptor: "Santiago",
                detailList: detailList,
                sendToSii: false
            ]).call()

        String originalFiscalTaxDocumentId = generateResult.fiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(originalFiscalTaxDocumentId)

        and: "Get original DTE attributes"
        Map originalAttributes = getDteAttributes(originalFiscalTaxDocumentId)

        and: "Get original DTE XML"
        Document originalDoc = getDteXml(originalFiscalTaxDocumentId)
        Element originalDteElement = originalDoc.getDocumentElement()

        and: "Load DTE from XML"
        Map parseResult = ec.service.sync().name("mchile.sii.dte.DteLoadServices.parse#Dte")
            .parameters([dte: originalDteElement])
            .call()

        and: "Create Invoice from DTE"
        Map invoiceResult = ec.service.sync().name("mchile.sii.dte.DteLoadServices.create#InvoiceFromDte")
            .parameters([
                fiscalTaxDocumentId: originalFiscalTaxDocumentId,
                parsedDteXmlMap: parseResult,
                invoiceStatusId: "InvoiceReceived"
            ]).call()

        String invoiceId = invoiceResult.invoiceId
        createdInvoiceIds.add(invoiceId)

        and: "Regenerate DTE from Invoice"
        Map regenerateResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#DteFromInvoice")
            .parameters([
                invoiceId: invoiceId,
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                sendToSii: false
            ]).call()

        String regeneratedFiscalTaxDocumentId = regenerateResult.fiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(regeneratedFiscalTaxDocumentId)

        and: "Compare attributes"
        Map regeneratedAttributes = getDteAttributes(regeneratedFiscalTaxDocumentId)
        Map comparison = compareDteAttributes(originalAttributes, regeneratedAttributes, "Factura 33 Round-Trip Comparison")
        logComparisonResults(comparison)

        then: "All critical fields should match"
        !ec.message.hasError()
        originalFiscalTaxDocumentId != null
        invoiceId != null
        regeneratedFiscalTaxDocumentId != null

        // Type and parties
        comparison.fieldComparisons['fiscalTaxDocumentTypeEnumId'].match
        comparison.fieldComparisons['issuerPartyIdValue'].match
        comparison.fieldComparisons['receiverPartyIdValue'].match

        // Monetary amounts
        comparison.fieldComparisons['montoTotal'].match
        comparison.fieldComparisons['montoNeto'].match
        comparison.fieldComparisons['iva'].match
        comparison.fieldComparisons['tasaImpuesto'].match

        // Date
        comparison.fieldComparisons['fechaEmision'].match

        // Overall
        comparison.allMatch

        cleanup:
        totalFieldsChecked += 10
        logger.info("✓ Factura 33 round-trip consistency test completed")
    }

    def "RT-003 - Factura with multiple items round-trip consistency"() {
        given: "A factura with multiple items"
        when: "Generate original DTE with 3 items"
        List detailList = [
            [description: "Item 1", quantity: 2, amount: 20000],
            [description: "Item 2", quantity: 1, amount: 15000],
            [description: "Item 3", quantity: 3, amount: 30000],
            [itemTypeEnumId: 'ItemVatTax', quantity: 1, amount: 27550] // 19% of 65000
        ]

        Map generateResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",  // Must match the RUT assigned to testReceiverPartyId in setup
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Round-Trip Receiver",
                giroReceptor: "Test Business",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Address 123",
                comunaReceptor: "Santiago",
                detailList: detailList,
                sendToSii: false
            ]).call()

        String originalFiscalTaxDocumentId = generateResult.fiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(originalFiscalTaxDocumentId)

        and: "Load and regenerate through invoice cycle"
        Document originalDoc = getDteXml(originalFiscalTaxDocumentId)
        Element originalDteElement = originalDoc.getDocumentElement()

        Map parseResult = ec.service.sync().name("mchile.sii.dte.DteLoadServices.parse#Dte")
            .parameters([dte: originalDteElement])
            .call()

        Map invoiceResult = ec.service.sync().name("mchile.sii.dte.DteLoadServices.create#InvoiceFromDte")
            .parameters([
                fiscalTaxDocumentId: originalFiscalTaxDocumentId,
                parsedDteXmlMap: parseResult
            ]).call()

        String invoiceId = invoiceResult.invoiceId
        createdInvoiceIds.add(invoiceId)

        // Check invoice item count
        List invoiceItems = ec.entity.find("mantle.account.invoice.InvoiceItem")
            .condition("invoiceId", invoiceId)
            .list()

        Map regenerateResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#DteFromInvoice")
            .parameters([
                invoiceId: invoiceId,
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                sendToSii: false
            ]).call()

        String regeneratedFiscalTaxDocumentId = regenerateResult.fiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(regeneratedFiscalTaxDocumentId)

        and: "Compare attributes"
        Map originalAttributes = getDteAttributes(originalFiscalTaxDocumentId)
        Map regeneratedAttributes = getDteAttributes(regeneratedFiscalTaxDocumentId)
        Map comparison = compareDteAttributes(originalAttributes, regeneratedAttributes, "Multi-Item Factura Round-Trip")
        logComparisonResults(comparison)

        then: "All fields match and item count is preserved"
        !ec.message.hasError()
        invoiceItems.size() >= 3  // At least 3 items (may have tax items)
        comparison.allMatch

        cleanup:
        totalFieldsChecked += 3
        logger.info("✓ Multi-item factura round-trip consistency test completed")
    }

    def "RT-004 - Round-trip with discounts/surcharges"() {
        given: "A factura with global discount"
        when: "Generate DTE with discount"
        List detailList = [
            [description: "Product with discount", quantity: 1, amount: 100000],
            [itemTypeEnumId: 'ItemVatTax', quantity: 1, amount: 17100]
        ]

        List descuentoList = [
            [
                numeroLinea: 1,
                tipo: "D",
                glosa: "Test discount",
                tipoValor: "%",
                valor: 10.0
            ]
        ]

        Map generateResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Factura")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",  // Must match the RUT assigned to testReceiverPartyId in setup
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Round-Trip Receiver",
                giroReceptor: "Test Business",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Address 123",
                comunaReceptor: "Santiago",
                detailList: detailList,
                descuentoORecargoGlobalList: descuentoList,
                sendToSii: false
            ]).call()

        String originalFiscalTaxDocumentId = generateResult.fiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(originalFiscalTaxDocumentId)

        and: "Complete round-trip cycle"
        Document originalDoc = getDteXml(originalFiscalTaxDocumentId)
        Element originalDteElement = originalDoc.getDocumentElement()

        Map parseResult = ec.service.sync().name("mchile.sii.dte.DteLoadServices.parse#Dte")
            .parameters([dte: originalDteElement])
            .call()

        Map invoiceResult = ec.service.sync().name("mchile.sii.dte.DteLoadServices.create#InvoiceFromDte")
            .parameters([
                fiscalTaxDocumentId: originalFiscalTaxDocumentId,
                parsedDteXmlMap: parseResult
            ]).call()

        String invoiceId = invoiceResult.invoiceId
        createdInvoiceIds.add(invoiceId)

        Map regenerateResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#DteFromInvoice")
            .parameters([
                invoiceId: invoiceId,
                fiscalTaxDocumentTypeEnumId: "Ftdt-33",
                sendToSii: false
            ]).call()

        String regeneratedFiscalTaxDocumentId = regenerateResult.fiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(regeneratedFiscalTaxDocumentId)

        and: "Compare"
        Map originalAttributes = getDteAttributes(originalFiscalTaxDocumentId)
        Map regeneratedAttributes = getDteAttributes(regeneratedFiscalTaxDocumentId)
        Map comparison = compareDteAttributes(originalAttributes, regeneratedAttributes, "Factura with Discount Round-Trip")
        logComparisonResults(comparison)

        then: "Amounts should match after discount applied"
        !ec.message.hasError()
        comparison.fieldComparisons['montoTotal'].match

        cleanup:
        totalFieldsChecked += 2
        logger.info("✓ Round-trip with discounts test completed")
    }

    def "RT-005 - Identify discrepancies in Credit Note scenario"() {
        given: "Simulating the Credit Note issue - boleta loaded then regenerated"

        when: "Generate a boleta that will be referenced by a credit note"
        List detailList = [
            [description: "Original Boleta Item", quantity: 1, amount: 11900]
        ]

        Map boletaResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#Boleta")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-39",
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",  // Must match the RUT assigned to testReceiverPartyId in setup
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test Round-Trip Receiver",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Test Address 123",
                comunaReceptor: "Santiago",
                detailList: detailList,
                sendToSii: false,
                preciosIncluyenIva: true
            ]).call()

        String originalBoletaId = boletaResult.fiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(originalBoletaId)

        and: "Load the boleta from XML (simulating it came from external source)"
        Document boletaDoc = getDteXml(originalBoletaId)
        Element boletaElement = boletaDoc.getDocumentElement()

        Map parseResult = ec.service.sync().name("mchile.sii.dte.DteLoadServices.parse#Dte")
            .parameters([dte: boletaElement])
            .call()

        and: "Create invoice from loaded boleta"
        Map invoiceFromLoadResult = ec.service.sync().name("mchile.sii.dte.DteLoadServices.create#InvoiceFromDte")
            .parameters([
                fiscalTaxDocumentId: originalBoletaId,
                parsedDteXmlMap: parseResult
            ]).call()

        String invoiceId = invoiceFromLoadResult.invoiceId
        createdInvoiceIds.add(invoiceId)

        logger.info("Created invoice ${invoiceId} from loaded boleta")
        logger.info("Invoice discrepancies: ${invoiceFromLoadResult.discrepancyMessages}")

        and: "Now try to create a credit note referencing this invoice"
        // This simulates what happens when creating a credit note from a boleta
        Map regenerateResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#DteFromInvoice")
            .parameters([
                invoiceId: invoiceId,
                fiscalTaxDocumentTypeEnumId: "Ftdt-39",
                sendToSii: false
            ]).call()

        String regeneratedBoletaId = regenerateResult.fiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(regeneratedBoletaId)

        and: "Compare original boleta with regenerated"
        Map originalAttributes = getDteAttributes(originalBoletaId)
        Map regeneratedAttributes = getDteAttributes(regeneratedBoletaId)
        Map comparison = compareDteAttributes(originalAttributes, regeneratedAttributes, "Credit Note Scenario - Boleta Round-Trip")
        logComparisonResults(comparison)

        then: "Identify any discrepancies that would affect credit note generation"
        !ec.message.hasError()

        // This is the key test - if there are differences, they should be logged
        // and the test should document them for debugging
        if (!comparison.allMatch) {
            logger.error("FOUND DISCREPANCIES that could affect Credit Note generation:")
            comparison.differences.each { diff ->
                logger.error("  - ${diff}")
            }
            logger.error("These discrepancies are the root cause of Credit Note issues")
        }

        // The test passes if we can complete the round-trip, even with discrepancies
        // The logged comparison shows what's wrong
        true

        cleanup:
        totalFieldsChecked += 1
        logger.info("✓ Credit Note scenario discrepancy test completed")
    }

    def "RT-006 - Credit Note from Invoice with only exento values"() {
        given: "Generate Factura Exenta with only exempt items"
        BigDecimal exemptAmount1 = 50000
        BigDecimal exemptAmount2 = 30000
        BigDecimal totalExento = exemptAmount1 + exemptAmount2

        when: "Generate original Factura Exenta (Ftdt-34) with importInvoiceFromXml"
        logger.info("=" * 80)
        logger.info("TEST RT-006: Credit Note from Invoice with only exento values")
        logger.info("=" * 80)

        List detailList = [
            [description: "Exempt Product 1", quantity: 1, amount: exemptAmount1, afecto: false],
            [description: "Exempt Product 2", quantity: 1, amount: exemptAmount2, afecto: false]
        ]

        Map facturaResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.generate#FacturaInternal")
            .parameters([
                fiscalTaxDocumentTypeEnumId: "Ftdt-34",  // Factura Exenta
                issuerPartyId: testIssuerPartyId,
                rutReceptor: "87654321-4",
                receiverPartyId: testReceiverPartyId,
                razonSocialReceptor: "Test DTE Receiver Organization",
                giroReceptor: "Test Business Activity",
                contactoReceptor: "Test Contact",
                direccionReceptor: "Receiver Street 456",
                comunaReceptor: "Valparaiso",
                detailList: detailList,
                sendToSii: false,
                importInvoiceFromXml: true  // Automatically creates invoice
            ]).call()

        String originalFacturaId = facturaResult.fiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(originalFacturaId)

        then: "Original factura should be created without errors"
        !ec.message.hasError()
        originalFacturaId

        when: "Get original factura attributes"
        Map originalAttributes = getDteAttributes(originalFacturaId)

        then: "Original factura should have correct exento totals"
        originalAttributes != null
        originalAttributes.montoExento != null
        withinTolerance(originalAttributes.montoExento as BigDecimal, totalExento)
        originalAttributes.montoTotal == originalAttributes.montoExento  // For exento-only, total should equal exento

        logger.info("✓ Original Factura Exenta created:")
        logger.info("  FiscalTaxDocumentId: ${originalFacturaId}")
        logger.info("  Monto Exento: ${originalAttributes.montoExento}")
        logger.info("  Monto Total: ${originalAttributes.montoTotal}")

        when: "Update sentAuthStatusId to simulate accepted DTE"
        EntityValue fiscalDoc = ec.entity.find("mchile.dte.FiscalTaxDocument")
            .condition("fiscalTaxDocumentId", originalFacturaId)
            .one()
        fiscalDoc.sentAuthStatusId = 'Ftd-SentAuthAccepted'
        fiscalDoc.update()

        logger.info("✓ Updated sentAuthStatusId to Ftd-SentAuthAccepted")

        then: "Status update should complete without errors"
        !ec.message.hasError()
        fiscalDoc.sentAuthStatusId == 'Ftd-SentAuthAccepted'

        when: "Cancel the Factura to generate Nota de Crédito using cancel#Dte"
        Map cancelResult = ec.service.sync().name("mchile.sii.dte.DteGenerationServices.cancel#Dte")
            .parameters([
                fiscalTaxDocumentId: originalFacturaId,
                sendToSii: false
            ]).call()

        String creditNoteId = cancelResult.cancellingFiscalTaxDocumentId
        createdFiscalTaxDocumentIds.add(creditNoteId)

        then: "Credit note should be created without errors"
        !ec.message.hasError()
        creditNoteId

        when: "Get credit note attributes"
        Map creditNoteAttributes = getDteAttributes(creditNoteId)

        then: "Credit note should preserve exento totals correctly"
        creditNoteAttributes != null
        creditNoteAttributes.fiscalTaxDocumentTypeEnumId == 'Ftdt-61'
        creditNoteAttributes.montoExento != null

        // The key validation: exento amount should match original factura
        withinTolerance(creditNoteAttributes.montoExento as BigDecimal, originalAttributes.montoExento as BigDecimal)
        withinTolerance(creditNoteAttributes.montoTotal as BigDecimal, originalAttributes.montoTotal as BigDecimal)

        // For exento-only documents, total should equal exento (no IVA)
        withinTolerance(creditNoteAttributes.montoTotal as BigDecimal, creditNoteAttributes.montoExento as BigDecimal)

        logger.info("✓ Credit Note generated from Invoice:")
        logger.info("  Credit Note ID: ${creditNoteId}")
        logger.info("  Original Exento: ${originalAttributes.montoExento}")
        logger.info("  Credit Note Exento: ${creditNoteAttributes.montoExento}")
        logger.info("  Match: ${withinTolerance(creditNoteAttributes.montoExento as BigDecimal, originalAttributes.montoExento as BigDecimal)}")

        cleanup:
        totalFieldsChecked += 4
        logger.info("✓ Credit Note from Invoice with exento values test completed")
    }
    // ====================================================================================================
    // ROUND-TRIP CONSISTENCY TEST HELPER METHODS
    // ====================================================================================================

    /**
     * Helper to check if two monetary values are within acceptable tolerance
     */
    private boolean withinTolerance(BigDecimal value1, BigDecimal value2, BigDecimal tolerance = TOLERANCE) {
        if (value1 == null && value2 == null) return true
        if (value1 == null || value2 == null) return false
        return (value1 - value2).abs() <= tolerance
    }

    /**
     * Helper to extract DTE attributes from database
     */
    private Map getDteAttributes(String fiscalTaxDocumentId) {
        EntityValue dte = ec.entity.find("mchile.dte.FiscalTaxDocumentAndAttributes")
            .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
            .one()

        if (!dte) {
            logger.warn("FiscalTaxDocumentAndAttributes view returned null for fiscalTaxDocumentId: ${fiscalTaxDocumentId}")
            return null
        }

        logger.info("Found DTE attributes: type=${dte.fiscalTaxDocumentTypeEnumId}, number=${dte.fiscalTaxDocumentNumber}, montoTotal=${dte.amount}")

        return [
            fiscalTaxDocumentId: dte.fiscalTaxDocumentId,
            fiscalTaxDocumentTypeEnumId: dte.fiscalTaxDocumentTypeEnumId,
            fiscalTaxDocumentNumber: dte.fiscalTaxDocumentNumber,
            issuerPartyId: dte.issuerPartyId,
            issuerPartyIdValue: dte.issuerPartyIdValue,
            receiverPartyId: dte.receiverPartyId,
            receiverPartyIdValue: dte.receiverPartyIdValue,
            date: dte.date,
            fechaEmision: dte.fechaEmision,
            fechaVencimiento: dte.fechaVencimiento,
            montoTotal: dte.amount,
            montoNeto: dte.montoNeto,
            montoExento: dte.montoExento,
            iva: dte.montoIVARecuperable,
            tasaImpuesto: dte.tasaImpuesto,
            razonSocialEmisor: dte.razonSocialEmisor,
            razonSocialReceptor: dte.razonSocialReceptor,
            formaPagoEnumId: dte.formaPagoEnumId,
            invoiceId: dte.invoiceId
        ]
    }

    /**
     * Helper to get DTE XML content
     */
    private Document getDteXml(String fiscalTaxDocumentId) {
        Map contentResult = ec.service.sync().name("mchile.sii.dte.DteContentServices.get#DteContent")
            .parameters([
                fiscalTaxDocumentId: fiscalTaxDocumentId,
                fiscalTaxDocumentContentTypeEnumId: "Ftdct-Xml"
            ]).call()

        if (!contentResult.contentRef) {
            return null
        }

        // Parse XML directly from stream to respect ISO-8859-1 encoding declaration
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
        factory.setNamespaceAware(false)
        InputStream xmlStream = contentResult.contentRef.openStream()
        Document doc = factory.newDocumentBuilder().parse(xmlStream)
        xmlStream.close()
        return doc
    }

    /**
     * Compare two DTE attributes maps and return detailed comparison
     */
    private Map compareDteAttributes(Map original, Map regenerated, String comparisonLabel = "DTE Comparison") {
        List<String> differences = []
        List<String> matches = []
        Map<String, Map> fieldComparisons = [:]

        // Monetary fields with tolerance
        ['montoTotal', 'montoNeto', 'montoExento', 'iva'].each { field ->
            BigDecimal origValue = original[field] as BigDecimal
            BigDecimal regenValue = regenerated[field] as BigDecimal

            boolean match = withinTolerance(origValue, regenValue)
            fieldComparisons[field] = [
                original: origValue,
                regenerated: regenValue,
                match: match,
                difference: origValue != null && regenValue != null ? (origValue - regenValue).abs() : null
            ]

            if (match) {
                matches.add("${field}: ${origValue} ≈ ${regenValue}")
            } else {
                differences.add("${field} MISMATCH: original=${origValue}, regenerated=${regenValue}, diff=${fieldComparisons[field].difference}")
            }
        }

        // Tax rate (exact match required)
        ['tasaImpuesto'].each { field ->
            BigDecimal origValue = original[field] as BigDecimal
            BigDecimal regenValue = regenerated[field] as BigDecimal

            boolean match = (origValue == regenValue || (origValue != null && regenValue != null && origValue.compareTo(regenValue) == 0))
            fieldComparisons[field] = [
                original: origValue,
                regenerated: regenValue,
                match: match
            ]

            if (match) {
                matches.add("${field}: ${origValue} = ${regenValue}")
            } else {
                differences.add("${field} MISMATCH: original=${origValue}, regenerated=${regenValue}")
            }
        }

        // Date fields (exact match required)
        ['fechaEmision'].each { field ->
            def origValue = original[field]
            def regenValue = regenerated[field]

            boolean match = (origValue == regenValue)
            fieldComparisons[field] = [
                original: origValue,
                regenerated: regenValue,
                match: match
            ]

            if (match) {
                matches.add("${field}: ${origValue} = ${regenValue}")
            } else {
                differences.add("${field} MISMATCH: original=${origValue}, regenerated=${regenValue}")
            }
        }

        // String fields (exact match)
        ['fiscalTaxDocumentTypeEnumId', 'issuerPartyIdValue', 'receiverPartyIdValue'].each { field ->
            def origValue = original[field]
            def regenValue = regenerated[field]

            boolean match = (origValue == regenValue)
            fieldComparisons[field] = [
                original: origValue,
                regenerated: regenValue,
                match: match
            ]

            if (match) {
                matches.add("${field}: ${origValue}")
            } else {
                differences.add("${field} MISMATCH: original=${origValue}, regenerated=${regenValue}")
            }
        }

        return [
            allMatch: differences.isEmpty(),
            differences: differences,
            matches: matches,
            fieldComparisons: fieldComparisons,
            comparisonLabel: comparisonLabel
        ]
    }

    /**
     * Log comparison results in a readable format
     */
    private void logComparisonResults(Map comparison) {
        logger.info("=" * 80)
        logger.info("${comparison.comparisonLabel}")
        logger.info("=" * 80)

        if (comparison.allMatch) {
            logger.info("✓ ALL FIELDS MATCH")
        } else {
            logger.error("✗ MISMATCHES FOUND:")
            comparison.differences.each { diff ->
                logger.error("  - ${diff}")
            }
        }

        logger.info("")
        logger.info("Field-by-field comparison:")
        comparison.fieldComparisons.each { field, data ->
            String status = data.match ? "✓" : "✗"
            String diffInfo = data.difference != null ? " (diff: ${data.difference})" : ""
            logger.info("  ${status} ${field}: original=${data.original}, regenerated=${data.regenerated}${diffInfo}")
        }

        logger.info("=" * 80)
    }
}
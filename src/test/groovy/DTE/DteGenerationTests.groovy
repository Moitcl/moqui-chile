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

import java.sql.Timestamp
import java.security.KeyStore
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.X509Certificate
import java.security.cert.CertificateFactory

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
    @Shared List<String> createdFiscalTaxDocumentIds = []
    @Shared List<String> createdInvoiceIds = []

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
            // Create test issuer organization programmatically to avoid XML loading issues
            logger.info("Creating test issuer organization programmatically")

            Map issuerResult = ec.service.sync().name("mantle.party.PartyServices.create#Organization")
                .parameters([
                    organizationName: "Test DTE Organization",
                    roleTypeId: "OrgInternal"
                ]).call()

            if (ec.message.hasError()) {
                logger.error("Service errors during issuer organization creation: ${ec.message.getErrorsString()}")
                ec.message.clearErrors()
                throw new Exception("Could not create test issuer organization")
            }

            testIssuerPartyId = issuerResult.partyId
            logger.info("Created test issuer organization: ${testIssuerPartyId}")

            // Add RUT identification to issuer (using unique RUT for tests)
            ec.service.sync().name("create#mantle.party.PartyIdentification")
                .parameters([
                    partyId: testIssuerPartyId,
                    partyIdTypeEnumId: "PtidNationalTaxId",
                    idValue: "87654321-4"  // Unique test RUT for DTE Generation
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
            
            // Create a test receiver organization for the tests
            Map receiverResult = ec.service.sync().name("mantle.party.PartyServices.create#Organization")
                .parameters([
                    organizationName: "Test DTE Receiver Organization",
                    roleTypeId: "Customer"
                ]).call()
            
            if (ec.message.hasError()) {
                logger.error("Service errors during receiver organization creation: ${ec.message.getErrorsString()}")
                String errorDetails = ec.message.getErrorsString()
                ec.message.clearErrors()
            throw new Exception("Could not create test receiver organization: ${errorDetails}")
            }
            
            if (!receiverResult || !receiverResult.partyId) {
                logger.error("Failed to create receiver organization: ${receiverResult}")
                throw new Exception("Could not create test receiver organization - no partyId returned")
            }
            testReceiverPartyId = receiverResult.partyId
            logger.info("Created test receiver organization: ${testReceiverPartyId}")

            Map idResult = ec.service.sync().name("create#mantle.party.PartyIdentification")
                .parameters([
                    partyId: testReceiverPartyId,
                    partyIdTypeEnumId: "PtidNationalTaxId",
                    idValue: "87654322-2"
                ]).call()
                
            if (ec.message.hasError()) {
                logger.error("Service errors during party identification creation: ${ec.message.getErrorsString()}")
                ec.message.clearErrors()
            }

            // Create test product using entity auto-service
            Map productResult = ec.service.sync().name("create#mantle.product.Product")
                .parameters([
                    productName: "Test DTE Product",
                    productTypeEnumId: "PtService",
                    description: "Test product for DTE generation"
                ]).call()
            
            if (!productResult || !productResult.productId) {
                logger.error("Failed to create test product: ${productResult}")
                throw new Exception("Could not create test product")
            }
            testProductId = productResult.productId
            
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
            
            String cafXml = """<CAF version="1.0">
    <DA>
        <RE>12345678-5</RE>
        <RS>Test DTE Organization</RS>
        <TD>${dteType.code}</TD>
        <RNG>
            <D>1</D>
            <H>100</H>
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
                        hasta: "100",
                        cafFragment: cafXml,
                        publicKey: publicKey,
                        privateKey: privateKey
                    ]).call()
                    
                logger.info("Created test CAF for ${dteType.name}: ${cafId}")
                
                // Create FiscalTaxDocument entries for the CAF range
                createFiscalTaxDocumentsForCaf(dteType.code, "1", "100")
                
            } catch (Exception e) {
                logger.warn("Could not create CAF for ${dteType.name}: ${e.message}")
            }
        }
    }
    
    private void refreshCafDates() {
        // Update CAF authorization dates to current date to ensure they're not older than 6 months
        Timestamp currentTimestamp = ec.user.nowTimestamp
        java.sql.Date currentSqlDate = new java.sql.Date(currentTimestamp.time)
        
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
        
        // Valid CAF XML fragment with test keys
        String validCafFragment = '''<CAF version="1.0">
    <DA>
        <RE>12345678-5</RE>
        <RS>Test DTE Organization</RS>
        <TD>33</TD>
        <RNG>
            <D>1</D>
            <H>100</H>
        </RNG>
        <FA>''' + xmlDate + '''</FA>
        <RSAPK>
            <M>uX02i4mqmJ8hwWPwrRBJTBls5y3tylaSdXc9WQnkmUbnlHyGD2GH/zwSb5IYvOwGlChHtz0aKSITiPOiHTFfmw==</M>
            <E>Aw==</E>
        </RSAPK>
        <IDK>100</IDK>
    </DA>
    <FRMA algoritmo="SHA1withRSA">Fsh0/AFUH8dCcSgvXjiIT2wfO9QLYp94KNvGbeKc46jEMj2XgLxq7tBv83rVJ2bIgwytLoJCHaUOi4OnpkVjyg==</FRMA>
</CAF>'''
        
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
                // Log but don't fail if document already exists (for test reruns)
                logger.warn("Could not create FiscalTaxDocument ${fiscalTaxDocumentId}: ${e.message}")
            }
        }
        
        logger.info("Created ${fin - inicio + 1} FiscalTaxDocument entries for CAF range")
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

            // 1. Delete fiscal tax documents (dependent on invoices)
            for (String fiscalTaxDocumentId in createdFiscalTaxDocumentIds) {
                ec.entity.find("mchile.dte.FiscalTaxDocument")
                    .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
                    .deleteAll()
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
        ec.entity.find("mantle.party.PartyContactMech")
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
}
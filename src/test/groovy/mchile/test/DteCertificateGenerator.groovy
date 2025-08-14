package mchile.test

import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import java.security.*
import java.security.cert.X509Certificate

/**
 * Certificate generator for DTE testing that creates X.509 certificates
 * with Chilean RUT extensions in the Subject Alternative Name field.
 * 
 * This generator creates two types of certificates:
 * 1. Basic certificate for CAF folio authorization (public-private key pair)
 * 2. Certificate with RUT extension for DTE signing (includes Chilean RUT in SAN)
 */
class DteCertificateGenerator {
    
    static {
        // Add BouncyCastle provider if not already present
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider())
        }
    }
    
    // Chilean RUT OIDs
    static final String PERSON_RUT_OID = "1.3.6.1.4.1.8321.1"
    static final String ISSUER_RUT_OID = "1.3.6.1.4.1.8321.2"
    
    /**
     * Generate a basic X.509 certificate for CAF folio authorization
     * This is a simple self-signed certificate with just the key pair
     */
    static Map<String, Object> generateBasicCertificate(Map params = [:]) {
        // Parameters with defaults
        String subjectDN = params.subjectDN ?: "CN=Test Certificate,O=Test Organization,C=CL"
        int keySize = params.keySize ?: 2048
        int validityDays = params.validityDays ?: 365
        
        // Generate key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC")
        keyGen.initialize(keySize)
        KeyPair keyPair = keyGen.generateKeyPair()
        
        // Certificate validity period - start 1 hour ago to ensure validity for test emissions
        Date notBefore = params.validFromDate ?: new Date(System.currentTimeMillis() - 3600000L) // 1 hour ago
        Date notAfter = new Date(notBefore.time + validityDays * 86400000L)
        
        // Build certificate
        X500Name subject = new X500Name(subjectDN)
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis())
        
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            subject,     // issuer (self-signed)
            serial,
            notBefore,
            notAfter,
            subject,     // subject
            keyPair.public
        )
        
        // Add basic constraints for CA certificate
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            new BasicConstraints(false)
        )
        
        // Add key usage
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment)
        )
        
        // Sign certificate
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(keyPair.private)
        
        X509CertificateHolder certHolder = certBuilder.build(signer)
        X509Certificate certificate = new JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder)
        
        return [
            certificate: certificate,
            privateKey: keyPair.private,
            publicKey: keyPair.public,
            keyPair: keyPair
        ]
    }
    
    /**
     * Generate an X.509 certificate with Chilean RUT extension in Subject Alternative Name
     * This certificate includes the RUT in a format that can be extracted by getRutFromCertificate
     */
    static Map<String, Object> generateCertificateWithRUT(Map params = [:]) {
        // Parameters with defaults
        String subjectDN = params.subjectDN ?: "CN=Test Certificate,O=Test Organization,C=CL"
        String personRut = params.personRut ?: "15234567-8"
        String issuerRut = params.issuerRut ?: "96790240-3"
        int keySize = params.keySize ?: 2048
        int validityDays = params.validityDays ?: 365
        boolean includeRutInSAN = params.includeRutInSAN != false // default true
        
        // Generate key pair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA", "BC")
        keyGen.initialize(keySize)
        KeyPair keyPair = keyGen.generateKeyPair()
        
        // Certificate validity period - start 1 hour ago to ensure validity for test emissions
        Date notBefore = params.validFromDate ?: new Date(System.currentTimeMillis() - 3600000L) // 1 hour ago
        Date notAfter = new Date(notBefore.time + validityDays * 86400000L)
        
        // Build certificate
        X500Name subject = new X500Name(subjectDN)
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis())
        
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            subject,     // issuer (self-signed)
            serial,
            notBefore,
            notAfter,
            subject,     // subject
            keyPair.public
        )
        
        // Add basic constraints
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            new BasicConstraints(false)
        )
        
        // Add key usage
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.nonRepudiation)
        )
        
        if (includeRutInSAN) {
            // Create Subject Alternative Name with RUT extensions
            GeneralNames generalNames = createSANWithRUT(personRut, issuerRut)
            
            certBuilder.addExtension(
                Extension.subjectAlternativeName,
                false,  // not critical
                generalNames
            )
        }
        
        // Sign certificate
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(keyPair.private)
        
        X509CertificateHolder certHolder = certBuilder.build(signer)
        X509Certificate certificate = new JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder)
        
        return [
            certificate: certificate,
            privateKey: keyPair.private,
            publicKey: keyPair.public,
            keyPair: keyPair,
            personRut: personRut,
            issuerRut: issuerRut
        ]
    }
    
    /**
     * Create GeneralNames structure with Chilean RUT extensions
     * Uses OtherName with custom OIDs for person and issuer RUTs
     */
    private static GeneralNames createSANWithRUT(String personRut, String issuerRut) {
        List<GeneralName> names = []
        
        // Add person RUT as OtherName
        if (personRut) {
            ASN1ObjectIdentifier personOid = new ASN1ObjectIdentifier(PERSON_RUT_OID)
            DERIA5String personValue = new DERIA5String(personRut)
            ASN1Encodable personOtherValue = new DERTaggedObject(true, 0, personValue)
            OtherName personOtherName = new OtherName(personOid, personOtherValue)
            names.add(new GeneralName(GeneralName.otherName, personOtherName))
        }
        
        // Add issuer RUT as OtherName
        if (issuerRut) {
            ASN1ObjectIdentifier issuerOid = new ASN1ObjectIdentifier(ISSUER_RUT_OID)
            DERIA5String issuerValue = new DERIA5String(issuerRut)
            ASN1Encodable issuerOtherValue = new DERTaggedObject(true, 0, issuerValue)
            OtherName issuerOtherName = new OtherName(issuerOid, issuerOtherValue)
            names.add(new GeneralName(GeneralName.otherName, issuerOtherName))
        }
        
        return new GeneralNames(names as GeneralName[])
    }
    
    /**
     * Create a PKCS12 keystore containing the certificate and private key
     * This can be stored in PartyDteParams.certData as base64
     */
    static byte[] createPKCS12(X509Certificate certificate, PrivateKey privateKey, String alias, String password) {
        KeyStore keyStore = KeyStore.getInstance("PKCS12", "BC")
        keyStore.load(null, null)
        
        // Add certificate and key
        keyStore.setKeyEntry(
            alias,
            privateKey,
            password.toCharArray(),
            [certificate] as java.security.cert.Certificate[]
        )
        
        // Export to byte array
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        keyStore.store(baos, password.toCharArray())
        return baos.toByteArray()
    }
    
    /**
     * Generate test certificate data suitable for storage in PartyDteParams
     * Returns a map with certData (base64) and passCert
     */
    static Map<String, Object> generateTestCertificateData(Map params = [:]) {
        // Generate certificate with RUT
        Map certResult = generateCertificateWithRUT(params)
        
        // Create PKCS12 keystore
        String alias = params.alias ?: "test-certificate"
        String password = params.password ?: "test-password"
        
        byte[] pkcs12Data = createPKCS12(
            certResult.certificate,
            certResult.privateKey,
            alias,
            password
        )
        
        return [
            certData: pkcs12Data.encodeBase64().toString(),
            passCert: password,
            certificate: certResult.certificate,
            privateKey: certResult.privateKey,
            publicKey: certResult.publicKey,
            personRut: certResult.personRut,
            issuerRut: certResult.issuerRut
        ]
    }
    
    /**
     * Verify that a certificate contains the expected RUT in its SAN extension
     * This uses the same extraction method as MoquiDTEUtils.getRutFromCertificate
     */
    static String extractRutFromCertificate(X509Certificate certificate) {
        if (certificate == null) return null
        
        byte[] sanExtension = certificate.getExtensionValue("2.5.29.17")
        if (sanExtension == null) return null
        
        // Convert to string and search for RUT pattern
        String sanString = new String(sanExtension)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[\\d]{6,8}-[\\dkK]")
        java.util.regex.Matcher matcher = pattern.matcher(sanString)
        
        if (matcher.find()) {
            return matcher.group()
        }
        return null
    }
}
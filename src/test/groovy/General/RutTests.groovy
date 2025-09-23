package General


import org.moqui.Moqui

/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Timestamp

/* To run these make sure moqui, and mantle are in place and run:
    "gradle cleanAll load runtime/mantle/mantle-usl:test"
   Or to quick run with saved DB copy use "gradle loadSave" once then each time "gradle reloadSave runtime/mantle/mantle-usl:test"
 */

class RutTests extends Specification {
   @Shared protected final static Logger logger = LoggerFactory.getLogger(RutTests.class)
   @Shared ExecutionContext ec
   @Shared long effectiveTime = System.currentTimeMillis()
   @Shared long totalTestChecked = 0

   def setupSpec() {
      // init the framework, get the ec
      logger.info("[General] Preparar Contexto para las Pruebas")
      ec = Moqui.getExecutionContext()
      
      // Use the standard john.doe test user from moit-test data type
      // This user is created with: username=john.doe, password=moqui
      ec.user.loginUser("john.doe", "moqui")
      logger.info("[General] Logged in as john.doe test user")

      // set an effective date so data check works, etc
      ec.user.setEffectiveTime(new Timestamp(effectiveTime))
      
      // Create required tenant owner parties programmatically
      initializeTestData()
   }
   
   private void initializeTestData() {
      ec.artifactExecution.disableAuthz()
      try {
         logger.info("[General] Creating tenant owner parties for RUT tests")
         
         // Create TENANT1-OWNER party if it doesn't exist
         if (!ec.entity.find("mantle.party.Party").condition("partyId", "TENANT1-OWNER").one()) {
            ec.service.sync().name("create#mantle.party.Party")
               .parameters([
                  partyId: "TENANT1-OWNER",
                  partyTypeEnumId: "PtyOrganization"
               ]).call()
               
            ec.service.sync().name("create#mantle.party.Organization")
               .parameters([
                  partyId: "TENANT1-OWNER",
                  organizationName: "Test DTE Organization as owner"
               ]).call()
               
            logger.info("[General] Created TENANT1-OWNER party")
         }
         
         // Create TENANT2-OWNER party if it doesn't exist  
         if (!ec.entity.find("mantle.party.Party").condition("partyId", "TENANT2-OWNER").one()) {
            ec.service.sync().name("create#mantle.party.Party")
               .parameters([
                  partyId: "TENANT2-OWNER", 
                  partyTypeEnumId: "PtyOrganization"
               ]).call()
               
            ec.service.sync().name("create#mantle.party.Organization")
               .parameters([
                  partyId: "TENANT2-OWNER",
                  organizationName: "Test DTE Organization as owner"
               ]).call()
               
            logger.info("[General] Created TENANT2-OWNER party")
         }
         
         logger.info("[General] Test data initialization completed")
      } finally {
         ec.artifactExecution.enableAuthz()
      }
   }

   def cleanupSpec() {
      ec.destroy()
      logger.info("[General] Prueba Finalizada. ${totalTestChecked} pruebas ejecutadas")
   }

   def setup() {
      ec.message.clearErrors()  // Clear any errors from previous tests
      // Disable authorization for test service calls (following mantle-usl pattern)
      // User is authenticated but authorization is disabled for accessing test services
      ec.artifactExecution.disableAuthz()
      ec.transaction.begin(null)  // Begin new transaction for each test
   }

   def cleanup() {
      // Follow mantle-usl pattern: warn about unexpected errors, don't proactively clear
      if (ec.message.hasError()) {
         logger.warn("Unexpected errors in test: ${ec.message.getErrorsString()}")
         // DO NOT clear errors here - let tests fail if unexpected errors occur
      }
      ec.artifactExecution.enableAuthz()
      ec.transaction.commit()  // Commit transaction after each test
   }

   def "001 - Verifica RUTs Válidos (#rut)"() {
      setup:
      // Llamada al servicio que vamos a probar
      logger.info("[General] Llamar al servicio de verificación de RUT (${rut})")
      Map responseMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut")
              .parameters([rut:rut, ignoreErrors:true])
              .call()
      totalTestChecked += 1
      logger.info("[General] Verificar respuesta del servicio")

      expect:
      responseMap.errorMessage == null

      where:
      rut | dv
      "13252311-8" | ""
      "1-9" | ""
      "66666666-6" | ""
   }

   def "002 - Verifica RUTs Inválidos (#rut)"() {
      setup:
      // Llamada al servicio que vamos a probar
      logger.info("[General] Llamar al servicio de verificación de RUT (${rut})")
      Map responseMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut")
              .parameters([rut:rut, ignoreErrors:true])
              .call()
      totalTestChecked += 1
      logger.info("[General] Verificar respuesta del servicio (${responseMap.errorMessage})")

      expect:
      responseMap.errorMessage != null

      where:
      rut | dv
      "13252311-9" | ""  // Invalid check digit
      "1-0" | ""         // Invalid check digit  
      "66666666-1" | ""  // Invalid check digit
   }

   def "003 - Verifica RUTs con Formatos Especiales (#rut, #descripcion)"() {
      setup:
      logger.info("[General] Verificar RUT con formato especial: ${descripcion} (${rut})")
      Map responseMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut")
              .parameters([rut:rut, ignoreErrors:true])
              .call()
      totalTestChecked += 1
      
      // Check if the service had validation errors (empty RUTs, etc)
      boolean hasErrors = ec.message.hasError() || responseMap?.errorMessage != null

      expect:
      !hasErrors == esValido

      cleanup:
      // This test intentionally triggers validation errors - clear them
      ec.message.clearErrors()

      where:
      rut           | esValido | descripcion
      "76514104-4"  | true     | "RUT empresa típico"
      "12345678-5"  | true     | "RUT persona típico"  // Changed to valid check digit
      "1-9"         | true     | "RUT mínimo válido"
      ""            | false    | "RUT vacío"
      "12345678"    | false    | "Sin dígito verificador"
      "12345678-"   | false    | "Dígito verificador vacío"
      "-4"          | false    | "Sin número base"
      "123456789-0" | false    | "Demasiados dígitos"
      "abc-4"       | false    | "Letras en número base"
      "12345678-x"  | false    | "Letra en dígito verificador"
      "0-0"         | true     | "RUT con ceros"  // Service accepts this as valid
      "99999999-K"  | false    | "RUT inválido con K"
   }

   def "004 - Verifica Normalización de RUTs con k minúscula (#rut, #descripcion)"() {
      setup:
      logger.info("[General] Verificar normalización de RUT: ${descripcion} (${rut})")
      String testOrgId = "TEST-RUT-NORMALIZE-${rut.replace('-', '')}"
      String expectedNormalizedRut = rut.toLowerCase().endsWith('-k') ? rut.toLowerCase().replace('-k', '-K') : rut

      when: "Validating RUT format"
      Map responseMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut")
              .parameters([rut:rut, ignoreErrors:true])
              .call()

      // Check if the service had validation errors
      boolean hasErrors = ec.message.hasError() || responseMap?.errorMessage != null

      and: "Creating organization with the RUT if validation passes"
      Map createResult = null
      if (esValido && !hasErrors) {
          // Create test organization
          ec.service.sync().name("mantle.party.PartyServices.create#Organization")
                  .parameters([partyId: testOrgId, organizationName: "Test RUT Normalization Org"])
                  .call()

          // Add RUT identification - this should store the normalized version
          createResult = ec.service.sync().name("create#mantle.party.PartyIdentification")
                  .parameters([
                      partyId: testOrgId,
                      partyIdTypeEnumId: "PtidNationalTaxId",
                      idValue: rut
                  ])
                  .call()
      }

      then: "RUT validation should match expected result"
      !hasErrors == esValido

      and: "Service should normalize lowercase k to uppercase K"
      if (esValido && rut.toLowerCase().endsWith('-k')) {
          // The service should normalize lowercase 'k' to uppercase 'K'
          responseMap.normalizedRut == expectedNormalizedRut || responseMap.rut == expectedNormalizedRut
      }

      and: "Database should store the normalized RUT with uppercase K"
      if (esValido && !hasErrors && createResult != null) {
          // Query the database to verify the stored RUT
          def storedPartyId = ec.entity.find("mantle.party.PartyIdentification")
                  .condition("partyId", testOrgId)
                  .condition("partyIdTypeEnumId", "PtidNationalTaxId")
                  .one()

          storedPartyId != null
          storedPartyId.idValue == expectedNormalizedRut

          logger.info("[General] RUT stored in DB: '${storedPartyId.idValue}', expected: '${expectedNormalizedRut}'")

          // Specifically verify that lowercase 'k' was converted to uppercase 'K'
          if (rut.toLowerCase().endsWith('-k')) {
              assert storedPartyId.idValue.endsWith('-K') : "RUT should be stored with uppercase K, but found: ${storedPartyId.idValue}"
              assert !storedPartyId.idValue.endsWith('-k') : "RUT should not be stored with lowercase k"
          }
      }

      cleanup:

      totalTestChecked += 1
      // This test may trigger validation errors - clear them
      ec.message.clearErrors()

      // Clean up test data if organization was created
      if (esValido && testOrgId) {
          try {
              // Delete PartyIdentification records
              def partyIdentifications = ec.entity.find("mantle.party.PartyIdentification")
                      .condition("partyId", testOrgId).list()
              partyIdentifications.each { partyIdRecord ->
                  ec.service.sync().name("delete#mantle.party.PartyIdentification")
                      .parameters([partyId: partyIdRecord.partyId, partyIdTypeEnumId: partyIdRecord.partyIdTypeEnumId, idValue: partyIdRecord.idValue])
                      .disableAuthz().call()
              }

              // Delete Party record
              ec.service.sync().name("delete#mantle.party.Organization").parameters([partyId: testOrgId]).disableAuthz().call()
              ec.service.sync().name("delete#mantle.party.Party").parameters([partyId: testOrgId]).disableAuthz().call()
          } catch (Exception e) {
              logger.warn("[General] Cleanup warning for ${testOrgId}: ${e.message}")
          }
      }

      where:
      rut           | esValido | descripcion
      "34567893-k"  | true     | "RUT válido con k minúscula"
      "11111111-k"  | false    | "RUT inválido con k minúscula"
      "7826361-k"   | true     | "RUT válido con k minúscula (7 dígitos)"
      "34567893-K"  | true     | "RUT válido con K mayúscula"
   }

   def "005 - Verifica Normalización de RUTs con separadores de miles (#rut, #descripcion)"() {
      setup:
      logger.info("[General] Verificar normalización de RUT con separadores: ${descripcion} (${rut})")
      String testOrgId = "TEST-RUT-DOTS-${System.currentTimeMillis()}"
      String expectedNormalizedRut = rut.replace('.', '').toLowerCase().endsWith('-k') ?
              rut.replace('.', '').toLowerCase().replace('-k', '-K') :
              rut.replace('.', '')

      when: "Validating RUT format with dot separators"
      Map responseMap = ec.service.sync().name("mchile.GeneralServices.verify#Rut")
              .parameters([rut:rut, ignoreErrors:true])
              .call()

      // Check if the service had validation errors
      boolean hasErrors = ec.message.hasError() || responseMap?.errorMessage != null

      and: "Creating organization with the RUT if validation passes"
      Map createResult = null
      if (esValido && !hasErrors) {
          // Create test organization
          ec.service.sync().name("mantle.party.PartyServices.create#Organization")
                  .parameters([partyId: testOrgId, organizationName: "Test RUT Dots Normalization Org"])
                  .call()

          // Add RUT identification - this should store the normalized version (no dots)
          createResult = ec.service.sync().name("create#mantle.party.PartyIdentification")
                  .parameters([
                      partyId: testOrgId,
                      partyIdTypeEnumId: "PtidNationalTaxId",
                      idValue: rut
                  ])
                  .call()
      }

      then: "RUT validation should match expected result"
      !hasErrors == esValido

      and: "Service should normalize dots and case"
      if (esValido) {
          // The service should remove dots and normalize case
          responseMap.normalizedRut == expectedNormalizedRut || responseMap.rut == expectedNormalizedRut
      }

      and: "Database should store the normalized RUT without dots and with uppercase K"
      if (esValido && !hasErrors && createResult != null) {
          // Query the database to verify the stored RUT
          def storedPartyId = ec.entity.find("mantle.party.PartyIdentification")
                  .condition("partyId", testOrgId)
                  .condition("partyIdTypeEnumId", "PtidNationalTaxId")
                  .one()

          storedPartyId != null
          storedPartyId.idValue == expectedNormalizedRut

          logger.info("[General] RUT with dots stored in DB: '${storedPartyId.idValue}', expected: '${expectedNormalizedRut}'")

          // Specifically verify normalization rules
          assert !storedPartyId.idValue.contains('.') : "RUT should be stored without dots, but found: ${storedPartyId.idValue}"

          if (rut.toLowerCase().endsWith('-k')) {
              assert storedPartyId.idValue.endsWith('-K') : "RUT should be stored with uppercase K, but found: ${storedPartyId.idValue}"
              assert !storedPartyId.idValue.endsWith('-k') : "RUT should not be stored with lowercase k"
          }
      }

      cleanup:
      totalTestChecked += 1
      // This test may trigger validation errors - clear them
      ec.message.clearErrors()

      // Clean up test data if organization was created
      if (esValido && testOrgId) {
          try {
              // Delete PartyIdentification records
              def partyIdentifications = ec.entity.find("mantle.party.PartyIdentification")
                      .condition("partyId", testOrgId).list()
              partyIdentifications.each { partyIdRecord ->
                  ec.service.sync().name("delete#mantle.party.PartyIdentification")
                      .parameters([partyId: partyIdRecord.partyId, partyIdTypeEnumId: partyIdRecord.partyIdTypeEnumId, idValue: partyIdRecord.idValue])
                      .disableAuthz().call()
              }

              // Delete Party record
              ec.service.sync().name("delete#mantle.party.Organization").parameters([partyId: testOrgId]).disableAuthz().call()
              ec.service.sync().name("delete#mantle.party.Party").parameters([partyId: testOrgId]).disableAuthz().call()
          } catch (Exception e) {
              logger.warn("[General] Cleanup warning for ${testOrgId}: ${e.message}")
          }
      }

      where:
      rut              | esValido | descripcion
      "12.345.678-5"   | true     | "RUT con separadores de miles válido"
      "1.234.567-k"    | true     | "RUT con separadores y k minúscula"
      "76.514.104-4"   | true     | "RUT empresa con separadores"
      "7.826.361-K"    | true     | "RUT con separadores y K mayúscula"
      "12.345.678-9"   | false    | "RUT con separadores inválido"
      "1.111.111-k"    | false    | "RUT con separadores inválido y k minúscula"
   }

   def "006 - Verifica RUTs Especiales del SII"() {
      setup:
      logger.info("[General] Obtener lista de RUTs especiales del SII")
      Map responseMap = ec.service.sync().name("mchile.sii.SIIServices.get#RutEspeciales").call()
      totalTestChecked += 1

      expect:
      responseMap.rutMap != null
      responseMap.rutList != null
      responseMap.rutMap.size() > 0

      // Verify specific known special RUTs
      responseMap.rutMap['11111111-1'] == 'Utilizado en Ejemplos'
      responseMap.rutMap['66666666-6'] == 'Persona con rut desconocido'
      responseMap.rutMap['77777777-7'] == 'Agrupación de transacciones con tarjetas de crédito bancarias'

      logger.info("[General] RUTs especiales encontrados: ${responseMap.rutMap.keySet()}")
   }

   def "007 - Verifica Duplicación de RUTs - Misma Organización"() {
      setup:
      logger.info("[General] Probar duplicación de RUTs en la misma organización")
      String testOrgId = "TEST-RUT-DUP-ORG"
      String testRut = "12345678-5"
      
      // Create test organization
      ec.service.sync().name("mantle.party.PartyServices.create#Organization")
              .parameters([partyId: testOrgId, organizationName: "Test RUT Duplication Org"])
              .call()
      
      // Add first RUT identification
      ec.service.sync().name("create#mantle.party.PartyIdentification")
              .parameters([
                  partyId: testOrgId,
                  partyIdTypeEnumId: "PtidNationalTaxId", 
                  idValue: testRut
              ])
              .call()
      
      // Try to add the same RUT again to the same party (should fail)
      Map duplicateResult = ec.service.sync().name("create#mantle.party.PartyIdentification")
              .parameters([
                  partyId: testOrgId,
                  partyIdTypeEnumId: "PtidNationalTaxId", 
                  idValue: testRut
              ])
              .call()
      
      // Check both error sources as per Moqui testing best practices
      boolean duplicateAttemptFailed = ec.message.hasError() || duplicateResult?.errorMessage != null
      if (duplicateAttemptFailed) {
          logger.info("[General] Duplicate RUT correctly rejected via MessageFacade")
      }

      totalTestChecked += 1

      expect:
      duplicateAttemptFailed == true  // Should fail when trying to add duplicate RUT to same party

      cleanup:
      // This test intentionally triggers duplicate RUT errors - clear them
      ec.message.clearErrors()
      // Clean up test data using delete services (preferred for resilient cleanup)
      try {
          // Delete PartyIdentification records
          def partyIdentifications = ec.entity.find("mantle.party.PartyIdentification")
                  .condition("partyId", testOrgId).list()
          partyIdentifications.each { partyId ->
              ec.service.sync().name("delete#mantle.party.PartyIdentification")
                  .parameters([partyId: partyId.partyId, partyIdTypeEnumId: partyId.partyIdTypeEnumId, idValue: partyId.idValue])
                  .disableAuthz().call()
          }
          
          // Delete Party record
          ec.service.sync().name("delete#mantle.party.Organization").parameters([partyId: testOrgId]).disableAuthz().call()
          ec.service.sync().name("delete#mantle.party.Party").parameters([partyId: testOrgId]).disableAuthz().call()
      } catch (Exception e) {
          logger.warn("[General] Cleanup warning: ${e.message}")
      }
   }

   def "008 - Verifica Duplicación de RUTs - Organizaciones con Mismo ownerPartyId (debe fallar)"() {
      setup:
      logger.info("[General] Probar duplicación de RUTs entre organizaciones con mismo ownerPartyId")
      String testOrg1Id = "TEST-RUT-SAME-OWNER-ORG1"
      String testOrg2Id = "TEST-RUT-SAME-OWNER-ORG2"
      String testRut = "87654321-4"
      String sameOwnerPartyId = "TENANT1-OWNER"

      // Create test organizations with same owner
      ec.service.sync().name("mantle.party.PartyServices.create#Organization")
              .parameters([partyId: testOrg1Id, organizationName: "Test RUT Same Owner Org 1", ownerPartyId:sameOwnerPartyId])
              .call()
      ec.service.sync().name("mantle.party.PartyServices.create#Organization")
              .parameters([partyId: testOrg2Id, organizationName: "Test RUT Same Owner Org 2", ownerPartyId:sameOwnerPartyId])
              .call()
      
      // Add RUT to first organization
      ec.service.sync().name("create#mantle.party.PartyIdentification")
              .parameters([
                  partyId: testOrg1Id,
                  partyIdTypeEnumId: "PtidNationalTaxId", 
                  idValue: testRut
              ])
              .call()
      
      // Try to add the same RUT to second organization with same owner (should fail)
      Map secondResult = ec.service.sync().name("create#mantle.party.PartyIdentification")
              .parameters([
                  partyId: testOrg2Id,
                  partyIdTypeEnumId: "PtidNationalTaxId", 
                  idValue: testRut
              ])
              .call()
      
      // Check both error sources as per Moqui testing best practices
      boolean hasErrors = ec.message.hasError() || secondResult?.errorMessage != null
      boolean duplicateAttemptFailed = hasErrors
      if (duplicateAttemptFailed) {
          logger.info("[General] RUT duplication correctly rejected for same ownerPartyId")
      } else {
          logger.warn("[General] RUT duplication was allowed when it should have failed")
      }

      totalTestChecked += 1

      expect:
      duplicateAttemptFailed == true  // Should fail when same ownerPartyId

      cleanup:
      // This test intentionally triggers duplicate RUT errors - clear them
      ec.message.clearErrors()
      // Clean up test data using delete services (preferred for resilient cleanup)
      try {
          [testOrg1Id, testOrg2Id].each { partyId ->
              // Delete PartyIdentification records for this party
              def partyIdentifications = ec.entity.find("mantle.party.PartyIdentification")
                      .condition("partyId", partyId).list()
              partyIdentifications.each { partyIdRecord ->
                  ec.service.sync().name("delete#mantle.party.PartyIdentification")
                      .parameters([partyId: partyIdRecord.partyId, partyIdTypeEnumId: partyIdRecord.partyIdTypeEnumId, idValue: partyIdRecord.idValue])
                      .disableAuthz().call()
              }
              
              // Delete Party record
              ec.service.sync().name("delete#mantle.party.Organization").parameters([partyId: partyId]).disableAuthz().call()
              ec.service.sync().name("delete#mantle.party.Party").parameters([partyId: partyId]).disableAuthz().call()
          }
      } catch (Exception e) {
          logger.warn("[General] Cleanup warning: ${e.message}")
      }
   }

   def "009 - Verifica Duplicación de RUTs - Organizaciones con Diferentes Tenants (EntityFilter simulation)"() {
      setup:
      logger.info("[General] Probar duplicación de RUTs entre organizaciones de diferentes tenants usando EntityFilters")
      String testOrg1Id = "TEST-RUT-TENANT1-ORG1"
      String testOrg2Id = "TEST-RUT-TENANT2-ORG2"
      String testRut = "87654321-4"
      String tenant1OwnerPartyId = "TENANT1-OWNER"
      String tenant2OwnerPartyId = "TENANT2-OWNER"
      
      // Create test organizations for different tenants (with authz disabled temporarily)
      ec.service.sync().name("mantle.party.PartyServices.create#Organization")
              .parameters([partyId: testOrg1Id, organizationName: "Test RUT Tenant 1 Org", ownerPartyId: tenant1OwnerPartyId])
              .call()
      ec.service.sync().name("mantle.party.PartyServices.create#Organization")
              .parameters([partyId: testOrg2Id, organizationName: "Test RUT Tenant 2 Org", ownerPartyId: tenant2OwnerPartyId])
              .call()

      // Important: Enable authorization for EntityFilter testing (opposite of normal test pattern)
      // Authorization configuration is loaded from TestEntityFilterData.xml
      ec.artifactExecution.enableAuthz()
      logger.info("[General] Authorization enabled for EntityFilter testing")
      logger.info("[General] Current user groups: ${ec.user.userGroupIdSet}")

      // Simulate tenant 1 user context - can only see organizations owned by TENANT1-OWNER
      ec.user.context.filterOrgIds = [tenant1OwnerPartyId]
      logger.info("[General] Set filterOrgIds to tenant 1: ${ec.user.context.filterOrgIds}")

      // Add RUT to first organization (tenant 1) - should work within tenant scope
      Map firstResult = ec.service.sync().name("create#mantle.party.PartyIdentification")
              .parameters([
                  partyId: testOrg1Id,
                  partyIdTypeEnumId: "PtidNationalTaxId", 
                  idValue: testRut
              ])
              .call()

      boolean firstResultSuccess = !ec.message.hasError() && firstResult?.errorMessage == null
      if (!firstResultSuccess) {
          logger.warn("[General] First RUT creation failed: ${firstResult?.errorMessage}")
      }

      // Switch to tenant 2 user context - can only see organizations owned by TENANT2-OWNER
      ec.user.context.filterOrgIds = [tenant2OwnerPartyId]
      logger.info("[General] Set filterOrgIds to tenant 2: ${ec.user.context.filterOrgIds}")
      
      // Try to add the same RUT to second organization (tenant 2)
      // This should succeed because tenant 2 can't see tenant 1's organizations due to EntityFilters
      Map secondResult = ec.service.sync().name("create#mantle.party.PartyIdentification")
              .parameters([
                  partyId: testOrg2Id,
                  partyIdTypeEnumId: "PtidNationalTaxId", 
                  idValue: testRut
              ])
              .call()

      // Check both error sources as per Moqui testing best practices
      boolean hasErrors = ec.message.hasError() || secondResult?.errorMessage != null
      boolean secondRutAdded = !hasErrors
      if (secondRutAdded) {
          logger.info("[General] Same RUT correctly allowed for different tenant contexts via EntityFilters")
      } else {
          logger.warn("[General] RUT duplication failed between different tenants: ${secondResult?.errorMessage}")
          logger.warn("[General] Current ec.user.context.filterOrgIds: ${ec.user.context.filterOrgIds}")
          logger.warn("[General] Current user groups: ${ec.user.userAccount?.groups*.userGroupId}")
      }

      totalTestChecked += 1

      expect:
      firstResultSuccess == true      // First RUT should be created successfully
      // NOTE: This test may fail if the current EECA implementation doesn't use EntityFilters
      // EntityFilters only work during queries, not during EECA validation logic
      // The EECA manually expands parent/child organization lists without using EntityFilters
      secondRutAdded == true          // Should succeed if EntityFilters work in EECA context
      
      cleanup:
      // This test may trigger errors during RUT validation - clear them
      ec.message.clearErrors()

      // Clear tenant context
      ec.user.context.remove('filterOrgIds')

      // Disable authorization for cleanup operations
      ec.artifactExecution.disableAuthz()
      
      // Clean up test data using delete services (preferred for resilient cleanup)
      try {
          // Clean up PartyIdentification records and organizations
          [testOrg1Id, testOrg2Id].each { partyId ->
              // Delete PartyIdentification records for this party
              def partyIdentifications = ec.entity.find("mantle.party.PartyIdentification")
                      .condition("partyId", partyId).list()
              partyIdentifications.each { partyIdRecord ->
                  ec.service.sync().name("delete#mantle.party.PartyIdentification")
                      .parameters([partyId: partyIdRecord.partyId, partyIdTypeEnumId: partyIdRecord.partyIdTypeEnumId, idValue: partyIdRecord.idValue])
                      .disableAuthz().call()
              }
              
              // Delete Party record
              ec.service.sync().name("delete#mantle.party.Organization").parameters([partyId: partyId]).disableAuthz().call()
              ec.service.sync().name("delete#mantle.party.Party").parameters([partyId: partyId]).disableAuthz().call()
          }
          
          // Note: Authorization configuration is loaded from TestEntityFilterData.xml
          // and does not need to be cleaned up here
          
      } catch (Exception e) {
          logger.warn("[General] Cleanup warning: ${e.message}")
      }
   }

   def "010 - Verifica RUTs Especiales Permiten Duplicación"() {
      setup:
      logger.info("[General] Probar que RUTs especiales permiten duplicación")
      String testOrg1Id = "TEST-SPECIAL-RUT-ORG1"
      String testOrg2Id = "TEST-SPECIAL-RUT-ORG2"
      String specialRut = "11111111-1"  // Special RUT: "Utilizado en Ejemplos"
      
      // Get special RUTs list to confirm
      Map specialRuts = ec.service.sync().name("mchile.sii.SIIServices.get#RutEspeciales").call()
      
      // Create test organizations
      ec.service.sync().name("mantle.party.PartyServices.create#Organization")
              .parameters([partyId: testOrg1Id, organizationName: "Test Special RUT Org 1"])
              .call()
      ec.service.sync().name("mantle.party.PartyServices.create#Organization")
              .parameters([partyId: testOrg2Id, organizationName: "Test Special RUT Org 2"])
              .call()
      
      // Add special RUT to first organization
      ec.service.sync().name("create#mantle.party.PartyIdentification")
              .parameters([
                  partyId: testOrg1Id,
                  partyIdTypeEnumId: "PtidNationalTaxId", 
                  idValue: specialRut
              ])
              .call()
      
      // Try to add the same special RUT to second organization (should succeed)
      Map specialResult = ec.service.sync().name("create#mantle.party.PartyIdentification")
              .parameters([
                  partyId: testOrg2Id,
                  partyIdTypeEnumId: "PtidNationalTaxId", 
                  idValue: specialRut
              ])
              .call()
      
      // Check both error sources as per Moqui testing best practices
      boolean hasErrors = ec.message.hasError() || specialResult?.errorMessage != null
      boolean specialRutDuplicationAllowed = !hasErrors
      if (specialRutDuplicationAllowed) {
          logger.info("[General] Special RUT duplication correctly allowed")
      } else {
          logger.warn("[General] Special RUT duplication failed")
      }

      totalTestChecked += 1

      expect:
      specialRuts.rutMap[specialRut] != null  // Confirm it's a special RUT
      specialRutDuplicationAllowed == true    // Should succeed for special RUTs
      
      cleanup:
      // This test may trigger errors during special RUT validation - clear them
      ec.message.clearErrors()

      // Clean up test data using delete services (preferred for resilient cleanup)
      try {
          [testOrg1Id, testOrg2Id].each { partyId ->
              // Delete PartyIdentification records for this party
              def partyIdentifications = ec.entity.find("mantle.party.PartyIdentification")
                      .condition("partyId", partyId).list()
              partyIdentifications.each { partyIdRecord ->
                  ec.service.sync().name("delete#mantle.party.PartyIdentification")
                      .parameters([partyId: partyIdRecord.partyId, partyIdTypeEnumId: partyIdRecord.partyIdTypeEnumId, idValue: partyIdRecord.idValue])
                      .disableAuthz().call()
              }
              
              // Delete Party record
              ec.service.sync().name("delete#mantle.party.Organization").parameters([partyId: partyId]).disableAuthz().call()
              ec.service.sync().name("delete#mantle.party.Party").parameters([partyId: partyId]).disableAuthz().call()
          }
      } catch (Exception e) {
          logger.warn("[General] Cleanup warning: ${e.message}")
      }
   }
}

package DTE
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
import org.moqui.Moqui
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

class FacturacionAfectaTests extends Specification {
   @Shared protected final static Logger logger = LoggerFactory.getLogger(FacturacionAfectaTests.class)
   @Shared ExecutionContext ec
   @Shared String partyId = 'INVCJ'
   @Shared String dteType = 'Ftdt-33', productId = '100211'
   @Shared String rutEmisor = '76514104-4'
   @Shared long effectiveTime = System.currentTimeMillis()
   @Shared long totalFieldsChecked = 0

   def setupSpec() {
      // init the framework, get the ec
      ec = Moqui.getExecutionContext()
      ec.user.loginUser("jhp", "jhp")
      logger.info("[DTE] Prueba facturación iniciada")

      // set an effective date so data check works, etc
      ec.user.setEffectiveTime(new Timestamp(effectiveTime))
   }

   def cleanupSpec() {
      ec.destroy()

      logger.info("[DTE] Prueba facturación completa, ${totalFieldsChecked} registros chequeados")
   }

   def setup() {
      ec.artifactExecution.disableAuthz()
   }

   def cleanup() {
      ec.artifactExecution.enableAuthz()
   }


   def "Factura Afecta"() {
      when:
      // Creación de invoice
      logger.info("[DTE] Creación del Invoice a Factura")
      Map invoiceOut = ec.service.sync().name("mantle.account.InvoiceServices.create#Invoice")
              .parameters([invoiceTypeEnumId:'InvoiceSales', fromPartyId:partyId, toPartyId:'100204'])
              .call()
      String invoiceId = invoiceOut.invoiceId

      // Adición de productos
      logger.info("[DTE] Creación de un Item para el Invoice a Facturar")
      Map productOut = ec.service.sync().name("mantle.account.InvoiceServices.create#InvoiceItem")
              .parameters([invoiceId:invoiceId, productId:productId, quantity:5, amount:500000, description:'Item Afecto'])
              .call()

      logger.info("[DTE] Generación de la Factura a partir del Invoice Creado")
      Map factOut = ec.service.sync().name("mchile.DTEServices.facturar#Invoice")
              .parameters([invoiceId:invoiceId, fiscalTaxDocumentTypeEnumId:dteType, activeOrgId:partyId])
              .call()
      String fiscalTaxDocumentId = factOut.fiscalTaxDocumentId

      List<String> dataCheckErrors = []
      long fieldsChecked = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <mchile.dte.FiscalTaxDocument fiscalTaxDocumentId="${fiscalTaxDocumentId}"  fiscalTaxDocumentTypeEnumId="${dteType}" />

        </entity-facade-xml>""").check(dataCheckErrors)
      totalFieldsChecked += fieldsChecked
      logger.info("Checked ${fieldsChecked} fields")
      if (dataCheckErrors) for (String dataCheckError in dataCheckErrors) logger.info(dataCheckError)
      if (ec.message.hasError()) logger.warn(ec.message.getErrorsString())

      then:
      dataCheckErrors.size() == 0
   }

}

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

class NotaDeCreditoTests extends Specification {
   @Shared protected final static Logger logger = LoggerFactory.getLogger(NotaDeCreditoTests.class)
   @Shared ExecutionContext ec
   @Shared String partyId = 'INVCJ'
   @Shared String dteType = '34', productId = '100105'
   @Shared long effectiveTime = System.currentTimeMillis()
   @Shared long totalFieldsChecked = 0

   def setupSpec() {
      // init the framework, get the ec
      ec = Moqui.getExecutionContext()
      ec.user.loginUser("jhp", "jhp")
      // set an effective date so data check works, etc
      ec.user.setEffectiveTime(new Timestamp(effectiveTime))

   }

   def cleanupSpec() {

      ec.destroy()

      logger.info("Prueba nota de crédito completa, ${totalFieldsChecked} registros chequeados")
   }

   def setup() {
      ec.artifactExecution.disableAuthz()
   }

   def cleanup() {
      ec.artifactExecution.enableAuthz()
   }


   def "notaCredito"() {
      when:
      // Creacion de orden - Requiere que exista tienda definida, no tenemos sesión para obtener organización
      Map orderOut = ec.service.sync().name("mantle.order.OrderServices.create#Order")
              .parameters([orderName:'Test Nota Credito', currencyUomId:'CLP', grandTotal:'2500000'])
              .call()
      String orderId = orderOut.orderId
      String orderPartSeqId = orderOut.orderPartSeqId
      logger.warn("Orden:"+orderId+","+orderPartSeqId)

      Map orderItemOut = ec.service.sync().name("mantle.order.OrderServices.create#OrderItem")
              .parameters([orderId:orderId, orderPartSeqId:orderPartSeqId, productId:productId, quantity:5, unitAmount:500000])
              .call()

      ec.service.sync().name("mantle.order.OrderServices.update#OrderPart")
              .parameters([orderId:orderId, orderPartSeqId:orderPartSeqId, vendorPartyId:partyId, customerPartyId:'100204' ])
              .call()

      // Se cierra la orden
      ec.service.sync().name("mantle.order.OrderServices.place#Order")
              .parameters([orderId:orderId, orderPartSeqId:orderPartSeqId])
              .call()
      ec.service.sync().name("mantle.order.OrderServices.approve#Order")
              .parameters([orderId:orderId, orderPartSeqId:orderPartSeqId])
              .call()

      // Creación de invoice
      Map invoiceOut = ec.service.sync().name("mantle.account.InvoiceServices.create#EntireOrderPartInvoice")
              .parameters([orderId:orderId, orderPartSeqId:orderPartSeqId])
              .call()
      String invoiceId = invoiceOut.invoiceId

      logger.warn("Invoice:"+invoiceId)

      // Se crea devolucion
      Map returnOut = ec.service.sync().name("mantle.order.ReturnServices.create#Return")
              .parameters([orderId:orderId, orderPartSeqId:orderPartSeqId, vendorPartyId:partyId, customerPartyId:'100204'])
              .call()
      String returnId = returnOut.returnId

      logger.warn("Return:"+returnId)
      // Adición de items
      ec.service.sync().name("mantle.order.ReturnServices.add#OrderItemToReturn")
              .parameters([returnId:returnId, orderId:orderId, orderPartSeqId:orderPartSeqId, vendorPartyId:partyId, customerPartyId:'100204',
                           orderItemSeqId:'01', returnReasonEnumId:'RrsnDefective', returnResponseEnumId:'RrspRefund', returnQuantity:5, returnPrice:500000])
              .call()

      // Lista de items
      //List items = [['01-1-10000-0-HORAS PROGRAMADOR']]
      List<String> items = new ArrayList<>()
      items.add('01-1-10000-0-HORAS PROGRAMADOR')

      // Creacion de Nota de Credito
      Map factOut = ec.service.sync().name("mchile.DTEServices.generar#NotaCredito")
              .parameters([returnId:returnId, invoiceId:invoiceId, activeOrgId:partyId, fiscalTaxDocumentTypeEnumId:'Ftdt-61',items:items])
              .call()
      String fiscalTaxDocumentId = factOut.fiscalTaxDocumentId

      logger.warn("fiscalTaxDocumentId:"+fiscalTaxDocumentId)

      List<String> dataCheckErrors = []
      long fieldsChecked = ec.entity.makeDataLoader().xmlText("""<entity-facade-xml>
            <mchile.dte.FiscalTaxDocument fiscalTaxDocumentId="${fiscalTaxDocumentId}" fiscalTaxDocumentTypeEnumId="Ftdt-61" />
        </entity-facade-xml>""").check(dataCheckErrors)
      totalFieldsChecked += fieldsChecked
      logger.info("Checked ${fieldsChecked} fields")
      if (dataCheckErrors) for (String dataCheckError in dataCheckErrors) logger.info(dataCheckError)
      if (ec.message.hasError()) logger.warn(ec.message.getErrorsString())

      then:
      dataCheckErrors.size() == 0
   }

}

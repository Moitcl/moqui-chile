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
      ec.user.loginUser("moitadmin", "demomoit")

      // set an effective date so data check works, etc
      ec.user.setEffectiveTime(new Timestamp(effectiveTime))
   }

   def cleanupSpec() {
      ec.destroy()
      logger.info("[General] Prueba Finalizada. ${totalTestChecked} pruebas ejecutadas")
   }

   def setup() {
      ec.artifactExecution.disableAuthz()
   }

   def cleanup() {
      ec.artifactExecution.enableAuthz()
   }

   def "Verifica RUTs Válidos (#rut)"() {
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

   def "Verifica RUTs Inválidos (#rut)"() {
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
      "13252311-9" | ""
      "1-0" | ""
      "66666666-1" | ""
   }
}

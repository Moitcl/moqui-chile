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

import org.junit.platform.suite.api.SelectClasses
import org.junit.platform.suite.api.Suite
import org.junit.jupiter.api.AfterAll
import org.moqui.Moqui

/**
 * MoquiChile Component Test Suite
 * Following enterprise patterns from mantle-usl for Chilean localization testing
 * 
 * Simplified structure reflecting unified generate#Factura service architecture:
 * - RUT validation utilities
 * - Complete DTE generation testing (all document types via single service)
 */
@Suite
@SelectClasses([
    General.RutTests.class,           // Chilean RUT validation utilities
    DTE.DteGenerationTests.class      // All DTE types via generate#Factura and generate#Boleta services
])
class MoquiChileSuite {
    @AfterAll
    static void destroyMoqui() {
        Moqui.destroyActiveExecutionContextFactory()
    }
}
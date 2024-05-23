import groovy.xml.MarkupBuilder
import org.moqui.entity.EntityCondition
import org.w3c.dom.Document
import cl.moit.dte.MoquiDTEUtils
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityValue

ExecutionContext ec = ec

if (tipoOperacion == null)
    ec.message.addError("Se debe especificar el tipo")
if (tipoOperacion != 'VENTA' && tipoOperacion != 'COMPRA')
    ec.message.addError("tipo debe ser 'VENTA' o 'COMPRA'")

if (ec.message.hasError())
    return

Map dteConfig = ec.service.sync().name("mchile.sii.dte.DteInternalServices.load#DteConfig").parameter("partyId", organizationPartyId).call()
String periodoTributario = ec.l10n.format(periodo, 'yyyy-MM')
Calendar cal = Calendar.instance
cal.setTimeInMillis(periodo.time)
cal.set(Calendar.DAY_OF_MONTH, 1)
cal.set(Calendar.HOUR_OF_DAY, 0)
cal.set(Calendar.MINUTE, 0)
cal.set(Calendar.SECOND, 0)
cal.set(Calendar.MILLISECOND, 0)
fromDate = new Timestamp(cal.timeInMillis)
cal.add(Calendar.MONTH, 1)
thruDate = new Timestamp(cal.timeInMillis)

StringWriter xmlWriter = new StringWriter()
MarkupBuilder xmlBuilder = new MarkupBuilder(xmlWriter)

Map totalesPeriodo = [:]
List documentList = []

vatTaxRate = ec.service.sync().name("mchile.TaxServices.get#VatTaxRate").call().taxRate as BigDecimal
tasaImpuestoIva = (vatTaxRate * 100) as String
dteTypeEnumList = ec.entity.find("moqui.basic.Enumeration").condition("parentEnumId", EntityCondition.ComparisonOperator.IN, ['Ftdt-DT', 'Ftdt-DTE']).list()
dteTypeList = dteTypeEnumList.enumId

List<String> compraDteTypeList = ['Ftdt-45', 'Ftdt-46']
List<String> tiposInformacionElectronicaVentas = ['Ftdt-29', 'Ftdt-30', 'Ftdt-32', 'Ftdt-33', 'Ftdt-34', 'Ftdt-35', 'Ftdt-38', 'Ftdt-39', 'Ftdt-40', 'Ftdt-41', 'Ftdt-43', 'Ftdt-45', 'Ftdt-46', 'Ftdt-47', 'Ftdt-48', 'Ftdt-55', 'Ftdt-56', 'Ftdt-60', 'Ftdt-61', 'Ftdt-101', 'Ftdt-102', 'Ftdt-103', 'Ftdt-104', 'Ftdt-105', 'Ftdt-106', 'Ftdt-108', 'Ftdt-109', 'Ftdt-110', 'Ftdt-111', 'Ftdt-112', 'Ftdt-901', 'Ftdt-902', 'Ftdt-903', 'Ftdt-919', 'Ftdt-920', 'Ftdt-922', 'Ftdt-924']
List<String> tiposInformacionElectronicaCompras = ['Ftdt-29', 'Ftdt-30', 'Ftdt-32', 'Ftdt-33', 'Ftdt-34', 'Ftdt-40', 'Ftdt-43', 'Ftdt-45', 'Ftdt-46', 'Ftdt-55', 'Ftdt-56', 'Ftdt-60', 'Ftdt-61', 'Ftdt-108', 'Ftdt-901', 'Ftdt-911', 'Ftdt-914']
List<String> tiposSoloDetalles = ['Ftdt-35', 'Ftdt-38', 'Ftdt-39', 'Ftdt-41', 'Ftdt-47', 'Ftdt-48', 'Ftdt-105', 'Ftdt-919', 'Ftdt-920', 'Ftdt-922', 'Ftdt-924']
List<String> tiposNotasDebitoCredito = ['Ftdt-55', 'Ftdt-56', 'Ftdt-60', 'Ftdt-61', 'Ftdt-104', 'Ftdt-106', 'Ftdt-111', 'Ftdt-112']

EntityFind entityFind = ec.entity.find("mchile.dte.FiscalTaxDocumentAndAttributes").condition("date", EntityCondition.GREATER_THAN_EQUAL_TO, fromDate)
        .condition("date", EntityCondition.LESS_THAN, thruDate).condition("statusId", EntityCondition.IN, ['Ftd-Issued', 'Ftd-Cancelled'])
if (fiscalTaxDocumentIdList)
    entityFind.condition("fiscalTaxDocumentId", EntityCondition.IN, fiscalTaxDocumentIdList)
if (!includeUnsentDtes)
    entityFind.condition("sentAuthStatusId", EntityCondition.IN, ['Ftd-SentAuthAccepted', 'Ftd-SentAuthAcceptedWithDiscrepancies', 'Ftd-SentAuthUnverified'])
ecf = ec.entity.conditionFactory
EntityCondition facturaVentaCondition = null
EntityCondition facturaCompraCondition = null
EntityCondition tipoDocumentoCondition = null
if (tipoOperacion == 'VENTA') {
    facturaVentaCondition = ecf.makeCondition([ecf.makeCondition("issuerPartyIdValue", EntityCondition.ComparisonOperator.EQUALS, dteConfig.rutOrganizacion),
                                               ecf.makeCondition("issuerPartyId", EntityCondition.ComparisonOperator.EQUALS, dteConfig.partyId),
                                               ecf.makeCondition("fiscalTaxDocumentTypeEnumId", EntityCondition.ComparisonOperator.NOT_IN, compraDteTypeList)])
    facturaCompraCondition = ecf.makeCondition([ecf.makeCondition("receiverPartyIdValue", EntityCondition.ComparisonOperator.EQUALS, dteConfig.rutOrganizacion),
                                                ecf.makeCondition("receiverPartyId", EntityCondition.ComparisonOperator.EQUALS, dteConfig.partyId),
                                                ecf.makeCondition("fiscalTaxDocumentTypeEnumId", EntityCondition.ComparisonOperator.IN, compraDteTypeList)])
    tipoDocumentoCondition = ecf.makeCondition("fiscalTaxDocumentTypeEnumId", EntityCondition.ComparisonOperator.IN, tiposInformacionElectronicaVentas)
} else {
    facturaVentaCondition = ecf.makeCondition([ecf.makeCondition("receiverPartyIdValue", EntityCondition.ComparisonOperator.EQUALS, dteConfig.rutOrganizacion),
                                               ecf.makeCondition("receiverPartyId", EntityCondition.ComparisonOperator.EQUALS, dteConfig.partyId),
                                               ecf.makeCondition("fiscalTaxDocumentTypeEnumId", EntityCondition.ComparisonOperator.NOT_IN, compraDteTypeList)])
    facturaCompraCondition = ecf.makeCondition([ecf.makeCondition("issuerPartyIdValue", EntityCondition.ComparisonOperator.EQUALS, dteConfig.rutOrganizacion),
                                                ecf.makeCondition("issuerPartyId", EntityCondition.ComparisonOperator.EQUALS, dteConfig.partyId),
                                                ecf.makeCondition("fiscalTaxDocumentTypeEnumId", EntityCondition.ComparisonOperator.IN, compraDteTypeList)])
    tipoDocumentoCondition = ecf.makeCondition("fiscalTaxDocumentTypeEnumId", EntityCondition.ComparisonOperator.IN, tiposInformacionElectronicaCompras)
    //ec.message.addError("Searching for receiverPartyId: ${dteConfig.partyId}")
}
entityFind.condition(ecf.makeCondition([facturaVentaCondition, facturaCompraCondition], EntityCondition.JoinOperator.OR)).condition(tipoDocumentoCondition)
documentEvList = entityFind.list()

documentEvList.each { EntityValue dte ->
    String tipoDte = ec.service.sync().name("mchile.sii.dte.DteInternalServices.get#SiiCode").parameters([fiscalTaxDocumentTypeEnumId:dte.fiscalTaxDocumentTypeEnumId]).call().siiCode
    if (totalesPeriodo[tipoDte] == null) {
        totalesPeriodo[tipoDte] = [:]
    }
    tot = totalesPeriodo[tipoDte]
    doc = [tipoDocumento:tipoDte]
    // Boletas y otros tipos solamente se incorporan en detalles
    if (!((dte.fiscalTaxDocumentTypeEnumId as String) in tiposSoloDetalles))
        documentList.add(doc)
    tot.totalDocumentos = (tot.totalDocumentos?:0) + 1
    doc.anulado = (dte.statusId == 'Ftd-Cancelled')
    if (doc.anulado)
        tot.totalAnulados = (tot.totalAnulados?:0) + 1
    if ((dte.fiscalTaxDocumentTypeEnumId as String) in tiposNotasDebitoCredito) {
        // Nota de Débito o Crédito
        referenceList = ec.entity.find("mchile.dte.ReferenciaDte").condition("referenciaTypeEnumId", "RefDteTypeFiscalTaxDocument")
                .condition("fiscalTaxDocumentId", dte.fiscalTaxDocumentId).condition("fiscalTaxDocumentTypeEnumId", EntityCondition.ComparisonOperator.IN, dteTypeList).list()
        if (referenceList.size() > 1)
            ec.message.addError("More than 1 DT referenced in DTE ${dte.fiscalTaxDocumentId}")
        if (referenceList.size() < 1)
            ec.message.addError("Less than 1 DT referenced in DTE ${dte.fiscalTaxDocumentId}")
        EntityValue reference = referenceList.get(0)
        if ((reference.fiscalTaxDocumentTypeEnumId as String) in compraDteTypeList)
            doc.emisor = '1'
        if (dte.fiscalTaxDocumentTypeEnumId in ['Ftdt-60', 'Ftdt-61'] && reference.codigoReferenciaEnumId == 'MCHRefDteCodeAnula') {
            doc.tipoDocumentoReferencia = ec.entity.find("moqui.basic.Enumeration").condition("enumId", reference.fiscalTaxDocumentTypeEnumId).one().enumCode
            doc.folioDocumentoReferencia = reference.folio
        }
    }
    impuestoZonaFranca = false
    if (impuestoZonaFranca) {
        doc.tipoImpuesto = '2'
        tot.totalLey18211 = (tot.totalLey18211?:0)+montoImpuestoZonaFranca
    } else {
        doc.tipoImpuesto = '1'
        doc.tasaImpuesto = tasaImpuestoIva
    }
    doc.numeroDocumento = dte.fiscalTaxDocumentNumber
    if (dte.formaPagoEnumId == 'Ftdfp-Gratuita')
        doc.indicadorSinCosto = '1'
    doc.fechaDocumento = dte.date
    doc.codigoSucursalSii = dteConfig.codigoSucursalSii
    if ((dte.fiscalTaxDocumentTypeEnumId as String) in compraDteTypeList) {
        rutComprador = dte.issuerPartyIdValue
        partyIdComprador = dte.issuerPartyId
        rutVendedor = dte.receiverPartyIdValue
        partyIdVendedor = dte.receiverPartyId
    } else {
        rutVendedor = dte.issuerPartyIdValue
        partyIdVendedor = dte.issuerPartyId
        rutComprador = dte.receiverPartyIdValue
        partyIdComprador = dte.receiverPartyId
    }
    if (tipoOperacion == 'VENTA') {
        doc.rutContraparte = rutComprador
        partyIdContraparte = partyIdComprador
    } else {
        doc.rutContraparte = rutVendedor
        partyIdContraparte = partyIdVendedor
    }
    contraparte = ec.entity.find("mantle.party.PartyDetail").condition("partyId", partyIdContraparte).one()
    doc.razonSocialContraparte = contraparte.taxOrganizationName ?: ec.resource.expand('PartyNameOnlyTemplate', null, contraparte)
    doc.montoExento = dte.montoExento
    tot.montoExento = (tot.montoExento ?: 0) + (dte.montoExento ?: 0)
    doc.montoNeto = dte.montoNeto
    tot.montoNeto = (tot.montoNeto ?: 0) + (dte.montoNeto ?: 0)
    doc.montoIva = (dte.montoIVARecuperable ?:0) + (dte.montoIVANoRecuperable ?: 0) + (dte.montoIVAUsoComun ?: 0) + (dte.montoIVAActivoFijo ?: 0)
    tot.montoNeto = (tot.montoNeto ?: 0) + doc.montoIva
    doc.montoTotal = (doc.montoNeto ?: 0) + (doc.montoExento ?: 0) + doc.montoIva
    tot.totalMontoTotal = (tot.totalMontoTotal ?: 0) + doc.montoTotal
    if (dte.montoIVARecuperable > 0) {
        if (dte.fiscalTaxDocumentTypeEnumId in compraDteTypeList) {
            tot.numeroOperacionesIvaRetenido = (tot.numeroOperacionesIvaRetenido ?: 0) + 1
            tot.totalIvaRetenidoTotal = (tot.totalIvaRetenidoTotal ?: 0) + dte.montoIVARecuperable
        } else {
            tot.numeroOperacionesIvaRecuperable = (tot.numeroOperacionesIvaRecuperable ?: 0) + 1
        }
    }
    if (dte.montoIVANoRecuperable > 0) {
        Map ivaNoRecuperableMap = [codigo:dte.codigoIVANoRecuperable, monto:dte.montoIVANoRecuperable]
        doc.ivaNorecuperable = [ivaNoRecuperableMap]
        if (tot.totalIvaNoRecuperable == null)
            tot.totalIvaNoRecuperable = []
        totalIvaNoRecuperableMap = tot.totalIvaNoRecuperable.find { codigo == dte.codigIVANoRecuperable }
        if (totalIvaNoRecuperable == null) {
            totalIvaNoRecuperableMap = [codigo:dte.codigoIVANoRecuperable, monto:dte.montoIVANoRecuperable]
            tot.totalIvaNoRecuperable.add(totalIvaNoRecuperableMap)
        } else {
            totalIvaNoRecuperableMap.monto = totalIvaNoRecuperableMap.monto + dte.montoIVANoRecuperable
        }
    }
    if (dte.montoIVAUsoComun > 0) {
        tot.numeroOperacionesIvaUsoComun = (tot.numeroOperacionesIvaUsoComun ?: 0) + 1
        tot.totalMontoIvaUsoComun = (tot.totalMontoIvaUsoComun ?: 0) + dte.montoIVAUsoComun
        tot.factorProporcionalidadIva = (proporcionalidadIvaUsoComunRate * 100).setScale(5, java.math.RoundingMode.HALF_UP)
        tot.totalCreditoIvaUsoComun = (tot.totalCreditoIvaUsoComun ?:0) + (dte.montoIVAUsoComun * proporcionalidadIvaUsoComunRate).setScale(0, java.math.RoundingMode.HALF_UP)
    }
    if (dte.montoNetoActivoFijo > 0 || dte.montoIVAActivoFijo > 0) {
        tot.numeroOperacionesIvaActivoFijo = (tot.numeroOperacionesIvaActivoFijo ?: 0) + 1
        doc.montoNetoActivoFijo = (dte.montoNetoActivoFijo ?: 0.0)
        doc.montoIvaActivoFijo = (dte.montoIVAActivoFijo ?: 0.0)
        tot.totalMontoNetoActivoFijo = (tot.totalMontoNetoActivoFijo ?: 0.0) + doc.montoNetoActivoFijo
        tot.totalMontoIvaActivoFijo = (tot.totalMontoIvaActivoFijo ?: 0.0) + doc.montoIvaActivoFijo
    }
}

idLibro = "Libro${tipoOperacion == 'VENTA' ? 'Ventas' : 'Compras'}-" + ec.l10n.format(ec.user.nowTimestamp, "yyyyMMddHHmmssSSS")
String schemaLocation = 'http://www.sii.cl/SiiDte LibroCV_v10.xsd'
String tmstFirma = ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd'T'HH:mm:ss")
xmlBuilder.LibroCompraVenta(xmlns: 'http://www.sii.cl/SiiDte', 'xmlns:xsi': 'http://www.w3.org/2001/XMLSchema-instance', version: '1.0', 'xsi:schemaLocation': schemaLocation) {
    EnvioLibro(ID: idLibro) {
        Caratula {
            RutEmisorLibro(dteConfig.rutOrganizacion)
            RutEnvia(dteConfig.rutEnviador)
            PeriodoTributario(periodoTributario)
            FchResol(dteConfig.fechaResolucionSii)
            NroResol(dteConfig.numeroResolucionSii)
            TipoOperacion(tipoOperacion)
            if (folioNotificacion)
                TipoLibro('ESPECIAL')
            else
                TipoLibro('MENSUAL')
            TipoEnvio('TOTAL')
            if (folioNotificacion)
                FolioNotificacion(folioNotificacion)
        }
        ResumenPeriodo {
            totalesPeriodo.each { tipoDocumento, totalesPorTipo ->
                TotalesPeriodo {
                    TpoDoc(tipoDocumento)
                    if (totalesPorTipo.tipoImpuesto) TpoImp(totalesPorTipo.tipoImpuesto)
                    TotDoc(totalesPorTipo.totalDocumentos)
                    if (totalesPorTipo.totalAnulados) TotAnulado(totalesPorTipo.totalAnulados)
                    TotMntExe(totalesPorTipo.totalMontoExento ?: 0)
                    TotMntNeto(totalesPorTipo.totalMontoNeto ?: 0)
                    if (totalesPorTipo.numeroOperacionesIvaRecuperable) TotOpIVARec(totalesPorTipo.numeroOperacionesIvaRecuperable)
                    TotMntIVA(totalesPorTipo.totalMontoIva ?: 0)
                    if (totalesPorTipo.numeroOperacionesActivoFijo) TotOpActivoFijo(totalesPorTipo.numeroOperacionesActivoFijo)
                    if (totalesPorTipo.totalMontoNetoActivoFijo) TotMntActivoFijo(totalesPorTipo.totalMontoNetoActivoFijo)
                    if (totalesPorTipo.totalMontoIvaActivoFijo) TotMntIVAActivoFijo(totalesPorTipo.totalMontoIvaActivoFijo)
                    if (totalesPorTipo.totalIvaNoRecuperable)
                        totalesPorTipo.totalIvaNoRecuperable.each { ivaNoRecuperable ->
                            TotIVANoRec {
                                CodIVANoRec(ivaNoRecuperable.codigo)
                                if (ivaNoRecuperable.numeroOperaciones) TotOpIVANoRec(ivaNoRecuperable.numeroOperaciones)
                                TotMntIVANoRec(ivaNoRecuperable.totalMonto)
                            }
                        }
                    if (totalesPorTipo.numeroOperacionesIvaUsoComun) TotOpIVAUsoComun(totalesPorTipo.numeroOperacionesIvaUsoComun)
                    if (totalesPorTipo.totalMontoIvaUsoComun) TotIVAUsoComun(totalesPorTipo.totalMontoIvaUsoComun)
                    if (totalesPorTipo.factorProporcionalidadIva) FctProp(totalesPorTipo.factorProporcionalidadIva)
                    if (totalesPorTipo.totalCreditoIvaUsoComun) TotCredIVAUsoComun(totalesPorTipo.totalCreditoIvaUsoComun)
                    if (totalesPorTipo.totalIvaFueraPlazo) TotIVAFueraPlazo(totalesPorTipo.totalIvaFueraPlazo)
                    if (totalesPorTipo.totalIvaPropio) TotIVAPropio(totalesPorTipo.totalIvaPropio)
                    if (totalesPorTipo.totalIvaTerceros) TotIVATerceros(totalesPorTipo.totalIvaTerceros)
                    if (totalesPorTipo.totalLey18211) TotLey18211(totalesPorTipo.totalLey18211)
                    if (totalesPorTipo.totalOtrosImpuestos)
                        totalesPorTipo.totalOtrosImpuestos.each { otroImpuesto ->
                            TotOtrosImp {
                                CodImp(otroImpuesto.total)
                                TotMntImp(otroImpuesto.totalMonto)
                                FctImpAdic(otroImpuesto.factorImpuestoAdicional)
                                TotCredimp(otroImpuesto.totalCreditoImpuesto)
                            }
                        }
                    if (totalesPorTipo.totalImpuestosSinCredito)
                        TotImpSinCredito(totalesPorTipo.totalImpuestosSinCredito)
                    if (totalesPorTipo.numeroOperacionesIvaRetenidoTotal)
                        TotIVARetTotal(totalesPorTipo.numeroOperacionesIvaRetenidoTotal)
                    if (totalesPorTipo.totalIvaRetenidoTotal)
                        TotIVARetTotal(totalesPorTipo.totalIvaRetenidoTotal)
                    if (totalesPorTipo.numeroOperacionesIvaRetenidoParcial)
                        TotIVARetParcial(totalesPorTipo.numeroOperacionesIvaRetenidoParcial)
                    if (totalesPorTipo.totalIvaRetenidoParcial)
                        TotIVARetParcial(totalesPorTipo.totalIvaRetenidoParcial)
                    if (totalesPorTipo.totalCreditoEmpresaConstructora)
                        TotCredEC(totalesPorTipo.totalCreditoEmpresaConstructora)
                    if (totalesPorTipo.totalDepositoEnvase)
                        TotDepEnvase(totalesPorTipo.totalDepositoEnvase)
                    if (totalesPorTipo.totalLiquidationes)
                        TotLiquidaciones {
                            if (totalesPorTipo.totalLiquidationes.valorComisionesNeto) TotValComNeto(totalesPorTipo.totalLiquidationes.valorComisionesNeto)
                            if (totalesPorTipo.totalLiquidationes.valorComisionesExento) TotValComExe(totalesPorTipo.totalLiquidationes.valorComisionesExento)
                            if (totalesPorTipo.totalLiquidationes.valorComisionesIva) TotValComIVA(totalesPorTipo.totalLiquidationes.valorComisionesIva)
                        }
                    TotMntTotal(totalesPorTipo.totalMontoTotal)
                    if (totalesPorTipo.numeroOperacionesIvaNoRetenido)
                        TotOpIVANoRetenido(totalesPorTipo.numeroOperacionesIvaNoRetenido)
                    if (totalesPorTipo.totalIvaNoRetenido)
                        TotIVANoRetenido(totalesPorTipo.totalIvaNoRetenido)
                    if (totalesPorTipo.totalMontoNoFacturable)
                        TotMntNoFact(totalesPorTipo.totalMontoNoFacturable)
                    if (totalesPorTipo.totalMontoPeriodo)
                        TotaMntPeriodo(totalesPorTipo.totalMontoPeriodo)
                    if (totalesPorTipo.totalVentaPasajeNacional)
                        TotPsjNac(totalesPorTipo.totalVentaPasajeNacional)
                    if (totalesPorTipo.totalVentaPasajeInternacional)
                        TotPsjInt(totalesPorTipo.totalVentaPasajeInternacional)
                    if (totalesPorTipo.totalTabacoPuros)
                        TotTabPuros(totalesPorTipo.totalTabacoPuros)
                    if (totalesPorTipo.totalTabacoCigarrillos)
                        TotTabCigarrillos(totalesPorTipo.totalTabacoCigarrillos)
                    if (totalesPorTipo.totalTabacoElaborado)
                        TotTabElaborado(totalesPorTipo.totalTabacoElaborado)
                    if (totalesPorTipo.totalImpuestoVehiculos)
                        TotImpVehiculo(totalesPorTipo.totalImpuestoVehiculos)
                }
            }
        }
        documentList.each { doc ->
            Detalle {
                TpoDoc(doc.tipoDocumento)
                if (doc.emisor) Emisor(doc.emisor)
                if (doc.indicadorFacturaCompra) IndFactCompra(doc.indicadorFacturaCompra)
                NroDoc(doc.numeroDocumento)
                if (doc.anulado) Anulado('A')
                if (doc.operacion) Operacion(doc.operacion)
                if (doc.tipoImpuesto) TpoImp(doc.tipoImpuesto)
                if (doc.tasaImpuesto) TasaImp(doc.tasaImpuesto)
                if (doc.numeroInterno) NumInt(doc.numeroInterno)
                if (doc.indicadorServicio) IndServicio(doc.indicadorServicio)
                if (doc.indicadorSinCosto) IndSinCosto(doc.indicadorSinCosto)
                if (doc.fechaDocumento) FchDoc(ec.l10n.format(doc.fechaDocumento, 'yyyy-MM-dd'))
                if (doc.codigoSucursalSii) CdgSIISucur(doc.codigoSucursalSii)
                if (doc.rutContraparte) RUTDoc(doc.rutContraparte)
                if (doc.razonSocialContraparte) RznSoc(doc.razonSocialContraparte)
                if (doc.receptorExtranjero)
                    Extranjero {
                        if (doc.receptorExtranjero.idValue) NumId(doc.receptorExtranjero.idValue)
                        if (doc.receptorExtranjero.nacionalidad) Nacionalidad(doc.receptorExtranjero.nacionalidad)
                    }
                if (doc.tipoDocumentoReferencia) TpoDocRef(doc.tipoDocumentoReferencia)
                if (doc.folioDocumentoReferencia) FolioDocRef(doc.folioDocumentoReferencia)
                if (doc.montoExento) MntExe(doc.montoExento)
                if (doc.montoNeto) MntNeto(doc.montoNeto)
                if (doc.montoIva) MntIVA(doc.montoIva)
                if (doc.montoNetoActivoFijo) MntActivoFijo(doc.montoNetoActivoFijo)
                if (doc.montoIvaActivoFijo) MntIVAActivoFijo(doc.montoIvaActivoFijo)
                doc.ivaNorecuperable.each { ivaNoRecuperable ->
                    IVANoRec {
                        CodIVANoRec(ivaNoRecuperable.codigo)
                        MntIVANoRec(ivaNoRecuperable.monto)
                    }
                }
                if (doc.ivaUsoComun) IVAUsoComun(doc.ivaUsoComun)
                if (doc.ivaFueraPlazo) IVAFueraPlazo(doc.ivaFueraPlazo)
                if (doc.ivaPropio) IVAPropio(doc.ivaPropio)
                if (doc.ivaTerceros) IVATerceros(doc.ivaTerceros)
                if (doc.ley18211) Ley18211(doc.ley18211)
                if (doc.otrosImpuestos)
                    doc.otrosImpuestos.each { otroImpuesto ->
                        OtrosImp {
                            CodImp(otroImpuesto.codigo)
                            if (otroImpuesto.tasa) TasaImp(otroImpuesto.tasa)
                            MntImp(otroImpuesto.monto)
                        }
                    }
                if (doc.montoSinCredito) MntSinCred(doc.montoSinCredito)
                if (doc.ivaRetenidoTotal) IVARetTotal(doc.ivaRetenidoTotal)
                if (doc.ivaRetenidoParcial) IVARetParcial(doc.ivaRetenidoParcial)
                if (doc.creditoEmpresaConstructora) CredEC(doc.creditoEmpresaConstructora)
                if (doc.depositoEnvase) DepEnvase(doc.depositoEnvase)
                if (doc.liquidaciones)
                    Liquidaciones {
                        RutEmisor(doc.liquidaciones.rutEmisor)
                        ValComNeto(doc.liquidaciones.valorComisionesNeto)
                        ValComExe(doc.liquidaciones.valorComisionesExento)
                        ValComIVA(doc.liquidaciones.valorComisionesIva)
                    }
                if (doc.montoTotal) MntTotal(doc.montoTotal)
                if (doc.ivaNoRetenido) IVANoRetenido(doc.ivaNoRetenido)
                if (doc.montoNoFacturable) MntNoFact(doc.montoNoFacturable)
                if (doc.montoPeriodo) MntPeriodo(doc.montoPeriodo)
                if (doc.montoPasajeNacional) PsjNac(doc.montoPasajeNacional)
                if (doc.montoPasajeInternacional) PsjInt(doc.montoPasajeInternacional)
                if (doc.tabacoPuros) TabPuros(doc.tabacoPuros)
                if (doc.tabacoCigarrillos) TabCigarrillos(doc.tabacoCigarrillos)
                if (doc.tabacoElaborado) TabElaborado(doc.tabacoElaborado)
                if (doc.impuestoVehiculo) ImpVehiculo(doc.impuestoVehiculo)
            }
        }
        TmstFirma(tmstFirma)
    }
}

libroXmlString = xmlWriter.toString()
libroXmlString = libroXmlString.replaceAll("[^\\x00-\\xFF]", "")
xmlWriter.close()
Document doc2 = MoquiDTEUtils.parseDocument(libroXmlString.getBytes())
byte[] libroXml = MoquiDTEUtils.sign(doc2, "#" + idLibro, dteConfig.pkey, dteConfig.certificate, "#" + idLibro, "EnvioLibro")
libroXmlString = new String(libroXml, "ISO-8859-1")

doc = MoquiDTEUtils.parseDocument(libroXml)

try {
    MoquiDTEUtils.validateDocumentSii(ec, libroXml, schemaLocation)
} catch (Exception e) {
    ec.logger.info("Failed validation for libro: ${libroXmlString}")
    ec.message.addError("Failed validation: " + e.getMessage())
}

ts = ec.user.nowTimestamp
if (MoquiDTEUtils.verifySignature(doc, "/sii:LibroCompraVenta/sii:EnvioLibro", "./sii:TmstFirma/text()")) {
    xmlContentLocation = "dbresource://moit/erp/dte//${rutOrganizacion}/${idLibro}.xml"
    envioRr = ec.resource.getLocationReference(xmlContentLocation)
    envioRr.putBytes(libroXml)
    fileName = envioRr.fileName
    ec.logger.warn("Libro generado OK")
} else {
    xmlContentLocation = "dbresource://moit/erp/dte/${rutOrganizacion}/${idLibro}-mala.xml"
    envioRr = ec.resource.getLocationReference(xmlContentLocation)
    envioRr.putBytes(libroXml)
    fileName = envioRr.fileName
    ec.logger.warn("Error al generar libro")
}

return
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPath
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

ExecutionContext ec = context.ec

// Initialize out-parameters (no type declarations)
validationPassed = false
discrepancies = []
clonedOrderId = null
generationError = null

try {
    // ===== Phase 1: Load Original DTE and Find Order =====

    ec.logger.info("==== validating DTE ${fiscalTaxDocumentId}")
    // 1.1 Query FiscalTaxDocument
    EntityValue fiscalTaxDocument = ec.entity.find("mchile.dte.FiscalTaxDocument")
            .condition("fiscalTaxDocumentId", fiscalTaxDocumentId)
            .one()

    if (!fiscalTaxDocument) {
        ec.message.addError("FiscalTaxDocument not found: ${fiscalTaxDocumentId}")
        return
    }

    // 1.2 Validate it has an invoiceId
    String invoiceId = fiscalTaxDocument.invoiceId
    if (!invoiceId) {
        ec.message.addError("FiscalTaxDocument ${fiscalTaxDocumentId} has no invoiceId")
        return
    }

    // 1.3 Find orderId from invoiceId via OrderItemBilling
    EntityValue orderItemBilling = ec.entity.find("mantle.order.OrderItemBilling")
            .condition("invoiceId", invoiceId)
            .list()
            .first()

    if (!orderItemBilling) {
        ec.message.addError("No OrderItemBilling found for invoiceId: ${invoiceId}")
        return
    }

    String orderId = orderItemBilling.orderId

    // 1.4 Get original invoice date
    EntityValue invoice = ec.entity.find("mantle.account.invoice.Invoice")
            .condition("invoiceId", invoiceId)
            .one()

    if (!invoice) {
        ec.message.addError("Invoice not found: ${invoiceId}")
        return
    }

    java.sql.Timestamp originalInvoiceDate = invoice.invoiceDate

    // 1.5 Get original DTE XML
    Map contentResult = ec.service.sync()
            .name("mchile.sii.dte.DteContentServices.get#DteContent")
            .parameters([
                    fiscalTaxDocumentId: fiscalTaxDocumentId,
                    fiscalTaxDocumentContentTypeEnumId: "Ftdct-Xml"
            ])
            .call()

    if (!contentResult.contentRef) {
        ec.message.addError("No XML content found for FiscalTaxDocument: ${fiscalTaxDocumentId}")
        return
    }

    String originalXml = contentResult.contentRef.getText()

    if (!originalXml) {
        ec.message.addError("XML content is empty for FiscalTaxDocument: ${fiscalTaxDocumentId}")
        return
    }

    // ===== Phase 2: Clone Order =====

    Map cloneResult = ec.service.sync()
            .name("mantle.order.OrderServices.clone#Order")
            .parameters([
                    baseOrderId: orderId,
                    statusId: "OrderOpen",
                    overrideInvoiceDate: originalInvoiceDate,
                    productItemsOnly: false
            ])
            .call()

    if (ec.message.hasError() || !cloneResult.orderId) {
        String errorMsg = "Failed to clone order ${orderId}: ${ec.message.errorsString}"
        ec.message.clearErrors()
        ec.message.addError(errorMsg)
        return
    }

    clonedOrderId = cloneResult.orderId

    // ===== Phase 3: Generate DTE from Cloned Order =====

    Map dteResult = ec.service.sync()
            .name("moit.erp.CRMServices.generate#DTE")
            .parameters([orderId: clonedOrderId])
            .call()

    if (ec.message.hasError() || !dteResult.fiscalTaxDocumentId) {
        generationError = "Failed to generate DTE from cloned order ${clonedOrderId}: ${ec.message.errorsString}"
        // Continue with validation - we want to report the error but still return the clonedOrderId
        validationPassed = false
        return
    }

    String generatedFtdId = dteResult.fiscalTaxDocumentId

    // 3.2 Get new DTE XML
    Map newContentResult = ec.service.sync()
            .name("mchile.sii.dte.DteContentServices.get#DteContent")
            .parameters([
                    fiscalTaxDocumentId: generatedFtdId,
                    fiscalTaxDocumentContentTypeEnumId: "Ftdct-Xml"
            ])
            .call()

    if (!newContentResult.contentRef) {
        generationError = "No XML content found for generated FiscalTaxDocument: ${generatedFtdId}"
        validationPassed = false
        return
    }

    String newXml = newContentResult.contentRef.getText()

    if (!newXml) {
        generationError = "XML content is empty for generated FiscalTaxDocument: ${generatedFtdId}"
        validationPassed = false
        return
    }

    // ===== Phase 4: XML Comparison =====

    // 4.1 Parse both XMLs
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance()
    factory.setNamespaceAware(false)

    Document originalDoc = factory.newDocumentBuilder()
            .parse(new ByteArrayInputStream(originalXml.getBytes("UTF-8")))

    Document newDoc = factory.newDocumentBuilder()
            .parse(new ByteArrayInputStream(newXml.getBytes("UTF-8")))

    XPath xpath = XPathFactory.newInstance().newXPath()

    // 4.2 Compare Detalle items
    NodeList originalDetalles = (NodeList) xpath.evaluate("//Detalle", originalDoc, XPathConstants.NODESET)
    NodeList newDetalles = (NodeList) xpath.evaluate("//Detalle", newDoc, XPathConstants.NODESET)

    if (originalDetalles.getLength() != newDetalles.getLength()) {
        discrepancies.add([
                xpath: "//Detalle count",
                expected: originalDetalles.getLength(),
                actual: newDetalles.getLength()
        ])
    }

    int detalleCount = Math.min(originalDetalles.getLength(), newDetalles.getLength())
    List<String> detalleFields = ["NroLinDet", "QtyItem", "PrcItem", "MontoItem", "DescItem"]

    for (int i = 0; i < detalleCount; i++) {
        Element originalDetalle = (Element) originalDetalles.item(i)
        Element newDetalle = (Element) newDetalles.item(i)

        for (String field : detalleFields) {
            String originalValue = xpath.evaluate(field, originalDetalle)
            String newValue = xpath.evaluate(field, newDetalle)

            if (originalValue != newValue) {
                discrepancies.add([
                        xpath: "//Detalle[${i + 1}]/${field}",
                        expected: originalValue,
                        actual: newValue
                ])
            }
        }
    }

    // 4.3 Compare Totales
    List<String> totalesFields = ["MntNeto", "MntExe", "IVA", "MntTotal"]

    for (String field : totalesFields) {
        String originalValue = xpath.evaluate("//Totales/${field}", originalDoc)
        String newValue = xpath.evaluate("//Totales/${field}", newDoc)

        // Only compare if both values exist
        if (originalValue && newValue && originalValue != newValue) {
            discrepancies.add([
                    xpath: "//Totales/${field}",
                    expected: originalValue,
                    actual: newValue
            ])
        } else if (originalValue && !newValue) {
            discrepancies.add([
                    xpath: "//Totales/${field}",
                    expected: originalValue,
                    actual: "(missing)"
            ])
        } else if (!originalValue && newValue) {
            discrepancies.add([
                    xpath: "//Totales/${field}",
                    expected: "(missing)",
                    actual: newValue
            ])
        }
    }

    // 4.4 Compare Emisor
    List<String> emisorFields = ["RUTEmisor", "RznSoc", "DirOrigen", "CmnaOrigen"]

    for (String field : emisorFields) {
        String originalValue = xpath.evaluate("//Emisor/${field}", originalDoc)
        String newValue = xpath.evaluate("//Emisor/${field}", newDoc)

        if (originalValue != newValue) {
            discrepancies.add([
                    xpath: "//Emisor/${field}",
                    expected: originalValue,
                    actual: newValue
            ])
        }
    }

    // 4.5 Compare Receptor
    //List<String> receptorFields = ["RUTRecep", "RznSocRecep", "DirRecep", "CmnaRecep"]
    List<String> receptorFields = ["RUTRecep", "RznSocRecep"]

    for (String field : receptorFields) {
        String originalValue = xpath.evaluate("//Receptor/${field}", originalDoc)
        String newValue = xpath.evaluate("//Receptor/${field}", newDoc)

        if (originalValue != newValue) {
            discrepancies.add([
                    xpath: "//Receptor/${field}",
                    expected: originalValue,
                    actual: newValue
            ])
        }
    }

    // 4.6 Compare IdDoc (excluding Folio as it will differ)
    List<String> idDocFields = ["TipoDTE", "FchEmis"]

    for (String field : idDocFields) {
        String originalValue = xpath.evaluate("//IdDoc/${field}", originalDoc)
        String newValue = xpath.evaluate("//IdDoc/${field}", newDoc)

        if (originalValue != newValue) {
            discrepancies.add([
                    xpath: "//IdDoc/${field}",
                    expected: originalValue,
                    actual: newValue
            ])
        }
    }

    // ===== Phase 5: Cleanup - Delete Generated DTE =====

    ec.service.sync()
            .name("mchile.sii.dte.DteGenerationServices.delete#Dte")
            .parameters([fiscalTaxDocumentId: generatedFtdId])
            .call()

    // Clear any errors from cleanup operation - we don't want cleanup errors to affect validation results
    if (ec.message.hasError()) {
        ec.logger.warn("Error during cleanup of generated DTE ${generatedFtdId}: ${ec.message.errorsString}")
        ec.message.clearErrors()
    }

    // ===== Phase 6: Return Results =====

    validationPassed = discrepancies.isEmpty() && !generationError

} catch (Exception e) {
    ec.logger.error("Error during DTE validation: ${e.message}", e)
    generationError = "Validation error: ${e.message}"
    validationPassed = false
}

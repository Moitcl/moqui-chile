import org.moqui.context.ExecutionContext

ExecutionContext ec = context.ec

knownOuterLines = ['INDICACIONES GENERALES:', 'Se debe adjuntar ejemplar tributario y cedible de los documentos: Factura Electrónica, Factura No Afecta o Exenta Electrónica, Guía de Despacho Electrónica y Factura de Compra Electrónica.',
                   'Para consultar los datos del contribuyente, como el giro, razón social, direcciones y sucursales, Dirección Regional o Unidad debe ingresar en la opción "Mi SII" de la página del SII.',
                   'Además se les recomienda no utilizar abreviaciones en los giros y no agregar textos que informen arreglos de contrato con los clientes.']

BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(setPruebasTxt.bytes)))
String section = "preambulo"
String tipoSubsection
String casoActual
Long numeroAtencion = null
String line = ""
int lineNumber = 0
tipoDocumentoMap = ['FACTURA ELECTRONICA':'Ftdt-33', 'FACTURA  ELECTRONICA':'Ftdt-33', 'NOTA DE CREDITO ELECTRONICA':'Ftdt-61', 'GUIA DE DESPACHO':'Ftdt-52',
                    'FACTURA NO AFECTA O EXENTA ELECTRONICA':'Ftdt-34', 'NOTA DE DEBITO ELECTRONICA':'Ftdt-56', 'FACTURA':'Ftdt-30', 'NOTA DE CREDITO':'Ftdt-60',
                    'FACTURA DE COMPRA':'Ftdt-45', 'FACTURA DE COMPRA ELECTRONICA':'Ftdt-46', 'FACTURA DE EXPORTACION ELECTRONICA':'Ftdt-110',
                    'NOTA DE CREDITO DE EXPORTACION ELECTRONICA':'Ftdt-112', 'NOTA DE DEBITO DE EXPORTACION ELECTRONICA':'Ftdt-111']
unprocessedLines = []
unprocessedLineNumber = 0
setList = []
currentSet = null
etapaSubset = null
line = reader.readLine()
while (line != null) {
    lineNumber++
    def inicioSubset = line =~ /^SET ([A-Z ()1-2]+) - NUMERO DE ATENCI[OÓ]N: ([0-9]+)/
    if (inicioSubset.matches()) {
        itemFields = null
        currentLibroVentasExpectedLine = null
        numeroAtencion = Long.parseLong(inicioSubset[0][2])
        tipoSubset = inicioSubset[0][1]
        currentSet = [numeroAtencion:numeroAtencion, tipo:tipoSubset, unprocessedLines:[]]
        setList.add(currentSet)
    } else  if (tipoSubset == "BASICO DOCUMENTOS DE EXPORTACION (1)") {
        if (currentSet.documents == null) {
            currentSet.documents = []
            currentDocument = null
        }
        def inicioCaso = line =~ /^CASO $numeroAtencion-([0-9]+)/
        def detalleInternacional = line =~ /^([A-Z()* ]+):\s+ ([0-9.A-Z, ])$/
        if (inicioCaso.matches()) {
            itemFields = null
            seqNum = inicioCaso[0][1]
            line = reader.readLine()
            lineNumber++
            if (line =~ /^=+$/) {
                line = reader.readLine()
                lineNumber++
            } else {
                ec.message.addError("At line number ${lineNumber} expected '==============', got: ${line}")
            }
            docType = line =~ /^DOCUMENTO\s+([A-Z ]+)$/
            if (docType.matches()) {
                fiscalTaxDocumentTypeEnumId = tipoDocumentoMap[docType[0][1]]
                if (!fiscalTaxDocumentTypeEnumId)
                    ec.message.addError("Línea ${lineNumber}: No se encuentra tipo de documento para ${docType[0][1]}")
            } else {
                ec.message.addError("At line number ${lineNumber} expected 'DOCUMENTO', got: ${line}")
                fiscalTaxDocumentTypeEnumId = null
            }
            currentDocument = [seqNum: seqNum, fiscalTaxDocumentTypeEnumId: fiscalTaxDocumentTypeEnumId]
            currentSet.documents.add(currentDocument)
        } else if (line?.startsWith("ITEM\s")) {
            itemFields = line.substring(32).split("\t+")
            currentDocument.items = []
        } else if (detalleInternacional.matches()) {
            campo = detalleInternacional[0][1]
            valor = detalleInternacional[0][2]
            if (campo in ['MONEDA DE LA OPERACION', 'FORMA DE PAGO EXPORTACION', 'MODALIDAD DE VENTA', 'CLAUSULA DE VENTA DE EXPORTACION', 'TOTAL CLAUSULA DE VENTA',
                          'VIA DE TRANSPORTE', 'PUERTO DE EMBARQUE', 'PUERTO DE DESEMBARQUE', 'UNIDAD DE MEDIDA DE TARA', 'UNIDAD PESO BRUTO', 'UNIDAD PESO NETO',
                          'TIPO DE BULTO', 'TOTAL BULTOS', 'FLETE (**)', 'SEGURO (**)', 'PAIS RECEPTOR Y PAIS DESTINO']) {
                if (currentSet.internationalSpec == null)
                    currentSet.internationalSpec = [:]
                currentSet.internationalSpec[campo] = valor
            } else {
                currentSet.unprocessedLines.add("${lineNumber}: ${line}")
                unprocessedLineNumber++
            }
        } else if (line =~ /^-+$/ || line == null || line =~ /^$/) {
            itemFields = null
        } else if (itemFields != null && !(line =~ /^$/) ) {
            values = line.substring(32).split("\t+")
            if (itemFields.size() != values.size())
                ec.message.addError("line ${lineNumber}: Field amount mismatch, header has ${itemFields.size()}, line has ${values.size()}")
            itemMap = [:]
            for (int i = 0; i < itemFields.size(); i++) {
                if (itemFields[i] == "ITEM") {
                    if (values[i].endsWith(" AFECTO"))
                        itemMap.tipoTributario = "AFECTO"
                    else if (values[i].endsWith(" EXENTO"))
                        itemMap.tipoTributario = "EXENTO"
                }
                itemMap[itemFields[i]] = values[i].trim()
            }
            currentDocument.items.add(itemMap)
        }
    } else  if (tipoSubset in ["BASICO", "FACTURA EXENTA", "GUIA DE DESPACHO"]) {
        if (currentSet.documents == null) {
            currentSet.documents = []
            currentDocument = null
        }
        def inicioCaso = line =~ /^CASO $numeroAtencion-([0-9]+)/
        def referencia = line =~ /^REFERENCIA\s+([A-Z ]+) CORRESPONDIENTE A CASO ([0-9]+)-([0-9]+)$/
        def descuentoGlobalAfectosMatch = line =~ /^DESCUENTO GLOBAL ITEMES AFECTOS\s+([0-9]+%)/
        def motivoMatch = line =~ /^MOTIVO:\s*([A-Z ]+)$/
        def trasladoMatch = line =~ /^TRASLADO POR:\s*([A-Z ]+)$/
        def indicacionMatch = line =~ /^(INDICACIÓN|IMPORTANTE):\s*(.*)\s*$/
        if (inicioCaso.matches()) {
            itemFields = null
            seqNum = inicioCaso[0][1]
            line = reader.readLine()
            lineNumber++
            if (line =~ /^=+$/) {
                line = reader.readLine()
                lineNumber++
            } else {
                ec.message.addError("At line number ${lineNumber} expected '==============', got: ${line}")
            }
            docType = line =~ /^DOCUMENTO\s+([A-Z ]+)$/
            if (docType.matches()) {
                fiscalTaxDocumentTypeEnumId = tipoDocumentoMap[docType[0][1]]
                if (!fiscalTaxDocumentTypeEnumId)
                    ec.message.addError("Línea ${lineNumber}: No se encuentra tipo de documento para ${docType[0][1]}")
            } else {
                ec.message.addError("At line number ${lineNumber} expected 'DOCUMENTO', got: ${line}")
                fiscalTaxDocumentTypeEnumId = null
            }
            currentDocument = [seqNum:seqNum, fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId]
            currentSet.documents.add(currentDocument)
        } else if (referencia.matches()) {
            String fiscalTaxDocumentTypeEnumId = tipoDocumentoMap[referencia[0][1]]
            Long numeroAtencionReferencia = Long.parseLong(referencia[0][2])
            String seqNumReferencia = referencia[0][3]
            if (!fiscalTaxDocumentTypeEnumId)
                ec.message.addError("Línea ${lineNumber}: No se encuentra tipo de documento para ${referencia[0][1]}")
            if (numeroAtencionReferencia != numeroAtencion)
                ec.message.addError("Mismatch numeroAtencion: current is ${numeroAtencion}, reference has ${numeroAtencionReferencia}")
            if (currentDocument.referencias == null)
                currentDocument.referencias = []
            line = reader.readLine()
            lineNumber++
            razonReferenciaMatch = line =~ /^RAZON REFERENCIA\t([A-Z ]+)$/
            if (razonReferenciaMatch.matches()) {
                razonReferencia = razonReferenciaMatch[0][1]
            } else {
                ec.message.addError("At line number ${lineNumber} expected 'RAZON REFERENCIA', got: '${line}'")
                razonReferencia = null
            }
            currentDocument.referencias.add([seqNumReferencia:seqNumReferencia, fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, razonReferencia:razonReferencia])
        } else if (descuentoGlobalAfectosMatch.matches()) {
            if (currentDocument.descuentoGlobalAfecto != null)
                ec.message.addError("Duplicated descuento global afecto, had ${currentDocument.descuentoGlobalAfecto}, received ${descuentoGlobalAfectosMatch[0][1]}")
            currentDocument.descuentoGlobalAfecto = descuentoGlobalAfectosMatch[0][1]
        } else if (line?.startsWith("ITEM\t")) {
            itemFields = line.split("\t+")
            currentDocument.items = []
        } else if (line =~ /^-+$/ || line == null) {
            itemFields = null
        } else if (itemFields != null && !(line =~ /^$/) ) {
            values = line.split("\t+")
            if (itemFields.size() != values.size())
                ec.message.addError("line ${lineNumber}: Field amount mismatch, header has ${itemFields.size()}, line has ${values.size()}")
            itemMap = [:]
            for (int i = 0; i < itemFields.size(); i++) {
                if (itemFields[i] == "ITEM") {
                    if (values[i].endsWith(" AFECTO"))
                        itemMap.tipoTributario = "AFECTO"
                    else if (values[i].endsWith(" EXENTO"))
                        itemMap.tipoTributario = "EXENTO"
                }
                itemMap[itemFields[i]] = values[i].trim()
            }
            currentDocument.items.add(itemMap)
        } else if (motivoMatch.matches()) {
            currentDocument.motivo = motivoMatch[0][1]
        } else if (trasladoMatch.matches()) {
            currentDocument.trasladoPor = trasladoMatch[0][1]
        } else if (indicacionMatch.matches()) {
            if (currentDocument == null) {
                if (currentSet.indicaciones == null)
                    currentSet.indicaciones = []
                currentSet.indicaciones.add(indicacionMatch[0][2])
            } else {
                if (currentDocument.indicaciones == null)
                    currentDocument.indicaciones = []
                currentDocument.indicaciones.add(indicacionMatch[0][2])
            }
        } else if (!(line =~ /^$/) ) {
            currentSet.unprocessedLines.add("${lineNumber}: ${line}")
            unprocessedLineNumber++
        }
    } else if (tipoSubset == "LIBRO DE COMPRAS") {
        expectedLines = ['==========================================================================', 'TIPO DOCUMENTO\t\t\t\tFOLIO', 'OBSERVACIONES', 'MONTO EXENTO\tMONTO AFECTO', '==========================================================================']
        if (currentSet.items == null) {
            currentSet.items = []
            currentLibroComprasExpectedLine = 0
            libroDeComprasPhase = "itemes"
        }
        if (line =~ /^\s*$/) {

        } else if (currentLibroComprasExpectedLine < expectedLines.size()) {
            if (line.trim() == expectedLines[currentLibroComprasExpectedLine])
                currentLibroComprasExpectedLine++
            else
                ec.message.addError("Unexpected line at ${lineNumber}\n    received:\n${line.trim()}\n    expected: ${expectedLines[currentLibroComprasExpectedLine]}")
        } else if (line =~ /^=+$/) {
            // Fin de la sección
            libroDeComprasPhase = "comentariosPost"
            currentSet.observaciones = []
        } else if (libroDeComprasPhase == "itemes") {
            // Líneas vienen de a 3
            documentoMatcher =  line =~ /^([A-Z ]+) *\t+ *([0-9]+) *$/
            if (documentoMatcher.matches()) {
                tipoDocumento = documentoMatcher[0][1].trim()
                folio = documentoMatcher[0][2]
            } else
                ec.message.addError("Unexpected line at ${lineNumber}, expected tipoDocumento\\tfolio, got: ${line}")
            line = reader.readLine()
            lineNumber++
            descripcion = line.trim()
            line = reader.readLine()
            lineNumber++
            montoMatcher = line =~ /^ *([0-9]*)\t+ *([0-9]*) *$/
            if (montoMatcher.matches()) {
                montoAfecto = montoMatcher[0][1]?:null
                montoExento = montoMatcher[0][2]?:null
            } else
                ec.message.addError("Unexpected line at ${lineNumber}, expected montoExento\\tmontoAfecto, got: ${line}")
            fiscalTaxDocumentTypeEnumId = tipoDocumentoMap[tipoDocumento]
            if (fiscalTaxDocumentTypeEnumId == null)
                ec.message.addError("Did not find enumId for type '${tipoDocumento}'")
            currentSet.items.add([fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, folio:folio, descripcion:descripcion, montoExento:montoExento, montoAfecto:montoAfecto])
        } else if (libroDeComprasPhase == "comentariosPost") {
            currentSet.observaciones.add(line.trim())
        } else {
            currentSet.unprocessedLines.add("${lineNumber}: ${line}")
            unprocessedLineNumber++
        }
    } else if (tipoSubset == "LIBRO DE VENTAS") {
        expectedLines = ['CONSTRUYA EL LIBRO DE VENTAS CON LOS DOCUMENTOS CON QUE GENERO', 'EL SET BASICO O EL SET DE FACTURA EXENTA, SEGUN CORRESPONDA.', 'SI OBTUVO AMBOS SET, UTILICE LOS DOCUMENTOS DEL SET BASICO PARA', 'CONSTRUIR EL LIBRO DE VENTAS.']
        if (currentLibroVentasExpectedLine == null)
            currentLibroVentasExpectedLine = 0
        if (line =~ /^-*$/) {
        } else if (currentLibroVentasExpectedLine < expectedLines.size() && line.trim() == expectedLines[currentLibroVentasExpectedLine]) {
            currentLibroVentasExpectedLine++
        } else if (currentLibroVentasExpectedLine < expectedLines.size()) {
            ec.message.addError("Unexpected line at ${lineNumber}\n    received:\n${line.trim()}\n    expected: ${expectedLines[currentLibroVentasExpectedLine]}")
        } else {
            currentSet.unprocessedLines.add("${lineNumber}: ${line}")
            unprocessedLineNumber++
        }
    } else if (tipoSubset == "LIBRO DE GUIAS") {
        expectedLines = ['CONSTRUYA EL LIBRO CON LAS GUIAS CON QUE GENERO EL SET GUIA DE DESPACHO,', 'TENIENDO EN CUENTA LAS SIGUIENTES CONSIDERACIONES', '- EL CASO 2 CORRESPONDE A UNA GUIA QUE SE FACTURO EN EL PERIODO', '- EL CASO 3 CORRESPONDE A UNA GUIA ANULADA']
        if (currentLibroGuiasExpectedLine == null)
            currentLibroGuiasExpectedLine = 0
        if (line =~ /^-*$/) {
        } else if (currentLibroGuiasExpectedLine < expectedLines.size() && line.trim() == expectedLines[currentLibroGuiasExpectedLine]) {
            currentLibroGuiasExpectedLine++
        } else if (currentLibroGuiasExpectedLine < expectedLines.size()) {
            ec.message.addError("Unexpected line at ${lineNumber}\n    received:\n${line.trim()}\n    expected: ${expectedLines[currentLibroGuiasExpectedLine]}")
        } else {
            currentSet.unprocessedLines.add("${lineNumber}: ${line}")
            unprocessedLineNumber++
        }
    } else if (!(line =~ /^$/) ) {
        if (! line.trim() in knownOuterLines) {
            unprocessedLines.add("${lineNumber}: ${line}")
            unprocessedLineNumber++
        }
    }
    if (currentSet != null)
        currentSet.expectedLines = expectedLines
    line = reader.readLine()
}

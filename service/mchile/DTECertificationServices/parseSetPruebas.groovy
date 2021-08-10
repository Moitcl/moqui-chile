import org.moqui.context.ExecutionContext

ExecutionContext ec = context.ec

BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(setPruebasTxt.bytes)))
String section = "preambulo"
String tipoSubsection
String casoActual
Long numeroAtencion = null
String line = ""
int lineNumber = 0
tipoDocumentoMap = ['FACTURA ELECTRONICA':'Ftdt-33', 'FACTURA  ELECTRONICA':'Ftdt-33', 'NOTA DE CREDITO ELECTRONICA':'Ftdt-61', 'GUIA DE DESPACHO':'Ftdt-52', 'FACTURA NO AFECTA O EXENTA ELECTRONICA':'Ftdt-34', 'NOTA DE DEBITO ELECTRONICA':'Ftdt-56']
setList = []
currentSet = null
while (line != null) {
    line = reader.readLine()
    lineNumber++
    def inicioSubset = line =~ /^SET ([A-Z ]+) - NUMERO DE ATENCI[OÓ]N: ([0-9]+)/
    if (inicioSubset.matches()) {
        itemFields = null
        numeroAtencion = Long.parseLong(inicioSubset[0][2])
        tipoSubset = inicioSubset[0][1]
        currentSet = [numeroAtencion:numeroAtencion, tipo:tipoSubset]
        setList.add(currentSet)
        continue
    }
    if (tipoSubset in ["BASICO", "FACTURA EXENTA", "GUIA DE DESPACHO"]) {
        if (currentSet.documents == null)
            currentSet.documents = []
        def inicioCaso = line =~ /^CASO $numeroAtencion-([0-9]+)/
        if (inicioCaso.matches()) {
            itemFields = null
            seqNum = [id:inicioCaso[0][1]]
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
            continue
        }
        def referencia = line =~ /^REFERENCIA\s+([A-Z ]+) CORRESPONDIENTE A CASO ([0-9]+)-([0-9]+)$/
        if (referencia.matches()) {
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
            if (razonReferenciaMatch.matches())
                razonReferencia = razonReferenciaMatch[0][1]
            else {
                ec.message.addError("At line number ${lineNumber} expected 'RAZON REFERENCIA', got: '${line}'")
                razonReferencia = null
            }
            currentDocument.referencias.add([seqNumReferencia:seqNumReferencia, fiscalTaxDocumentTypeEnumId:fiscalTaxDocumentTypeEnumId, razonReferencia:razonReferencia])
        }
        if (line?.startsWith("ITEM\t")) {
            itemFields = line.split("\t+")
            currentDocument.items = []
        } else if (line =~ /^$/ || line =~ /^-+$/ || line == null) {
            itemFields = null
        } else if (itemFields != null) {
            values = line.split("\t+")
            if (itemFields.size() != values.size())
                ec.message.addError("line ${lineNumber}: Field amount mismatch, header has ${itemFields.size()}, line has ${values.size()}")
            itemMap = [:]
            for (int i = 0; i < itemFields.size(); i++)
                itemMap[itemFields[i]] = values[i]
            currentDocument.items.add(itemMap)
        }
        continue
    }
    if (tipoSubset == "LIBRO DE COMPRAS") {
    }
    if (tipoSubset == "LIBRO DE VENTAS") {
    }
    if (tipoSubset == "LIBRO DE GUIAS") {
    }
}

ec.message.addMessage("setList: ${setList}")
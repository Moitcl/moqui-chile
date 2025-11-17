package EmailProcessing

import org.moqui.context.ExecutionContext
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Comprehensive test utilities for Email Processing Integration Tests.
 * Provides mock utilities, test data generators, and helper methods for:
 * - Job run context mocking and simulation
 * - Email message and attachment creation
 * - DTE envio test data generation
 * - Test cleanup and isolation utilities
 */
class EmailProcessingTestUtilities {
    
    // ========================================
    // Static Utility References
    // ========================================
    
    private static final Map<String, Object> mockJobContext = new ConcurrentHashMap<>()
    private static final JsonSlurper jsonSlurper = new JsonSlurper()
    private static final JsonBuilder jsonBuilder = new JsonBuilder()
    
    // ========================================
    // Job Run Context Mocking Infrastructure
    // ========================================
    
    /**
     * Create mock ServiceJobRun entity for testing job context capture
     */
    static Map<String, Object> createMockJobRun(ExecutionContext ec, Map<String, Object> params = [:]) {
        String jobRunId = params.jobRunId ?: "TEST-JOB-${UUID.randomUUID().toString().substring(0, 8)}"
        String jobName = params.jobName ?: "TEST-EMAIL-DTE-PROCESSING"
        String serviceName = params.serviceName ?: "mchile.dte.EmailProcessingServices.process#EmailDteAttachments"
        String statusId = params.statusId ?: "SjsRunning"
        String runBy = params.runBy ?: "system"
        String runThread = params.runThread ?: "EmailProcessing-Worker-Test"
        
        // Create ServiceJobRun using auto-service to ensure proper validation
        Map createResult = ec.service.sync().name("create#moqui.service.job.ServiceJobRun")
                .parameters([
                    jobRunId: jobRunId,
                    jobName: jobName,
                    serviceName: serviceName,
                    runStartTime: params.runStartTime ?: ec.user.nowTimestamp,
                    runEndTime: params.runEndTime,
                    statusId: statusId,
                    hasError: params.hasError ?: "N",
                    runThread: runThread,
                    runBy: runBy,
                    results: params.results ?: '{"status":"initialized","progress":"0%"}'
                ]).call()
        
        if (createResult.errors) {
            throw new RuntimeException("Failed to create mock job run: ${createResult.errors}")
        }
        
        // Store in mock context for later retrieval
        mockJobContext.put(jobRunId, [
            jobRunId: jobRunId,
            jobName: jobName,
            serviceName: serviceName,
            statusId: statusId,
            runBy: runBy,
            runThread: runThread,
            startTime: params.runStartTime ?: ec.user.nowTimestamp,
            mockData: params.mockData ?: [:]
        ])
        
        return [
            jobRunId: jobRunId,
            jobName: jobName,
            serviceName: serviceName,
            statusId: statusId,
            success: true
        ]
    }
    
    /**
     * Update mock job run status and results
     */
    static Map<String, Object> updateMockJobRunStatus(ExecutionContext ec, String jobRunId, 
                                                       String newStatusId, Map<String, Object> results = [:]) {
        // Update using auto-service
        Map updateResult = ec.service.sync().name("update#moqui.service.job.ServiceJobRun")
                .parameters([
                    jobRunId: jobRunId,
                    statusId: newStatusId,
                    runEndTime: (newStatusId in ['SjsComplete', 'SjsError', 'SjsCancelled']) ? ec.user.nowTimestamp : null,
                    hasError: (newStatusId == 'SjsError') ? "Y" : "N",
                    results: results ? new JsonBuilder(results).toString() : null
                ]).call()
        
        if (updateResult.errors) {
            throw new RuntimeException("Failed to update mock job run: ${updateResult.errors}")
        }
        
        // Update mock context
        if (mockJobContext.containsKey(jobRunId)) {
            mockJobContext.get(jobRunId).statusId = newStatusId
            mockJobContext.get(jobRunId).lastUpdate = ec.user.nowTimestamp
            mockJobContext.get(jobRunId).results = results
        }
        
        return [
            jobRunId: jobRunId,
            statusId: newStatusId,
            success: true
        ]
    }
    
    /**
     * Get mock job run context for testing
     */
    static Map<String, Object> getMockJobRunContext(String jobRunId) {
        return mockJobContext.get(jobRunId, [:])
    }
    
    /**
     * Simulate job run progress updates
     */
    static void simulateJobProgress(ExecutionContext ec, String jobRunId, 
                                    List<String> progressSteps, long delayMs = 100) {
        progressSteps.eachWithIndex { step, index ->
            if (delayMs > 0) {
                Thread.sleep(delayMs)
            }
            
            Map progressResults = [
                status: "processing",
                currentStep: step,
                progress: "${((index + 1) * 100 / progressSteps.size()).intValue()}%",
                completedSteps: index + 1,
                totalSteps: progressSteps.size(),
                timestamp: ec.user.nowTimestamp.toString()
            ]
            
            updateMockJobRunStatus(ec, jobRunId, "SjsRunning", progressResults)
        }
        
        // Mark as complete
        updateMockJobRunStatus(ec, jobRunId, "SjsComplete", [
            status: "completed",
            progress: "100%",
            completedSteps: progressSteps.size(),
            totalSteps: progressSteps.size(),
            completionTime: ec.user.nowTimestamp.toString()
        ])
    }
    
    // ========================================
    // Email Message and Attachment Utilities
    // ========================================
    
    /**
     * Create comprehensive test email message with attachments
     */
    static Map<String, Object> createTestEmailWithAttachments(ExecutionContext ec, Map<String, Object> params = [:]) {
        String emailMessageId = params.emailMessageId ?: "TEST-EMAIL-${UUID.randomUUID().toString().substring(0, 8)}"
        String subject = params.subject ?: "Test DTE Email - ${emailMessageId}"
        String fromAddress = params.fromAddress ?: "test-sender@moquichile.cl"
        String toAddresses = params.toAddresses ?: "test-receiver@moquichile.cl"
        String body = params.body ?: "Test email body with DTE attachments for integration testing."
        
        // Create email message using auto-service
        Map emailResult = ec.service.sync().name("create#moqui.basic.email.EmailMessage")
                .parameters([
                    emailMessageId: emailMessageId,
                    statusId: params.statusId ?: "EsReceived",
                    subject: subject,
                    fromAddress: fromAddress,
                    toAddresses: toAddresses,
                    ccAddresses: params.ccAddresses ?: "",
                    bccAddresses: params.bccAddresses ?: "",
                    receivedDate: params.receivedDate ?: ec.user.nowTimestamp,
                    sentDate: params.sentDate ?: ec.user.nowTimestamp,
                    body: body,
                    contentType: params.contentType ?: "text/plain",
                    emailServerId: params.emailServerId ?: "TEST-EMAIL-SERVER",
                    messageId: params.messageId ?: "<${emailMessageId}@test.moquichile.cl>",
                    inReplyTo: params.inReplyTo ?: "",
                    references: params.references ?: "",
                    fromUserId: params.fromUserId ?: "system"
                ]).call()
        
        if (emailResult.errors) {
            throw new RuntimeException("Failed to create test email: ${emailResult.errors}")
        }
        
        // Create attachments if specified
        List<Map> attachments = []
        if (params.attachments) {
            params.attachments.each { attachmentData ->
                Map attachment = createEmailAttachment(ec, emailMessageId, attachmentData)
                attachments.add(attachment)
            }
        }
        
        return [
            emailMessageId: emailMessageId,
            subject: subject,
            fromAddress: fromAddress,
            toAddresses: toAddresses,
            body: body,
            attachments: attachments,
            success: true
        ]
    }
    
    /**
     * Create email attachment with DTE XML content
     */
    static Map<String, Object> createEmailAttachment(ExecutionContext ec, String emailMessageId, Map<String, Object> attachmentData) {
        String attachmentName = attachmentData.attachmentName ?: "DTE_Test_${UUID.randomUUID().toString().substring(0, 8)}.xml"
        String contentType = attachmentData.contentType ?: "text/xml"
        String xmlContent = attachmentData.attachmentData ?: generateTestDteXml(attachmentData)
        
        // Create attachment using auto-service
        Map attachmentResult = ec.service.sync().name("create#moqui.basic.email.EmailTemplateAttachment")
                .parameters([
                    emailMessageId: emailMessageId,
                    attachmentName: attachmentName,
                    contentType: contentType,
                    attachmentData: xmlContent
                ]).call()
        
        if (attachmentResult.errors) {
            throw new RuntimeException("Failed to create email attachment: ${attachmentResult.errors}")
        }
        
        return [
            emailMessageId: emailMessageId,
            attachmentName: attachmentName,
            contentType: contentType,
            attachmentSize: xmlContent.length(),
            success: true
        ]
    }
    
    /**
     * Generate test DTE XML content with customizable parameters
     */
    static String generateTestDteXml(Map<String, Object> params = [:]) {
        String tipoDte = params.tipoDte ?: "33"
        String folio = params.folio ?: "${(Math.random() * 99999).intValue()}"
        String fechaEmision = params.fechaEmision ?: "2024-09-03"
        String rutEmisor = params.rutEmisor ?: "76514104-4"
        String razonSocialEmisor = params.razonSocialEmisor ?: "Empresa Test S.A."
        String rutReceptor = params.rutReceptor ?: "13477571-8"
        String razonSocialReceptor = params.razonSocialReceptor ?: "Cliente Test Ltda."
        String montoTotal = params.montoTotal ?: "${(Math.random() * 1000000).intValue()}"
        
        String documentId = params.documentId ?: "DTE-${tipoDte}-${folio}"
        
        // Generate XML with proper escaping for XML attributes
        return """<?xml version="1.0" encoding="ISO-8859-1"?>
<DTE version="1.0">
    <Documento ID="${documentId}">
        <Encabezado>
            <IdDoc>
                <TipoDTE>${tipoDte}</TipoDTE>
                <Folio>${folio}</Folio>
                <FchEmis>${fechaEmision}</FchEmis>
            </IdDoc>
            <Emisor>
                <RUTEmisor>${rutEmisor}</RUTEmisor>
                <RznSoc>${razonSocialEmisor}</RznSoc>
            </Emisor>
            <Receptor>
                <RUTRecep>${rutReceptor}</RUTRecep>
                <RznSocRecep>${razonSocialReceptor}</RznSocRecep>
            </Receptor>
            <Totales>
                <MntTotal>${montoTotal}</MntTotal>
            </Totales>
        </Encabezado>
    </Documento>
</DTE>"""
    }
    
    /**
     * Generate malformed DTE XML for error testing
     */
    static String generateMalformedDteXml(String errorType = "parsing") {
        switch (errorType) {
            case "parsing":
                return """<?xml version="1.0" encoding="ISO-8859-1"?>
<DTE version="1.0">
    <Documento ID="INVALID">
        <Encabezado>
            <IdDoc>
                <TipoDTE>INVALID</TipoDTE>
                <Folio>INVALID</Folio>
                <!-- Missing closing tag intentionally -->
            </IdDoc>
        <Emisor>
            <RUTEmisor>INVALID-RUT</RUTEmisor>
        </Emisor>
        <!-- Missing closing Documento tag -->
    </DTE>"""
            
            case "validation":
                return """<?xml version="1.0" encoding="ISO-8859-1"?>
<DTE version="1.0">
    <Documento ID="VALIDATION-ERROR">
        <Encabezado>
            <IdDoc>
                <TipoDTE>999</TipoDTE>
                <Folio>-1</Folio>
                <FchEmis>INVALID-DATE</FchEmis>
            </IdDoc>
            <Emisor>
                <RUTEmisor>INVALID-RUT-FORMAT</RUTEmisor>
                <RznSoc></RznSoc>
            </Emisor>
            <Receptor>
                <RUTRecep>ALSO-INVALID</RUTRecep>
                <RznSocRecep></RznSocRecep>
            </Receptor>
            <Totales>
                <MntTotal>-1000</MntTotal>
            </Totales>
        </Encabezado>
    </Documento>
</DTE>"""
            
            case "structure":
                return """<?xml version="1.0" encoding="ISO-8859-1"?>
<INVALID_ROOT>
    <WrongStructure>
        <NotADTE>This is not a valid DTE structure</NotADTE>
    </WrongStructure>
</INVALID_ROOT>"""
            
            default:
                return "<MALFORMED>Invalid XML</MALFORMED"
        }
    }
    
    // ========================================
    // DTE Envio Test Data Generation
    // ========================================
    
    /**
     * Create test DteEnvio entity with realistic data
     */
    static Map<String, Object> createTestDteEnvio(ExecutionContext ec, Map<String, Object> params = [:]) {
        String envioId = params.envioId ?: "TEST-ENVIO-${UUID.randomUUID().toString().substring(0, 8)}"
        String issuerPartyId = params.issuerPartyId ?: "TEST-EMAIL-PROC-ORG"
        String receiverPartyId = params.receiverPartyId ?: "TEST-EMAIL-RECV-ORG"
        String documentType = params.fiscalTaxDocumentTypeEnumId ?: "Ftdt-33"
        String documentNumber = params.documentNumber ?: "${(Math.random() * 99999).intValue()}"
        String issueDate = params.issueDate ?: "2024-09-03"
        String totalAmount = params.totalAmount ?: "${(Math.random() * 1000000).intValue()}"
        String statusId = params.statusId ?: "Ftde-Received"
        
        // Generate XML content if not provided
        String xmlContent = params.xmlContent ?: generateTestDteXml([
            tipoDte: documentType.replace("Ftdt-", ""),
            folio: documentNumber,
            fechaEmision: issueDate,
            montoTotal: totalAmount,
            rutEmisor: params.rutEmisor ?: "76514104-4",
            razonSocialEmisor: params.razonSocialEmisor ?: "Test Issuer Organization",
            rutReceptor: params.rutReceptor ?: "13477571-8",
            razonSocialReceptor: params.razonSocialReceptor ?: "Test Receiver Organization"
        ])
        
        // Create DteEnvio using auto-service
        Map envioResult = ec.service.sync().name("create#mchile.dte.DteEnvio")
                .parameters([
                    envioId: envioId,
                    issuerPartyId: issuerPartyId,
                    receiverPartyId: receiverPartyId,
                    fiscalTaxDocumentTypeEnumId: documentType,
                    documentNumber: documentNumber,
                    issueDate: issueDate,
                    totalAmount: totalAmount,
                    currencyUomId: params.currencyUomId ?: "CLP",
                    statusId: statusId,
                    receivedDate: params.receivedDate ?: ec.user.nowTimestamp,
                    xmlContent: xmlContent,
                    trackNumber: params.trackNumber ?: "TRK-${envioId}",
                    receptionAck: params.receptionAck ?: "Y"
                ]).call()
        
        if (envioResult.errors) {
            throw new RuntimeException("Failed to create test DTE envio: ${envioResult.errors}")
        }
        
        return [
            envioId: envioId,
            issuerPartyId: issuerPartyId,
            receiverPartyId: receiverPartyId,
            documentType: documentType,
            documentNumber: documentNumber,
            totalAmount: totalAmount,
            statusId: statusId,
            success: true
        ]
    }
    
    /**
     * Create multiple test envios for batch processing scenarios
     */
    static List<Map<String, Object>> createBatchTestEnvios(ExecutionContext ec, int count, Map<String, Object> baseParams = [:]) {
        List<Map<String, Object>> envios = []
        
        (1..count).each { index ->
            Map envioParams = baseParams.clone()
            envioParams.envioId = "TEST-BATCH-ENVIO-${String.format('%03d', index)}"
            envioParams.documentNumber = "${baseParams.documentNumber ?: '1000'}${String.format('%03d', index)}"
            envioParams.totalAmount = "${((Math.random() * 500000) + 50000).intValue()}"
            
            Map envio = createTestDteEnvio(ec, envioParams)
            envios.add(envio)
        }
        
        return envios
    }
    
    // ========================================
    // Test Scenario Builders
    // ========================================
    
    /**
     * Build complete email processing scenario with job context
     */
    static Map<String, Object> buildEmailProcessingScenario(ExecutionContext ec, String scenarioType, Map<String, Object> params = [:]) {
        switch (scenarioType) {
            case "multi-attachment":
                return buildMultiAttachmentScenario(ec, params)
            
            case "single-attachment":
                return buildSingleAttachmentScenario(ec, params)
                
            case "error-scenario":
                return buildErrorScenario(ec, params)
                
            case "batch-processing":
                return buildBatchProcessingScenario(ec, params)
                
            case "performance-test":
                return buildPerformanceTestScenario(ec, params)
                
            default:
                throw new IllegalArgumentException("Unknown scenario type: ${scenarioType}")
        }
    }
    
    private static Map<String, Object> buildMultiAttachmentScenario(ExecutionContext ec, Map<String, Object> params) {
        // Create job run
        Map jobRun = createMockJobRun(ec, [
            jobName: "TEST-MULTI-ATTACHMENT-JOB",
            statusId: "SjsRunning"
        ])
        
        // Create email with multiple attachments
        List attachments = [
            [
                attachmentName: "DTE_33_12345_20240903.xml",
                tipoDte: "33",
                folio: "12345",
                montoTotal: "250000"
            ],
            [
                attachmentName: "DTE_33_12346_20240903.xml",
                tipoDte: "33",
                folio: "12346",
                montoTotal: "180000"
            ]
        ]
        
        Map email = createTestEmailWithAttachments(ec, [
            subject: "Múltiples DTEs - Test Scenario",
            attachments: attachments
        ])
        
        // Create corresponding envios
        List envios = []
        attachments.each { attachment ->
            Map envio = createTestDteEnvio(ec, [
                documentNumber: attachment.folio,
                totalAmount: attachment.montoTotal,
                fiscalTaxDocumentTypeEnumId: "Ftdt-${attachment.tipoDte}"
            ])
            envios.add(envio)
        }
        
        return [
            scenarioType: "multi-attachment",
            jobRun: jobRun,
            email: email,
            envios: envios,
            attachmentCount: attachments.size(),
            success: true
        ]
    }
    
    private static Map<String, Object> buildSingleAttachmentScenario(ExecutionContext ec, Map<String, Object> params) {
        // Create job run
        Map jobRun = createMockJobRun(ec, [
            jobName: "TEST-SINGLE-ATTACHMENT-JOB",
            statusId: "SjsRunning"
        ])
        
        // Create email with single attachment
        List attachments = [
            [
                attachmentName: "BOLETA_39_98765_20240903.xml",
                tipoDte: "39",
                folio: "98765",
                montoTotal: "85990"
            ]
        ]
        
        Map email = createTestEmailWithAttachments(ec, [
            subject: "Boleta Electrónica - Test Scenario",
            attachments: attachments
        ])
        
        // Create corresponding envio
        Map envio = createTestDteEnvio(ec, [
            documentNumber: "98765",
            totalAmount: "85990",
            fiscalTaxDocumentTypeEnumId: "Ftdt-39"
        ])
        
        return [
            scenarioType: "single-attachment",
            jobRun: jobRun,
            email: email,
            envios: [envio],
            attachmentCount: 1,
            success: true
        ]
    }
    
    private static Map<String, Object> buildErrorScenario(ExecutionContext ec, Map<String, Object> params) {
        // Create job run that will have errors
        Map jobRun = createMockJobRun(ec, [
            jobName: "TEST-ERROR-SCENARIO-JOB",
            statusId: "SjsRunning"
        ])
        
        // Create email with malformed attachment
        List attachments = [
            [
                attachmentName: "DTE_INVALID_ERROR.xml",
                attachmentData: generateMalformedDteXml("parsing")
            ]
        ]
        
        Map email = createTestEmailWithAttachments(ec, [
            subject: "DTE con Errores - Test Error Scenario",
            fromAddress: "errors@test-validation.cl",
            attachments: attachments
        ])
        
        // Create failed envio
        Map envio = createTestDteEnvio(ec, [
            envioId: "TEST-ENVIO-ERROR-SCENARIO",
            documentNumber: "ERROR-DOC",
            totalAmount: "0",
            statusId: "Ftde-Failed",
            xmlContent: generateMalformedDteXml("parsing"),
            receptionAck: "N"
        ])
        
        // Update job to error state
        updateMockJobRunStatus(ec, jobRun.jobRunId, "SjsError", [
            error: "XML parsing failed for attachment",
            errorType: "parsing",
            attachmentName: "DTE_INVALID_ERROR.xml"
        ])
        
        return [
            scenarioType: "error-scenario",
            jobRun: jobRun,
            email: email,
            envios: [envio],
            errorType: "parsing",
            success: true
        ]
    }
    
    private static Map<String, Object> buildBatchProcessingScenario(ExecutionContext ec, Map<String, Object> params) {
        int batchSize = params.batchSize ?: 5
        
        // Create batch job run
        Map jobRun = createMockJobRun(ec, [
            jobName: "TEST-BATCH-PROCESSING-JOB",
            statusId: "SjsRunning",
            results: '{"batchSize":' + batchSize + ',"status":"processing"}'
        ])
        
        // Create multiple emails and envios
        List emails = []
        List allEnvios = []
        
        (1..batchSize).each { index ->
            List attachments = [
                [
                    attachmentName: "DTE_33_BATCH${String.format('%03d', index)}_20240903.xml",
                    tipoDte: "33",
                    folio: "BATCH${String.format('%03d', index)}",
                    montoTotal: "${((Math.random() * 500000) + 50000).intValue()}"
                ]
            ]
            
            Map email = createTestEmailWithAttachments(ec, [
                subject: "Lote DTE ${index}/${batchSize} - Batch Processing",
                attachments: attachments
            ])
            emails.add(email)
            
            Map envio = createTestDteEnvio(ec, [
                documentNumber: "BATCH${String.format('%03d', index)}",
                totalAmount: attachments[0].montoTotal,
                fiscalTaxDocumentTypeEnumId: "Ftdt-33"
            ])
            allEnvios.add(envio)
        }
        
        return [
            scenarioType: "batch-processing",
            jobRun: jobRun,
            emails: emails,
            envios: allEnvios,
            batchSize: batchSize,
            success: true
        ]
    }
    
    private static Map<String, Object> buildPerformanceTestScenario(ExecutionContext ec, Map<String, Object> params) {
        int attachmentCount = params.attachmentCount ?: 10
        
        // Create performance job run
        Map jobRun = createMockJobRun(ec, [
            jobName: "TEST-PERFORMANCE-JOB",
            statusId: "SjsRunning",
            results: '{"attachmentCount":' + attachmentCount + ',"status":"performance-testing"}'
        ])
        
        // Create email with many attachments
        List attachments = []
        (1..attachmentCount).each { index ->
            attachments.add([
                attachmentName: "DTE_33_PERF${String.format('%03d', index)}_20240903.xml",
                tipoDte: "33",
                folio: "PERF${String.format('%03d', index)}",
                montoTotal: "${((Math.random() * 1000000) + 100000).intValue()}"
            ])
        }
        
        Map email = createTestEmailWithAttachments(ec, [
            subject: "Prueba de Rendimiento - ${attachmentCount} DTEs",
            fromAddress: "performance@test-load.cl",
            body: "Email de prueba de rendimiento con ${attachmentCount} attachments para validar el comportamiento del sistema bajo carga.",
            attachments: attachments
        ])
        
        // Create corresponding envios
        List envios = createBatchTestEnvios(ec, attachmentCount, [
            documentNumber: "PERF",
            fiscalTaxDocumentTypeEnumId: "Ftdt-33"
        ])
        
        return [
            scenarioType: "performance-test",
            jobRun: jobRun,
            email: email,
            envios: envios,
            attachmentCount: attachmentCount,
            success: true
        ]
    }
    
    // ========================================
    // Test Cleanup and Isolation Utilities
    // ========================================
    
    /**
     * Clean up all test data created by these utilities
     */
    static void cleanupAllTestData(ExecutionContext ec) {
        // Clear mock context
        mockJobContext.clear()
        
        // Clean up test entities using proper cleanup patterns
        cleanupTestEmails(ec)
        cleanupTestEnvios(ec)
        cleanupTestJobRuns(ec)
        cleanupTestInfrastructure(ec)
    }
    
    /**
     * Clean up test email messages and attachments
     */
    static void cleanupTestEmails(ExecutionContext ec) {
        // Delete test attachments first (due to foreign key constraints)
        ec.entity.find("moqui.basic.email.EmailTemplateAttachment")
                .condition("emailMessageId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                .deleteAll()
        
        // Delete test email messages
        ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                .deleteAll()
    }
    
    /**
     * Clean up test DTE envios
     */
    static void cleanupTestEnvios(ExecutionContext ec) {
        ec.entity.find("mchile.dte.DteEnvio")
                .condition("envioId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                .deleteAll()
    }
    
    /**
     * Clean up test service job runs
     */
    static void cleanupTestJobRuns(ExecutionContext ec) {
        ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                .deleteAll()
    }
    
    /**
     * Clean up test infrastructure (organizations, users, etc.)
     */
    static void cleanupTestInfrastructure(ExecutionContext ec) {
        // Clean up test parties (this will cascade to related entities)
        List testPartyIds = ["TEST-EMAIL-PROC-ORG", "TEST-EMAIL-RECV-ORG", 
                            "TEST-ISSUER-MULTI-ORG", "TEST-RECEIVER-MULTI-ORG"]
        
        testPartyIds.each { partyId ->
            // Clean up party relationships first
            ec.entity.find("mantle.party.contact.PartyContactMech").condition("partyId", partyId).deleteAll()
            ec.entity.find("mantle.party.PartyIdentification").condition("partyId", partyId).deleteAll()
            ec.entity.find("mantle.party.PartyRole").condition("partyId", partyId).deleteAll()
            ec.entity.find("mchile.dte.PartyDteParams").condition("partyId", partyId).deleteAll()
            ec.entity.find("mchile.dte.PartyGiro").condition("partyId", partyId).deleteAll()
            ec.entity.find("mantle.party.Organization").condition("partyId", partyId).deleteAll()
            ec.entity.find("mantle.party.Party").condition("partyId", partyId).deleteAll()
        }
        
        // Clean up test users
        ec.entity.find("moqui.security.UserGroupMember").condition("userId", "test-email-processor").deleteAll()
        ec.entity.find("moqui.security.UserAccount").condition("userId", "test-email-processor").deleteAll()
    }
    
    /**
     * Verify test environment is clean before starting tests
     */
    static boolean verifyCleanTestEnvironment(ExecutionContext ec) {
        // Check for leftover test data
        long testEmails = ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                .count()
        
        long testEnvios = ec.entity.find("mchile.dte.DteEnvio")
                .condition("envioId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                .count()
        
        long testJobRuns = ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                .count()
        
        return (testEmails == 0 && testEnvios == 0 && testJobRuns == 0)
    }
    
    /**
     * Generate test data summary for debugging and reporting
     */
    static Map<String, Object> generateTestDataSummary(ExecutionContext ec) {
        return [
            emailMessages: ec.entity.find("moqui.basic.email.EmailMessage")
                    .condition("emailMessageId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                    .count(),
            emailAttachments: ec.entity.find("moqui.basic.email.EmailTemplateAttachment")
                    .condition("emailMessageId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                    .count(),
            dteEnvios: ec.entity.find("mchile.dte.DteEnvio")
                    .condition("envioId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                    .count(),
            jobRuns: ec.entity.find("moqui.service.job.ServiceJobRun")
                    .condition("jobRunId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                    .count(),
            mockJobContextSize: mockJobContext.size(),
            timestamp: ec.user.nowTimestamp.toString()
        ]
    }

    // ========================================
    // DTE XML Sample Management (SystemMessage-based)
    // ========================================

    /**
     * Get DTE XML sample by ID from SystemMessage entities
     */
    static String getDteXmlSample(ExecutionContext ec, String sampleId) {
        def sample = ec.entity.find("moqui.service.message.SystemMessage")
                .condition("systemMessageId", sampleId)
                .one()
        
        if (!sample) {
            throw new IllegalArgumentException("DTE XML sample not found: ${sampleId}")
        }
        
        return sample.bodyText
    }

    /**
     * Get all valid DTE XML samples
     */
    static List<Map> getValidDteXmlSamples(ExecutionContext ec) {
        return ec.entity.find("moqui.service.message.SystemMessage")
                .condition("systemMessageTypeId", "DteXmlSample-Valid")
                .list()
                .collect { sample ->
                    [
                        sampleId: sample.systemMessageId,
                        messageId: sample.messageId,
                        documentType: sample.docType,
                        description: sample.messageText,
                        xmlContent: sample.bodyText
                    ]
                }
    }

    /**
     * Get all error DTE XML samples for testing error scenarios
     */
    static List<Map> getErrorDteXmlSamples(ExecutionContext ec) {
        return ec.entity.find("moqui.service.message.SystemMessage")
                .condition("systemMessageTypeId", "DteXmlSample-Error")
                .list()
                .collect { sample ->
                    [
                        sampleId: sample.systemMessageId,
                        messageId: sample.messageId,
                        errorType: sample.docType,
                        description: sample.messageText,
                        xmlContent: sample.bodyText
                    ]
                }
    }

    /**
     * Get complex DTE XML samples for advanced testing
     */
    static List<Map> getComplexDteXmlSamples(ExecutionContext ec) {
        return ec.entity.find("moqui.service.message.SystemMessage")
                .condition("systemMessageTypeId", "DteXmlSample-Complex")
                .list()
                .collect { sample ->
                    [
                        sampleId: sample.systemMessageId,
                        messageId: sample.messageId,
                        documentType: sample.docType,
                        description: sample.messageText,
                        xmlContent: sample.bodyText
                    ]
                }
    }

    /**
     * Get DTE XML sample by document type and scenario
     */
    static String getDteXmlSampleByType(ExecutionContext ec, String documentType, String scenario = "valid") {
        String messageTypeId
        switch (scenario.toLowerCase()) {
            case "valid":
                messageTypeId = "DteXmlSample-Valid"
                break
            case "error":
                messageTypeId = "DteXmlSample-Error"
                break
            case "complex":
                messageTypeId = "DteXmlSample-Complex"
                break
            default:
                throw new IllegalArgumentException("Invalid scenario: ${scenario}")
        }

        def sample = ec.entity.find("moqui.service.message.SystemMessage")
                .condition("systemMessageTypeId", messageTypeId)
                .condition("docType", documentType)
                .list()
                .first()

        if (!sample) {
            throw new IllegalArgumentException("DTE XML sample not found for type: ${documentType}, scenario: ${scenario}")
        }

        return sample.bodyText
    }

    /**
     * Create test email attachment using XML sample
     */
    static Map<String, Object> createEmailAttachmentFromSample(ExecutionContext ec, String emailMessageId, String sampleId, Map<String, Object> overrides = [:]) {
        String xmlContent = getDteXmlSample(ec, sampleId)
        
        String attachmentName = overrides.attachmentName ?: "DTE_Sample_${sampleId}.xml"
        String contentType = overrides.contentType ?: "text/xml"
        
        Map attachmentData = [
            attachmentName: attachmentName,
            contentType: contentType,
            attachmentData: xmlContent
        ]
        attachmentData.putAll(overrides)
        
        return createEmailAttachment(ec, emailMessageId, attachmentData)
    }

    /**
     * Create test DTE envio using XML sample
     */
    static Map<String, Object> createTestDteEnvioFromSample(ExecutionContext ec, String sampleId, Map<String, Object> overrides = [:]) {
        String xmlContent = getDteXmlSample(ec, sampleId)
        
        // Extract document info from sample
        def sample = ec.entity.find("moqui.service.message.SystemMessage")
                .condition("systemMessageId", sampleId)
                .one()
                
        String documentType = sample.docType
        String documentNumber = overrides.documentNumber ?: extractDocumentNumber(xmlContent)
        
        Map envioParams = [
            xmlContent: xmlContent,
            fiscalTaxDocumentTypeEnumId: documentType != "ERROR" ? "Ftdt-${documentType}" : "Ftdt-33",
            documentNumber: documentNumber
        ]
        envioParams.putAll(overrides)
        
        return createTestDteEnvio(ec, envioParams)
    }

    /**
     * Extract document number from DTE XML content
     */
    private static String extractDocumentNumber(String xmlContent) {
        try {
            def xml = new XmlSlurper().parseText(xmlContent.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"))
            return xml.Documento.Encabezado.IdDoc.Folio.text() ?: "DEFAULT-001"
        } catch (Exception e) {
            return "ERROR-${System.currentTimeMillis()}"
        }
    }

    /**
     * Create test scenario using XML samples
     */
    static Map<String, Object> buildScenarioWithXmlSamples(ExecutionContext ec, String scenarioType, Map<String, Object> params = [:]) {
        List<String> sampleIds
        String messageTypeId
        
        switch (scenarioType) {
            case "valid-documents":
                sampleIds = ["SAMPLE-FACTURA-ELECTRONICA", "SAMPLE-FACTURA-EXENTA", "SAMPLE-BOLETA-ELECTRONICA"]
                messageTypeId = "DteXmlSample-Valid"
                break
            case "error-documents":
                sampleIds = ["ERROR-MALFORMED-XML", "ERROR-INVALID-DATA", "ERROR-MISSING-REQUIRED", "ERROR-WRONG-STRUCTURE"]
                messageTypeId = "DteXmlSample-Error"
                break
            case "complex-documents":
                sampleIds = ["COMPLEX-MULTI-LINE-INVOICE"]
                messageTypeId = "DteXmlSample-Complex"
                break
            default:
                throw new IllegalArgumentException("Invalid scenario type: ${scenarioType}")
        }
        
        // Create job run
        Map jobRun = createMockJobRun(ec, [
            jobName: "XML-SAMPLE-SCENARIO-${scenarioType.toUpperCase()}",
            statusId: messageTypeId == "DteXmlSample-Error" ? "SjsError" : "SjsRunning"
        ])
        
        // Create email with attachments from samples
        List attachments = []
        sampleIds.each { sampleId ->
            String xmlContent = getDteXmlSample(ec, sampleId)
            attachments.add([
                attachmentName: "DTE_${sampleId}.xml",
                attachmentData: xmlContent,
                contentType: "text/xml"
            ])
        }
        
        Map email = createTestEmailWithAttachments(ec, [
            subject: "DTE Test - ${scenarioType} scenario",
            fromAddress: "test-${scenarioType}@test-samples.cl",
            attachments: attachments
        ])
        
        // Create envios from samples
        List envios = []
        sampleIds.each { sampleId ->
            try {
                Map envio = createTestDteEnvioFromSample(ec, sampleId, [
                    statusId: messageTypeId == "DteXmlSample-Error" ? "Ftde-Failed" : "Ftde-Received"
                ])
                envios.add(envio)
            } catch (Exception e) {
                // For error samples, create a basic error envio
                envios.add([
                    envioId: "TEST-ERROR-${sampleId}",
                    statusId: "Ftde-Failed",
                    success: false,
                    error: "Sample processing failed: ${e.message}"
                ])
            }
        }
        
        return [
            success: true,
            scenarioType: scenarioType,
            messageTypeId: messageTypeId,
            jobRun: jobRun,
            email: email,
            envios: envios,
            sampleIds: sampleIds,
            attachmentCount: attachments.size(),
            envioCount: envios.size()
        ]
    }
}
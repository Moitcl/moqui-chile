package EmailProcessing

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import java.util.UUID

/**
 * Example test demonstrating comprehensive usage of the email processing test infrastructure.
 * Shows how to use all mocking utilities, test data generators, and job context simulation
 * for realistic integration testing scenarios.
 * 
 * This test serves as both documentation and validation of the test infrastructure.
 */
class EmailProcessingMockingExampleTest extends Specification {
    
    @Shared ExecutionContext ec
    @Shared String testUsername = "john.doe"
    @Shared EmailProcessingTestUtilities testUtils
    @Shared ServiceJobRunMockHelper jobMockHelper
    
    def setupSpec() {
        // Initialize framework
        ec = Moqui.getExecutionContext()
        
        // Login test user
        if (!ec.user.userId) {
            boolean loginSuccess = ec.user.loginUser(testUsername, "moqui")
            if (!loginSuccess) {
                throw new RuntimeException("Failed to login - ensure test data is loaded")
            }
        }
        
        // Set unique sequence ranges for test isolation
        ec.entity.tempSetSequencedIdPrimary("moqui.basic.email.EmailMessage", 90000, 50)
        ec.entity.tempSetSequencedIdPrimary("moqui.service.job.ServiceJobRun", 91000, 50)
        ec.entity.tempSetSequencedIdPrimary("mchile.dte.DteEnvio", 92000, 50)
        
        ec.artifactExecution.disableAuthz()
        
        // Initialize utility helpers
        testUtils = new EmailProcessingTestUtilities()
        jobMockHelper = new ServiceJobRunMockHelper()
        
        // Load test data
        ec.entity.loadData("data/TestEmailProcessingData.xml")
        ec.entity.loadData("data/TestDteXmlSamples.xml")
        
        // Ensure clean test environment
        cleanupTestData()
    }
    
    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        cleanupTestData()
        testUtils.cleanupAllTestData(ec)
        jobMockHelper.cleanupAllJobs()
        
        ec.user.logoutUser()
        ec.destroy()
    }
    
    def setup() {
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
        // Verify clean environment before each test
        assert testUtils.verifyCleanTestEnvironment(ec)
    }
    
    def cleanup() {
        cleanupTestData()
        jobMockHelper.cleanupAllJobs()
        
        if (ec.message.hasError()) {
            logger.warn("Unexpected errors in test: ${ec.message.getErrorsString()}")
        }
        ec.artifactExecution.enableAuthz()
        if (ec.transaction.status == javax.transaction.Status.STATUS_MARKED_ROLLBACK)
            ec.transaction.rollback("Marked as Rollback", null)
        else
            ec.transaction.commit()
        ec.message.clearErrors()
    }
    
    // ========================================
    // Example Tests Using Test Infrastructure
    // ========================================
    
    def "001 - should demonstrate basic job run context mocking"() {
        given: "A mock job run context"
        Map jobParams = [
            jobName: "EXAMPLE-EMAIL-PROCESSING",
            serviceName: "mchile.dte.EmailProcessingServices.process#EmailDteAttachments",
            runBy: "test-user",
            parameters: [emailBatchSize: 5, processingMode: "standard"]
        ]
        
        when: "Creating and starting a job run"
        ServiceJobRunMockHelper.JobRunContext jobContext = jobMockHelper.createAndStartJobRun(ec, jobParams)
        
        then: "Job context should be properly initialized"
        jobContext != null
        jobContext.jobRunId != null
        jobContext.statusId == "SjsRunning"
        jobContext.jobName == "EXAMPLE-EMAIL-PROCESSING"
        jobContext.startTime != null
        jobContext.logMessages.size() > 0
        
        and: "Job should be trackable"
        jobMockHelper.isJobRunActive(jobContext.jobRunId)
        jobMockHelper.getCurrentJobRunId() == jobContext.jobRunId
        
        and: "Job should have proper event history"
        List events = jobMockHelper.getJobEventHistory(jobContext.jobRunId)
        events.size() == 1
        events[0].eventType == "JOB_STARTED"
        
        cleanup:
        jobMockHelper.completeAndRemoveJob(ec, jobContext.jobRunId)
    }
    
    def "002 - should demonstrate email processing scenario building"() {
        given: "Parameters for a multi-attachment scenario"
        Map scenarioParams = [
            emailCount: 2,
            attachmentsPerEmail: 2,
            includeJobContext: true
        ]
        
        when: "Building a complete email processing scenario"
        Map scenario = testUtils.buildEmailProcessingScenario(ec, "multi-attachment", scenarioParams)
        
        then: "Scenario should be complete with all components"
        scenario.success == true
        scenario.scenarioType == "multi-attachment"
        scenario.jobRun != null
        scenario.email != null
        scenario.envios != null
        scenario.attachmentCount == 2
        
        and: "Job run should be active and trackable"
        String jobRunId = scenario.jobRun.jobRunId
        jobMockHelper.isJobRunActive(jobRunId)
        jobMockHelper.getJobRunStatus(jobRunId) == "SjsRunning"
        
        and: "Email should have proper attachments"
        scenario.email.attachments.size() == 2
        scenario.email.subject.contains("Múltiples DTEs")
        
        and: "Envios should be properly created"
        scenario.envios.size() == 2
        scenario.envios.each { envio ->
            assert envio.success == true
            assert envio.envioId != null
            assert envio.documentNumber != null
        }
        
        cleanup:
        jobMockHelper.completeAndRemoveJob(ec, jobRunId)
    }
    
    def "003 - should demonstrate job execution simulation with progress tracking"() {
        given: "A job run with execution steps"
        ServiceJobRunMockHelper.JobRunContext jobContext = jobMockHelper.createAndStartJobRun(ec, [
            jobName: "EMAIL-PROCESSING-SIMULATION",
            parameters: [emailCount: 3, attachmentsPerEmail: 2]
        ])
        
        List executionSteps = [
            [description: "Initialize email processing", data: [phase: "init"]],
            [description: "Process email 1", data: [phase: "email", emailIndex: 1]],
            [description: "Process attachment 1 of email 1", data: [phase: "attachment", emailIndex: 1, attachmentIndex: 1]],
            [description: "Process attachment 2 of email 1", data: [phase: "attachment", emailIndex: 1, attachmentIndex: 2]],
            [description: "Process email 2", data: [phase: "email", emailIndex: 2]],
            [description: "Process attachment 1 of email 2", data: [phase: "attachment", emailIndex: 2, attachmentIndex: 1]],
            [description: "Process attachment 2 of email 2", data: [phase: "attachment", emailIndex: 2, attachmentIndex: 2]],
            [description: "Finalize processing", data: [phase: "finalization"]]
        ]
        
        when: "Simulating job execution"
        ServiceJobRunMockHelper.JobRunContext completedContext = jobMockHelper.simulateJobExecution(
            ec, jobContext.jobRunId, executionSteps, [stepDelayMs: 10]
        )
        
        then: "Job should complete successfully"
        completedContext.statusId == "SjsComplete"
        completedContext.endTime != null
        completedContext.executionTimeMs > 0
        completedContext.results.status == "completed"
        completedContext.results.progress == "100%"
        completedContext.results.completedSteps == executionSteps.size()
        
        and: "Event history should track all steps"
        List events = jobMockHelper.getJobEventHistory(jobContext.jobRunId)
        events.size() == executionSteps.size() + 2  // +2 for start and status change events
        events.count { it.eventType == "STEP_COMPLETED" } == executionSteps.size()
        
        and: "Log messages should document execution"
        completedContext.logMessages.size() > executionSteps.size()
        completedContext.logMessages.any { it.contains("Job execution completed successfully") }
        
        cleanup:
        jobMockHelper.completeAndRemoveJob(ec, jobContext.jobRunId)
    }
    
    def "004 - should demonstrate realistic email processing job simulation"() {
        given: "Email processing job parameters"
        String jobRunId = "EMAIL-PROC-REAL-${UUID.randomUUID().toString().substring(0, 8)}"
        ServiceJobRunMockHelper.JobRunContext jobContext = jobMockHelper.createAndStartJobRun(ec, [
            jobRunId: jobRunId,
            jobName: "REALISTIC-EMAIL-PROCESSING",
            serviceName: "mchile.dte.EmailProcessingServices.process#EmailBatch"
        ])
        
        Map processingParams = [
            emailCount: 3,
            attachmentsPerEmail: 2,
            includeErrors: false
        ]
        
        when: "Simulating realistic email processing"
        ServiceJobRunMockHelper.JobRunContext result = jobMockHelper.simulateEmailProcessingJob(
            ec, jobRunId, processingParams
        )
        
        then: "Job should complete with realistic execution pattern"
        result.statusId == "SjsComplete"
        result.executionTimeMs > 0
        result.results.status == "completed"
        
        and: "Should have processed all emails and attachments"
        result.results.completedSteps > 0
        result.results.totalSteps > 0
        result.results.progress == "100%"
        
        and: "Event history should show realistic processing flow"
        List events = jobMockHelper.getJobEventHistory(jobRunId)
        events.size() > 10  // Should have many events for realistic processing
        events.any { it.eventType == "JOB_STARTED" }
        events.any { it.eventType == "STEP_COMPLETED" }
        events.any { it.description.contains("Processing email") }
        events.any { it.description.contains("attachment") }
        
        and: "Log messages should document complete workflow"
        result.logMessages.size() > 5
        result.logMessages.any { it.contains("Starting job execution") }
        result.logMessages.any { it.contains("Completed step") }
        
        cleanup:
        jobMockHelper.completeAndRemoveJob(ec, jobRunId)
    }
    
    @Unroll
    def "005 - should demonstrate different scenario types: #scenarioType"() {
        given: "Scenario parameters for #scenarioType"
        Map scenarioParams = [
            emailCount: emailCount,
            attachmentsPerEmail: attachmentsPerEmail,
            includeErrors: includeErrors
        ]
        
        when: "Building scenario of type #scenarioType"
        Map scenario = testUtils.buildEmailProcessingScenario(ec, scenarioType, scenarioParams)
        
        then: "Scenario should be built successfully"
        scenario.success == true
        scenario.scenarioType == scenarioType
        scenario.jobRun != null
        
        and: "Should have expected number of components"
        if (scenarioType == "batch-processing") {
            assert scenario.emails.size() == expectedEmailCount
            assert scenario.envios.size() == expectedEnvioCount
        } else {
            assert scenario.email != null
            assert scenario.envios.size() == expectedEnvioCount
        }
        
        and: "Job should be in expected state"
        String jobRunId = scenario.jobRun.jobRunId
        jobMockHelper.isJobRunActive(jobRunId)
        String expectedStatus = (scenarioType == "error-scenario") ? "SjsError" : "SjsRunning"
        jobMockHelper.getJobRunStatus(jobRunId) == expectedStatus
        
        cleanup:
        if (scenario.jobRun?.jobRunId) {
            jobMockHelper.completeAndRemoveJob(ec, scenario.jobRun.jobRunId)
        }
        
        where:
        scenarioType        | emailCount | attachmentsPerEmail | includeErrors | expectedEmailCount | expectedEnvioCount
        "single-attachment" | 1          | 1                   | false         | 1                  | 1
        "multi-attachment"  | 1          | 3                   | false         | 1                  | 3
        "batch-processing"  | 5          | 2                   | false         | 5                  | 5
        "error-scenario"    | 1          | 1                   | true          | 1                  | 1
        "performance-test"  | 1          | 10                  | false         | 1                  | 10
    }
    
    def "006 - should demonstrate service execution with job context"() {
        given: "A job run and service parameters"
        ServiceJobRunMockHelper.JobRunContext jobContext = jobMockHelper.createAndStartJobRun(ec, [
            jobName: "SERVICE-EXECUTION-TEST"
        ])
        
        String serviceName = "mchile.dte.EmailProcessingServices.process#EmailAttachment"
        Map serviceParams = [
            emailMessageId: "TEST-EMAIL-123",
            attachmentName: "test-dte.xml",
            processingMode: "standard"
        ]
        
        when: "Executing service with job context"
        Map result = jobMockHelper.simulateJobAwareServiceCall(ec, serviceName, serviceParams, jobContext.jobRunId)
        
        then: "Service should execute successfully with job context"
        result.success == true
        result.serviceName == serviceName
        result.jobRunId == jobContext.jobRunId
        result.executionTime > 0
        
        and: "Job context should log service execution"
        jobContext.logMessages.any { it.contains("Simulated service call") }
        jobContext.logMessages.any { it.contains(serviceName) }
        
        and: "Service parameters should be reflected in results"
        result.emailMessageId == "TEST-EMAIL-123"
        
        cleanup:
        jobMockHelper.completeAndRemoveJob(ec, jobContext.jobRunId)
    }
    
    def "007 - should demonstrate comprehensive test data summary generation"() {
        given: "Multiple test scenarios with data"
        // Create multi-attachment scenario
        Map scenario1 = testUtils.buildEmailProcessingScenario(ec, "multi-attachment", [attachmentsPerEmail: 2])
        
        // Create single-attachment scenario
        Map scenario2 = testUtils.buildEmailProcessingScenario(ec, "single-attachment", [:])
        
        // Create some additional test envios
        List batchEnvios = testUtils.createBatchTestEnvios(ec, 3, [documentNumber: "BATCH"])
        
        when: "Generating comprehensive test data summary"
        Map dataSummary = testUtils.generateTestDataSummary(ec)
        
        and: "Getting job run summaries"
        List jobSummaries = jobMockHelper.getAllJobRunSummaries()
        
        then: "Data summary should show all created entities"
        dataSummary.emailMessages >= 2  // At least 2 from scenarios
        dataSummary.emailAttachments >= 3  // At least 3 attachments total
        dataSummary.dteEnvios >= 6  // 2 + 1 + 3 from scenarios and batch
        dataSummary.jobRuns >= 2  // At least 2 job runs
        dataSummary.mockJobContextSize == 2  // 2 active jobs
        dataSummary.timestamp != null
        
        and: "Job summaries should provide detailed information"
        jobSummaries.size() == 2
        jobSummaries.each { summary ->
            assert summary.jobRunId != null
            assert summary.jobName != null
            assert summary.status != null
            assert summary.startTime != null
            assert summary.isActive == true
            assert summary.summary.totalEvents > 0
        }
        
        cleanup:
        [scenario1, scenario2].each { scenario ->
            if (scenario.jobRun?.jobRunId) {
                jobMockHelper.completeAndRemoveJob(ec, scenario.jobRun.jobRunId)
            }
        }
    }
    
    def "008 - should demonstrate error scenario handling"() {
        given: "An error scenario"
        Map errorScenario = testUtils.buildEmailProcessingScenario(ec, "error-scenario", [:])
        String jobRunId = errorScenario.jobRun.jobRunId
        
        when: "Examining the error scenario components"
        ServiceJobRunMockHelper.JobRunContext jobContext = jobMockHelper.getJobRunContext(jobRunId)
        List events = jobMockHelper.getJobEventHistory(jobRunId)
        Map jobSummary = jobMockHelper.generateJobRunSummary(jobRunId)
        
        then: "Error scenario should be properly configured"
        errorScenario.scenarioType == "error-scenario"
        errorScenario.errorType == "parsing"
        
        and: "Job should be in error state"
        jobContext.statusId == "SjsError"
        jobContext.hasError == true
        jobContext.results.error != null
        
        and: "Event history should show error progression"
        events.any { it.eventType == "JOB_ERROR" }
        events.any { it.description.contains("failed") }
        
        and: "Job summary should reflect error status"
        jobSummary.status == "SjsError"
        jobSummary.hasError == true
        jobSummary.summary.errorEvents > 0
        
        and: "Email and envio should reflect error state"
        errorScenario.email.subject.contains("Errores")
        errorScenario.envios[0].statusId == "DteEnvioError"
        errorScenario.envios[0].receptionAck == "N"
        
        cleanup:
        jobMockHelper.completeAndRemoveJob(ec, jobRunId)
    }
    
    def "009 - should demonstrate XML content generation and validation"() {
        given: "Parameters for XML generation"
        Map xmlParams = [
            tipoDte: "33",
            folio: "TEST-12345",
            fechaEmision: "2024-09-03",
            rutEmisor: "76514104-4",
            razonSocialEmisor: "Test XML Generator Company",
            rutReceptor: "12345678-9",
            razonSocialReceptor: "Test XML Receiver",
            montoTotal: "500000"
        ]
        
        when: "Generating test DTE XML"
        String validXml = testUtils.generateTestDteXml(xmlParams)
        String malformedXml = testUtils.generateMalformedDteXml("parsing")
        String validationErrorXml = testUtils.generateMalformedDteXml("validation")
        
        then: "Valid XML should contain expected elements"
        validXml.contains('<?xml version="1.0"')
        validXml.contains('<DTE version="1.0">')
        validXml.contains('<TipoDTE>33</TipoDTE>')
        validXml.contains('<Folio>TEST-12345</Folio>')
        validXml.contains('<RUTEmisor>76514104-4</RUTEmisor>')
        validXml.contains('<MntTotal>500000</MntTotal>')
        validXml.contains('</DTE>')
        
        and: "Malformed XML should be intentionally broken"
        malformedXml.contains('<DTE version="1.0">')
        !malformedXml.contains('</DTE>')  // Missing closing tag
        
        and: "Validation error XML should have invalid data"
        validationErrorXml.contains('<TipoDTE>999</TipoDTE>')
        validationErrorXml.contains('<Folio>-1</Folio>')
        validationErrorXml.contains('INVALID-RUT-FORMAT')
    }
    
    def "010 - should demonstrate complete integration workflow"() {
        given: "Complete integration scenario setup"
        Map integrationParams = [
            emailCount: 2,
            attachmentsPerEmail: 2,
            includeJobTracking: true,
            includeServiceCalls: true,
            includeProgressUpdates: true
        ]
        
        when: "Running complete integration workflow"
        // 1. Create job run
        ServiceJobRunMockHelper.JobRunContext jobContext = jobMockHelper.createAndStartJobRun(ec, [
            jobName: "COMPLETE-INTEGRATION-WORKFLOW",
            parameters: integrationParams
        ])
        
        // 2. Create email processing scenario
        Map scenario = testUtils.buildEmailProcessingScenario(ec, "multi-attachment", integrationParams)
        
        // 3. Simulate email processing with job context
        ServiceJobRunMockHelper.JobRunContext processedJob = jobMockHelper.simulateEmailProcessingJob(
            ec, jobContext.jobRunId, integrationParams
        )
        
        // 4. Execute service calls with job context
        Map serviceResult1 = jobMockHelper.simulateJobAwareServiceCall(
            ec, "mchile.dte.EmailProcessingServices.process#EmailAttachment", 
            [emailMessageId: scenario.email.emailMessageId, attachmentIndex: 1], 
            jobContext.jobRunId
        )
        
        Map serviceResult2 = jobMockHelper.simulateJobAwareServiceCall(
            ec, "mchile.dte.EmailProcessingServices.create#EnvioTracking",
            [envioId: scenario.envios[0].envioId, trackingData: [status: "processed"]],
            jobContext.jobRunId
        )
        
        // 5. Generate final summaries
        Map dataSummary = testUtils.generateTestDataSummary(ec)
        Map jobSummary = jobMockHelper.generateJobRunSummary(jobContext.jobRunId)
        
        then: "Complete workflow should execute successfully"
        // Job execution validation
        processedJob.statusId == "SjsComplete"
        processedJob.executionTimeMs > 0
        processedJob.results.status == "completed"
        
        // Scenario validation
        scenario.success == true
        scenario.email.attachments.size() == 2
        scenario.envios.size() == 2
        
        // Service execution validation
        serviceResult1.success == true
        serviceResult1.jobRunId == jobContext.jobRunId
        serviceResult2.success == true
        serviceResult2.envioId == scenario.envios[0].envioId
        
        // Data summary validation
        dataSummary.emailMessages >= 1
        dataSummary.emailAttachments >= 2
        dataSummary.dteEnvios >= 2
        dataSummary.jobRuns >= 1
        
        // Job summary validation
        jobSummary.status == "SjsComplete"
        jobSummary.summary.totalEvents > 10
        jobSummary.summary.serviceExecutions >= 2
        jobSummary.summary.statusChanges > 0
        
        and: "Event history should document complete workflow"
        List events = jobMockHelper.getJobEventHistory(jobContext.jobRunId)
        events.size() > 15  // Many events for complete workflow
        events.any { it.eventType == "JOB_STARTED" }
        events.any { it.eventType == "STEP_COMPLETED" }
        events.any { it.eventType == "SERVICE_EXECUTION" }
        events.any { it.eventType == "SERVICE_COMPLETED" }
        
        cleanup:
        jobMockHelper.completeAndRemoveJob(ec, jobContext.jobRunId)
        if (scenario.jobRun?.jobRunId && scenario.jobRun.jobRunId != jobContext.jobRunId) {
            jobMockHelper.completeAndRemoveJob(ec, scenario.jobRun.jobRunId)
        }
    }
    
    // ========================================
    // Helper Methods
    // ========================================
    
    private void cleanupTestData() {
        // Clean up test entities created during tests
        ec.entity.find("moqui.basic.email.EmailMessageAttachment")
                .condition("emailMessageId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                .deleteAll()
        
        ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                .deleteAll()
        
        ec.entity.find("mchile.dte.DteEnvio")
                .condition("envioId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                .deleteAll()
        
        ec.entity.find("moqui.service.job.ServiceJobRun")
                .condition("jobRunId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "TEST-%")
                .condition("jobRunId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "MOCK-%")
                .condition("jobRunId", org.moqui.entity.EntityCondition.ComparisonOperator.LIKE, "EXAMPLE-%")
                .deleteAll()
    }
}
package EmailProcessing

import org.moqui.context.ExecutionContext
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Specialized mock helper for ServiceJobRun context simulation in email processing tests.
 * Provides comprehensive job execution simulation, status tracking, and context management
 * for realistic testing of job-aware email processing services.
 */
class ServiceJobRunMockHelper {
    
    // ========================================
    // Mock Job Context Management
    // ========================================
    
    private static final Map<String, JobRunContext> activeJobs = new ConcurrentHashMap<>()
    private static final Map<String, List<JobEvent>> jobEventHistory = new ConcurrentHashMap<>()
    private static final AtomicReference<String> currentJobRunId = new AtomicReference<>()
    private static final JsonSlurper jsonSlurper = new JsonSlurper()
    
    /**
     * Job run context data structure
     */
    static class JobRunContext {
        String jobRunId
        String jobName
        String serviceName
        String statusId
        String runBy
        String runThread
        Date startTime
        Date endTime
        boolean hasError
        Map<String, Object> results
        Map<String, Object> parameters
        Map<String, Object> contextData
        List<String> logMessages
        long executionTimeMs
        
        JobRunContext(String jobRunId) {
            this.jobRunId = jobRunId
            this.logMessages = []
            this.contextData = [:]
            this.results = [:]
            this.parameters = [:]
            this.executionTimeMs = 0
        }
        
        Map<String, Object> toMap() {
            return [
                jobRunId: jobRunId,
                jobName: jobName,
                serviceName: serviceName,
                statusId: statusId,
                runBy: runBy,
                runThread: runThread,
                startTime: startTime,
                endTime: endTime,
                hasError: hasError,
                results: results,
                parameters: parameters,
                contextData: contextData,
                logMessages: logMessages,
                executionTimeMs: executionTimeMs
            ]
        }
    }
    
    /**
     * Job event for audit trail
     */
    static class JobEvent {
        Date timestamp
        String eventType
        String description
        Map<String, Object> data
        
        JobEvent(String eventType, String description, Map<String, Object> data = [:]) {
            this.timestamp = new Date()
            this.eventType = eventType
            this.description = description
            this.data = data
        }
    }
    
    // ========================================
    // Job Run Creation and Management
    // ========================================
    
    /**
     * Create and start a new mock job run with comprehensive context
     */
    static JobRunContext createAndStartJobRun(ExecutionContext ec, Map<String, Object> params = [:]) {
        String jobRunId = params.jobRunId ?: "MOCK-JOB-${UUID.randomUUID().toString().substring(0, 12)}"
        String jobName = params.jobName ?: "mock-email-processing-job"
        String serviceName = params.serviceName ?: "mchile.dte.EmailProcessingServices.process#EmailDteAttachments"
        String runBy = params.runBy ?: "test-user"
        String runThread = params.runThread ?: "MockJobWorker-${Thread.currentThread().getId()}"
        
        // Create job context
        JobRunContext context = new JobRunContext(jobRunId)
        context.jobName = jobName
        context.serviceName = serviceName
        context.statusId = "SjsRunning"
        context.runBy = runBy
        context.runThread = runThread
        context.startTime = new Date()
        context.hasError = false
        context.parameters = params.parameters ?: [:]
        context.results = [status: "initialized", progress: "0%"]
        
        // Create actual ServiceJobRun entity for database consistency
        createServiceJobRunEntity(ec, context)
        
        // Store in active jobs
        activeJobs.put(jobRunId, context)
        currentJobRunId.set(jobRunId)
        
        // Add initial event
        addJobEvent(jobRunId, "JOB_STARTED", "Job run started", [
            jobName: jobName,
            serviceName: serviceName,
            runBy: runBy,
            startTime: context.startTime
        ])
        
        // Log context
        context.logMessages.add("Job run ${jobRunId} started at ${context.startTime}")
        context.logMessages.add("Service: ${serviceName}")
        context.logMessages.add("Run by: ${runBy}")
        
        return context
    }
    
    /**
     * Create the actual ServiceJobRun entity in the database
     */
    private static void createServiceJobRunEntity(ExecutionContext ec, JobRunContext context) {
        try {
            Map createResult = ec.service.sync().name("create#moqui.service.job.ServiceJobRun")
                    .parameters([
                        jobRunId: context.jobRunId,
                        jobName: context.jobName,
                        serviceName: context.serviceName,
                        runStartTime: context.startTime,
                        statusId: context.statusId,
                        hasError: context.hasError ? "Y" : "N",
                        runThread: context.runThread,
                        runBy: context.runBy,
                        results: new JsonBuilder(context.results).toString()
                    ]).call()
            
            if (createResult.errors) {
                throw new RuntimeException("Failed to create ServiceJobRun entity: ${createResult.errors}")
            }
        } catch (Exception e) {
            context.logMessages.add("Warning: Failed to create ServiceJobRun entity: ${e.message}")
            // Continue without database entity for testing purposes
        }
    }
    
    /**
     * Update job run status with automatic entity synchronization
     */
    static JobRunContext updateJobStatus(ExecutionContext ec, String jobRunId, String newStatusId, 
                                        Map<String, Object> newResults = [:]) {
        JobRunContext context = activeJobs.get(jobRunId)
        if (!context) {
            throw new IllegalArgumentException("Job run not found: ${jobRunId}")
        }
        
        String previousStatus = context.statusId
        context.statusId = newStatusId
        
        // Merge new results with existing results
        if (newResults) {
            context.results.putAll(newResults)
        }
        
        // Handle job completion
        if (newStatusId in ['SjsComplete', 'SjsError', 'SjsCancelled']) {
            context.endTime = new Date()
            context.executionTimeMs = context.endTime.time - context.startTime.time
            context.hasError = (newStatusId == 'SjsError')
            
            // Update results with execution summary
            context.results.putAll([
                executionTimeMs: context.executionTimeMs,
                completedAt: context.endTime,
                finalStatus: newStatusId
            ])
        }
        
        // Update database entity
        updateServiceJobRunEntity(ec, context)
        
        // Add event
        addJobEvent(jobRunId, "STATUS_CHANGED", "Status changed from ${previousStatus} to ${newStatusId}", [
            previousStatus: previousStatus,
            newStatus: newStatusId,
            results: newResults
        ])
        
        // Add log message
        context.logMessages.add("Status changed to ${newStatusId} at ${new Date()}")
        if (newResults) {
            context.logMessages.add("Results updated: ${newResults}")
        }
        
        return context
    }
    
    /**
     * Update the ServiceJobRun entity in the database
     */
    private static void updateServiceJobRunEntity(ExecutionContext ec, JobRunContext context) {
        try {
            Map updateParams = [
                jobRunId: context.jobRunId,
                statusId: context.statusId,
                hasError: context.hasError ? "Y" : "N",
                results: new JsonBuilder(context.results).toString()
            ]
            
            if (context.endTime) {
                updateParams.runEndTime = context.endTime
            }
            
            Map updateResult = ec.service.sync().name("update#moqui.service.job.ServiceJobRun")
                    .parameters(updateParams).call()
            
            if (updateResult.errors) {
                throw new RuntimeException("Failed to update ServiceJobRun entity: ${updateResult.errors}")
            }
        } catch (Exception e) {
            context.logMessages.add("Warning: Failed to update ServiceJobRun entity: ${e.message}")
            // Continue without database update for testing purposes
        }
    }
    
    // ========================================
    // Job Execution Simulation
    // ========================================
    
    /**
     * Simulate job execution with realistic progress updates
     */
    static JobRunContext simulateJobExecution(ExecutionContext ec, String jobRunId, 
                                             List<Map<String, Object>> executionSteps, 
                                             Map<String, Object> options = [:]) {
        JobRunContext context = activeJobs.get(jobRunId)
        if (!context) {
            throw new IllegalArgumentException("Job run not found: ${jobRunId}")
        }
        
        long stepDelayMs = options.stepDelayMs ?: 50  // Realistic but fast for testing
        boolean simulateRandomDelay = options.simulateRandomDelay ?: false
        double errorProbability = options.errorProbability ?: 0.0  // 0.0 = no errors, 1.0 = always error
        
        context.logMessages.add("Starting job execution with ${executionSteps.size()} steps")
        
        try {
            executionSteps.eachWithIndex { step, index ->
                // Simulate processing delay
                if (stepDelayMs > 0) {
                    long delay = stepDelayMs
                    if (simulateRandomDelay) {
                        delay = (Math.random() * stepDelayMs * 2).longValue()
                    }
                    Thread.sleep(delay)
                }
                
                // Simulate potential errors
                if (Math.random() < errorProbability) {
                    throw new RuntimeException("Simulated error at step ${index + 1}: ${step.description}")
                }
                
                // Update progress
                int progressPercent = ((index + 1) * 100 / executionSteps.size()).intValue()
                Map stepResults = [
                    status: "processing",
                    currentStep: step.description ?: "Step ${index + 1}",
                    progress: "${progressPercent}%",
                    completedSteps: index + 1,
                    totalSteps: executionSteps.size(),
                    stepData: step.data ?: [:]
                ]
                
                updateJobStatus(ec, jobRunId, "SjsRunning", stepResults)
                
                // Add step event
                addJobEvent(jobRunId, "STEP_COMPLETED", step.description ?: "Step ${index + 1}", stepResults)
                
                // Log step completion
                context.logMessages.add("Completed step ${index + 1}/${executionSteps.size()}: ${step.description}")
            }
            
            // Mark job as complete
            Map finalResults = [
                status: "completed",
                progress: "100%",
                completedSteps: executionSteps.size(),
                totalSteps: executionSteps.size(),
                executionSummary: "All steps completed successfully"
            ]
            
            updateJobStatus(ec, jobRunId, "SjsComplete", finalResults)
            context.logMessages.add("Job execution completed successfully")
            
        } catch (Exception e) {
            // Handle execution error
            Map errorResults = [
                status: "error",
                error: e.message,
                errorType: e.class.simpleName,
                failedAt: new Date(),
                completedSteps: executionSteps.findIndexOf { it -> false } // Steps completed before error
            ]
            
            updateJobStatus(ec, jobRunId, "SjsError", errorResults)
            addJobEvent(jobRunId, "JOB_ERROR", "Job execution failed: ${e.message}", errorResults)
            context.logMessages.add("Job execution failed: ${e.message}")
        }
        
        return context
    }
    
    /**
     * Simulate email processing job with realistic DTE processing steps
     */
    static JobRunContext simulateEmailProcessingJob(ExecutionContext ec, String jobRunId, 
                                                   Map<String, Object> processingParams = [:]) {
        int emailCount = processingParams.emailCount ?: 1
        int attachmentsPerEmail = processingParams.attachmentsPerEmail ?: 2
        boolean includeErrors = processingParams.includeErrors ?: false
        
        List<Map<String, Object>> steps = []
        
        // Job initialization steps
        steps.add([description: "Initializing email processing job", data: [phase: "initialization"]])
        steps.add([description: "Loading job configuration", data: [phase: "initialization"]])
        steps.add([description: "Setting up processing context", data: [phase: "initialization"]])
        
        // Email processing steps
        (1..emailCount).each { emailIndex ->
            steps.add([description: "Processing email ${emailIndex}/${emailCount}", 
                      data: [phase: "email_processing", emailIndex: emailIndex]])
            steps.add([description: "Analyzing email attachments", 
                      data: [phase: "attachment_analysis", emailIndex: emailIndex]])
            
            // Attachment processing steps
            (1..attachmentsPerEmail).each { attachmentIndex ->
                steps.add([description: "Processing attachment ${attachmentIndex}/${attachmentsPerEmail} of email ${emailIndex}", 
                          data: [phase: "attachment_processing", emailIndex: emailIndex, attachmentIndex: attachmentIndex]])
                steps.add([description: "Validating DTE XML content", 
                          data: [phase: "validation", emailIndex: emailIndex, attachmentIndex: attachmentIndex]])
                steps.add([description: "Creating DTE envio record", 
                          data: [phase: "envio_creation", emailIndex: emailIndex, attachmentIndex: attachmentIndex]])
                steps.add([description: "Linking envio to email", 
                          data: [phase: "linking", emailIndex: emailIndex, attachmentIndex: attachmentIndex]])
            }
            
            steps.add([description: "Finalizing email ${emailIndex} processing", 
                      data: [phase: "email_finalization", emailIndex: emailIndex]])
        }
        
        // Job finalization steps
        steps.add([description: "Generating processing summary", data: [phase: "finalization"]])
        steps.add([description: "Updating processing status", data: [phase: "finalization"]])
        steps.add([description: "Cleaning up temporary resources", data: [phase: "cleanup"]])
        
        // Execute the simulation
        Map<String, Object> simulationOptions = [
            stepDelayMs: 25,  // Fast execution for tests
            simulateRandomDelay: false,
            errorProbability: includeErrors ? 0.1 : 0.0  // 10% error chance if errors enabled
        ]
        
        return simulateJobExecution(ec, jobRunId, steps, simulationOptions)
    }
    
    // ========================================
    // Job Context Query and Monitoring
    // ========================================
    
    /**
     * Get current job run context (simulates ExecutionContext.getJobRunId())
     */
    static String getCurrentJobRunId() {
        return currentJobRunId.get()
    }
    
    /**
     * Get job run context by ID
     */
    static JobRunContext getJobRunContext(String jobRunId) {
        return activeJobs.get(jobRunId)
    }
    
    /**
     * Get all active job runs
     */
    static Map<String, JobRunContext> getAllActiveJobs() {
        return activeJobs.clone()
    }
    
    /**
     * Check if job run is active
     */
    static boolean isJobRunActive(String jobRunId) {
        return activeJobs.containsKey(jobRunId)
    }
    
    /**
     * Get job run status
     */
    static String getJobRunStatus(String jobRunId) {
        JobRunContext context = activeJobs.get(jobRunId)
        return context?.statusId
    }
    
    /**
     * Get job run results
     */
    static Map<String, Object> getJobRunResults(String jobRunId) {
        JobRunContext context = activeJobs.get(jobRunId)
        return context?.results ?: [:]
    }
    
    /**
     * Get job run execution time
     */
    static long getJobRunExecutionTime(String jobRunId) {
        JobRunContext context = activeJobs.get(jobRunId)
        if (!context) return 0
        
        if (context.endTime) {
            return context.endTime.time - context.startTime.time
        } else {
            return new Date().time - context.startTime.time
        }
    }
    
    // ========================================
    // Job Event Management
    // ========================================
    
    /**
     * Add event to job history
     */
    private static void addJobEvent(String jobRunId, String eventType, String description, Map<String, Object> data = [:]) {
        if (!jobEventHistory.containsKey(jobRunId)) {
            jobEventHistory.put(jobRunId, [])
        }
        
        JobEvent event = new JobEvent(eventType, description, data)
        jobEventHistory.get(jobRunId).add(event)
    }
    
    /**
     * Get job event history
     */
    static List<JobEvent> getJobEventHistory(String jobRunId) {
        return jobEventHistory.get(jobRunId, [])
    }
    
    /**
     * Get job log messages
     */
    static List<String> getJobLogMessages(String jobRunId) {
        JobRunContext context = activeJobs.get(jobRunId)
        return context?.logMessages ?: []
    }
    
    // ========================================
    // Service Integration Helpers
    // ========================================
    
    /**
     * Mock service execution with job context
     */
    static Map<String, Object> executeServiceWithJobContext(ExecutionContext ec, String serviceName, 
                                                           Map<String, Object> parameters, String jobRunId) {
        JobRunContext context = activeJobs.get(jobRunId)
        if (!context) {
            throw new IllegalArgumentException("Job run not found: ${jobRunId}")
        }
        
        // Set current job context
        String previousJobRunId = currentJobRunId.get()
        currentJobRunId.set(jobRunId)
        
        try {
            // Log service execution
            context.logMessages.add("Executing service: ${serviceName}")
            context.logMessages.add("Parameters: ${parameters}")
            
            // Add service execution event
            addJobEvent(jobRunId, "SERVICE_EXECUTION", "Executing service: ${serviceName}", [
                serviceName: serviceName,
                parameters: parameters
            ])
            
            // Execute the actual service
            Map result = ec.service.sync().name(serviceName).parameters(parameters).call()
            
            // Log results
            context.logMessages.add("Service execution completed")
            if (result.errors) {
                context.logMessages.add("Service errors: ${result.errors}")
            }
            
            // Add completion event
            addJobEvent(jobRunId, "SERVICE_COMPLETED", "Service execution completed: ${serviceName}", [
                serviceName: serviceName,
                success: !result.errors,
                errors: result.errors
            ])
            
            return result
            
        } finally {
            // Restore previous job context
            currentJobRunId.set(previousJobRunId)
        }
    }
    
    /**
     * Simulate job-aware service call pattern used in email processing
     */
    static Map<String, Object> simulateJobAwareServiceCall(ExecutionContext ec, String serviceName, 
                                                          Map<String, Object> parameters, String jobRunId) {
        JobRunContext context = activeJobs.get(jobRunId)
        if (!context) {
            throw new IllegalArgumentException("Job run not found: ${jobRunId}")
        }
        
        // Simulate service processing time
        Thread.sleep(10)
        
        // Create mock successful result
        Map<String, Object> result = [
            success: true,
            processed: true,
            serviceName: serviceName,
            jobRunId: jobRunId,
            executionTime: 10,
            timestamp: new Date()
        ]
        
        // Add any parameters as result data
        parameters.each { key, value ->
            if (key.endsWith("Id") || key.endsWith("Count") || key.endsWith("Status")) {
                result.put(key, value)
            }
        }
        
        // Log the simulated call
        context.logMessages.add("Simulated service call: ${serviceName} -> SUCCESS")
        
        return result
    }
    
    // ========================================
    // Cleanup and Utility Methods
    // ========================================
    
    /**
     * Complete and remove job run from active jobs
     */
    static JobRunContext completeAndRemoveJob(ExecutionContext ec, String jobRunId, String finalStatus = "SjsComplete") {
        JobRunContext context = updateJobStatus(ec, jobRunId, finalStatus)
        activeJobs.remove(jobRunId)
        
        // Clear current job if this was the current one
        if (currentJobRunId.get() == jobRunId) {
            currentJobRunId.set(null)
        }
        
        context.logMessages.add("Job run removed from active jobs")
        return context
    }
    
    /**
     * Clean up all mock job data
     */
    static void cleanupAllJobs() {
        activeJobs.clear()
        jobEventHistory.clear()
        currentJobRunId.set(null)
    }
    
    /**
     * Generate comprehensive job run summary
     */
    static Map<String, Object> generateJobRunSummary(String jobRunId) {
        JobRunContext context = activeJobs.get(jobRunId)
        if (!context) {
            return [error: "Job run not found: ${jobRunId}"]
        }
        
        List<JobEvent> events = getJobEventHistory(jobRunId)
        
        return [
            jobRunId: jobRunId,
            jobName: context.jobName,
            serviceName: context.serviceName,
            status: context.statusId,
            runBy: context.runBy,
            runThread: context.runThread,
            startTime: context.startTime,
            endTime: context.endTime,
            executionTimeMs: context.executionTimeMs,
            hasError: context.hasError,
            results: context.results,
            parameters: context.parameters,
            contextData: context.contextData,
            logMessageCount: context.logMessages.size(),
            eventCount: events.size(),
            isActive: activeJobs.containsKey(jobRunId),
            summary: [
                totalEvents: events.size(),
                errorEvents: events.count { it.eventType == "JOB_ERROR" },
                serviceExecutions: events.count { it.eventType == "SERVICE_EXECUTION" },
                statusChanges: events.count { it.eventType == "STATUS_CHANGED" }
            ]
        ]
    }
    
    /**
     * Get all job run summaries for testing dashboard
     */
    static List<Map<String, Object>> getAllJobRunSummaries() {
        List<Map<String, Object>> summaries = []
        
        activeJobs.keySet().each { jobRunId ->
            summaries.add(generateJobRunSummary(jobRunId))
        }
        
        return summaries
    }
}
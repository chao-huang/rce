/*
 * Copyright (C) 2006-2015 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */
package de.rcenvironment.components.cluster.execution;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import de.rcenvironment.components.cluster.common.ClusterComponentConstants;
import de.rcenvironment.components.cluster.execution.internal.ClusterJobFinishListener;
import de.rcenvironment.core.component.api.ComponentException;
import de.rcenvironment.core.component.datamanagement.api.ComponentDataManagementService;
import de.rcenvironment.core.component.execution.api.ComponentContext;
import de.rcenvironment.core.component.execution.api.ComponentLog;
import de.rcenvironment.core.component.executor.SshExecutorConstants;
import de.rcenvironment.core.component.model.spi.DefaultComponent;
import de.rcenvironment.core.configuration.ConfigurationService;
import de.rcenvironment.core.datamodel.api.TypedDatum;
import de.rcenvironment.core.datamodel.types.api.DirectoryReferenceTD;
import de.rcenvironment.core.datamodel.types.api.IntegerTD;
import de.rcenvironment.core.utils.cluster.ClusterQueuingSystem;
import de.rcenvironment.core.utils.cluster.ClusterQueuingSystemConstants;
import de.rcenvironment.core.utils.cluster.ClusterService;
import de.rcenvironment.core.utils.cluster.ClusterServiceManager;
import de.rcenvironment.core.utils.common.StringUtils;
import de.rcenvironment.core.utils.common.TempFileServiceAccess;
import de.rcenvironment.core.utils.common.concurrent.AsyncExceptionListener;
import de.rcenvironment.core.utils.common.concurrent.CallablesGroup;
import de.rcenvironment.core.utils.common.concurrent.SharedThreadPool;
import de.rcenvironment.core.utils.common.concurrent.TaskDescription;
import de.rcenvironment.core.utils.common.validation.ValidationFailureException;
import de.rcenvironment.core.utils.executor.CommandLineExecutor;
import de.rcenvironment.core.utils.ssh.jsch.SshSessionConfiguration;
import de.rcenvironment.core.utils.ssh.jsch.SshSessionConfigurationFactory;
import de.rcenvironment.core.utils.ssh.jsch.executor.context.JSchExecutorContext;

/**
 * Submits a job to a cluster batch system with upload and download of directories.
 * 
 * @author Doreen Seider
 */
public class ClusterComponent extends DefaultComponent {

    private static final String FAILED_TO_WAIT_FOR_JOB_TO_BECOME_COMPLETED = "Failed to wait for job to become completed";

    private static final String FAILED_TO_SUBMIT_JOB = "Failed to submit job: ";

    private static final String FAILED_FILE_NAME = "cluster_job_failed";

    private static final String SLASH = "/";

    private static final String OUTPUT_FOLDER_NAME = "output";

    private static final String AT = "@";

    private static final String PATH_PATTERN = "iteration-%d/cluster-job-%d";

    private static Log log = LogFactory.getLog(ClusterComponent.class);
    
    private final Object executorLock = new Object();
    
    private ComponentLog componentLog;

    private ComponentContext componentContext;

    private ClusterServiceManager clusterServiceManager;

    private ComponentDataManagementService dataManagementService;

    private ClusterComponentConfiguration clusterConfiguration;

    private SshSessionConfiguration sshConfiguration;

    private JSchExecutorContext context;

    private CommandLineExecutor executor;

    private ClusterService clusterService;

    private ClusterQueuingSystem queuingSystem;

    private Map<String, String> pathsToQueuingSystemCommands;

    private Integer jobCount = null;

    private boolean considerSharedInputDir = true;

    private int iteration = 0;

    private Semaphore upDownloadSemaphore;

    private boolean isJobScriptProvidedWithinInputDir;

    private Map<String, Deque<TypedDatum>> inputValues = new HashMap<String, Deque<TypedDatum>>();
    
    @Override
    public void setComponentContext(ComponentContext componentContext) {
        this.componentContext = componentContext;
        componentLog = componentContext.getLog();
    }

    @Override
    public void start() throws ComponentException {
        clusterServiceManager = componentContext.getService(ClusterServiceManager.class);
        dataManagementService = componentContext.getService(ComponentDataManagementService.class);

        ConfigurationService configurationService = componentContext.getService(ConfigurationService.class);
        // TODO 6.0.0 review: preliminary path
        clusterConfiguration = new ClusterComponentConfiguration(
            configurationService.getConfigurationSegment("componentSettings/de.rcenvironment.cluster"));

        isJobScriptProvidedWithinInputDir = Boolean.valueOf(componentContext.getConfigurationValue(
            ClusterComponentConstants.KEY_IS_SCRIPT_PROVIDED_WITHIN_INPUT_DIR));

        String host = componentContext.getConfigurationValue(SshExecutorConstants.CONFIG_KEY_HOST);
        Integer port = Integer.valueOf(componentContext.getConfigurationValue(SshExecutorConstants.CONFIG_KEY_PORT));
        String authUser = componentContext.getConfigurationValue(SshExecutorConstants.CONFIG_KEY_AUTH_USER);
        String authPhrase = componentContext.getConfigurationValue(SshExecutorConstants.CONFIG_KEY_AUTH_PHRASE);
        String sandboRootWorkDir = componentContext.getConfigurationValue(SshExecutorConstants.CONFIG_KEY_SANDBOXROOT);

        queuingSystem = ClusterQueuingSystem.valueOf(componentContext.getConfigurationValue(
            ClusterComponentConstants.CONFIG_KEY_QUEUINGSYSTEM));
        pathsToQueuingSystemCommands = ClusterComponentConstants.extractPathsToQueuingSystemCommands(
            componentContext.getConfigurationValue(ClusterComponentConstants.CONFIG_KEY_PATHTOQUEUINGSYSTEMCOMMANDS));
        sshConfiguration = SshSessionConfigurationFactory.createSshSessionConfigurationWithAuthPhrase(host, port, authUser, authPhrase);

        clusterService = clusterServiceManager.retrieveSshBasedClusterService(
            queuingSystem,
            pathsToQueuingSystemCommands,
            sshConfiguration.getDestinationHost(),
            sshConfiguration.getPort(),
            sshConfiguration.getSshAuthUser(),
            sshConfiguration.getSshAuthPhrase());

        context = new JSchExecutorContext(sshConfiguration, sandboRootWorkDir);

        try {
            context.setUpSession();
            componentLog.componentInfo("Session established: " + authUser + AT + host + ":" + port);
        } catch (IOException e) {
            throw new ComponentException("Failed to establish connection to remote host", e);
        } catch (ValidationFailureException e) {
            throw new ComponentException("Failed to validate passed parameters", e);
        }

        try {
            executor = context.setUpSandboxedExecutor();
            componentLog.componentInfo("Remote sandbox created: " + executor.getWorkDirPath());
        } catch (IOException e) {
            throw new ComponentException("Failed to set up remote sandbox", e);
        }

        upDownloadSemaphore = new Semaphore(clusterConfiguration.getMaxChannels());

        if (!componentContext.getInputs().contains(ClusterComponentConstants.INPUT_JOBCOUNT)) {
            jobCount = 1;
        }
        if (!componentContext.getInputs().contains(ClusterComponentConstants.INPUT_SHAREDJOBINPUT)) {
            considerSharedInputDir = false;
        }
    }

    @Override
    public void processInputs() throws ComponentException {
        for (String inputName : componentContext.getInputsWithDatum()) {
            if (!inputValues.containsKey(inputName)) {
                inputValues.put(inputName, new LinkedList<TypedDatum>());
            }
            inputValues.get(inputName).add(componentContext.readInput(inputName));
        }
        if (jobCount == null && inputValues.containsKey(ClusterComponentConstants.INPUT_JOBCOUNT)) {
            jobCount = readAndEvaluateJobCount();
        }
        if (jobCount != null && inputValues.containsKey(ClusterComponentConstants.INPUT_JOBINPUTS)
            && inputValues.get(ClusterComponentConstants.INPUT_JOBINPUTS).size() >= jobCount
            && (!considerSharedInputDir || (inputValues.containsKey(ClusterComponentConstants.INPUT_SHAREDJOBINPUT)
            && inputValues.get(ClusterComponentConstants.INPUT_SHAREDJOBINPUT).size() >= 1))) {

            // consume inputs
            List<DirectoryReferenceTD> inputDirs = new ArrayList<DirectoryReferenceTD>();
            for (int i = 0; i < jobCount; i++) {
                inputDirs.add((DirectoryReferenceTD) inputValues.get(ClusterComponentConstants.INPUT_JOBINPUTS).poll());
            }
            DirectoryReferenceTD sharedInputDir = null;
            if (considerSharedInputDir) {
                sharedInputDir = (DirectoryReferenceTD) inputValues
                    .get(ClusterComponentConstants.INPUT_SHAREDJOBINPUT).poll();                
            }

            // upload
            uploadInputDirectories(inputDirs, sharedInputDir);

            if (!isJobScriptProvidedWithinInputDir) {
                uploadJobScript();
            }

            // execution
            Queue<BlockingQueue<String>> queues = submitJobs();

            // download
            downloadDirectoriesAndSendToOutputsOnJobFinished(queues);

            jobCount = null;
            iteration++;
        }
    }
    
    private Integer readAndEvaluateJobCount() throws ComponentException {
        Integer count = Integer.valueOf((int) ((IntegerTD) inputValues.get(ClusterComponentConstants.INPUT_JOBCOUNT).poll()).getIntValue());
        if (count <= 0) {
            throw new ComponentException(StringUtils.format("Job count is invalid. It is %d, but must be greater than 0", count));
        }
        return count;
    }

    @Override
    public void tearDown(FinalComponentState state) {
        super.tearDown(state);
        deleteSandboxIfNeeded();
    }

    private void deleteSandboxIfNeeded() {
        Boolean deleteSandbox = Boolean.valueOf(componentContext.getConfigurationValue(SshExecutorConstants.CONFIG_KEY_DELETESANDBOX));

        if (deleteSandbox) {
            String sandbox = executor.getWorkDirPath();
            try {
                // delete here explicitly as context.tearDownSandbox(executor) doesn't support -r option for safety reasons
                executor.start("rm -r " + sandbox);
                context.tearDownSession();
                componentLog.componentInfo("Remote sandbox deleted: " + sandbox);
            } catch (IOException e) {
                String errorMessage = "Failed to delete remote sandbox '%s'";
                componentLog.componentInfo(StringUtils.format(errorMessage, sandbox) + ": " + e.getMessage());
                log.error(StringUtils.format(errorMessage, sandbox), e);
            }
        }
    }

    private void uploadJobScript() throws ComponentException {
        String message = "Failed to upload job script";
        try {
            File jobFile = TempFileServiceAccess.getInstance().createTempFileWithFixedFilename(
                ClusterComponentConstants.JOB_SCRIPT_NAME);
            FileUtils.write(jobFile, componentContext.getConfigurationValue(SshExecutorConstants.CONFIG_KEY_SCRIPT));
            componentLog.componentInfo("Uploading job script: " + jobFile.getName());
            upDownloadSemaphore.acquire();
            executor.uploadFileToWorkdir(jobFile, ".");
            upDownloadSemaphore.release();
            TempFileServiceAccess.getInstance().disposeManagedTempDirOrFile(jobFile);
            componentLog.componentInfo("Job script uploaded: " + jobFile.getName());
        } catch (IOException | InterruptedException e) {
            throw new ComponentException(message, e);
        }

    }

    private void uploadInputDirectories(List<DirectoryReferenceTD> inputDirs, final DirectoryReferenceTD sharedInputDir) {

        componentLog.componentInfo("Uploading input directories...");
        int count = 0;
        CallablesGroup<RuntimeException> callablesGroup = SharedThreadPool.getInstance().createCallablesGroup(RuntimeException.class);

        for (final DirectoryReferenceTD inputDir : inputDirs) {
            final int countSnapshot = count++;
            callablesGroup.add(new Callable<RuntimeException>() {

                @Override
                @TaskDescription("Upload input directory for cluster job execution")
                public RuntimeException call() throws Exception {
                    try {
                        uploadInputDirectory(inputDir, "/cluster-job-" + countSnapshot, "input");
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                }
            });
        }

        if (sharedInputDir != null) {
            callablesGroup.add(new Callable<RuntimeException>() {
    
                @Override
                @TaskDescription("Upload shared input directory for cluster job execution")
                public RuntimeException call() throws Exception {
                    try {
                        componentLog.componentInfo("Uploading shared input directory...");
                        uploadInputDirectory(sharedInputDir, "", "cluster-job-shared-input");
                        componentLog.componentInfo("Shared input directory uploaded");
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                }
            });
        }

        List<RuntimeException> exceptions = callablesGroup.executeParallel(new AsyncExceptionListener() {

            @Override
            public void onAsyncException(Exception e) {
                // should never happen
                log.warn("Illegal state: Uncaught exception from Callable", e);
            }
        });

        for (RuntimeException e : exceptions) {
            if (e != null) {
                log.error("Exception caught when uploading directories", e);
            }
        }

        for (RuntimeException e : exceptions) {
            if (e != null) {
                throw e;
            }
        }

        componentLog.componentInfo("Input directories uploaded");
    }

    private void uploadInputDirectory(DirectoryReferenceTD jobDir, String directoryParent, String dirName) throws ComponentException {
        String message = "Failed to upload directory: ";
        try {
            File dir = TempFileServiceAccess.getInstance().createManagedTempDir();
            dataManagementService.copyDirectoryReferenceTDToLocalDirectory(componentContext, jobDir,
                dir);
            componentLog.componentInfo("Uploading directory: " + jobDir.getDirectoryName());
            File inputDir = new File(dir, dirName);
            new File(dir, jobDir.getDirectoryName()).renameTo(inputDir);
            upDownloadSemaphore.acquire();
            executor.uploadDirectoryToWorkdir(inputDir, "iteration-" + iteration + directoryParent);
            upDownloadSemaphore.release();
            TempFileServiceAccess.getInstance().disposeManagedTempDirOrFile(dir);
            componentLog.componentInfo("Directory uploaded: " + jobDir.getDirectoryName());
        } catch (IOException | InterruptedException e) {
            throw new ComponentException(message + jobDir.getDirectoryName(), e);
        }
    }

    private Queue<BlockingQueue<String>> submitJobs() throws ComponentException {
        Queue<BlockingQueue<String>> blockingQueues = new LinkedList<BlockingQueue<String>>();
        for (int i = 0; i < jobCount; i++) {
            blockingQueues.add(submitJob(i));
        }

        return blockingQueues;
    }

    private BlockingQueue<String> submitJob(int job) throws ComponentException {

        final int byteBuffer = 10000;

        String stdout;

        try {
            String mkDirOutputCommand = "mkdir " + getOutputFolderPath(job) + " ";
            executor.start(mkDirOutputCommand);
            executor.waitForTermination();
            String qsubCommand = buildQsubCommand(getJobFolderPath(job));
            executor.start(qsubCommand);
            componentLog.componentInfo(StringUtils.format("Job submitted: %s from %s", 
                ClusterComponentConstants.JOB_SCRIPT_NAME, getJobFolderPath(job)));
            
            try (InputStream stdoutStream = executor.getStdout();
                InputStream stderrStream = executor.getStderr()) {

                executor.waitForTermination();

                try (BufferedInputStream bufferedStdoutStream = new BufferedInputStream(stdoutStream);
                    BufferedInputStream bufferedStderrStream = new BufferedInputStream(stderrStream)) {

                    bufferedStdoutStream.mark(byteBuffer);
                    bufferedStderrStream.mark(byteBuffer);

                    String stderr = IOUtils.toString(bufferedStderrStream);
                    if (stderr != null && !stderr.isEmpty()) {
                        throw new ComponentException(FAILED_TO_SUBMIT_JOB + stderr);
                    }
                    stdout = IOUtils.toString(bufferedStdoutStream);

                    bufferedStdoutStream.reset();
                    bufferedStderrStream.reset();

                    // do it after termination because stdout and stderr is needed for component logic and not only for logging purposes.
                    // the delay is short because cluster job submission produces only few console output and terminates very quickly
                    for (String line : stdout.split("\n")) {
                        componentLog.toolStdout(line);
                    }
                }
            }

        } catch (IOException | InterruptedException e) {
            throw new ComponentException("Failed to submit job", e);
        }

        String jobId = extractJobIdFromQsubStdout(stdout);

        componentLog.componentInfo("Id of submitted job: " + jobId);
        BlockingQueue<String> synchronousQueue = new SynchronousQueue<String>();
        clusterService.addClusterJobStateChangeListener(jobId, new ClusterJobFinishListener(synchronousQueue));

        return synchronousQueue;
    }

    private String buildQsubCommand(String path) throws ComponentException {
        String jobScript = ClusterComponentConstants.JOB_SCRIPT_NAME;
        if (isJobScriptProvidedWithinInputDir) {
            jobScript = "input" + SLASH + jobScript;
        } else {
            for (@SuppressWarnings("unused") String subdir : path.split(SLASH)) {
                jobScript = ".." + SLASH + jobScript;
            }
        }
        return buildQsubCommand(path, jobScript);
    }

    private String buildQsubCommand(String path, String jobScript) throws ComponentException {
        switch (queuingSystem) {
        case TORQUE:
            return buildTorqueQsubCommand(path, jobScript);
        case SGE:
            return buildSgeQsubCommand(path, jobScript);
        default:
            throw new ComponentException("Queuing system not supported: " + queuingSystem.name());
        }
    }

    private String buildQsubMainCommand() {
        String qsubCommand = ClusterQueuingSystemConstants.COMMAND_QSUB;
        // with Java 8 this can be improved by Map.getOrDefault()(
        if (pathsToQueuingSystemCommands.get(ClusterQueuingSystemConstants.COMMAND_QSUB) != null) {
            qsubCommand = pathsToQueuingSystemCommands.get(ClusterQueuingSystemConstants.COMMAND_QSUB)
                + ClusterQueuingSystemConstants.COMMAND_QSUB;
        }
        return qsubCommand;
    }

    private String buildTorqueQsubCommand(String path, String jobScript) {
        StringBuffer buffer = new StringBuffer();
        buffer.append("cd " + path);
        buffer.append(" && ");
        buffer.append(buildQsubMainCommand());
        buffer.append(" -d $PWD");
        buffer.append(" ");
        buffer.append(jobScript);
        return buffer.toString();
    }

    private String buildSgeQsubCommand(String path, String scriptFileName) {
        StringBuffer buffer = new StringBuffer();
        buffer.append("cd " + path);
        buffer.append(" && ");
        buffer.append(buildQsubMainCommand());
        buffer.append(" -wd $PWD");
        buffer.append(" ");
        buffer.append(scriptFileName);
        return buffer.toString();
    }

    private String extractJobIdFromQsubStdout(String stdout) throws ComponentException {
        switch (queuingSystem) {
        case TORQUE:
            return extractJobIdFromTorqueQsubStdout(stdout);
        case SGE:
            return extractJobIdFromSgeQsubStdout(stdout);
        default:
            throw new ComponentException("Queuing system not supported: " + queuingSystem.name());
        }
    }

    private String extractJobIdFromTorqueQsubStdout(String stdout) throws ComponentException {
        Matcher matcher = Pattern.compile("\\d+\\.\\w+").matcher(stdout);
        if (matcher.find()) {
            return matcher.group();
        } else {
            matcher = Pattern.compile("\\d+").matcher(stdout);
            if (matcher.find()) {
                return matcher.group();
            } else {
                throw new ComponentException(FAILED_TO_SUBMIT_JOB + stdout);
            }
        }
    }

    private String extractJobIdFromSgeQsubStdout(String stdout) throws ComponentException {
        Matcher matcher = Pattern.compile("\\d+").matcher(stdout);
        if (matcher.find()) {
            return matcher.group();
        } else {
            throw new ComponentException(FAILED_TO_SUBMIT_JOB + stdout);
        }
    }

    private void downloadDirectoriesAndSendToOutputsOnJobFinished(Queue<BlockingQueue<String>> queues) {

        CallablesGroup<RuntimeException> callablesGroup = SharedThreadPool.getInstance().createCallablesGroup(RuntimeException.class);

        int i = 0;
        for (BlockingQueue<String> queue : queues) {
            final BlockingQueue<String> queueSnapshot = queue;
            final int jobSnapshot = i++;
            callablesGroup.add(new Callable<RuntimeException>() {

                @Override
                @TaskDescription("Wait for Job termination, check for failure, and download output directory afterwards")
                public RuntimeException call() throws Exception {
                    try {
                        try {
                            if (queueSnapshot.take().equals(ClusterComponentConstants.CLUSTER_FETCHING_FAILED)) {
                                throw new ComponentException(FAILED_TO_WAIT_FOR_JOB_TO_BECOME_COMPLETED);
                            }
                            checkIfClusterJobSucceeded(jobSnapshot);
                            downloadDirectoryAndSendToOutput(jobSnapshot);
                        } catch (InterruptedException e) {
                            throw new RuntimeException("Internal error: " + FAILED_TO_WAIT_FOR_JOB_TO_BECOME_COMPLETED, e);
                        }
                        return null;
                    } catch (RuntimeException e) {
                        return e;
                    }
                }
            });
        }

        List<RuntimeException> exceptions = callablesGroup.executeParallel(new AsyncExceptionListener() {

            @Override
            public void onAsyncException(Exception e) {
                // should never happen
                log.warn("Illegal state: Uncaught exception from Callable", e);
            }
        });

        for (RuntimeException e : exceptions) {
            if (e != null) {
                log.error("Exception caught when downloading directories: " + e.getMessage());
            }
        }

        for (RuntimeException e : exceptions) {
            if (e != null) {
                throw e;
            }
        }

    }

    private void checkIfClusterJobSucceeded(int job) throws ComponentException {

        String message = StringUtils.format("Failed to determine if cluster job %d succeeded - assumed that it does"
            + " to avoid false negatives", job);
        String path = getOutputFolderPath(job);
        String command = StringUtils.format("ls %s", path);
        try {
            synchronized (executorLock) {
                executor.start(command);
                try (InputStream stdoutStream = executor.getStdout();
                    InputStream stderrStream = executor.getStderr()) {
                    executor.waitForTermination();
                    if (!IOUtils.toString(stderrStream).isEmpty()) {
                        componentLog.componentError(StringUtils.format("Failed to execute command '%s' on %s: %s", command,
                            sshConfiguration.getDestinationHost(), IOUtils.toString(stderrStream)));
                        componentLog.componentError(message);
                    } else if (IOUtils.toString(stdoutStream).contains(FAILED_FILE_NAME)) {
                        String errorMessage = "N/A";
                        File file = TempFileServiceAccess.getInstance().createTempFileWithFixedFilename("out-" + job);
                        try {
                            executor.downloadFileFromWorkdir(path + SLASH + FAILED_FILE_NAME, file);
                            errorMessage = FileUtils.readFileToString(file);
                        } catch (IOException e) {
                            componentLog.componentError(StringUtils.format("Failed to download file '%s' "
                                + "- error message could not be extracted", FAILED_FILE_NAME));
                        }
                        throw new ComponentException(StringUtils.format("Cluster job %d failed with message: %s", job, errorMessage));
                    } else {
                        componentLog.componentInfo(StringUtils.format("Cluster job %d succeeded", job));
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            componentLog.componentError(message + ": " + e.getMessage());
            log.error(message, e);
        }
    }

    private void downloadDirectoryAndSendToOutput(int job) throws ComponentException {

        String message = "Downloading output directory failed: ";
        String path = getOutputFolderPath(job);
        try {
            File dir = TempFileServiceAccess.getInstance().createManagedTempDir();
            componentLog.componentInfo("Downloading output directory: " + path);
            upDownloadSemaphore.acquire();
            executor.downloadDirectoryFromWorkdir(path, dir);
            upDownloadSemaphore.release();
            File outputDir = new File(dir, OUTPUT_FOLDER_NAME + "-" + job);
            new File(dir, OUTPUT_FOLDER_NAME).renameTo(outputDir);
            DirectoryReferenceTD dirRef = dataManagementService.createDirectoryReferenceTDFromLocalDirectory(componentContext,
                outputDir, outputDir.getName());
            componentContext.writeOutput(ClusterComponentConstants.OUTPUT_JOBOUTPUTS, dirRef);
            componentLog.componentInfo("Output directory downloaded: " + path + ". Will be sent as: " + outputDir.getName());
            TempFileServiceAccess.getInstance().disposeManagedTempDirOrFile(dir);
        } catch (IOException | InterruptedException e) {
            throw new ComponentException(message + path, e);
        }
    }

    private String getJobFolderPath(int job) {
        return StringUtils.format(PATH_PATTERN, iteration, job);
    }

    private String getOutputFolderPath(int job) {
        return getJobFolderPath(job) + SLASH + OUTPUT_FOLDER_NAME;
    }

}

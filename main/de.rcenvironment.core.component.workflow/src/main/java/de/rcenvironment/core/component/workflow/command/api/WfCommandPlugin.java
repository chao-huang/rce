/*
 * Copyright (C) 2006-2015 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */

package de.rcenvironment.core.component.workflow.command.api;

import java.io.File;
import java.io.FileNotFoundException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import de.rcenvironment.core.command.common.CommandException;
import de.rcenvironment.core.command.spi.CommandContext;
import de.rcenvironment.core.command.spi.CommandDescription;
import de.rcenvironment.core.command.spi.CommandPlugin;
import de.rcenvironment.core.communication.common.CommunicationException;
import de.rcenvironment.core.communication.common.NodeIdentifier;
import de.rcenvironment.core.component.execution.api.ExecutionControllerException;
import de.rcenvironment.core.component.workflow.execution.api.FinalWorkflowState;
import de.rcenvironment.core.component.workflow.execution.api.WorkflowExecutionException;
import de.rcenvironment.core.component.workflow.execution.api.WorkflowExecutionInformation;
import de.rcenvironment.core.component.workflow.execution.api.WorkflowExecutionUtils;
import de.rcenvironment.core.component.workflow.execution.api.WorkflowFileException;
import de.rcenvironment.core.component.workflow.execution.api.WorkflowState;
import de.rcenvironment.core.component.workflow.execution.headless.api.HeadlessWorkflowDescriptionLoaderCallback;
import de.rcenvironment.core.component.workflow.execution.headless.api.HeadlessWorkflowExecutionContextBuilder;
import de.rcenvironment.core.component.workflow.execution.headless.api.HeadlessWorkflowExecutionService;
import de.rcenvironment.core.component.workflow.execution.headless.api.HeadlessWorkflowExecutionService.DeletionBehavior;
import de.rcenvironment.core.component.workflow.model.api.WorkflowDescription;
import de.rcenvironment.core.datamanagement.MetaDataService;
import de.rcenvironment.core.utils.common.StringUtils;
import de.rcenvironment.core.utils.common.concurrent.AsyncExceptionListener;
import de.rcenvironment.core.utils.common.concurrent.CallablesGroup;
import de.rcenvironment.core.utils.common.concurrent.SharedThreadPool;
import de.rcenvironment.core.utils.common.concurrent.TaskDescription;
import de.rcenvironment.core.utils.common.rpc.RemoteOperationException;
import de.rcenvironment.core.utils.common.textstream.TextOutputReceiver;

/**
 * A {@link CommandPlugin} providing "wf [...]" commands.
 * 
 * @author Robert Mischke
 * @author Doreen Seider
 * @author Brigitte Boden
 */
public class WfCommandPlugin implements CommandPlugin {

    private static final String DELETE_COMMAND = "delete";

    private static final int PARSING_WORKFLOW_FILE_RETRY_INTERVAL = 2000;

    private static final int MAXIMUM_WORKFLOW_PARSE_RETRIES = 5;

    private static final String[] SELF_TEST_RELATIVE_WF_FOLDER_PATHS =
        new String[] {
            "de.rcenvironment.core.component.workflow.tests/src/test/resources/workflows_automated_without_placeholders",
            "de.rcenvironment.core.component.workflow.tests/src/test/resources/workflows_automated_with_placeholders",
            // note: this path is not intended as these files' permanent place; move to non-gui
            // fragment - misc_ro
            "de.rcenvironment.core.gui.wizards.exampleproject/templates/workflows_examples"
        };

    private static final String BASEDIR_OPTION = "--basedir";

    private static final String STRING_DOT = ".";

    private static final int WORKFLOW_SUFFIX_NUMBER_MODULO = 100;

    private static final String WRONG_STATE_ERROR = "%s workflow not possible in current workflow state: %s";

    private static final String WORKFLOW_ID = "<id>";

    // TODO >5.0.0: crude fix for #10436 - align better with generated workflow name - misc_ro
    private static final AtomicInteger GLOBAL_WORKFLOW_SUFFIX_SEQUENCE_COUNTER = new AtomicInteger();

    private HeadlessWorkflowExecutionService workflowExecutionService;

    private AtomicInteger sequenceNumberGenerator = new AtomicInteger();

    private Log log = LogFactory.getLog(getClass());

    private MetaDataService metaDataService;

    @Override
    public Collection<CommandDescription> getCommandDescriptions() {
        final Collection<CommandDescription> contributions = new ArrayList<CommandDescription>();
        contributions.add(new CommandDescription("wf", "", false, "short form of \"wf list\""));
        contributions.add(new CommandDescription("wf run", "[--delete <onfinished|never|always>] [--compact-output] "
            + "[-p <JSON placeholder file>] <workflow file>", false, "execute a workflow file"));
        contributions.add(new CommandDescription("wf verify",
            "[--delete <onfinished|never|always>] [--pr <parallel runs>] [--sr <sequential runs>] [-p <JSON placeholder file>] "
                + "([--basedir <root directory for all subsequent files>] (<workflow filename>|\"*\")+ )+",
            false, "batch test the specified workflow files"));
        contributions.add(new CommandDescription("wf list", "",
            false, "show workflow list"));
        contributions.add(new CommandDescription("wf details", WORKFLOW_ID,
            false, "show details of a workflow"));
        contributions.add(new CommandDescription("wf pause", WORKFLOW_ID,
            false, "pause a running workflow"));
        contributions.add(new CommandDescription("wf resume", WORKFLOW_ID,
            false, "resume a paused workflow"));
        contributions.add(new CommandDescription("wf cancel", WORKFLOW_ID,
            false, "cancel a running or paused workflow"));
        contributions.add(new CommandDescription("wf delete", WORKFLOW_ID,
            false, "delete and dispose a finished, cancelled or failed workflow"));
        contributions.add(new CommandDescription("wf self-test", "-p <JSON placeholder file> <root path containing RCE core projects>",
            true, "batch test all default workflow files"));
        return contributions;
    }

    @Override
    public void execute(CommandContext context) throws CommandException {
        context.consumeExpectedToken("wf");
        String subCmd = context.consumeNextToken();
        if (subCmd == null) {
            // "wf" -> "wf list" by default
            performWfList(context);
        } else {
            if ("run".equals(subCmd)) {
                // "wf run <filename>"
                performWfRun(context);
            } else if ("verify".equals(subCmd)) {
                // "wf verify ..."
                performWfVerify(context);
            } else if ("pause".equals(subCmd)) {
                // "wf pause ..."
                performWfPause(context);
            } else if ("resume".equals(subCmd)) {
                // "wf resume ..."
                performWfResume(context);
            } else if ("cancel".equals(subCmd)) {
                // "wf cancel ..."
                performWfCancel(context);
            } else if ("dispose".equals(subCmd)) {
                // "wf dispose ..."
                performWfDisposeOrDelete(context, subCmd);
            } else if (DELETE_COMMAND.equals(subCmd)) {
                // "wf delete ..."
                performWfDisposeOrDelete(context, subCmd);
            } else if ("list".equals(subCmd)) {
                // "wf list ..."
                performWfList(context);
            } else if ("details".equals(subCmd)) {
                // "wf details ..."
                performWfShowDetails(context);
            } else if ("self-test".equals(subCmd)) {
                performWfSelfTest(context);
            } else {
                throw CommandException.unknownCommand(context);
            }
        }
    }

    /**
     * OSGi-DS lifecycle method.
     */
    public void activate() {}

    /**
     * OSGi-DS bind method.
     * 
     * @param newInstance the new service instance
     */
    public void bindWorkflowExecutionService(HeadlessWorkflowExecutionService newInstance) {
        this.workflowExecutionService = newInstance;
    }

    /**
     * OSGi-DS bind method.
     * 
     * @param newInstance the new service instance
     */
    public void bindMetaDataService(MetaDataService newInstance) {
        this.metaDataService = newInstance;
    }

    private void performWfRun(CommandContext cmdCtx) throws CommandException {
        // "wf run [--dispose <...>] [--compact-output] [-p <JSON placeholder file>] <filename>"

        HeadlessWorkflowExecutionService.DisposalBehavior dispose = readOptionalDisposeParameter(cmdCtx);

        HeadlessWorkflowExecutionService.DeletionBehavior delete = readOptionalDeleteParameter(cmdCtx);

        boolean compactId = cmdCtx.consumeNextTokenIfEquals("--compact-output");

        File placeholdersFile = readOptionalPlaceholdersFileParameter(cmdCtx);

        final String filename = cmdCtx.consumeNextToken();
        // verify: filename is present and the only parameter
        if (filename == null) {
            throw CommandException.syntaxError("Missing filename", cmdCtx);
        }

        final String additionalToken = cmdCtx.consumeNextToken();
        if (additionalToken != null) {
            throw CommandException.syntaxError("Expected end of command, but found another argument: " + additionalToken, cmdCtx);
        }
        final File wfFile;
        try {
            wfFile = WorkflowExecutionUtils.resolveWorkflowOrPlaceholderFileLocation(filename,
                WorkflowExecutionUtils.DEFAULT_ERROR_MESSAGE_TEMPLATE_CANNOT_READ_WORKFLOW_FILE);
        } catch (FileNotFoundException e) {
            throw CommandException.executionError(e.getMessage(), cmdCtx);
        }

        // introduced to allow retries in distributed setup, if not all required connections are
        // established when --batch is executed
        // It slows down the execution as parsing the workflow file is done twice now. Should be
        // improved. -seid_do
        validateWorkflow(cmdCtx, wfFile);

        try {
            // TODO specify log directory?
            HeadlessWorkflowExecutionContextBuilder exeContextBuilder =
                new HeadlessWorkflowExecutionContextBuilder(wfFile, setupLogDirectoryForWfFile(wfFile));
            exeContextBuilder.setPlaceholdersFile(placeholdersFile);
            exeContextBuilder.setTextOutputReceiver(cmdCtx.getOutputReceiver(), compactId);
            exeContextBuilder.setDisposalBehavior(dispose);
            exeContextBuilder.setDeletionBehavior(delete);

            workflowExecutionService.executeWorkflowSync(exeContextBuilder.build());
        } catch (WorkflowExecutionException e) {
            log.error("Exception while executing workflow from file: " + wfFile.getAbsolutePath(), e);
            throw CommandException.executionError(e.getMessage(), cmdCtx);
        }
    }

    private void validateWorkflow(CommandContext context, File wfFile) throws CommandException {
        int retries = 0;
        while (true) {
            try {
                WorkflowDescription workflowDescription = workflowExecutionService
                    .loadWorkflowDescriptionFromFileConsideringUpdates(wfFile,
                        new HeadlessWorkflowDescriptionLoaderCallback(context.getOutputReceiver()));
                if (workflowExecutionService.validateWorkflowDescription(workflowDescription).isSucceeded()) {
                    break;
                } else {
                    if (retries >= MAXIMUM_WORKFLOW_PARSE_RETRIES) {
                        log.debug(StringUtils.format("Maximum number of retries (%d) reached while validating the workflow file '%s'",
                            MAXIMUM_WORKFLOW_PARSE_RETRIES, wfFile.getAbsolutePath()));
                        throw CommandException.executionError(
                            StringUtils.format("Workflow file '%s' is not valid. See log above for more details.",
                                wfFile.getAbsolutePath()),
                            context);
                    }
                    log.debug("Retrying workflow validation in a few seconds.");
                    try {
                        Thread.sleep(PARSING_WORKFLOW_FILE_RETRY_INTERVAL);
                    } catch (InterruptedException e1) {
                        log.error("Waiting for parsing retry failed", e1);
                        throw CommandException.executionError(e1.getMessage(), context);
                    }
                    retries++;
                }
            } catch (WorkflowFileException e) {
                log.error("Exception while parsing the workflow file " + wfFile.getAbsolutePath(), e);
                throw CommandException.executionError(e.getMessage(), context);
            }
        }
    }

    private void performWfVerify(final CommandContext context) throws CommandException {
        HeadlessWorkflowExecutionService.DisposalBehavior dispose = readOptionalDisposeParameter(context);
        HeadlessWorkflowExecutionService.DeletionBehavior delete = readOptionalDeleteParameter(context);

        int parallelRuns = readOptionalParallelRunsParameter(context);
        int sequentialRuns = readOptionalSequentialRunsParameter(context);
        File placeholdersFile = readOptionalPlaceholdersFileParameter(context);
        List<File> wfFiles = parseWfVerifyCommand(context);
        executeWfVerifySetup(context, wfFiles, placeholdersFile, parallelRuns, sequentialRuns, dispose, delete);
    }

    private void performWfPause(final CommandContext context) throws CommandException {
        TextOutputReceiver outputReceiver = context.getOutputReceiver();

        final String executionId = context.consumeNextToken();
        // verify: executionId is present
        if (executionId == null) {
            throw CommandException.wrongNumberOfParameters(context);
        }

        WorkflowExecutionInformation wfExecInf = getWfExecInfFromExecutionId(executionId, outputReceiver);

        if (wfExecInf != null) {
            // Find the node running this workflow
            NodeIdentifier nodeId = wfExecInf.getWorkflowDescription().getControllerNode();

            try {
                if (wfExecInf.getWorkflowState().equals(WorkflowState.RUNNING)) {
                    workflowExecutionService.pause(executionId, nodeId);
                } else {
                    outputReceiver.addOutput(StringUtils.format(WRONG_STATE_ERROR, "Pausing", wfExecInf.getWorkflowState()));
                }
            } catch (ExecutionControllerException | RemoteOperationException e) {
                log.error(StringUtils.format("Failed to pause workflow '%s'; cause: %s", executionId, e.toString()));
            }
        }
    }

    private void performWfResume(final CommandContext context) throws CommandException {
        TextOutputReceiver outputReceiver = context.getOutputReceiver();

        final String executionId = context.consumeNextToken();
        // verify: executionId is present
        if (executionId == null) {
            throw CommandException.wrongNumberOfParameters(context);
        }
        WorkflowExecutionInformation wExecInf = getWfExecInfFromExecutionId(executionId, outputReceiver);

        if (wExecInf != null) {
            // Find the node running this workflow
            NodeIdentifier nodeId = wExecInf.getWorkflowDescription().getControllerNode();

            try {
                if (wExecInf.getWorkflowState().equals(WorkflowState.PAUSED)) {
                    workflowExecutionService.resume(executionId, nodeId);
                } else {
                    outputReceiver.addOutput(StringUtils.format(WRONG_STATE_ERROR, "Resuming", wExecInf.getWorkflowState()));
                }
            } catch (ExecutionControllerException | RemoteOperationException e) {
                log.error(StringUtils.format("Failed to resume workflow '%s'; cause: %s", executionId, e.toString()));
            }
        }
    }

    private void performWfCancel(final CommandContext context) throws CommandException {
        TextOutputReceiver outputReceiver = context.getOutputReceiver();

        final String executionId = context.consumeNextToken();
        // verify: executionId is present
        if (executionId == null) {
            throw CommandException.wrongNumberOfParameters(context);
        }

        WorkflowExecutionInformation wExecInf = getWfExecInfFromExecutionId(executionId, outputReceiver);

        if (wExecInf != null) {
            // Find the node running this workflow
            NodeIdentifier nodeId = wExecInf.getWorkflowDescription().getControllerNode();

            try {
                if (wExecInf.getWorkflowState().equals(WorkflowState.RUNNING)
                    || wExecInf.getWorkflowState().equals(WorkflowState.PAUSED)) {
                    workflowExecutionService.cancel(executionId, nodeId);
                } else {
                    outputReceiver.addOutput(StringUtils.format(WRONG_STATE_ERROR, "Canceling", wExecInf.getWorkflowState()));
                }
            } catch (ExecutionControllerException | RemoteOperationException e) {
                log.error(StringUtils.format("Failed to cancel workflow '%s'; cause: %s", executionId, e.toString()));
            }
        }
    }

    private void performWfDisposeOrDelete(final CommandContext context, String token) throws CommandException {
        TextOutputReceiver outputReceiver = context.getOutputReceiver();

        final String executionId = context.consumeNextToken();
        // verify: executionId is present
        if (executionId == null) {
            throw CommandException.wrongNumberOfParameters(context);
        }

        WorkflowExecutionInformation wExecInf = getWfExecInfFromExecutionId(executionId, outputReceiver);

        if (wExecInf != null) {
            // Find the node running this workflow
            NodeIdentifier nodeId = wExecInf.getWorkflowDescription().getControllerNode();

            try {
                if (wExecInf.getWorkflowState().equals(WorkflowState.CANCELLED)
                    || wExecInf.getWorkflowState().equals(WorkflowState.FAILED)
                    || wExecInf.getWorkflowState().equals(WorkflowState.FINISHED)) {
                    if (token.equals(DELETE_COMMAND)) {
                        metaDataService.deleteWorkflowRun(wExecInf.getWorkflowDataManagementId(), nodeId);
                    }
                    workflowExecutionService.dispose(executionId, nodeId);

                } else {
                    if (token.equals(DELETE_COMMAND)) {
                        outputReceiver.addOutput(StringUtils.format(WRONG_STATE_ERROR, "Deleting", wExecInf.getWorkflowState()));
                    } else {
                        outputReceiver.addOutput(StringUtils.format(WRONG_STATE_ERROR, "Disposing", wExecInf.getWorkflowState()));
                    }
                }
            } catch (ExecutionControllerException | CommunicationException | RemoteOperationException e) {
                log.error(StringUtils.format("Failed to dispose workflow '%s'; cause: %s", executionId, e.toString()));
            }
        }
    }

    private void performWfList(final CommandContext context) throws CommandException {

        TextOutputReceiver outputReceiver = context.getOutputReceiver();
        outputReceiver.addOutput("Fetching workflows...");
        List<WorkflowExecutionInformation> wfInfos = new ArrayList<>(workflowExecutionService.getWorkflowExecutionInformations(true));
        Collections.sort(wfInfos);
        String output = "";
        int total = 0;
        int running = 0;
        int paused = 0;
        int finished = 0;
        int cancelled = 0;
        int failed = 0;
        int other = 0;

        for (WorkflowExecutionInformation wfInfo : wfInfos) {
            WorkflowState state = wfInfo.getWorkflowState();
            output += StringUtils.format(" '%s' - %s [%s]\n", wfInfo.getInstanceName(), state, wfInfo.getExecutionIdentifier());
            total++;
            switch (state) {
            case RUNNING:
                running++;
                break;
            case PAUSED:
                paused++;
                break;
            case FINISHED:
                finished++;
                break;
            case CANCELLED:
                cancelled++;
                break;
            case FAILED:
                failed++;
                break;
            default:
                other++;
            }
        }
        output +=
            StringUtils.format(" -- TOTAL COUNT: %d workflow(s): %d running, %d paused, %d finished, %d cancelled, %d failed, %d other -- ",
                total, running, paused, finished, cancelled, failed, other);
        outputReceiver.addOutput(output);
    }

    private void performWfShowDetails(final CommandContext context) throws CommandException {

        final String executionId = context.consumeNextToken();
        // verify: executionId is present
        if (executionId == null) {
            throw CommandException.wrongNumberOfParameters(context);
        }

        TextOutputReceiver outputReceiver = context.getOutputReceiver();
        WorkflowExecutionInformation wExecInf = getWfExecInfFromExecutionId(executionId, outputReceiver);
        SimpleDateFormat df = new SimpleDateFormat("yyyy.MM.dd  HH:mm:ss");
        if (wExecInf != null) {
            outputReceiver.addOutput("Name: " + wExecInf.getInstanceName());
            outputReceiver.addOutput("Status: " + wExecInf.getWorkflowState());
            outputReceiver.addOutput("Controller: " + wExecInf.getWorkflowDescription().getControllerNode().getAssociatedDisplayName());
            outputReceiver.addOutput("Start: " + df.format(wExecInf.getStartTime()));
            outputReceiver.addOutput("Started from: " + wExecInf.getNodeIdStartedExecution().getAssociatedDisplayName());
            outputReceiver.addOutput("Additional Information: ");
            String additional = wExecInf.getAdditionalInformationProvidedAtStart();
            if (additional != null) {
                outputReceiver.addOutput(additional);
            }
            // outputReceiver.addOutput("Execution Identifier: " +
            // workflow.getExecutionIdentifier());
            // outputReceiver.addOutput("Node Identifier: " +
            // workflow.getWorkflowDescription().getControllerNode().getIdString());
        }
    }

    private void performWfSelfTest(final CommandContext context) throws CommandException {
        // reuse code, although the parameter is not actually optional
        HeadlessWorkflowExecutionService.DisposalBehavior dispose = readOptionalDisposeParameter(context);
        HeadlessWorkflowExecutionService.DeletionBehavior delete = readOptionalDeleteParameter(context);

        File placeholdersFile = readOptionalPlaceholdersFileParameter(context);
        if (placeholdersFile == null) {
            throw CommandException.executionError("Placeholder file (\"-p <filename>\") must be specified for self-test", context);
        }
        String baseDirPath = context.consumeNextToken();
        if (baseDirPath == null) {
            throw CommandException.wrongNumberOfParameters(context);
        }
        File baseDir = new File(baseDirPath);
        if (!baseDir.isDirectory()) {
            throw CommandException.executionError("Base directory does not exist: " + baseDir.getAbsolutePath(), context);
        }
        // construct synthetic "wf verify" command tokens and delegate
        List<String> newTokens = new ArrayList<>();
        for (String testFilesPath : SELF_TEST_RELATIVE_WF_FOLDER_PATHS) {
            File absolutePath = new File(baseDir, testFilesPath);
            newTokens.add(BASEDIR_OPTION);
            newTokens.add(absolutePath.getAbsolutePath());
            newTokens.add("*");
        }
        CommandContext syntheticContext = new CommandContext(newTokens, context.getOutputReceiver(), context.getInvokerInformation());
        List<File> wfFiles = parseWfVerifyCommand(syntheticContext);
        executeWfVerifySetup(context, wfFiles, placeholdersFile, dispose, delete);
    }

    private List<File> parseWfVerifyCommand(final CommandContext context) throws CommandException {
        // "wf verify [-pr <parallel runs>] [-sr <sequential runs>] [-p <JSON placeholder file>]
        // [--basedir <dir>]
        // <filename> [<filename> ...]"
        // TODO replace File with a custom class when more parameters are needed - misc_ro
        List<File> wfFiles = new ArrayList<>();

        String lastBaseDirOption = null;
        File lastBaseDir = null;

        String token;
        while ((token = context.consumeNextToken()) != null) {
            if (BASEDIR_OPTION.equals(token)) {
                lastBaseDirOption = context.consumeNextToken();
                if (lastBaseDirOption == null) {
                    throw CommandException.syntaxError("--basedir option specified without a value", context);
                }
                lastBaseDir = new File(lastBaseDirOption);
                // validate
                if (!lastBaseDir.isDirectory()) {
                    throw CommandException.executionError("Specified --basedir is not a valid directory: "
                        + lastBaseDir.getAbsolutePath(), context);
                }
            } else if ("*".equals(token)) {
                if (lastBaseDir == null) {
                    throw CommandException.executionError("The \"*\" wildcard requires a previous --basedir", context);
                }
                int count = 0;
                for (String filename : lastBaseDir.list()) {
                    if (filename.endsWith(".wf") && !filename.endsWith("_backup.wf")) {
                        final File wfFile = new File(lastBaseDir, filename);
                        checkWfFileExists(context, wfFile);
                        wfFiles.add(wfFile);
                        count++;
                    }
                }
                context.println("Added " + count + " non-backup workflow file(s) from " + lastBaseDir.getAbsolutePath());
            } else {
                // no option -> filename
                final File wfFile;
                if (lastBaseDir != null) {
                    wfFile = new File(lastBaseDir, token);
                } else {
                    wfFile = new File(token);
                }
                checkWfFileExists(context, wfFile);
                wfFiles.add(wfFile);
            }
        }
        return wfFiles;
    }

    private Integer readOptionalParallelRunsParameter(CommandContext context) throws CommandException {
        return readOptionalRunsParameter(context, "--pr");
    }

    private Integer readOptionalSequentialRunsParameter(CommandContext context) throws CommandException {
        return readOptionalRunsParameter(context, "--sr");
    }

    private Integer readOptionalRunsParameter(CommandContext context, String parameter) throws CommandException {
        int numberOfRuns = 1; // default (parameter is optional)
        if (context.consumeNextTokenIfEquals(parameter)) {
            String number = context.consumeNextToken();
            if (number == null) {
                throw CommandException.syntaxError("Missing number of runs", context);
            }
            try {
                numberOfRuns = Integer.parseInt(number);
            } catch (NumberFormatException e) {
                throw CommandException.executionError(e.getMessage(), context);
            }
        }
        return numberOfRuns;
    }

    private HeadlessWorkflowExecutionService.DisposalBehavior readOptionalDisposeParameter(CommandContext context) throws CommandException {
        if (context.consumeNextTokenIfEquals("--dispose")) {
            String dispose = context.consumeNextToken();
            try {
                if (HeadlessWorkflowExecutionService.DisposalBehavior.Always.name().toLowerCase().equals(dispose)) {
                    return HeadlessWorkflowExecutionService.DisposalBehavior.Always;
                } else if (HeadlessWorkflowExecutionService.DisposalBehavior.Never.name().toLowerCase().equals(dispose)) {
                    return HeadlessWorkflowExecutionService.DisposalBehavior.Never;
                } else if (HeadlessWorkflowExecutionService.DisposalBehavior.OnFinished.name().toLowerCase().equals(dispose)) {
                    return HeadlessWorkflowExecutionService.DisposalBehavior.OnFinished;
                }
            } catch (IllegalArgumentException | NullPointerException e) {
                throw CommandException.syntaxError("Invalid dispose behavior: " + dispose, context);
            }
        }
        return HeadlessWorkflowExecutionService.DisposalBehavior.OnFinished;
    }

    private HeadlessWorkflowExecutionService.DeletionBehavior readOptionalDeleteParameter(CommandContext context) throws CommandException {
        if (context.consumeNextTokenIfEquals("--delete")) {
            String delete = context.consumeNextToken();
            try {
                if (HeadlessWorkflowExecutionService.DeletionBehavior.Always.name().toLowerCase().equals(delete)) {
                    return HeadlessWorkflowExecutionService.DeletionBehavior.Always;
                } else if (HeadlessWorkflowExecutionService.DeletionBehavior.Never.name().toLowerCase().equals(delete)) {
                    return HeadlessWorkflowExecutionService.DeletionBehavior.Never;
                } else if (HeadlessWorkflowExecutionService.DeletionBehavior.OnFinished.name().toLowerCase().equals(delete)) {
                    return HeadlessWorkflowExecutionService.DeletionBehavior.OnFinished;
                }
            } catch (IllegalArgumentException | NullPointerException e) {
                throw CommandException.syntaxError("Invalid delete behavior: " + delete, context);
            }
        }
        return HeadlessWorkflowExecutionService.DeletionBehavior.OnFinished;
    }

    private File readOptionalPlaceholdersFileParameter(CommandContext context) throws CommandException {
        File placeholdersFile = null; // optional
        if (context.consumeNextTokenIfEquals("-p")) {
            String placeholdersFilename = context.consumeNextToken();
            if (placeholdersFilename == null) {
                throw CommandException.syntaxError("Missing placeholder filename", context);
            }
            try {
                placeholdersFile =
                    WorkflowExecutionUtils.resolveWorkflowOrPlaceholderFileLocation(placeholdersFilename,
                        WorkflowExecutionUtils.DEFAULT_ERROR_MESSAGE_TEMPLATE_CANNOT_READ_PLACEHOLDER_FILE);
            } catch (FileNotFoundException e) {
                throw CommandException.executionError(e.getMessage(), context);
            }
        }
        return placeholdersFile;
    }

    private void checkWfFileExists(final CommandContext context, final File wfFile) throws CommandException {
        if (!wfFile.isFile()) {
            throw CommandException.executionError("Specified workflow file does not exist: " + wfFile.getAbsolutePath(), context);
        }
    }

    private void executeWfVerifySetup(final CommandContext context, List<File> wfFiles, final File placeholdersFile,
        HeadlessWorkflowExecutionService.DisposalBehavior dispose, DeletionBehavior delete) throws CommandException {
        executeWfVerifySetup(context, wfFiles, placeholdersFile, 1, 1, dispose, delete);
    }

    private void executeWfVerifySetup(final CommandContext context, List<File> wfFiles, final File placeholdersFile, int parallelRuns,
        int sequentialRuns, final HeadlessWorkflowExecutionService.DisposalBehavior dispose,
        final HeadlessWorkflowExecutionService.DeletionBehavior delete) throws CommandException {
        if (wfFiles.isEmpty()) {
            throw CommandException.syntaxError("Error: at least one workflow file must be specified", context);
        }
        final WfVerifyResult wfVerifyResult = new WfVerifyResult(wfFiles.size() * parallelRuns * sequentialRuns);
        for (int j = 0; j < sequentialRuns; j++) {
            CallablesGroup<Void> callablesGroup = SharedThreadPool.getInstance().createCallablesGroup(Void.class);
            for (int i = 0; i < parallelRuns; i++) {
                for (final File wfFile : wfFiles) {
                    // attach a task id to help with debugging, e.g. for identifying the thread of a
                    // stalled workflow - misc_ro
                    String taskId = StringUtils.format("wf-verify-%s-%s", sequenceNumberGenerator.incrementAndGet(), wfFile.getName());
                    callablesGroup.add(new Callable<Void>() {

                        @Override
                        @TaskDescription("Single 'wf verify' workflow execution")
                        public Void call() {
                            try {
                                // TODO specify log directory?
                                HeadlessWorkflowExecutionContextBuilder exeContextBuilder =
                                    new HeadlessWorkflowExecutionContextBuilder(wfFile, setupLogDirectoryForWfFile(wfFile));
                                exeContextBuilder.setPlaceholdersFile(placeholdersFile);
                                exeContextBuilder.setTextOutputReceiver(context.getOutputReceiver());
                                exeContextBuilder.setDisposalBehavior(dispose);
                                exeContextBuilder.setDeletionBehavior(delete);
                                FinalWorkflowState finalState = workflowExecutionService.executeWorkflowSync(exeContextBuilder.build());
                                wfVerifyResult.addFinalState(finalState);
                            } catch (WorkflowExecutionException | RuntimeException e) {
                                context.println(StringUtils.format("Error while executing workflow '%s': %s", wfFile, e.getMessage()));
                                log.warn("Exception while executing workflow '" + wfFile + "' triggered by 'wf verify' command", e);
                                wfVerifyResult.addError();
                            }
                            return null;
                        }
                    }, taskId);
                }
            }
            callablesGroup.executeParallel(new AsyncExceptionListener() {

                @Override
                public void onAsyncException(Exception e) {
                    context.println("Async error while executing workflow(s): " + e.toString());
                    log.warn("Async error while executing workflow(s) for 'wf verify' command", e);
                }
            });
        }
        context.println(StringUtils.format("Workflow verification results - %s", wfVerifyResult.asString()));
    }

    /**
     * Counts overall result of wf verify execution.
     * 
     * @author Doreen Seider
     */
    private class WfVerifyResult {

        private final int runsSubmitted;

        private AtomicInteger totalRuns = new AtomicInteger(0);

        private AtomicInteger finished = new AtomicInteger(0);

        private AtomicInteger failed = new AtomicInteger(0);

        private AtomicInteger canceled = new AtomicInteger(0);

        private AtomicInteger error = new AtomicInteger(0);

        public WfVerifyResult(int runsSubmitted) {
            this.runsSubmitted = runsSubmitted;
        }

        public void addError() {
            error.incrementAndGet();
            totalRuns.incrementAndGet();
        }

        public void addFinalState(FinalWorkflowState finalState) {
            switch (finalState) {
            case FINISHED:
                finished.incrementAndGet();
                break;
            case CANCELLED:
                canceled.incrementAndGet();
                break;
            case FAILED:
                failed.incrementAndGet();
                break;
            default:
                break;
            }
            totalRuns.incrementAndGet();
        }

        private String asString() {
            return StringUtils.format("Executions: %d/%d -> Finished: %d,  Cancelled: %d, Failed: %d, Error: %d",
                totalRuns.get(), runsSubmitted, finished.get(), canceled.get(), failed.get(), error.get());
        }
    }

    private File setupLogDirectoryForWfFile(File wfFile) throws WorkflowExecutionException {

        if (!wfFile.isFile()) {
            throw new WorkflowExecutionException("The workflow file \"" + wfFile.getAbsolutePath()
                + "\" does not exist or it cannot be opened");
        }

        File parentDir = wfFile.getParentFile();
        // sanity check
        if (!parentDir.isDirectory()) {
            throw new WorkflowExecutionException("Consistency error: parent directory is not a directory: " + parentDir.getAbsolutePath());
        }

        long millis = new GregorianCalendar().getTimeInMillis();

        String folderName = wfFile.getName();
        if (folderName.contains(STRING_DOT)) {
            folderName = folderName.substring(0, folderName.lastIndexOf(STRING_DOT));
        }

        // make the last two digits sequentially increasing to reduce the likelihood of timestamp
        // collisions
        // TODO >5.0.0: crude fix for #10436 - align better with generated workflow name - misc_ro
        int suffixNumber = GLOBAL_WORKFLOW_SUFFIX_SEQUENCE_COUNTER.incrementAndGet() % WORKFLOW_SUFFIX_NUMBER_MODULO;
        // TODO don't use SQL timestamp for formatting; also, use StringUtils.format()
        Timestamp ts = new Timestamp(millis);
        folderName = "logs/" + folderName + "_" + ts.toString().replace('.', '-').replace(' ', '_').replace(':', '-') + "_" + suffixNumber;

        File logDir = new File(parentDir, folderName);
        logDir.mkdirs();
        if (!logDir.isDirectory()) {
            throw new WorkflowExecutionException("Failed to create log directory" + logDir.getAbsolutePath());
        }
        return logDir;
    }

    /**
     * Helper function, detects the workflow information for a given executionId.
     * 
     */
    private WorkflowExecutionInformation getWfExecInfFromExecutionId(String executionId, TextOutputReceiver outputReceiver) {

        WorkflowExecutionInformation wExecInf = null;
        Set<WorkflowExecutionInformation> wis = workflowExecutionService.getWorkflowExecutionInformations();
        for (WorkflowExecutionInformation workflow : wis) {
            if (workflow.getExecutionIdentifier().equals(executionId)) {
                wExecInf = workflow;
                break;
            }
        }
        if (wExecInf == null) {
            outputReceiver.addOutput("Workflow with id '" + executionId + "' not found");
        }
        return wExecInf;
    }
    
}

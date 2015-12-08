/*
 * Copyright (C) 2006-2015 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */

package de.rcenvironment.core.component.integration;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.apache.commons.exec.OS;
import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.core.runtime.Platform;

import de.rcenvironment.core.component.api.ComponentConstants;
import de.rcenvironment.core.component.api.ComponentException;
import de.rcenvironment.core.component.api.ComponentUtils;
import de.rcenvironment.core.component.datamanagement.api.ComponentDataManagementService;
import de.rcenvironment.core.component.execution.api.ComponentContext;
import de.rcenvironment.core.component.execution.api.ComponentLog;
import de.rcenvironment.core.component.execution.api.ConsoleRow;
import de.rcenvironment.core.component.execution.api.ConsoleRowUtils;
import de.rcenvironment.core.component.model.spi.DefaultComponent;
import de.rcenvironment.core.component.scripting.WorkflowConsoleForwardingWriter;
import de.rcenvironment.core.datamodel.api.DataType;
import de.rcenvironment.core.datamodel.api.TypedDatum;
import de.rcenvironment.core.datamodel.api.TypedDatumFactory;
import de.rcenvironment.core.datamodel.api.TypedDatumService;
import de.rcenvironment.core.datamodel.types.api.DirectoryReferenceTD;
import de.rcenvironment.core.datamodel.types.api.FileReferenceTD;
import de.rcenvironment.core.datamodel.types.api.FloatTD;
import de.rcenvironment.core.datamodel.types.api.VectorTD;
import de.rcenvironment.core.scripting.ScriptDataTypeHelper;
import de.rcenvironment.core.scripting.ScriptingService;
import de.rcenvironment.core.scripting.ScriptingUtils;
import de.rcenvironment.core.utils.common.StringUtils;
import de.rcenvironment.core.utils.common.TempFileServiceAccess;
import de.rcenvironment.core.utils.common.security.StringSubstitutionSecurityUtils;
import de.rcenvironment.core.utils.common.security.StringSubstitutionSecurityUtils.SubstitutionContext;
import de.rcenvironment.core.utils.common.textstream.TextStreamWatcher;
import de.rcenvironment.core.utils.executor.LocalApacheCommandLineExecutor;
import de.rcenvironment.core.utils.scripting.ScriptLanguage;

/**
 * Main class for the generic tool integration.
 * 
 * @author Sascha Zur
 */
public class CommonToolIntegratorComponent extends DefaultComponent {

    private static final String DELETION_BEHAVIOR_ERROR_MSG =
        "Chosen working directory deletion behavior not support for tool %s. Please choose enabled behavior in component's properties.";

    // adjust if necessary/not reasonable for productive environments
    private static final int MAX_TOOLS_COPIED_IN_PARALLEL = 1;

    /** Lock to restrict how many tools can be copied at the same time. */
    private static final Semaphore COPY_TOOL_SEMAPHORE = new Semaphore(MAX_TOOLS_COPIED_IN_PARALLEL, true);

    private static final Log LOG = LogFactory.getLog(CommonToolIntegratorComponent.class);

    private static final String SLASH = "/";

    private static final String ESCAPESLASH = "\\\\";

    private static final String PROPERTY_PLACEHOLDER = "${prop:%s}";

    private static final String ADD_PROPERTY_PLACEHOLDER = "${addProp:%s}";

    private static final String OUTPUT_PLACEHOLDER = "${out:%s}";

    private static final String INPUT_PLACEHOLDER = "${in:%s}";

    private static final String SCRIPT_LANGUAGE = "Jython";

    private static final String DIRECTORY_PLACEHOLDER_TEMPLATE = "${dir:%s}";

    private static final String QUOTE = "\"";

    private static final String SUBSTITUTION_ERROR_MESSAGE_PREFIX = " can not be substituted in the script, because it contains"
        + " at least one unsecure character. See log message above to see which characters are affected";

    protected ComponentContext componentContext;

    protected ComponentLog componentLog;

    protected ComponentDataManagementService datamanagementService;

    protected File executionToolDirectory;

    protected File inputDirectory;

    protected File outputDirectory;

    protected IntegrationHistoryDataItem historyDataItem;

    protected Map<String, TypedDatum> lastRunStaticInputValues = null;

    protected Map<String, TypedDatum> lastRunStaticOutputValues = null;

    protected boolean needsToRun = true;

    protected String copyToolBehaviour;

    private ScriptingService scriptingService;

    private TypedDatumFactory typedDatumFactory;

    private File baseWorkingDirectory;

    private File iterationDirectory;

    private File configDirectory;

    private File sourceToolDirectory;

    private String rootWDPath;

    private int runCount;

    private File currentWorkingDirectory;

    private String deleteToolBehaviour;

    private boolean useIterationDirectories;

    private boolean dontCrashOnNonZeroExitCodes;

    private String jythonPath;

    private String workingPath;

    private boolean setToolDirectoryAsWorkingDirectory;

    private TextStreamWatcher stdoutWatcher;

    private TextStreamWatcher stderrWatcher;

    private Writer stdoutWriter;

    private Writer stderrWriter;

    private Map<String, Object> stateMap;

    private boolean keepOnFailure;

    private Map<String, String> outputMapping;

    @Override
    public void setComponentContext(ComponentContext componentContext) {
        this.componentContext = componentContext;
        componentLog = componentContext.getLog();
    }

    @Override
    public boolean treatStartAsComponentRun() {
        return componentContext.getInputs().isEmpty();
    }

    @Override
    public void start() throws ComponentException {
        datamanagementService = componentContext.getService(ComponentDataManagementService.class);
        scriptingService = componentContext.getService(ScriptingService.class);
        typedDatumFactory = componentContext.getService(TypedDatumService.class).getFactory();

        // Create basic folder structure and prepare sandbox

        String toolName = componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_TOOL_NAME);
        rootWDPath = componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_ROOT_WORKING_DIRECTORY);
        String toolDirPath = componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_TOOL_DIRECTORY);

        sourceToolDirectory = new File(toolDirPath);
        if (!sourceToolDirectory.isAbsolute()) {
            try {
                sourceToolDirectory = new File(new File(Platform.getInstallLocation().getURL().toURI()), toolDirPath);
            } catch (URISyntaxException e) {
                sourceToolDirectory = new File(new File(Platform.getInstallLocation().getURL().getPath()), toolDirPath);
            }
        }
        useIterationDirectories = Boolean.parseBoolean(componentContext.getConfigurationValue(
            ToolIntegrationConstants.KEY_TOOL_USE_ITERATION_DIRECTORIES));
        copyToolBehaviour = componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_COPY_TOOL_BEHAVIOUR);

        dontCrashOnNonZeroExitCodes = componentContext
            .getConfigurationValue(ToolIntegrationConstants.DONT_CRASH_ON_NON_ZERO_EXIT_CODES) != null
            && Boolean.parseBoolean(componentContext.getConfigurationValue(ToolIntegrationConstants.DONT_CRASH_ON_NON_ZERO_EXIT_CODES));
        setToolDirectoryAsWorkingDirectory =
            componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_SET_TOOL_DIR_AS_WORKING_DIR) != null
                && Boolean.parseBoolean(componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_SET_TOOL_DIR_AS_WORKING_DIR));
        if (copyToolBehaviour == null) {
            copyToolBehaviour = ToolIntegrationConstants.VALUE_COPY_TOOL_BEHAVIOUR_NEVER;
        }
        getToolDeleteBehaviour();

        try {
            if (rootWDPath == null || rootWDPath.isEmpty()) {
                baseWorkingDirectory = TempFileServiceAccess.getInstance().createManagedTempDir(toolName);
            } else {
                baseWorkingDirectory = new File(rootWDPath + File.separator + toolName + "_" + UUID.randomUUID().toString()
                    + File.separator);
                baseWorkingDirectory.mkdirs();
            }
            FileUtils.write(new File(baseWorkingDirectory, "rce-workflow-info.txt"), "Workflow name: "
                + componentContext.getWorkflowInstanceName());
            if (!useIterationDirectories) {
                iterationDirectory = baseWorkingDirectory;
                currentWorkingDirectory = baseWorkingDirectory;
                createFolderStructure(baseWorkingDirectory);

            }
            if (copyToolBehaviour.equals(ToolIntegrationConstants.VALUE_COPY_TOOL_BEHAVIOUR_ONCE)) {
                copySandboxToolConsideringRestriction(baseWorkingDirectory);
            }
            if (copyToolBehaviour.equals(ToolIntegrationConstants.VALUE_COPY_TOOL_BEHAVIOUR_NEVER)) {
                executionToolDirectory = sourceToolDirectory;
            }
        } catch (IOException e) {
            throw new ComponentException("Failed to create working directory", e);
        }
        prepareJythonForUsingModules();
        initializeNewHistoryDataItem();

        stateMap = new HashMap<String, Object>();
        if (treatStartAsComponentRun()) {
            processInputs();
            if (historyDataItem != null) {
                historyDataItem.setWorkingDirectory(currentWorkingDirectory.getAbsolutePath());
            }
        }
        if ((copyToolBehaviour.equals(ToolIntegrationConstants.VALUE_COPY_TOOL_BEHAVIOUR_ALWAYS)
            || deleteToolBehaviour.equals(ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_ALWAYS))
            && !useIterationDirectories) {
            throw new ComponentException(
                "Tool shall be copied always but working directory is not new for each run. "
                    + "Please check tool configuration \"Launch Settings\".");
        }
    }

    protected boolean isMockMode() {
        boolean isMockMode = false;
        if (Boolean.valueOf(componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_MOCK_MODE_SUPPORTED))
            && componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_IS_MOCK_MODE) != null) {
            isMockMode = Boolean.valueOf(componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_IS_MOCK_MODE));
        }
        return isMockMode;
    }

    private void getToolDeleteBehaviour() throws ComponentException {
        boolean deleteAlwaysActive =
            Boolean.parseBoolean(componentContext
                .getConfigurationValue(ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_ALWAYS));
        boolean deleteNeverActive =
            Boolean.parseBoolean(componentContext
                .getConfigurationValue(ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_NEVER));
        boolean deleteOnceActive =
            Boolean.parseBoolean(componentContext
                .getConfigurationValue(ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_ONCE));

        if (componentContext.getConfigurationValue(ToolIntegrationConstants.CHOSEN_DELETE_TEMP_DIR_BEHAVIOR) != null) {
            deleteToolBehaviour = componentContext.getConfigurationValue(ToolIntegrationConstants.CHOSEN_DELETE_TEMP_DIR_BEHAVIOR);
        } else {

            deleteToolBehaviour = ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_ALWAYS;
            if (deleteOnceActive) {
                deleteToolBehaviour = ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_ONCE;
            } else if (deleteNeverActive) {
                deleteToolBehaviour = ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_NEVER;
            }
        }
        keepOnFailure = false;
        if (componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_KEEP_ON_FAILURE) != null) {
            keepOnFailure = Boolean.parseBoolean(componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_KEEP_ON_FAILURE));
        }
        if ((ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_ALWAYS.equals(deleteToolBehaviour) && !deleteAlwaysActive)
            || (ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_ONCE.equals(deleteToolBehaviour) && !deleteOnceActive)
            || (ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_NEVER.equals(deleteToolBehaviour) && !deleteNeverActive)) {
            throw new ComponentException(
                StringUtils.format(DELETION_BEHAVIOR_ERROR_MSG, componentContext.getInstanceName()));
        }
    }

    @Override
    public void processInputs() throws ComponentException {
        Map<String, TypedDatum> inputValues = new HashMap<>();
        if (componentContext != null && componentContext.getInputsWithDatum() != null) {
            for (String inputName : componentContext.getInputsWithDatum()) {
                inputValues.put(inputName, componentContext.readInput(inputName));
            }
        }

        // create iteration directory and prepare it
        if (useIterationDirectories) {
            iterationDirectory = new File(baseWorkingDirectory, "" + runCount++);
            currentWorkingDirectory = iterationDirectory;
            createFolderStructure(iterationDirectory);
            if (copyToolBehaviour.equals(ToolIntegrationConstants.VALUE_COPY_TOOL_BEHAVIOUR_ALWAYS)) {
                copySandboxToolConsideringRestriction(iterationDirectory);
            }
        }
        // create a list with used input values to delete them afterwards
        Map<String, String> inputNamesToLocalFile = new HashMap<String, String>();
        for (String inputName : inputValues.keySet()) {
            if (componentContext.getInputDataType(inputName) == DataType.FileReference) {
                inputNamesToLocalFile.put(inputName, copyInputFileToInputFolder(inputName, inputValues));
            } else if (componentContext.getInputDataType(inputName) == DataType.DirectoryReference) {
                inputNamesToLocalFile.put(inputName, copyInputFileToInputFolder(inputName, inputValues));
            }
        }
        // Create Conf files
        Set<String> configFileNames = new HashSet<String>();
        for (String configKey : componentContext.getConfigurationKeys()) {
            String configFilename = componentContext.getConfigurationMetaDataValue(configKey,
                ToolIntegrationConstants.KEY_PROPERTY_CONFIG_FILENAME);
            if (configFilename != null && !configFilename.isEmpty()) {
                configFileNames.add(configFilename);
            }
        }
        for (String filename : configFileNames) {
            File f = new File(configDirectory, filename);
            try {
                if (f.exists()) {
                    f.delete();
                }
                f.createNewFile();
                for (String configKey : componentContext.getConfigurationKeys()) {
                    String configFilename = componentContext.getConfigurationMetaDataValue(configKey,
                        ToolIntegrationConstants.KEY_PROPERTY_CONFIG_FILENAME);
                    if (configFilename != null && !configFilename.isEmpty() && configFilename.equals(filename)) {
                        List<String> lines = FileUtils.readLines(f);
                        lines.add(configKey + "=" + componentContext.getConfigurationValue(configKey));
                        FileUtils.writeLines(f, lines);
                    }
                }
            } catch (IOException e) {
                throw new ComponentException("Failed to write configuration file: " + f.getAbsolutePath(), e);
            }
        }

        String preScript = componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_PRE_SCRIPT);
        beforePreScriptExecution(inputValues, inputNamesToLocalFile);
        needsToRun = needToRun(inputValues, inputNamesToLocalFile);
        lastRunStaticInputValues = new HashMap<String, TypedDatum>();
        lastRunStaticOutputValues = new HashMap<String, TypedDatum>();
        if (needsToRun) {

            for (String inputName : inputValues.keySet()) {
                if (componentContext.isStaticInput(inputName) && inputValues.containsKey(inputName)) {
                    lastRunStaticInputValues.put(inputName, inputValues.get(inputName));
                }
            }

            if (isMockMode()) {
                performRunInMockMode(inputValues, inputNamesToLocalFile);
            } else {
                performRunInNormalMode(preScript, inputValues, inputNamesToLocalFile);
            }

        } else {
            for (String outputName : lastRunStaticOutputValues.keySet()) {
                componentContext.writeOutput(outputName, lastRunStaticOutputValues.get(outputName));
            }
        }
        afterPostScriptExecution(inputValues, inputNamesToLocalFile);

        if (useIterationDirectories
            && ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_ALWAYS.equals(deleteToolBehaviour)) {
            try {
                FileUtils.deleteDirectory(currentWorkingDirectory);
            } catch (IOException e) {
                LOG.error(StringUtils.format("Failed to delete current workfing directory: %s",
                    currentWorkingDirectory.getAbsolutePath()), e);
            }
        }

        if (needsToRun) {
            try {
                closeConsoleWriters();
            } catch (IOException e) {
                LOG.error("Failed to close console writers", e);
            }
        } else {
            componentLog.componentInfo("Skipped tool execution as input(s) didn't change - output(s) from previous run sent");
        }

        storeHistoryDataItem();
    }

    private void performRunInNormalMode(String preScript, Map<String, TypedDatum> inputValues, Map<String, String> inputNamesToLocalFile)
        throws ComponentException {
        runScript(preScript, inputValues, inputNamesToLocalFile, "Pre");
        beforeCommandExecution(inputValues, inputNamesToLocalFile);

        componentContext.announceExternalProgramStart();
        int exitCode = runCommand(inputValues, inputNamesToLocalFile);
        componentContext.announceExternalProgramTermination();

        afterCommandExecution(inputValues, inputNamesToLocalFile);
        String postScript = componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_POST_SCRIPT);
        if (postScript != null) {
            postScript = ComponentUtils.replaceVariable(postScript, String.valueOf(exitCode),
                ToolIntegrationConstants.PLACEHOLDER_EXIT_CODE, ADD_PROPERTY_PLACEHOLDER);
        }
        runScript(postScript, inputValues, inputNamesToLocalFile, "Post");
    }

    private void performRunInMockMode(Map<String, TypedDatum> inputValues, Map<String, String> inputNamesToLocalFile)
        throws ComponentException {

        beforeCommandExecution(inputValues, inputNamesToLocalFile);

        afterCommandExecution(inputValues, inputNamesToLocalFile);
        String postScript = componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_MOCK_SCRIPT);
        runScript(postScript, inputValues, inputNamesToLocalFile, "Mock");
    }

    // The before* and after* methods are for implementing own code in sub classes
    protected void afterPostScriptExecution(Map<String, TypedDatum> inputValues, Map<String, String> inputNamesToLocalFile)
        throws ComponentException {
        // LOG.debug("after postscript execution");
    }

    protected void beforePreScriptExecution(Map<String, TypedDatum> inputValues, Map<String, String> inputNamesToLocalFile)
        throws ComponentException {
        // LOG.debug("Before prescript execution");
    }

    protected void beforeCommandExecution(Map<String, TypedDatum> inputValues, Map<String, String> inputNamesToLocalFile)
        throws ComponentException {
        // LOG.debug("before commad execution");
    }

    protected void afterCommandExecution(Map<String, TypedDatum> inputValues, Map<String, String> inputNamesToLocalFile)
        throws ComponentException {
        // LOG.debug("after command execution");
    }

    // The needToRun() method gives the ability to control if the integrated tool should run on some
    // conditions or not.
    protected boolean needToRun(Map<String, TypedDatum> inputValues, Map<String, String> inputNamesToLocalFile)
        throws ComponentException {
        // Standard case --> run always
        return true;
    }

    @Override
    public void completeStartOrProcessInputsAfterFailure() throws ComponentException {
        storeHistoryDataItem();
    }

    @Override
    public void tearDown(FinalComponentState state) {
        super.tearDown(state);
        switch (state) {
        case FAILED:
        case CANCELLED:
            deleteBaseWorkingDirectory(false);
            break;
        case FINISHED:
            deleteBaseWorkingDirectory(true);
            break;
        default:
            break;
        }
    }

    @Override
    public void dispose() {
        super.dispose();
        deleteBaseWorkingDirectory(false);
    }

    private int runCommand(Map<String, TypedDatum> inputValues, Map<String, String> inputNamesToLocalFile)
        throws ComponentException {
        String commScript = null;
        int exitCode = 0;
        if (OS.isFamilyWindows() && (Boolean.parseBoolean(componentContext.getConfigurationValue(
            ToolIntegrationConstants.KEY_COMMAND_SCRIPT_WINDOWS_ENABLED)))) {
            commScript = componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_COMMAND_SCRIPT_WINDOWS);
            commScript = replacePlaceholder(commScript, inputValues, inputNamesToLocalFile, SubstitutionContext.WINDOWS_BATCH);
        } else if (OS.isFamilyUnix() && (Boolean.parseBoolean(componentContext.getConfigurationValue(
            ToolIntegrationConstants.KEY_COMMAND_SCRIPT_LINUX_ENABLED)))) {
            commScript = componentContext.getConfigurationValue(ToolIntegrationConstants.KEY_COMMAND_SCRIPT_LINUX);
            commScript = replacePlaceholder(commScript, inputValues, inputNamesToLocalFile, SubstitutionContext.LINUX_BASH);
        } else {
            throw new ComponentException(StringUtils.format("No command(s) for operating system %s defined",
                System.getProperty("os.name")));
        }

        try {
            componentLog.componentInfo("Executing command(s)...");
            LocalApacheCommandLineExecutor executor = null;
            if (setToolDirectoryAsWorkingDirectory) {
                executor = new LocalApacheCommandLineExecutor(executionToolDirectory);
            } else {
                executor = new LocalApacheCommandLineExecutor(currentWorkingDirectory);
            }
            executor.startMultiLineCommand(commScript.split("\r?\n|\r"));
            stdoutWatcher = ConsoleRowUtils.logToWorkflowConsole(componentLog, executor.getStdout(),
                ConsoleRow.Type.TOOL_OUT, null, false);
            stderrWatcher = ConsoleRowUtils.logToWorkflowConsole(componentLog, executor.getStderr(),
                ConsoleRow.Type.TOOL_ERROR, null, false);
            exitCode = executor.waitForTermination();
            stdoutWatcher.waitForTermination();
            stderrWatcher.waitForTermination();
            componentLog.componentInfo("Command(s) executed - exit code: " + exitCode);
            if (historyDataItem != null) {
                historyDataItem.setExitCode(exitCode);
            }
            if (!dontCrashOnNonZeroExitCodes && exitCode != 0) {
                deleteBaseWorkingDirectory(false);
                throw new ComponentException(StringUtils.format("Command(s) execution terminated abnormally with exit code: %d",
                    exitCode));
            }
        } catch (IOException | InterruptedException e) {
            deleteBaseWorkingDirectory(false);
            throw new ComponentException("Failed to execute command(s)", e);
        }
        return exitCode;
    }

    private void runScript(String script, Map<String, TypedDatum> inputValues, Map<String, String> inputNamesToLocalFile,
        String scriptPrefix) throws ComponentException {

        // As the Jython script engine is not thread safe (console outputs of multiple script
        // executions are mixed), we must ensure that at most one script is executed at the same
        // time
        synchronized (ScriptingUtils.SCRIPT_EVAL_LOCK_OBJECT) {
            Object exitCode = null;
            if (script != null && !script.isEmpty()) {
                ScriptLanguage scriptLanguage = ScriptLanguage.getByName(SCRIPT_LANGUAGE);
                final ScriptEngine engine = scriptingService.createScriptEngine(scriptLanguage);
                Map<String, Object> scriptExecConfig = null;
                engine.put("config", scriptExecConfig);
                prepareScriptOutputForRun(engine);
                componentLog.componentInfo(StringUtils.format("Executing %s script...", scriptPrefix.toLowerCase()));

                script = replacePlaceholder(script, inputValues, inputNamesToLocalFile, SubstitutionContext.JYTHON);
                try {
                    engine.eval("RCE_Bundle_Jython_Path = " + QUOTE + jythonPath + QUOTE);
                    engine.eval("RCE_Temp_working_path = " + QUOTE + workingPath + QUOTE);

                    String headerScript = ScriptingUtils.prepareHeaderScript(stateMap, componentContext, inputDirectory,
                        new LinkedList<File>());
                    engine.eval(headerScript);
                    engine.eval(prepareTableInput(inputValues));
                    exitCode = engine.eval(script);
                    String footerScript = "\nRCE_Dict_OutputChannels = RCE.get_output_internal()\nRCE_CloseOutputChannelsList = "
                        + "RCE.get_closed_outputs_internal()\n" + "RCE_NotAValueOutputList = RCE.get_indefinite_outputs_internal()\n"
                        + StringUtils.format("sys.stdout.write('%s')\nsys.stderr.write('%s')\nsys.stdout.flush()\nsys.stderr.flush()",
                            WorkflowConsoleForwardingWriter.CONSOLE_END, WorkflowConsoleForwardingWriter.CONSOLE_END);
                    engine.eval(footerScript);

                    ((WorkflowConsoleForwardingWriter) engine.getContext().getWriter()).awaitPrintingLinesFinished();
                    ((WorkflowConsoleForwardingWriter) engine.getContext().getErrorWriter()).awaitPrintingLinesFinished();

                    String message = StringUtils.format("%s script executed", scriptPrefix);
                    if (exitCode != null) {
                        componentLog.componentInfo(message + " - exit code: " + exitCode);
                    } else {
                        componentLog.componentInfo(message);
                    }

                } catch (ScriptException e) {
                    deleteBaseWorkingDirectory(false);
                    throw new ComponentException(StringUtils.format("Failed to execute %s script",
                        scriptPrefix.toLowerCase()), e);
                } catch (InterruptedException e) {
                    LOG.error(StringUtils.format("Failed to wait for stdout or stderr writer of to finish (%s (%s))",
                        componentContext.getInstanceName(), componentContext.getExecutionIdentifier()), e);
                }
                if (exitCode != null && (!(exitCode instanceof Integer) || (((Integer) exitCode).intValue() != 0))) {
                    deleteBaseWorkingDirectory(false);
                    throw new ComponentException(StringUtils.format("%s script execution of terminated abnormally - exit code: %s",
                        scriptPrefix, exitCode));
                }
                writeOutputValues(engine, scriptExecConfig);
            }
        }
    }

    private String prepareTableInput(Map<String, TypedDatum> inputValues) {
        String script = "";
        for (String inputName : inputValues.keySet()) {
            if (componentContext.getInputDataType(inputName) == DataType.Vector) {
                script += inputName + "= [";
                for (FloatTD floatEntry : ((VectorTD) inputValues.get(inputName)).toArray()) {
                    script += floatEntry.getFloatValue() + ",";
                }
                script = script.substring(0, script.length() - 1) + "]\n";
            }
        }
        return script;
    }

    @SuppressWarnings("unchecked")
    private void writeOutputValues(ScriptEngine engine, Map<String, Object> scriptExecConfig) throws ComponentException {
        final Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
        /*
         * Extract all values calculated/set in the script to a custom scope so the calculated/set
         * values are accessible via the current Context.
         */
        for (final String key : bindings.keySet()) {
            Object value = bindings.get(key);
            if (value != null && value.getClass().getSimpleName().equals("NativeJavaObject")) {
                try {
                    value = value.getClass().getMethod("unwrap").invoke(value);
                } catch (IllegalArgumentException | SecurityException | IllegalAccessException | InvocationTargetException
                    | NoSuchMethodException e) {
                    throw new ComponentException("Failed to extract output values from post script", e);
                }
            }
            if (scriptExecConfig != null) {
                scriptExecConfig.put(key, value);
            }
            if (value != null && outputMapping.containsKey(key)) {
                if (componentContext.getOutputDataType(outputMapping.get(key)) == DataType.FileReference
                    || componentContext.getOutputDataType(outputMapping.get(key)) == DataType.DirectoryReference) {
                    File file = new File(value.toString());
                    if (!file.isAbsolute()) {
                        file = new File(currentWorkingDirectory, value.toString());
                    }
                    if (!file.exists()) {
                        throw new ComponentException(StringUtils.format("File for output '%s' doesn't exist: %s",
                            outputMapping.get(key), file.getAbsolutePath()));
                    }
                    try {
                        if (componentContext.getOutputDataType(outputMapping.get(key)) == DataType.FileReference) {
                            String metafilename = componentContext.getOutputMetaDataValue(outputMapping.get(key),
                                ToolIntegrationConstants.KEY_ENDPOINT_FILENAME);
                            String filename = file.getName();
                            if (metafilename != null && !metafilename.isEmpty()) {
                                filename = metafilename;
                            }
                            FileReferenceTD uuid = datamanagementService.createFileReferenceTDFromLocalFile(componentContext, file,
                                filename);
                            componentContext.writeOutput(outputMapping.get(key), uuid);
                            lastRunStaticOutputValues.put(outputMapping.get(key), uuid);
                        } else {
                            String metafilename = componentContext.getOutputMetaDataValue(outputMapping.get(key),
                                ToolIntegrationConstants.KEY_ENDPOINT_FILENAME);
                            String filename = file.getName();
                            if (metafilename != null && !metafilename.isEmpty()) {
                                filename = metafilename;
                            }
                            DirectoryReferenceTD uuid = datamanagementService.createDirectoryReferenceTDFromLocalDirectory(
                                componentContext, file, filename);
                            componentContext.writeOutput(outputMapping.get(key), uuid);
                            lastRunStaticOutputValues.put(outputMapping.get(key), uuid);
                        }
                    } catch (IOException e) {
                        throw new ComponentException(StringUtils.format("Failed to store file/directory '%s' into the data management"
                            + " - if it is not stored in the data management it can not be sent as value for output '%s'",
                            file.getAbsolutePath(), outputMapping.get(key)), e);
                    }

                } else {
                    TypedDatum valueTD = ScriptDataTypeHelper.getTypedDatum(value, typedDatumFactory);
                    componentContext.writeOutput(outputMapping.get(key), valueTD);
                    lastRunStaticOutputValues.put(outputMapping.get(key), valueTD);
                }
            }
        }
        ScriptingUtils.writeAPIOutput(stateMap, componentContext, engine, workingPath, historyDataItem);

        for (String outputName : (List<String>) engine.get("RCE_CloseOutputChannelsList")) {
            componentContext.closeOutput(outputName);
        }

        Map<String, Object> stateMapOutput = (Map<String, Object>) engine.get("RCE_STATE_VARIABLES");
        for (String key : stateMapOutput.keySet()) {
            stateMap.put(key, stateMapOutput.get(key));
        }

    }

    private String replacePlaceholder(String script, Map<String, TypedDatum> inputValues, Map<String, String> inputNamesToLocalFile,
        SubstitutionContext context) throws ComponentException {
        if (inputValues != null) {
            for (String inputName : inputValues.keySet()) {
                if (inputValues.containsKey(inputName) && script.contains(StringUtils.format(INPUT_PLACEHOLDER, inputName))) {
                    if (componentContext.getInputDataType(inputName) == DataType.FileReference) {
                        script = script.replace(StringUtils.format(INPUT_PLACEHOLDER, inputName),
                            inputNamesToLocalFile.get(inputName).replaceAll(ESCAPESLASH, SLASH));
                    } else if (componentContext.getInputDataType(inputName) == DataType.DirectoryReference) {
                        script = script.replace(StringUtils.format(INPUT_PLACEHOLDER, inputName),
                            inputNamesToLocalFile.get(inputName).replaceAll(ESCAPESLASH, SLASH));
                    } else if (componentContext.getInputDataType(inputName) == DataType.Vector) {
                        script = script.replace(StringUtils.format(INPUT_PLACEHOLDER, inputName), validate(inputName, context,
                            StringUtils.format("Name of Vector '%s'" + SUBSTITUTION_ERROR_MESSAGE_PREFIX, inputName)));

                    } else {
                        String value = inputValues.get(inputName).toString();
                        if (context == SubstitutionContext.JYTHON && componentContext.getInputDataType(inputName) == DataType.Boolean) {
                            value = value.substring(0, 1).toUpperCase() + value.substring(1);
                        }
                        script = script.replace(StringUtils.format(INPUT_PLACEHOLDER, inputName),
                            validate(value, context, StringUtils.format("Value '%s' from input '%s'"
                                + SUBSTITUTION_ERROR_MESSAGE_PREFIX, value, inputName)));
                    }
                }
            }
        }

        script = replaceOutputVariables(script, componentContext.getOutputs(), OUTPUT_PLACEHOLDER);
        Map<String, String> properties = new HashMap<>();
        // get properties!
        for (String configKey : componentContext.getConfigurationKeys()) {
            String value = componentContext.getConfigurationValue(configKey);
            validate(value, context, StringUtils.format("Value '%s' of property '%s'" + SUBSTITUTION_ERROR_MESSAGE_PREFIX,
                value, configKey));
            properties.put(configKey, componentContext.getConfigurationValue(configKey));
        }
        script = ComponentUtils.replacePropertyVariables(script, properties, PROPERTY_PLACEHOLDER);
        script = ComponentUtils.replaceVariable(script, configDirectory.getAbsolutePath(),
            ToolIntegrationConstants.DIRECTORIES_PLACEHOLDER[0], DIRECTORY_PLACEHOLDER_TEMPLATE);
        script = ComponentUtils.replaceVariable(script, currentWorkingDirectory.getAbsolutePath(),
            ToolIntegrationConstants.DIRECTORIES_PLACEHOLDER[1], DIRECTORY_PLACEHOLDER_TEMPLATE);
        script = ComponentUtils.replaceVariable(script, currentWorkingDirectory.getAbsolutePath(),
            ToolIntegrationConstants.DIRECTORIES_PLACEHOLDER[1] + "Dir", DIRECTORY_PLACEHOLDER_TEMPLATE);
        script = ComponentUtils.replaceVariable(script, inputDirectory.getAbsolutePath(),
            ToolIntegrationConstants.DIRECTORIES_PLACEHOLDER[2], DIRECTORY_PLACEHOLDER_TEMPLATE);
        script = ComponentUtils.replaceVariable(script, outputDirectory.getAbsolutePath(),
            ToolIntegrationConstants.DIRECTORIES_PLACEHOLDER[4], DIRECTORY_PLACEHOLDER_TEMPLATE);
        script = ComponentUtils.replaceVariable(script, executionToolDirectory.getAbsolutePath(),
            ToolIntegrationConstants.DIRECTORIES_PLACEHOLDER[3], DIRECTORY_PLACEHOLDER_TEMPLATE);

        return script;
    }

    private String replaceOutputVariables(String script, Set<String> outputs, String outputPlaceholder) {
        outputMapping = new HashMap<>();
        for (String outputName : outputs) {
            String outputID = "_RCE_OUTPUT_" + UUID.randomUUID().toString().replaceAll("-", "_");
            script = script.replace(StringUtils.format(outputPlaceholder, outputName), outputID);
            outputMapping.put(outputID, outputName);
        }
        return script;
    }

    private String validate(String key, SubstitutionContext context, String errorMsg) throws ComponentException {
        if (!StringSubstitutionSecurityUtils.isSafeForSubstitutionInsideDoubleQuotes(key, context)) {
            throw new ComponentException(errorMsg);
        }
        return key;
    }

    private String copyInputFileToInputFolder(String inputName, Map<String, TypedDatum> inputValues) throws ComponentException {
        File targetFile = null;
        TypedDatum fileReference = inputValues.get(inputName);
        try {
            if (componentContext.getInputDataType(inputName) == DataType.FileReference) {
                String fileName = ((FileReferenceTD) fileReference).getFileName();
                /**
                 * Commented out because of bug with renaming file / dir
                 */
                // if (!componentContext.getInputMetaDataValue(inputName,
                // ToolIntegrationConstants.KEY_ENDPOINT_FILENAME).isEmpty()) {
                // fileName = componentContext.getInputMetaDataValue(inputName,
                // ToolIntegrationConstants.KEY_ENDPOINT_FILENAME);
                // }
                targetFile = new File(inputDirectory.getAbsolutePath(), inputName + File.separator + fileName);
                if (targetFile.exists()) {
                    FileUtils.forceDelete(targetFile);
                }
                datamanagementService.copyFileReferenceTDToLocalFile(componentContext, (FileReferenceTD) fileReference, targetFile);
            } else {
                // String fileName = ((DirectoryReferenceTD) fileReference).getDirectoryName();
                targetFile = new File(inputDirectory.getAbsolutePath(), inputName);
                if (targetFile.exists()) {
                    FileUtils.forceDelete(targetFile);
                }
                datamanagementService.copyDirectoryReferenceTDToLocalDirectory(componentContext,
                    (DirectoryReferenceTD) fileReference, targetFile);
                /**
                 * Commented out because of bug with renaming file / dir
                 */
                // if (componentContext.getInputMetaDataValue(inputName,
                // ToolIntegrationConstants.KEY_ENDPOINT_FILENAME) != null
                // && !componentContext.getInputMetaDataValue(inputName,
                // ToolIntegrationConstants.KEY_ENDPOINT_FILENAME).isEmpty()) {
                // fileName = componentContext.getInputMetaDataValue(inputName,
                // ToolIntegrationConstants.KEY_ENDPOINT_FILENAME);
                // File newTarget = new File(new File(inputDirectory.getAbsolutePath(), inputName),
                // fileName);
                // FileUtils.moveDirectory(new File(targetFile, ((DirectoryReferenceTD)
                // fileReference).getDirectoryName()), newTarget);
                // targetFile = newTarget;
                // }
                targetFile = new File(targetFile, ((DirectoryReferenceTD) fileReference).getDirectoryName());
            }
        } catch (IOException e) {
            throw new ComponentException(StringUtils.format("Failed to write incoming file of input '%s' into working directory: %s",
                inputName, targetFile.getAbsolutePath()), e);
        }

        return targetFile.getAbsolutePath();
    }

    private void copySandboxTool(File directory) throws ComponentException {
        File targetToolDir = new File(directory + File.separator + sourceToolDirectory.getName());
        boolean copiedToolDir = false;
        try {
            FileUtils.copyDirectory(sourceToolDirectory, targetToolDir);
            componentLog.componentInfo("Copied tool directory '" + sourceToolDirectory.getName() + "' to working directory");
            copiedToolDir = true;
        } catch (IOException e) {
            deleteBaseWorkingDirectory(false);
            throw new ComponentException(StringUtils.format("Failed to copy tool directory: %s",
                sourceToolDirectory.getAbsolutePath()), e);
        }
        if (copiedToolDir) {
            executionToolDirectory = targetToolDir;
            Iterator<File> it = FileUtils.iterateFiles(targetToolDir, null, true);
            while (it.hasNext()) {
                File f = it.next();
                f.setExecutable(true, false);
            }
        }
    }

    private void copySandboxToolConsideringRestriction(File directory) throws ComponentException {
        try {
            COPY_TOOL_SEMAPHORE.acquire();
        } catch (InterruptedException e) {
            throw new ComponentException("Internal error: Interrupted while waiting for the release to copy the tool", e);
        }
        try {
            copySandboxTool(directory);
        } finally {
            COPY_TOOL_SEMAPHORE.release();
        }
    }

    private void createFolderStructure(File directory) {
        inputDirectory = new File(directory.getAbsolutePath() + File.separator + ToolIntegrationConstants.COMPONENT_INPUT_FOLDER_NAME
            + File.separator);
        inputDirectory.mkdirs();
        outputDirectory = new File(directory.getAbsolutePath() + File.separator + ToolIntegrationConstants.COMPONENT_OUTPUT_FOLDER_NAME
            + File.separator);
        outputDirectory.mkdirs();
        configDirectory = new File(directory.getAbsolutePath() + File.separator + ToolIntegrationConstants.COMPONENT_CONFIG_FOLDER_NAME
            + File.separator);
        configDirectory.mkdirs();
        componentLog.componentInfo("Created working directory: " + directory.getAbsolutePath());
    }

    private void deleteBaseWorkingDirectory(boolean workflowSuccess) {
        if ((ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_ONCE.equals(deleteToolBehaviour)
            || ToolIntegrationConstants.KEY_TOOL_DELETE_WORKING_DIRECTORIES_ALWAYS.equals(deleteToolBehaviour))
            && !(keepOnFailure && !workflowSuccess)
            && baseWorkingDirectory != null && baseWorkingDirectory.exists()) {
            try {
                if (rootWDPath == null || rootWDPath.isEmpty()) {
                    TempFileServiceAccess.getInstance().disposeManagedTempDirOrFile(baseWorkingDirectory);
                } else {
                    FileDeleteStrategy.FORCE.delete(baseWorkingDirectory);
                }
                componentLog.componentInfo("Deleted working directory: " + baseWorkingDirectory.getAbsolutePath());
            } catch (IOException e) {
                baseWorkingDirectory.deleteOnExit();
                LOG.error(StringUtils.format("Failed to delete working directory: %s",
                    baseWorkingDirectory.getAbsolutePath()), e);
            }
        }
    }

    protected void bindScriptingService(final ScriptingService service) {
        scriptingService = service;
    }

    protected void bindComponentDataManagementService(ComponentDataManagementService compDataManagementService) {
        datamanagementService = compDataManagementService;
    }

    private void prepareScriptOutputForRun(ScriptEngine scriptEngine) {
        final int buffer = 1024;
        StringWriter out = new StringWriter(buffer);
        StringWriter err = new StringWriter(buffer);
        stdoutWriter = new WorkflowConsoleForwardingWriter(out, componentLog, ConsoleRow.Type.TOOL_OUT);
        stderrWriter = new WorkflowConsoleForwardingWriter(err, componentLog, ConsoleRow.Type.TOOL_ERROR);
        scriptEngine.getContext().setWriter(stdoutWriter);
        scriptEngine.getContext().setErrorWriter(stderrWriter);
    }

    private void prepareJythonForUsingModules() throws ComponentException {
        try {
            jythonPath = ScriptingUtils.getJythonPath();
        } catch (IOException e) {
            throw new ComponentException("Internal error: Failed to initialize Jython", e);
        }
        if (jythonPath == null) {
            throw new ComponentException("Internal error: Failed to initialize Jython");
        }

        File file = new File(baseWorkingDirectory.getAbsolutePath(), "jython-import-" + UUID.randomUUID().toString() + ".tmp");
        workingPath = file.getAbsolutePath().toString();
        workingPath = workingPath.replaceAll(ESCAPESLASH, SLASH);

        String[] splitted = workingPath.split(SLASH);
        workingPath = "";
        for (int i = 0; i < splitted.length - 1; i++) {
            workingPath += splitted[i] + SLASH;
        }

    }

    protected void closeConsoleWriters() throws IOException {
        if (stdoutWriter != null) {
            stdoutWriter.flush();
            stdoutWriter.close();
        }
        if (stderrWriter != null) {
            stderrWriter.flush();
            stderrWriter.close();
        }
    }

    protected void initializeNewHistoryDataItem() {
        if (Boolean.valueOf(componentContext.getConfigurationValue(ComponentConstants.CONFIG_KEY_STORE_DATA_ITEM))) {
            historyDataItem = new IntegrationHistoryDataItem(componentContext.getComponentIdentifier());
        }
    }

    private void storeHistoryDataItem() {
        if (historyDataItem != null) {
            historyDataItem.setWorkingDirectory(currentWorkingDirectory.getAbsolutePath());
        }
        if (historyDataItem != null
            && Boolean.valueOf(componentContext.getConfigurationValue(ComponentConstants.CONFIG_KEY_STORE_DATA_ITEM))) {
            componentContext.writeFinalHistoryDataItem(historyDataItem);
        }
    }

}

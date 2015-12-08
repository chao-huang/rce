/*
 * Copyright (C) 2006-2015 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */

package de.rcenvironment.components.evaluationmemory.execution;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import de.rcenvironment.components.evaluationmemory.common.EvaluationMemoryComponentConstants;
import de.rcenvironment.components.evaluationmemory.common.EvaluationMemoryComponentHistoryDataItem;
import de.rcenvironment.components.evaluationmemory.execution.internal.EvaluationMemoryAccess;
import de.rcenvironment.components.evaluationmemory.execution.internal.EvaluationMemoryFileAccessService;
import de.rcenvironment.core.component.api.ComponentConstants;
import de.rcenvironment.core.component.api.ComponentException;
import de.rcenvironment.core.component.datamanagement.api.ComponentDataManagementService;
import de.rcenvironment.core.component.execution.api.ComponentContext;
import de.rcenvironment.core.component.execution.api.ComponentLog;
import de.rcenvironment.core.component.model.spi.DefaultComponent;
import de.rcenvironment.core.datamodel.api.DataType;
import de.rcenvironment.core.datamodel.api.TypedDatum;
import de.rcenvironment.core.datamodel.types.api.BooleanTD;
import de.rcenvironment.core.utils.common.LogUtils;
import de.rcenvironment.core.utils.common.StringUtils;

/**
 * Implementation of the Evaluation Memory component.
 * 
 * @author Doreen Seider
 */
public class EvaluationMemoryComponent extends DefaultComponent {

    /**
     * 'Processing input' modes.
     * 
     * @author Doreen Seider
     */
    private enum Mode {
        Check,
        Store,
        Finish;
    }
    
    private Log log = LogFactory.getLog(getClass());
    
    private ComponentLog componentLog;
    
    private EvaluationMemoryFileAccessService memoryFileHandlerService;
    
    private ComponentDataManagementService dataManagementService;
    
    private ComponentContext componentContext;
    
    private EvaluationMemoryAccess memoryAccess;
    
    private SortedMap<String, DataType> inputsToEvaluate = new TreeMap<>();

    private SortedMap<String, DataType> outputsEvaluationResult = new TreeMap<>();

    private Queue<SortedMap<String, TypedDatum>> valuesToEvaluate = new LinkedList<>();

    private String memoryFilePath;
    
    private File memoryFile;
    
    private EvaluationMemoryComponentHistoryDataItem historyData;
    
    @Override
    public void setComponentContext(ComponentContext componentContext) {
        super.setComponentContext(componentContext);
        this.componentContext = componentContext;
        componentLog = componentContext.getLog();
    }
    
    @Override
    public void start() throws ComponentException {
        
        memoryFileHandlerService = componentContext.getService(EvaluationMemoryFileAccessService.class);
        dataManagementService = componentContext.getService(ComponentDataManagementService.class);
        setInputsAndOutputs();
        
        initializeMemoryFileAccess(getMemoryFilePath());
    }
    
    private void setInputsAndOutputs() {
        for (String input : getInputsOfTypeToEvaluateSortedByName()) {
            inputsToEvaluate.put(input, componentContext.getInputDataType(input));
        }
        for (String output : getOutputsOfTypeEvaluationResultsSortedByName()) {
            outputsEvaluationResult.put(output, componentContext.getInputDataType(output));
        }
    }
    
    private String getMemoryFilePath() {
        if (Boolean.valueOf(componentContext.getConfigurationValue(
            EvaluationMemoryComponentConstants.CONFIG_SELECT_AT_WF_START))) {
            return componentContext.getConfigurationValue(EvaluationMemoryComponentConstants.CONFIG_MEMORY_FILE_WF_START);
        } else {
            return componentContext.getConfigurationValue(EvaluationMemoryComponentConstants.CONFIG_MEMORY_FILE);
        }
    }
    
    private void initializeMemoryFileAccess(String path) throws ComponentException {
        if (path == null || path.isEmpty()) {
            throw new ComponentException("No memory file given. Did you forget to configure one?");
        }
        memoryFile = new File(path);
        memoryFilePath = memoryFile.getAbsolutePath();
        try {
            memoryAccess = memoryFileHandlerService.acquireAccessToMemoryFile(memoryFilePath);
            if (memoryFile.exists() && FileUtils.sizeOf(memoryFile) > 0) { // exists and is not empty
                memoryAccess.validateEvaluationMemory(inputsToEvaluate, outputsEvaluationResult);
            } else {
                memoryFile.createNewFile();
                memoryAccess.setInputsOutputsDefinition(inputsToEvaluate, outputsEvaluationResult);
            }
        } catch (IOException e) {
            throw new ComponentException("Failed to access memory file: " + memoryFilePath, e);
        }
    }
    
    @Override
    public void processInputs() throws ComponentException {
        
        Set<String> inputsWithDatum = componentContext.getInputsWithDatum();
        SortedMap<String, TypedDatum> inputValues = getInputValuesSortedByInputsName(inputsWithDatum);
        
        switch (getInputProcessingMode(inputsWithDatum)) {
        case Check:
            initializeNewHistoryData();
            processInputsInCheckMode(inputValues);
            break;
        case Store:
            initializeNewHistoryData();
            processInputsInStoreMode();
            break;
        case Finish:
            processInputsInFinishMode();
            return;
        default:
            break;
        }
        
        try {
            addMemoryFileToHistoryData();
        } catch (IOException e) {
            String errorMessage = StringUtils.format("Failed to store memory file into the data management for '%s' (%s)",
                componentContext.getComponentName(), componentContext.getExecutionIdentifier());
            String errorId = LogUtils.logExceptionWithStacktraceAndAssignUniqueMarker(log, errorMessage, e);
            componentLog.componentError(errorMessage, e, errorId);
            
        }
        writeFinalHistoryData();
    }
    
    private void initializeNewHistoryData() {
        if (Boolean.valueOf(componentContext.getConfigurationValue(ComponentConstants.CONFIG_KEY_STORE_DATA_ITEM))) {
            historyData = new EvaluationMemoryComponentHistoryDataItem(EvaluationMemoryComponentConstants.COMPONENT_ID);
            historyData.setMemoryFilePath(memoryFilePath);
        }
    }

    private void addMemoryFileToHistoryData() throws IOException {
        if (Boolean.valueOf(componentContext.getConfigurationValue(ComponentConstants.CONFIG_KEY_STORE_DATA_ITEM))) {
            String memoryFileReference = dataManagementService.createTaggedReferenceFromLocalFile(
                componentContext, memoryFile, memoryFile.getName());
            historyData.setMemoryFileReference(memoryFileReference);
        }
    }
    
    private void writeFinalHistoryData() {
        if (historyData != null 
            && Boolean.valueOf(componentContext.getConfigurationValue(ComponentConstants.CONFIG_KEY_STORE_DATA_ITEM))) {
            componentContext.writeFinalHistoryDataItem(historyData);
            historyData = null;
        }
    }
    
    private void processInputsInCheckMode(SortedMap<String, TypedDatum> inputValues) {
        SortedMap<String, TypedDatum> evaluationResults = null;
        try {
            evaluationResults = memoryAccess.getEvaluationResult(inputValues, outputsEvaluationResult);
        } catch (IOException e) {
            String errorMessage = StringUtils.format("Failed to get evaluation results for values '%s' from evaluation memory '%s';"
                + " cause: %s - as it is not workflow critical, continue with execution...", inputValues, memoryFile, e.getMessage());
            log.error(errorMessage, e);
            componentLog.componentError(errorMessage);
        }
        if (evaluationResults == null) {
            componentLog.componentInfo(StringUtils.format("Forward values '%s' "
                + "- no evaluation results in memory", inputValues));
            forwardValues(inputValues);
            valuesToEvaluate.add(inputValues);
        } else {
            componentLog.componentInfo(StringUtils.format("Found evaluation results for values '%s' "
                + "in memory: %s -> directly feed back", inputValues, evaluationResults));
            for (String output : evaluationResults.keySet()) {
                componentContext.writeOutput(output, evaluationResults.get(output));
            }
        }
    }

    private void processInputsInStoreMode() throws ComponentException {
        Set<String> inputsWithDatum = componentContext.getInputsWithDatum();
        SortedMap<String, TypedDatum> evaluationResults = getDynamicInputValuesSortedByInputsName(inputsWithDatum);
        
        for (String input : evaluationResults.keySet()) {
            if (componentContext.isDynamicInput(input)) {
                componentContext.writeOutput(input, evaluationResults.get(input));                
            }
        }
        
        if (valuesToEvaluate.isEmpty()) {
            throw new ComponentException(StringUtils.format("Failed to store evaluation results in evaluation memory file: %s"
                + " - no values (to evaluate) stored from a previous run", memoryFilePath));
        }
        SortedMap<String, TypedDatum> values = valuesToEvaluate.poll();
        try {
            memoryAccess.addEvaluationValues(values, evaluationResults);
            componentLog.componentInfo(StringUtils.format("Stored evaluation results for values '%s' "
                + "in memory: %s", values, evaluationResults));
        } catch (IOException e) {
            String errorMessage = StringUtils.format("Failed to write evaluation values '%s' with '%s' to evaluation memory '%s';"
                + " cause: %s - as it is not workflow critical, continue with execution...", values, evaluationResults, memoryFile,
                e.getMessage());
            log.error(errorMessage, e);
            componentLog.componentError(errorMessage);
        }
    }

    private void processInputsInFinishMode() {
        if (((BooleanTD) componentContext.readInput(EvaluationMemoryComponentConstants.INPUT_NAME_LOOP_DONE)).getBooleanValue()) {
            for (String output : getOutputsOfTypeToEvaluateSortedByName()) {
                componentContext.closeOutput(output);
            }
        } else {
            componentLog.componentInfo("Skip processing inputs - got just 'false' on the input " 
                + EvaluationMemoryComponentConstants.INPUT_NAME_LOOP_DONE);
        }
    }
    
    @Override
    public void completeStartOrProcessInputsAfterFailure() throws ComponentException {
        writeFinalHistoryData();
    }
    
    @Override
    public void tearDown(FinalComponentState state) {
        if (memoryAccess != null) {
            memoryFileHandlerService.releaseAccessToMemoryFile(memoryFilePath);
        }
    }
    
    private Mode getInputProcessingMode(Set<String> inputsWithDatum) throws ComponentException {
        if (inputsWithDatum.contains(EvaluationMemoryComponentConstants.INPUT_NAME_LOOP_DONE)) {
            return Mode.Finish;
        } else {
            String input = inputsWithDatum.iterator().next();
            if (componentContext.isDynamicInput(input)) {
                if (componentContext.getDynamicInputIdentifier(input).equals(EvaluationMemoryComponentConstants.ENDPOINT_ID_TO_EVALUATE)) {
                    return Mode.Check;
                } else if (componentContext.getDynamicInputIdentifier(input).equals(
                    EvaluationMemoryComponentConstants.ENDPOINT_ID_EVALUATION_RESULTS)) {
                    return Mode.Store;
                }
            }
        }
        // should never happen
        throw new ComponentException("Unexpected set of input values");
    }
    
    private SortedMap<String, TypedDatum> getInputValuesSortedByInputsName(Set<String> inputsWithDatum) {
        SortedMap<String, TypedDatum> inputValues = new TreeMap<>();
        for (String input : inputsWithDatum) {
            inputValues.put(input, componentContext.readInput(input));
        }
        return inputValues;
    }
    
    private Set<String> getOutputsOfTypeToEvaluateSortedByName() {
        Set<String> outputs = new HashSet<>();
        for (String output: componentContext.getOutputs()) {
            if (componentContext.isDynamicOutput(output)
                && componentContext.getDynamicOutputIdentifier(output).equals(EvaluationMemoryComponentConstants.ENDPOINT_ID_TO_EVALUATE)) {
                outputs.add(output);
            }
        }
        return outputs;
    }
    
    private SortedSet<String> getOutputsOfTypeEvaluationResultsSortedByName() {
        SortedSet<String> outputs = new TreeSet<>();
        for (String output: componentContext.getOutputs()) {
            if (componentContext.isDynamicOutput(output)
                && componentContext.getDynamicOutputIdentifier(output).equals(
                    EvaluationMemoryComponentConstants.ENDPOINT_ID_EVALUATION_RESULTS)) {
                outputs.add(output);
            }
        }
        return outputs;
    }
    
    private SortedSet<String> getInputsOfTypeToEvaluateSortedByName() {
        SortedSet<String> inputs = new TreeSet<>();
        for (String input: componentContext.getOutputs()) {
            if (componentContext.isDynamicInput(input)
                && componentContext.getDynamicInputIdentifier(input).equals(
                    EvaluationMemoryComponentConstants.ENDPOINT_ID_TO_EVALUATE)) {
                inputs.add(input);
            }
        }
        return inputs;
    }
    
    private SortedMap<String, TypedDatum> getDynamicInputValuesSortedByInputsName(Set<String> inputsWithDatum) {
        SortedMap<String, TypedDatum> inputValues = new TreeMap<>();
        for (String input : inputsWithDatum) {
            if (componentContext.isDynamicInput(input)) {
                inputValues.put(input, componentContext.readInput(input));
            }
        }
        return inputValues;
    }
    
    private void forwardValues(SortedMap<String, TypedDatum> inputValues) {
        for (String input : inputValues.keySet()) {
            componentContext.writeOutput(input, inputValues.get(input));
        }
    }
    
}

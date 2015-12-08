/*
 * Copyright (C) 2006-2015 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */

package de.rcenvironment.core.gui.workflow.editor.properties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.widgets.TableItem;

import de.rcenvironment.core.component.api.LoopComponentConstants;
import de.rcenvironment.core.component.model.endpoint.api.EndpointDescription;
import de.rcenvironment.core.datamodel.api.DataType;
import de.rcenvironment.core.datamodel.api.EndpointType;
import de.rcenvironment.core.gui.workflow.editor.commands.endpoint.AddDynamicInputWithAnotherInputAndOutputsCommand;
import de.rcenvironment.core.gui.workflow.editor.commands.endpoint.EditDynamicInputWithAnotherInputAndOutputsCommand;
import de.rcenvironment.core.gui.workflow.editor.commands.endpoint.RemoveDynamicInputWithAnotherPossibleInputAndOutputsCommand;

/**
 * Input pane for endpoints that add one input and two outputs next to a requested input.
 *
 * @author Doreen Seider
 */
public class InputCoupledWithAnotherInputAndOutputsSelectionPane extends ForwardingEndpointSelectionPane {

    private final String dynEndpointId;

    private final Refreshable outputPane;

    private final String outputNameSuffix;

    private final String inputNameSuffix;

    private Map<String, String> metaDataInput = new HashMap<>();

    private Map<String, String> metaDataInputWithSuffix = new HashMap<>();

    private Map<String, String> metaDataOutput = new HashMap<>();

    private Map<String, String> metaDataOutputWithSuffix = new HashMap<>();

    public InputCoupledWithAnotherInputAndOutputsSelectionPane(String title, String dynEndpointId,
        String inputNameSuffix, String outputNameSuffix, WorkflowNodeCommand.Executor executor, Refreshable outputPane) {
        super(title, EndpointType.INPUT, executor, false, dynEndpointId, true, true);
        this.dynEndpointId = dynEndpointId;
        this.outputPane = outputPane;
        this.inputNameSuffix = inputNameSuffix;
        this.outputNameSuffix = outputNameSuffix;
    }

    @Override
    protected void executeAddCommand(String name, DataType type, Map<String, String> metaData) {
        metaDataInput.putAll(metaData);
        WorkflowNodeCommand command = new AddDynamicInputWithAnotherInputAndOutputsCommand(dynEndpointId, name, type, metaDataInput,
            inputNameSuffix, outputNameSuffix, LoopComponentConstants.ENDPOINT_STARTVALUE_GROUP, this, outputPane);
        metaDataInputWithSuffix.putAll(metaData);
        ((AddDynamicInputWithAnotherInputAndOutputsCommand) command).addMetaDataToInputWithSuffix(metaDataInputWithSuffix);
        ((AddDynamicInputWithAnotherInputAndOutputsCommand) command).setMetaDataOutput(metaDataOutput);
        ((AddDynamicInputWithAnotherInputAndOutputsCommand) command).setMetaDataOutputWithSuffix(metaDataOutputWithSuffix);
        execute(command);
    }

    @Override
    protected void executeEditCommand(EndpointDescription oldDescription, EndpointDescription newDescription) {
        WorkflowNodeCommand command =
            new EditDynamicInputWithAnotherInputAndOutputsCommand(oldDescription, newDescription, inputNameSuffix, outputNameSuffix,
                LoopComponentConstants.ENDPOINT_STARTVALUE_GROUP, false, this, outputPane);
        ((EditDynamicInputWithAnotherInputAndOutputsCommand) command).addMetaDataToInputWithSuffix(metaDataInputWithSuffix);
        ((EditDynamicInputWithAnotherInputAndOutputsCommand) command).setMetaDataOutput(metaDataOutput);
        ((EditDynamicInputWithAnotherInputAndOutputsCommand) command).setMetaDataOutputWithSuffix(metaDataOutputWithSuffix);
        execute(command);
    }

    @Override
    protected void executeRemoveCommand(List<String> names) {
        final WorkflowNodeCommand command =
            new RemoveDynamicInputWithAnotherPossibleInputAndOutputsCommand(dynEndpointId, names, inputNameSuffix, outputNameSuffix, this,
                outputPane);
        execute(command);
    }

    @Override
    protected void updateButtonActivation() {
        super.updateButtonActivation();
        TableItem[] selection = table.getSelection();
        if (selection.length == 1 && selection[0].getText().endsWith(LoopComponentConstants.ENDPOINT_STARTVALUE_SUFFIX)) {
            buttonEdit.setEnabled(false);
            buttonRemove.setEnabled(false);
        }
    }

    @Override
    public void setMetaDataInput(Map<String, String> metaDataInput) {
        this.metaDataInput = metaDataInput;
    }

    public void setMetaDataInputWithSuffix(Map<String, String> metaDataInputWithSuffix) {
        this.metaDataInputWithSuffix = metaDataInputWithSuffix;
    }

    public void setMetaDataOutput(Map<String, String> metaDataOutput) {
        this.metaDataOutput = metaDataOutput;
    }

    public void setMetaDataOutputWithSuffix(Map<String, String> metaDataOutputWithSuffix) {
        this.metaDataOutputWithSuffix = metaDataOutputWithSuffix;
    }

}

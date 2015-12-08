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

import de.rcenvironment.core.component.model.endpoint.api.EndpointDescription;
import de.rcenvironment.core.datamodel.api.DataType;
import de.rcenvironment.core.datamodel.api.EndpointType;
import de.rcenvironment.core.gui.workflow.editor.commands.endpoint.AddDynamicInputWithOutputsCommand;
import de.rcenvironment.core.gui.workflow.editor.commands.endpoint.EditDynamicInputWithOutputsCommand;
import de.rcenvironment.core.gui.workflow.editor.commands.endpoint.RemoveDynamicInputWithOutputsCommand;

/**
 * Input pane for endpoints that add two outputs next to a requested input.
 *
 * @author Doreen Seider
 */
public class InputCoupledWithOutputsSelectionPane extends ForwardingEndpointSelectionPane {

    private final String dynEndpointId;

    private final Refreshable outputPane;

    private final String nameSuffix;

    private Map<String, String> metaDataInput = new HashMap<>();

    private Map<String, String> metaDataOutput = new HashMap<>();

    private Map<String, String> metaDataOutputWithSuffix = new HashMap<>();

    public InputCoupledWithOutputsSelectionPane(String title, String dynEndpointId, String nameSuffix,
        WorkflowNodeCommand.Executor executor, Refreshable outputPane) {
        super(title, EndpointType.INPUT, executor, false, dynEndpointId, true, true);
        this.dynEndpointId = dynEndpointId;
        this.outputPane = outputPane;
        this.nameSuffix = nameSuffix;
    }

    @Override
    protected void executeAddCommand(String name, DataType type, Map<String, String> metaData) {
        metaDataInput.putAll(metaData);
        WorkflowNodeCommand command = new AddDynamicInputWithOutputsCommand(dynEndpointId, name, type, metaDataInput,
            nameSuffix, this, outputPane);
        ((AddDynamicInputWithOutputsCommand) command).setMetaDataOutput(metaDataOutput);
        ((AddDynamicInputWithOutputsCommand) command).setMetaDataOutputWithSuffix(metaDataOutputWithSuffix);
        execute(command);
    }

    @Override
    protected void executeEditCommand(EndpointDescription oldDescription, EndpointDescription newDescription) {
        WorkflowNodeCommand command = new EditDynamicInputWithOutputsCommand(oldDescription, newDescription, nameSuffix, this, outputPane);
        ((EditDynamicInputWithOutputsCommand) command).setMetaDataOutput(metaDataOutput);
        ((EditDynamicInputWithOutputsCommand) command).setMetaDataOutputWithSuffix(metaDataOutputWithSuffix);
        execute(command);
    }

    @Override
    protected void executeRemoveCommand(List<String> names) {
        final WorkflowNodeCommand command = new RemoveDynamicInputWithOutputsCommand(dynEndpointId, names, nameSuffix, this, outputPane);
        execute(command);
    }

    @Override
    public void setMetaDataInput(Map<String, String> metaDataInput) {
        this.metaDataInput = metaDataInput;
    }

    public void setMetaDataOutput(Map<String, String> metaDataOutput) {
        this.metaDataOutput = metaDataOutput;
    }

    public void setMetaDataOutputWithSuffix(Map<String, String> metaDataOutputWithSuffix) {
        this.metaDataOutputWithSuffix = metaDataOutputWithSuffix;
    }

}

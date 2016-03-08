/*
 * Copyright (C) 2006-2015 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */
package de.rcenvironment.core.gui.workflow.editor.commands.endpoint;

import java.util.Map;

import de.rcenvironment.core.datamodel.api.DataType;
import de.rcenvironment.core.gui.workflow.editor.properties.Refreshable;

/**
 * Adds one single input and two outputs - one with the same name as the input and one with the same
 * name as the input + given suffix.
 * 
 * @author Doreen Seider
 */
public class AddDynamicInputWithOutputsCommand extends AddDynamicInputWithOutputCommand {

    private final String nameSuffix;
    
    private Map<String, String> metaDataOutputWithSuffix;

    public AddDynamicInputWithOutputsCommand(String dynamicEndpointId, String name, DataType type, 
        Map<String, String> metaData, String nameSuffix, Refreshable... panes) {
        super(dynamicEndpointId, name, type, metaData, panes);
        this.nameSuffix = nameSuffix;
    }

    @Override
    public void execute() {
        InputWithOutputsCommandUtils.addOutputWithSuffix(getProperties(), dynEndpointId, name, type, nameSuffix, metaDataOutputWithSuffix);
        super.execute();
    }

    @Override
    public void undo() {
        InputWithOutputsCommandUtils.removeOutputWithSuffix(getProperties(), name, nameSuffix);
        super.undo();
    }
    
    public void setMetaDataOutputWithSuffix(Map<String, String> metaDataOutputWithSuffix) {
        this.metaDataOutputWithSuffix = metaDataOutputWithSuffix;
    }
}
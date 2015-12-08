/*
 * Copyright (C) 2006-2015 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */
 
package de.rcenvironment.core.component.model.endpoint.impl;

import de.rcenvironment.core.component.model.endpoint.api.InitialDynamicEndpointDefinition;
import de.rcenvironment.core.datamodel.api.DataType;

/**
 * Implementation of {@link InitialDynamicEndpointDefinition}.
 * 
 * @author Doreen Seider
 */
public class InitialDynamicEndpointDefinitionImpl implements InitialDynamicEndpointDefinition {

    private static final long serialVersionUID = -6493471587070176889L;

    private String name;
    
    private DataType dataType;
    
    public InitialDynamicEndpointDefinitionImpl() {}
    
    public InitialDynamicEndpointDefinitionImpl(String name, DataType dataType) {
        this.name = name;
        this.dataType = dataType;
    }
    
    @Override
    public String getName() {
        return name;
    }

    @Override
    public DataType getDataType() {
        return dataType;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }
    
    
    
}

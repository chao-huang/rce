/*
 * Copyright (C) 2006-2015 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */

package de.rcenvironment.core.start.validators.internal;

import java.util.Map;

import de.rcenvironment.core.configuration.ConfigurationSegment;
import de.rcenvironment.core.configuration.ConfigurationService;
import de.rcenvironment.core.start.common.validation.api.InstanceValidationResult;
import de.rcenvironment.core.start.common.validation.api.InstanceValidationResultFactory;
import de.rcenvironment.core.start.common.validation.spi.InstanceValidator;
import de.rcenvironment.core.utils.common.StringUtils;

/**
 * Ensures that the Connection configuration values are valid.
 * 
 * @author Goekhan Guerkan
 */

public class HostConfigurationValidator implements InstanceValidator {

    /**
     * Constant.
     */
    private static final String HOST = "host";

    /**
     * The invalid IP to check.
     */
    private static final String INVALID_IP = "0.0.0.0";

    private static final String VALIDATIONNAME = "Connection configuration (host IP check)";

    private ConfigurationService configurationService;

    @Override
    public InstanceValidationResult validate() {

        ConfigurationSegment configurationSegment = configurationService.getConfigurationSegment("network");

        Map<String, ConfigurationSegment> connectionElements = configurationSegment.listElements("connections");

        for (Map.Entry<String, ConfigurationSegment> entry : connectionElements.entrySet()) {

            ConfigurationSegment segment = entry.getValue();
            if (segment.getString(HOST).equals(INVALID_IP)) {
                String message = StringUtils.format(Messages.invalidIPconfig, entry.getKey());
                return InstanceValidationResultFactory.createResultForFailureWhichRequiresInstanceShutdown(
                    VALIDATIONNAME, message, message);
            }

        }
        return InstanceValidationResultFactory.createResultForPassed(VALIDATIONNAME);

    }

    protected void bindConfigurationService(ConfigurationService configIn) {
        configurationService = configIn;
    }

}

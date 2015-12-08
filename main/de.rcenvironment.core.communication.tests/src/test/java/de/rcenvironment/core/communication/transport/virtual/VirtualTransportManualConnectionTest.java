/*
 * Copyright (C) 2006-2012 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */
package de.rcenvironment.core.communication.transport.virtual;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.rcenvironment.core.communication.testutils.TestConfiguration;
import de.rcenvironment.core.communication.testutils.templates.AbstractManualConnectionTest;
import de.rcenvironment.core.communication.testutils.templates.AbstractTransportLowLevelTest;

/**
 * Concrete class for running the {@link AbstractTransportLowLevelTest} test cases with a duplex-enabled
 * {@link VirtualNetworkTransportProvider}.
 * 
 * @author Robert Mischke
 */
public class VirtualTransportManualConnectionTest extends AbstractManualConnectionTest {

    protected TestConfiguration defineTestConfiguration() {
        return new VirtualTransportTestConfiguration(false);
    }

    /**
     * Dummy test case to make this class being detected as a test outside of eclipse.
     */
    @Test
    public void dummyTest() {
        assertTrue(true);
    }
}

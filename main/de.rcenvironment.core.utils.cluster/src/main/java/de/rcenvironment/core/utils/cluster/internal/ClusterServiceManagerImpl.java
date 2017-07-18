/*
 * Copyright (C) 2006-2016 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */
 
package de.rcenvironment.core.utils.cluster.internal;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.codec.binary.Base64;

import de.rcenvironment.core.utils.cluster.ClusterService;
import de.rcenvironment.core.utils.cluster.ClusterServiceManager;
import de.rcenvironment.core.utils.cluster.ClusterQueuingSystem;
import de.rcenvironment.core.utils.cluster.sge.internal.SgeClusterService;
import de.rcenvironment.core.utils.cluster.torque.internal.TorqueClusterService;
import de.rcenvironment.core.utils.common.StringUtils;
import de.rcenvironment.core.utils.ssh.jsch.SshSessionConfiguration;
import de.rcenvironment.core.utils.ssh.jsch.SshSessionConfigurationFactory;

/**
 * Implementation of {@link ClusterServiceManager}.
 * 
 * @author Doreen Seider
 */
public class ClusterServiceManagerImpl implements ClusterServiceManager {

    private Map<String, ClusterService> informationServices = new HashMap<String, ClusterService>();

    @Override
    public synchronized ClusterService retrieveSshBasedClusterService(ClusterQueuingSystem system,
        Map<String, String> pathsToQueuingSystemCommands, String host, int port, String sshAuthUser, String sshAuthPhrase) {
        
        String clusterServiceId = createIdentifier(system, host, port, sshAuthUser, sshAuthPhrase,
            pathsToQueuingSystemCommands.toString());
        
        ClusterService clusterService;
        
        if (informationServices.containsKey(clusterServiceId)) {
            clusterService = informationServices.get(clusterServiceId);
        } else {
            SshSessionConfiguration sshConfiguration = SshSessionConfigurationFactory
                .createSshSessionConfigurationWithAuthPhrase(host, port, sshAuthUser, sshAuthPhrase);
            switch (system) {
            case TORQUE:
                clusterService = new TorqueClusterService(sshConfiguration, pathsToQueuingSystemCommands);
                break;
            case SGE:
                clusterService = new SgeClusterService(sshConfiguration, pathsToQueuingSystemCommands);
                break;
            default:
                throw new UnsupportedOperationException("Cluster queuing system not supported: " + system);
            }
            informationServices.put(clusterServiceId, clusterService);
        }
        return clusterService;
    }
    
    private String createIdentifier(ClusterQueuingSystem system, String host, int port, String sshAuthUser, String sshAuthPhrase,
        String pathsToCommands) {
        return StringUtils.escapeAndConcat(system.name(), host, String.valueOf(port), sshAuthUser,
            String.valueOf(Base64.encodeBase64(sshAuthPhrase.getBytes())), pathsToCommands);
    }

}

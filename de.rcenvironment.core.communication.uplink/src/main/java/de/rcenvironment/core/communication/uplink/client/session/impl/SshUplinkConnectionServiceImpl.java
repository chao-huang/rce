/*
 * Copyright 2006-2019 DLR, Germany
 * 
 * SPDX-License-Identifier: EPL-1.0
 * 
 * https://rcenvironment.de/
 */

package de.rcenvironment.core.communication.uplink.client.session.impl;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogConfigurationException;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import de.rcenvironment.core.communication.configuration.NodeConfigurationService;
import de.rcenvironment.core.communication.sshconnection.InitialUplinkConnectionConfig;
import de.rcenvironment.core.communication.sshconnection.SshConnectionContext;
import de.rcenvironment.core.communication.uplink.client.execution.api.ToolExecutionProvider;
import de.rcenvironment.core.communication.uplink.client.execution.api.ToolExecutionRequest;
import de.rcenvironment.core.communication.uplink.client.execution.impl.ToolExecutionProviderImpl;
import de.rcenvironment.core.communication.uplink.client.session.api.ClientSideUplinkSession;
import de.rcenvironment.core.communication.uplink.client.session.api.ClientSideUplinkSessionEventHandler;
import de.rcenvironment.core.communication.uplink.client.session.api.DestinationIdUtils;
import de.rcenvironment.core.communication.uplink.client.session.api.LocalUplinkSessionService;
import de.rcenvironment.core.communication.uplink.client.session.api.SshUplinkConnectionConstants;
import de.rcenvironment.core.communication.uplink.client.session.api.SshUplinkConnectionListener;
import de.rcenvironment.core.communication.uplink.client.session.api.SshUplinkConnectionListenerAdapter;
import de.rcenvironment.core.communication.uplink.client.session.api.SshUplinkConnectionService;
import de.rcenvironment.core.communication.uplink.client.session.api.SshUplinkConnectionSetup;
import de.rcenvironment.core.communication.uplink.client.session.api.ToolDescriptorListUpdate;
import de.rcenvironment.core.communication.uplink.client.session.api.UplinkLogicalNodeMappingService;
import de.rcenvironment.core.communication.uplink.client.session.internal.ClientSideUplinkSessionParameters;
import de.rcenvironment.core.communication.uplink.network.internal.UplinkProtocolErrorType;
import de.rcenvironment.core.component.integration.documentation.ToolIntegrationDocumentationService;
import de.rcenvironment.core.configuration.SecureStorageImportService;
import de.rcenvironment.core.configuration.SecureStorageSection;
import de.rcenvironment.core.configuration.SecureStorageService;
import de.rcenvironment.core.toolkitbridge.transitional.ConcurrencyUtils;
import de.rcenvironment.core.utils.common.FileCompressionFormat;
import de.rcenvironment.core.utils.common.FileCompressionService;
import de.rcenvironment.core.utils.common.SizeValidatedDataSource;
import de.rcenvironment.core.utils.common.StringUtils;
import de.rcenvironment.core.utils.common.exception.OperationFailureException;
import de.rcenvironment.core.utils.common.rpc.RemoteOperationException;
import de.rcenvironment.core.utils.ssh.jsch.JschSessionFactory;
import de.rcenvironment.core.utils.ssh.jsch.SshParameterException;
import de.rcenvironment.toolkit.modules.concurrency.api.AsyncCallback;
import de.rcenvironment.toolkit.modules.concurrency.api.AsyncCallbackExceptionPolicy;
import de.rcenvironment.toolkit.modules.concurrency.api.AsyncOrderedCallbackManager;
import de.rcenvironment.toolkit.modules.concurrency.api.AsyncTaskService;
import de.rcenvironment.toolkit.modules.concurrency.api.ThreadGuard;

/**
 * Default implementation of {@link SshUplinkConnectionService}.
 *
 * @author Brigitte Boden
 * @author Robert Mischke
 */
@Component
public class SshUplinkConnectionServiceImpl implements SshUplinkConnectionService {

    private static final String NO_SSH_CONNECTION_WITH_ID_S_CONFIGURED = "No SSH connection with id %s configured.";

    private static final String SLASH = "/";

    private final AsyncTaskService threadPool = ConcurrencyUtils.getAsyncTaskService();

    private final Map<String, SshUplinkConnectionSetup> connectionSetups;

    private final Log log = LogFactory.getLog(getClass());

    private final AsyncOrderedCallbackManager<SshUplinkConnectionListener> callbackManager =
        ConcurrencyUtils.getFactory().createAsyncOrderedCallbackManager(AsyncCallbackExceptionPolicy.LOG_AND_CANCEL_LISTENER);

    private NodeConfigurationService configurationService;

    private SecureStorageService securePreferencesService;

    private SecureStorageSection secureStorageSection;

    @Reference
    private SecureStorageImportService secureStorageImportService;

    @Reference
    private LocalUplinkSessionService uplinkSessionService;

    private SshUplinkConnectionListener uplinkConnectionlistener;

    @Reference
    private ToolIntegrationDocumentationService toolDocService;

    @Reference
    private UplinkLogicalNodeMappingService logicalNodeMappingService;

    public SshUplinkConnectionServiceImpl() {
        connectionSetups = new HashMap<String, SshUplinkConnectionSetup>();
        uplinkConnectionlistener = defineListenerForUplinkConnectionSetups();
    }

    @Override
    public ClientSideUplinkSession getAvtiveSshUplinkSession(String connectionId) {
        final SshUplinkConnectionSetup setup = connectionSetups.get(connectionId);
        if (setup == null) {
            log.warn(StringUtils.format(NO_SSH_CONNECTION_WITH_ID_S_CONFIGURED, connectionId));
            return null;
        }
        return setup.getSession();
    }
    
    @Override
    public boolean sshUplinkConnectionAlreadyExists(SshConnectionContext context) {
        for (String s : connectionSetups.keySet()) {
            if (connectionSetups.get(s).getHost().contentEquals(context.getDestinationHost())
                && connectionSetups.get(s).getPort() == context.getPort()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String addSshUplinkConnection(SshConnectionContext context) {
        String connectionId = UUID.randomUUID().toString();

        final SshUplinkConnectionSetupImpl newSetup;
        newSetup =
            new SshUplinkConnectionSetupImpl(connectionId, context.getDisplayName(), context.getQualifier(), context.getDestinationHost(),
                context.getPort(), context.getSshAuthUser(), context.getKeyfileLocation(), context.isUsePassphrase(),
                context.isConnectImmediately(), context.isAutoRetry(), context.isGateway(), uplinkConnectionlistener);

        if (newSetup != null) {
            synchronized (connectionSetups) {
                connectionSetups.put(connectionId, newSetup);
                final Collection<SshUplinkConnectionSetup> snapshot = Collections.unmodifiableCollection(connectionSetups.values());
                callbackManager.enqueueCallback(new AsyncCallback<SshUplinkConnectionListener>() {

                    @Override
                    public void performCallback(SshUplinkConnectionListener listener) {
                        listener.onCollectionChanged(snapshot);
                    }
                });
            }
        }
        return connectionId;
    }

    private SshUplinkConnectionListener defineListenerForUplinkConnectionSetups() {
        SshUplinkConnectionListener listenerAdapter = new SshUplinkConnectionListenerAdapter() {

            @Override
            public void onConnectionAttemptFailed(final SshUplinkConnectionSetup setup, final String reason,
                final boolean firstConsecutiveFailure, final boolean willAutoRetry) {

                if (willAutoRetry) {
                    scheduleAutoRetry(setup);
                }

                callbackManager.enqueueCallback(new AsyncCallback<SshUplinkConnectionListener>() {

                    @Override
                    public void performCallback(SshUplinkConnectionListener listener) {
                        listener.onConnectionAttemptFailed(setup, reason, firstConsecutiveFailure, willAutoRetry);
                    }
                });
            }

            @Override
            public void onConnectionClosed(final SshUplinkConnectionSetup setup, final boolean willAutoRetry) {

                if (willAutoRetry) {
                    scheduleAutoRetry(setup);
                }

                callbackManager.enqueueCallback(new AsyncCallback<SshUplinkConnectionListener>() {

                    @Override
                    public void performCallback(SshUplinkConnectionListener listener) {
                        listener.onConnectionClosed(setup, willAutoRetry);
                    }
                });
            }

            @Override
            public void onConnected(final SshUplinkConnectionSetup setup) {
                callbackManager.enqueueCallback(new AsyncCallback<SshUplinkConnectionListener>() {

                    @Override
                    public void performCallback(SshUplinkConnectionListener listener) {
                        listener.onConnected(setup);
                    }
                });
            }

            @Override
            public void onCreated(final SshUplinkConnectionSetup setup) {
                callbackManager.enqueueCallback(new AsyncCallback<SshUplinkConnectionListener>() {

                    @Override
                    public void performCallback(SshUplinkConnectionListener listener) {
                        listener.onCreated(setup);
                    }
                });
            }

            @Override
            public void onPublicationEntriesChanged(ToolDescriptorListUpdate publicationEntries, String connectionId) {
                callbackManager.enqueueCallback(new AsyncCallback<SshUplinkConnectionListener>() {

                    @Override
                    public void performCallback(SshUplinkConnectionListener listener) {
                        listener.onPublicationEntriesChanged(publicationEntries, connectionId);
                    }
                });
            }

        };
        return listenerAdapter;
    }

    private void scheduleAutoRetry(final SshUplinkConnectionSetup setup) {
        log.debug(StringUtils.format("Scheduling auto-retry of connection %s in %d msec", setup.getDisplayName(),
            SshUplinkConnectionConstants.DELAY_BEFORE_RETRY));
        threadPool.scheduleAfterDelay("Communication Layer: SshUplinkConnectionService auto-reconnect timer", () -> {
            if (setup.isWaitingForRetry()) {
                connectSession(setup.getId());
            }
        },
            SshUplinkConnectionConstants.DELAY_BEFORE_RETRY);
        setup.setWaitingForRetry(true);
    }

    @Override
    public boolean isConnected(String connectionId) {
        return connectionSetups.get(connectionId).isConnected();
    }

    @Override
    public boolean isWaitingForRetry(String connectionId) {
        return connectionSetups.get(connectionId).isWaitingForRetry();
    }

    @Override
    public void connectSession(String connectionId) {
        ThreadGuard.checkForForbiddenThread();

        String passphrase = "";
        if (connectionSetups.get(connectionId).getUsePassphrase()) {
            // Retreive passphrase from secure storage.
            passphrase = retrieveUplinkConnectionPassword(connectionId);
        }

        connectSession(connectionId, passphrase);
    }

    @Override
    public void connectSession(String connectionId, String passphrase) {
        ThreadGuard.checkForForbiddenThread();
        final SshUplinkConnectionSetup sshConnectionSetup = connectionSetups.get(connectionId);
        if (sshConnectionSetup == null) {
            log.warn(StringUtils.format(NO_SSH_CONNECTION_WITH_ID_S_CONFIGURED, connectionId));
        } else {
            connectSession(sshConnectionSetup, passphrase);
        }
    }

    @Override
    public void disconnectSession(String connectionId) {
        final SshUplinkConnectionSetup sshConnectionSetup = connectionSetups.get(connectionId);
        if (sshConnectionSetup == null) {
            log.warn(StringUtils.format(NO_SSH_CONNECTION_WITH_ID_S_CONFIGURED, connectionId));
            return;
        }
        if (sshConnectionSetup.isConnected()) {
            sshConnectionSetup.disconnect();
        } else if (sshConnectionSetup.isWaitingForRetry()) {
            sshConnectionSetup.setWaitingForRetry(false);
            callbackManager.enqueueCallback(new AsyncCallback<SshUplinkConnectionListener>() {

                @Override
                public void performCallback(SshUplinkConnectionListener listener) {
                    listener.onConnectionClosed(sshConnectionSetup, false);
                }
            });
        }
    }

    @Override
    public void disposeConnection(String connectionId) {
        final SshUplinkConnectionSetup setup = connectionSetups.get(connectionId);
        if (setup == null) {
            log.warn(StringUtils.format(NO_SSH_CONNECTION_WITH_ID_S_CONFIGURED, connectionId));
            return;
        }
        if (setup.isConnected()) {
            setup.disconnect();
        } else if (setup.isWaitingForRetry()) {
            setup.setWaitingForRetry(false);
        }
        synchronized (connectionSetups) {
            connectionSetups.remove(connectionId);
            final Collection<SshUplinkConnectionSetup> snapshot = Collections.unmodifiableCollection(connectionSetups.values());
            callbackManager.enqueueCallback(new AsyncCallback<SshUplinkConnectionListener>() {

                @Override
                public void performCallback(SshUplinkConnectionListener listener) {
                    listener.onDisposed(setup);
                    listener.onCollectionChanged(snapshot);
                }
            });
        }
    }

    @Override
    public SshUplinkConnectionSetup getConnectionSetup(String connnectionId) {
        return connectionSetups.get(connnectionId);
    }

    @Override
    public Collection<SshUplinkConnectionSetup> getAllSshConnectionSetups() {
        return Collections.unmodifiableCollection(connectionSetups.values());
    }

    @Override
    public Map<String, SshUplinkConnectionSetup> getAllActiveSshConnectionSetups() {
        Map<String, SshUplinkConnectionSetup> activeConnections = new HashMap<String, SshUplinkConnectionSetup>();
        for (SshUplinkConnectionSetup connection : connectionSetups.values()) {
            if (connection.isConnected()) {
                activeConnections.put(connection.getId(), connection);
            }
        }
        return activeConnections;
    }

    /**
     * Adds a {@link SshUplinkConnectionListener}.
     * 
     * @param listener The listener.
     */
    @Override
    public void addListener(SshUplinkConnectionListener listener) {
        callbackManager.addListener(listener);
    }

    /**
     * Removes a {@link SshUplinkConnectionListener}.
     * 
     * @param listener The listener.
     */
    public void removeListener(SshUplinkConnectionListener listener) {
        callbackManager.removeListener(listener);
    }

    @Override
    public void editSshUplinkConnection(SshConnectionContext context) {

        final SshUplinkConnectionSetupImpl newSetup;
        newSetup =
            new SshUplinkConnectionSetupImpl(context.getId(), context.getDisplayName(), context.getQualifier(),
                context.getDestinationHost(), context.getPort(), context.getSshAuthUser(), context.getKeyfileLocation(),
                context.isUsePassphrase(), context.isConnectImmediately(), context.isAutoRetry(), context.isGateway(),
                uplinkConnectionlistener);

        if (newSetup != null) {
            synchronized (connectionSetups) {
                connectionSetups.put(context.getId(), newSetup);
                final Collection<SshUplinkConnectionSetup> snapshot = Collections.unmodifiableCollection(connectionSetups.values());
                callbackManager.enqueueCallback(new AsyncCallback<SshUplinkConnectionListener>() {

                    @Override
                    public void performCallback(SshUplinkConnectionListener listener) {
                        listener.onCollectionChanged(snapshot);
                    }
                });
            }
        }
    }

    @Override
    public Collection<String> getAllActiveSshConnectionSetupIds() {
        return getAllActiveSshConnectionSetups().keySet();
    }

    /**
     * OSGi-DS lifecycle method.
     */
    @Activate
    public void activate() {

        // perform any file-based password imports
        final File importFilesDir =
            configurationService.getStandardImportDirectory(SshUplinkConnectionConstants.PASSWORD_FILE_IMPORT_SUBDIRECTORY);
        try {
            secureStorageImportService.processImportDirectory(importFilesDir,
                SshUplinkConnectionConstants.UPLINK_CONNECTIONS_PASSWORDS_NODE, null, null, true, true);
        } catch (OperationFailureException e) {
            log.warn("Error while attempting to import SSH Uplink connection passwords from " + importFilesDir + ": " + e.getMessage());
        }

        try {
            secureStorageSection =
                securePreferencesService.getSecureStorageSection(SshUplinkConnectionConstants.UPLINK_CONNECTIONS_PASSWORDS_NODE);
        } catch (IOException e) {
            // TODO decide: how to handle this case?
            log.error("Failed to initialize secure storage");
        }

        ConcurrencyUtils.getAsyncTaskService().execute("Client-Side Uplink Access: Add pre-configured SSH connections",
            () -> addAndConnectInitialUplinkConfigs(configurationService.getInitialUplinkConnectionConfigs()));
    }

    private void addAndConnectInitialUplinkConfigs(List<InitialUplinkConnectionConfig> configs) {
        ThreadGuard.checkForForbiddenThread();
        for (InitialUplinkConnectionConfig config : configs) {
            SshUplinkConnectionSetup setup =
                new SshUplinkConnectionSetupImpl(config.getId(), config.getDisplayName(), config.getQualifier(), config.getHost(),
                    config.getPort(), config.getUser(), config.getKeyFileLocation(), config.getUsePassphrase(),
                    config.getConnectOnStartup(),
                    config.getAutoRetry(), config.isGateway(), uplinkConnectionlistener);
            connectionSetups.put(config.getId(), setup);
            if (config.getConnectOnStartup()) {
                if (config.getUsePassphrase()) {
                    // log.error("Could not connect connection " + config.getDisplayName() + " on startup, it requires a passphrase.");
                    connectSession(setup, retrieveUplinkConnectionPassword(setup.getId()));
                } else {
                    connectSession(setup, "");
                }
            }
        }
    }

    /**
     * OSGI bind method.
     * 
     * @param service The service to bind.
     */
    @Reference
    public void bindNodeConfigurationService(NodeConfigurationService service) {
        this.configurationService = service;
    }

    private void storeSshConnectionPassword(String connectionId, String password) {

        try {
            secureStorageSection.store(connectionId, password);
        } catch (OperationFailureException e) {
            log.error("Could not store password: " + e);
        }
    }

    private void removeSshConnectionPasswordIfExists(String connectionId) {

        try {
            secureStorageSection.delete(connectionId);
        } catch (OperationFailureException e) {
            log.error("Could not remove password: " + e);
        }
    }

    @Override
    public String retrieveUplinkConnectionPassword(String connectionId) {
        String passphrase = null;
        try {
            passphrase = secureStorageSection.read(connectionId, null);
        } catch (OperationFailureException e) {
            log.error("Could not retrieve password: " + e);
            return null;
        }
        return passphrase;
    }

    @Override
    public void setAuthPhraseForSshConnection(String id, String sshAuthPassPhrase, boolean storePassphrase) {

        final SshUplinkConnectionSetup oldSetup = connectionSetups.get(id);
        final SshUplinkConnectionSetupImpl newSetup;
        newSetup =
            new SshUplinkConnectionSetupImpl(id, oldSetup.getDisplayName(), oldSetup.getQualifier(), oldSetup.getHost(),
                oldSetup.getPort(), oldSetup.getUsername(), oldSetup.getKeyfileLocation(), oldSetup.getUsePassphrase(),
                oldSetup.getConnectOnStartUp(), oldSetup.getAutoRetry(), oldSetup.isGateway(), uplinkConnectionlistener);

        if (newSetup != null) {
            synchronized (connectionSetups) {
                connectionSetups.put(id, newSetup);
                final Collection<SshUplinkConnectionSetup> snapshot = Collections.unmodifiableCollection(connectionSetups.values());
                callbackManager.enqueueCallback(new AsyncCallback<SshUplinkConnectionListener>() {

                    @Override
                    public void performCallback(SshUplinkConnectionListener listener) {
                        listener.onCollectionChanged(snapshot);
                    }
                });
            }
            if (storePassphrase) {
                storeSshConnectionPassword(id, sshAuthPassPhrase);
            } else {
                // Remove old stored password, if one exists.
                removeSshConnectionPasswordIfExists(id);
            }
        }
    }

    @Reference
    protected void bindSecureStorageService(SecureStorageService newService) {
        securePreferencesService = newService;
    }

    private void connectSession(SshUplinkConnectionSetup setup, String passphrase) {
        ThreadGuard.checkForForbiddenThread();

        final ClientSideUplinkSession uplinkSession;

        if (setup.getKeyfileLocation() == null && passphrase == null) {
            log.warn(StringUtils.format("Connecting SSH session failed because no key file and no passphrase is given: host %s, port %s.",
                setup.getHost(), setup.getPort()));
            String error = "No key file or passphrase could be found. Probable cause: "
                + "This was an automatic reconnection attempt and the passphrase is not stored.";
            uplinkConnectionlistener.onConnectionAttemptFailed(setup, error, true, false);
            return;
        }

        try {
            Session sshSession =
                JschSessionFactory.setupSession(setup.getHost(), setup.getPort(), setup.getUsername(),
                    setup.getKeyfileLocation(), passphrase, JschSessionFactory.createDelegateLogger(LogFactory.getLog(getClass())));
            final ClientSideUplinkSessionParameters sessionParameters = new ClientSideUplinkSessionParameters("My display name",
                setup.getQualifier(), null);
            uplinkSession = uplinkSessionService.createSession(new SshUplinkConnectionImpl(sshSession), sessionParameters,
                new ClientSideUplinkSessionEventHandler() {

                    private UplinkProtocolErrorType errorType;

                    @Override
                    public void onSessionReady(String namespaceId, String destinationIdPrefix) {
                        setup.setDestinationIdPrefix(namespaceId);
                        uplinkConnectionlistener.onConnected(setup);
                    }

                    @Override
                    public void onSessionTerminating() {
                        boolean willAutoRetry = setup.getAutoRetry();
                        // if the session was terminated by an error, check whether the error type suggests not to auto-retry
                        if (errorType != null) {
                            willAutoRetry = willAutoRetry && errorType.getClientRetryFlag();
                        }
                        uplinkConnectionlistener.onConnectionClosed(setup, willAutoRetry);
                        log.debug("Uplink session " + setup.getSession() + " is terminating");
                    }

                    @Override
                    public void registerConnectionOrSessionError(UplinkProtocolErrorType errorType, String errorMessage) {
                        // TODO Preliminary code, this should not be decided here.
                        // Will be changed when "state machine" approach is implemented for uplink connections
                        if (errorType.equals(UplinkProtocolErrorType.CLIENT_NAMESPACE_COLLISION)
                            || errorType.equals(UplinkProtocolErrorType.PROTOCOL_VERSION_MISMATCH)) {
                            uplinkConnectionlistener.onConnectionAttemptFailed(setup, errorMessage,
                                (setup.getConsecutiveConnectionFailures() <= 1), false);
                        }
                        this.errorType = errorType;
                        log.warn("Uplink session or connection error: " + errorMessage + " (type " + errorType + ", session id: "
                            + setup.getSession() + ")");
                    }

                    @Override
                    public void onSessionInFinalState() {
                        log.debug("Session closed");
                    }

                    @Override
                    public void processToolDescriptorListUpdate(ToolDescriptorListUpdate update) {
                        uplinkConnectionlistener.onPublicationEntriesChanged(update, setup.getId());
                    }

                    @Override
                    public ToolExecutionProvider setUpToolExecutionProvider(ToolExecutionRequest request) {
                        return new ToolExecutionProviderImpl(request);
                    }

                    @Override
                    public Optional<SizeValidatedDataSource> provideToolDocumentationData(String destinationId, String docReferenceId) {
                        String nodeId =
                            DestinationIdUtils.getNodeIdFromQualifiedDestinationId(destinationId);
                        File docDir;
                        try {
                            docDir =
                                toolDocService.getToolDocumentation(docReferenceId.split(SLASH)[0] + SLASH + docReferenceId.split(SLASH)[1],
                                    nodeId, docReferenceId.split(SLASH)[2]);
                            final byte[] data =
                                FileCompressionService.compressDirectoryToByteArray(docDir, FileCompressionFormat.ZIP, false);
                            return Optional.of(new SizeValidatedDataSource(data));
                        } catch (RemoteOperationException | IOException e) {
                            log.warn("Could not retreive tool documentation from tool documentation service: ", e);
                            return null;
                        }
                    }

                });
            ConcurrencyUtils.getAsyncTaskService().execute("Run SSH Uplink session", () -> {
                try {
                    uplinkSession.runSession();
                } catch (IOException e) {
                    log.error("Caught error from runSession() for connection " + setup.getDisplayName(), e);
                }
            });

            setup.setSession(uplinkSession);
            setup.setWaitingForRetry(false);
            setup.resetConsecutiveConnectionFailures();

        } catch (OperationFailureException | LogConfigurationException | JSchException | SshParameterException e) {
            log.warn(StringUtils.format("Connecting SSH session failed: host %s, port %s: %s", setup.getHost(),
                setup.getPort(), e.toString()));
            // Filter typical reasons to produce better error messages.
            String reason = e.getMessage();
            Throwable cause = e.getCause();
            if (reason == null) {
                reason = "";
            }
            // Reconnect only makes sense if some network problem occured, not if the credentials are wrong.
            boolean shouldTryToReconnect = setup.getAutoRetry();
            if (cause != null && cause instanceof ConnectException) {
                reason = "The remote instance could not be reached. Probably the hostname or port is wrong.";
            } else if (cause != null && cause instanceof UnknownHostException) {
                reason = "No host with this name could be found.";
            } else if (reason.equals("Auth fail")) {
                reason =
                    "Authentication failed. Probably the username or passphrase is wrong, the wrong key file was used or the account is "
                        + "not enabled on the remote host.";
                shouldTryToReconnect = false;
            } else if (reason.equals("USERAUTH fail")) {
                reason = "Authentication failed. The wrong passphrase for the key file " + setup.getKeyfileLocation() + " was used.";
                shouldTryToReconnect = false;
            } else if (reason.startsWith("invalid privatekey")) {
                reason = "Authentication failed. An invalid private key was used.";
                shouldTryToReconnect = false;
            }
            if (shouldTryToReconnect) {
                setup.raiseConsecutiveConnectionFailures();
            }
            uplinkConnectionlistener.onConnectionAttemptFailed(setup, reason, (setup.getConsecutiveConnectionFailures() <= 1),
                shouldTryToReconnect);

            return;
        }
    }
}
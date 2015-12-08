/*
 * Copyright (C) 2006-2015 DLR, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */

package de.rcenvironment.core.communication.nodeproperties.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import de.rcenvironment.core.communication.channel.MessageChannelLifecycleListenerAdapter;
import de.rcenvironment.core.communication.channel.MessageChannelService;
import de.rcenvironment.core.communication.channel.MessageChannelState;
import de.rcenvironment.core.communication.common.NodeIdentifier;
import de.rcenvironment.core.communication.common.SerializationException;
import de.rcenvironment.core.communication.configuration.NodeConfigurationService;
import de.rcenvironment.core.communication.messaging.NetworkRequestHandler;
import de.rcenvironment.core.communication.messaging.NetworkRequestHandlerMap;
import de.rcenvironment.core.communication.messaging.direct.api.DirectMessagingSender;
import de.rcenvironment.core.communication.messaging.internal.InternalMessagingException;
import de.rcenvironment.core.communication.model.NetworkMessage;
import de.rcenvironment.core.communication.model.NetworkRequest;
import de.rcenvironment.core.communication.model.NetworkResponse;
import de.rcenvironment.core.communication.model.NetworkResponseHandler;
import de.rcenvironment.core.communication.model.internal.NodeInformationRegistryImpl;
import de.rcenvironment.core.communication.nodeproperties.NodePropertiesService;
import de.rcenvironment.core.communication.nodeproperties.NodeProperty;
import de.rcenvironment.core.communication.nodeproperties.NodePropertyConstants;
import de.rcenvironment.core.communication.nodeproperties.spi.RawNodePropertiesChangeListener;
import de.rcenvironment.core.communication.protocol.NetworkRequestFactory;
import de.rcenvironment.core.communication.protocol.NetworkResponseFactory;
import de.rcenvironment.core.communication.protocol.ProtocolConstants;
import de.rcenvironment.core.communication.transport.spi.MessageChannel;
import de.rcenvironment.core.communication.utils.MessageUtils;
import de.rcenvironment.core.utils.common.RestartSafeIncreasingValueGenerator;
import de.rcenvironment.core.utils.common.StatsCounter;
import de.rcenvironment.core.utils.common.StringUtils;
import de.rcenvironment.core.utils.common.concurrent.AsyncCallback;
import de.rcenvironment.core.utils.common.concurrent.AsyncCallbackExceptionPolicy;
import de.rcenvironment.core.utils.common.concurrent.AsyncOrderedCallbackManager;
import de.rcenvironment.core.utils.common.concurrent.BatchAggregator;
import de.rcenvironment.core.utils.common.concurrent.SharedThreadPool;
import de.rcenvironment.core.utils.common.concurrent.ThreadPool;
import de.rcenvironment.core.utils.incubator.DebugSettings;

/**
 * Default {@link NodePropertiesService} implementation.
 * 
 * @author Robert Mischke
 */
public class NodePropertiesServiceImpl implements NodePropertiesService {

    private static final String MESSAGE_SUBTYPE_INITIAL = "init";

    private static final String MESSAGE_SUBTYPE_INCREMENTAL = "delta";

    // this limit only serves to avoid excessive aggregated delta sizes; the usual trigger should be the timer
    private static final int MAX_DELTA_BATCH_SIZE = 25;

    // tweak as necessary
    private static final int MAX_DELTA_BATCH_LATENCY = 150;

    private final Object knowledgeLock = new Object();

    private final NodePropertiesRegistry completeKnowledgeRegistry;

    /**
     * A reduced {@link NodePropertiesRegistry} that only keeps the properties published by the local node. It is used for two purposes:
     * avoid distributing the properties of different networks to each other in non-relay mode; and checking received updates for the local
     * node against what was actually published in this session, and overwriting stale entries if necessary.
     * 
     * This instance is always synchronized along with {@link #completeKnowledgeRegistry} via {@link #knowledgeLock}.
     */
    private final NodePropertiesRegistry locallyPublishedKnowledgeRegistry;

    private final RestartSafeIncreasingValueGenerator timeKeeper = new RestartSafeIncreasingValueGenerator();

    private final AsyncOrderedCallbackManager<RawNodePropertiesChangeListener> callbackManager;

    private NetworkRequestHandler networkRequestHandler;

    private MessageChannelService connectionService;

    private DirectMessagingSender directMessagingSender;

    private NodeIdentifier localNodeId;

    private NodeConfigurationService nodeConfigurationService;

    private final ThreadPool threadPool = SharedThreadPool.getInstance();

    private final boolean verboseLogging = DebugSettings.getVerboseLoggingEnabled(getClass());

    private final BatchAggregator<UpdateDeltaForBroadcasting> deltaBroadcastAggregator;

    private final Log log = LogFactory.getLog(getClass());

    private boolean localNodeIsRelay;

    /**
     * Represents the parsed form of a received update.
     * 
     * @author Robert Mischke
     */
    private final class IncomingUpdate {

        private String[] rawParts;

        private String subtype;

        private boolean isInitialUpdate;

        private List<NodePropertyImpl> entries;

        public IncomingUpdate(NetworkMessage request) {
            entries = new ArrayList<NodePropertyImpl>();
            try {
                rawParts = tokenizeMessageBody(request);
            } catch (SerializationException e) {
                throw new IllegalArgumentException("Error deserializing node property update", e);
            }
            subtype = null;
            for (String part : rawParts) {
                if (subtype == null) {
                    subtype = part;
                    // log.info("  Message type: " + subtype);
                    continue;
                }
                // log.info("  extracted node property entry: " + part);
                NodePropertyImpl entry = new NodePropertyImpl(part);
                entries.add(entry);
            }

            if (MESSAGE_SUBTYPE_INITIAL.equals(subtype)) {
                isInitialUpdate = true;
            } else if (MESSAGE_SUBTYPE_INCREMENTAL.equals(subtype)) {
                isInitialUpdate = false;
            } else {
                throw new IllegalArgumentException("Invalid node property update sub-type: " + subtype);
            }

        }

        private String[] tokenizeMessageBody(NetworkMessage request) throws SerializationException {
            String bodyString = (String) request.getDeserializedContent();
            if (bodyString == null) {
                throw new IllegalArgumentException("Received node property update with 'null' as content");
            }
            // log.debug(localNodeId + ": Received node property update: " + bodyString);
            String[] parts = StringUtils.splitAndUnescape(bodyString);
            return parts;
        }

    }

    /**
     * Represents a single received or locally-generated set of node property updates, with an optional field to exclude the sender from
     * receiving its own updates.
     * 
     * @author Robert Mischke
     */
    private class UpdateDeltaForBroadcasting {

        private final Collection<NodePropertyImpl> properties;

        private final NodeIdentifier recipientExclusion;

        // note: currently, the fact that new neighbors may appear while this delta is being aggregated with others
        // is not considered a problem; if this changes, a snapshot of the current neighbors may be needed, too
        public UpdateDeltaForBroadcasting(Collection<NodePropertyImpl> properties, NodeIdentifier recipientExclusion) {
            this.properties = properties;
            this.recipientExclusion = recipientExclusion;
        }

    }

    public NodePropertiesServiceImpl() {
        this.completeKnowledgeRegistry = new NodePropertiesRegistry();
        this.locallyPublishedKnowledgeRegistry = new NodePropertiesRegistry();
        this.callbackManager = new AsyncOrderedCallbackManager<RawNodePropertiesChangeListener>(threadPool,
            AsyncCallbackExceptionPolicy.LOG_AND_CANCEL_LISTENER);

        this.networkRequestHandler = new NetworkRequestHandler() {

            @Override
            public NetworkResponse handleRequest(NetworkRequest request, NodeIdentifier lastHopNodeId) throws InternalMessagingException {
                return handleIncomingUpdate(request);
            }
        };

        this.deltaBroadcastAggregator =
            new BatchAggregator<>(MAX_DELTA_BATCH_SIZE, MAX_DELTA_BATCH_LATENCY,
                new BatchAggregator.BatchProcessor<UpdateDeltaForBroadcasting>() {

                    private final AtomicInteger counter = new AtomicInteger();

                    @Override
                    public void processBatch(List<UpdateDeltaForBroadcasting> batch) {
                        // integer overflow is irrelevant here, the id is only used for logging
                        final int batchId = counter.incrementAndGet();
                        // to avoid overhead on single updates, check if no actual merging is needed
                        if (batch.size() == 1) {
                            UpdateDeltaForBroadcasting singleDelta = batch.get(0);
                            broadcastToAllNeighboursExcept(MESSAGE_SUBTYPE_INCREMENTAL, singleDelta.properties,
                                singleDelta.recipientExclusion, batchId);
                        } else {
                            broadcastIndividualMergedUpdates(MESSAGE_SUBTYPE_INCREMENTAL, batch, batchId);
                        }
                        StatsCounter.registerValue("Node property updates", "Number of aggregated deltas per batch", batch.size());
                    }

                });

        // register logging property listener on self
        addRawNodePropertiesChangeListener(new RawNodePropertiesChangeListener() {

            @Override
            public void onRawNodePropertiesAddedOrModified(Collection<? extends NodeProperty> newProperties) {
                if (verboseLogging) {
                    int i = 1;
                    for (NodeProperty property : newProperties) {
                        log.debug(StringUtils.format("Raw node property change (%d/%d) received by %s, published by %s: '%s' := '%s' [%d]",
                            i++, newProperties.size(), localNodeId.getIdString(), property.getNodeIdString(),
                            property.getKey(), property.getValue(), property.getSequenceNo()));
                    }
                }

                // listen to "display name" property changes and apply them
                // TODO move to better place?
                for (NodeProperty property : newProperties) {
                    if (NodePropertyConstants.KEY_DISPLAY_NAME.equals(property.getKey())) {
                        String nodeIdString = property.getNodeIdString();
                        String displayName = property.getValue();
                        if (verboseLogging) {
                            log.debug(StringUtils.format("Setting associated display name for node %s to '%s'", nodeIdString, displayName));
                        }
                        NodeInformationRegistryImpl.getInstance().getWritableNodeInformation(nodeIdString)
                            .setDisplayName(displayName);
                    }
                }
            }
        });
    }

    /**
     * OSGi-DS lifecycle method.
     */
    public void activate() {
        localNodeId = nodeConfigurationService.getLocalNodeId();
        if (localNodeId == null) {
            throw new NullPointerException();
        }
        localNodeIsRelay = nodeConfigurationService.isRelay();
        connectionService.addChannelLifecycleListener(new MessageChannelLifecycleListenerAdapter() {

            @Override
            public void setInitialMessageChannels(Set<MessageChannel> currentChannels) {
                // TODO handle existing channels? should not occur on usual startup, but possible in general - misc_ro
                if (currentChannels.size() != 0) {
                    log.warn("Initial message channels not empty: " + currentChannels);
                }
            }

            @Override
            public void onOutgoingChannelEstablished(MessageChannel channel) {
                log.debug(localNodeId + ": established channel (" + channel.getInitiatedByRemote()
                    + "), sending initial node property to " + channel.getRemoteNodeInformation().getNodeId());
                if (channel.getState() == MessageChannelState.ESTABLISHED) {
                    // consistency check
                    Set<MessageChannel> allOutgoingChannels = connectionService.getAllOutgoingChannels();
                    if (!allOutgoingChannels.contains(channel)) {
                        log.warn("Channel " + channel + " established, but not contained in the 'all channels' set yet!");
                    }
                    performInitialExchange(channel);
                } else {
                    log.debug("Ignoring node property update for channel " + channel.getChannelId() + " as it is " + channel.getState());
                }
            }
        });
    }

    /**
     * OSGi-DS "bind" method; made public for integration testing.
     * 
     * @param newInstance the new service instance to bind
     */
    public void bindMessageChannelService(MessageChannelService newInstance) {
        this.connectionService = newInstance;
        // note: currently, MessageChannelService extends DirectMessagingSender; this may be changed in the future
        this.directMessagingSender = newInstance;
    }

    /**
     * OSGi-DS "bind" method; made public for integration testing.
     * 
     * @param newInstance the new service instance to bind
     */
    public void bindNodeConfigurationService(NodeConfigurationService newInstance) {
        this.nodeConfigurationService = newInstance;
    }

    @Override
    public void addOrUpdateLocalNodeProperty(String key, String value) {
        // convert to map
        Map<String, String> map = new HashMap<String, String>();
        map.put(key, value);
        // delegate
        addOrUpdateLocalNodeProperties(map);
    }

    @Override
    public void addOrUpdateLocalNodeProperties(Map<String, String> data) {
        if (data.isEmpty()) {
            // FIXME 7.0.0: set to WARN for testing; reduce to DEBUG before release
            log.warn(
                "A node properties update was triggered with empty update data; logging stacktrace (no actual exception thrown)",
                new IllegalArgumentException());
            return;
        }
        synchronized (knowledgeLock) {
            // create set of NodeProperty entries
            long newSequenceNo = timeKeeper.invalidateAndGet();
            List<NodePropertyImpl> newDelta = new ArrayList<NodePropertyImpl>();
            for (Entry<String, String> entry : data.entrySet()) {
                newDelta.add(new NodePropertyImpl(localNodeId.getIdString(), entry.getKey(), newSequenceNo, entry.getValue()));
            }
            // all entries are new, so the merging can be kept simple
            completeKnowledgeRegistry.mergeUnchecked(newDelta);
            locallyPublishedKnowledgeRegistry.mergeUnchecked(newDelta);
            deltaBroadcastAggregator.enqueue(new UpdateDeltaForBroadcasting(newDelta, null)); // null = no broadcast exclusion
            // broadcastToAllNeighbours(MESSAGE_SUBTYPE_INCREMENTAL, newDelta);
            // guard against modification
            newDelta = Collections.unmodifiableList(newDelta);
            // notify listeners
            reportImmutableDeltaToListeners(newDelta);
        }
    }

    @Override
    public Map<String, String> getNodeProperties(NodeIdentifier nodeId) {
        synchronized (knowledgeLock) {
            return completeKnowledgeRegistry.getNodeProperties(nodeId);
        }
    }

    @Override
    public Map<NodeIdentifier, Map<String, String>> getAllNodeProperties(Collection<NodeIdentifier> nodeIds) {
        synchronized (knowledgeLock) {
            return completeKnowledgeRegistry.getAllNodeProperties(nodeIds);
        }
    }

    @Override
    public Map<NodeIdentifier, Map<String, String>> getAllNodeProperties() {
        synchronized (knowledgeLock) {
            return completeKnowledgeRegistry.getAllNodeProperties();
        }
    }

    @Override
    public void addRawNodePropertiesChangeListener(RawNodePropertiesChangeListener listener) {
        synchronized (knowledgeLock) {
            final Collection<NodePropertyImpl> copyOfCompleteKnowledge = completeKnowledgeRegistry.getDetachedCopyOfEntries();
            callbackManager.addListenerAndEnqueueCallback(listener, new AsyncCallback<RawNodePropertiesChangeListener>() {

                @Override
                public void performCallback(RawNodePropertiesChangeListener listener) {
                    listener.onRawNodePropertiesAddedOrModified(copyOfCompleteKnowledge);
                }
            });
        }
    }

    @Override
    public void removeRawNodePropertiesChangeListener(RawNodePropertiesChangeListener listener) {
        callbackManager.removeListener(listener);
    }

    @Override
    public NetworkRequestHandlerMap getNetworkRequestHandlers() {
        return new NetworkRequestHandlerMap(ProtocolConstants.VALUE_MESSAGE_TYPE_NODE_PROPERTIES_UPDATE, networkRequestHandler);
    }

    private void performInitialExchange(final MessageChannel channel) {
        Collection<NodePropertyImpl> knowledgeToPublish;
        synchronized (knowledgeLock) {
            if (localNodeIsRelay) {
                knowledgeToPublish = completeKnowledgeRegistry.getDetachedCopyOfEntries();
            } else {
                knowledgeToPublish = locallyPublishedKnowledgeRegistry.getDetachedCopyOfEntries();
            }
        }

        final NetworkRequest request = constructNetworkRequest(MESSAGE_SUBTYPE_INITIAL, knowledgeToPublish);
        directMessagingSender.sendDirectMessageAsync(request, channel, new NetworkResponseHandler() {

            @Override
            public void onResponseAvailable(NetworkResponse response) {
                final NodeIdentifier sender = channel.getRemoteNodeInformation().getNodeId();
                // sanity check
                if (sender == null) {
                    log.error("Consistency error: empty remote node id for channel " + channel + " after initial properties exchange");
                    // in this unlikely case, proceed; log messages will contain 'null', but all other behavior is valid - misc_ro
                }
                if (!response.isSuccess()) {
                    log.warn(StringUtils.format("Initial node property exchange with %s via channel %s failed: ",
                        sender, channel.getChannelId(), response.getResultCode()));
                    return;
                }
                try {
                    final IncomingUpdate parsedUpdate = new IncomingUpdate(response);
                    // TODO sanity/protocol check: test for proper subtype
                    final Collection<NodePropertyImpl> effectiveSubset = mergeIntoFullKnowledgeAndGetEffectiveSubset(parsedUpdate);
                    if (localNodeIsRelay) {
                        log.debug("Received initial node property response from " + sender
                            + "; forwarding to all other connected instances");
                        forwardIfNotEmpty(sender, effectiveSubset);
                    } else {
                        log.debug("Received initial node property response from " + sender);
                    }
                } catch (RuntimeException e) {
                    log.warn("Failed to deserialize response for initial node property exchange", e);
                }

            }
        });
    }

    private NetworkResponse handleIncomingUpdate(NetworkRequest request) throws InternalMessagingException {
        try {
            IncomingUpdate parsedUpdate = new IncomingUpdate(request);
            NodeIdentifier sender = request.accessMetaData().getSender();

            // TODO warn/fail on remote modification of local data?
            Collection<NodePropertyImpl> effectiveSubset = mergeIntoFullKnowledgeAndGetEffectiveSubset(parsedUpdate);
            if (localNodeIsRelay) {
                forwardIfNotEmpty(sender, effectiveSubset);
            }

            if (parsedUpdate.isInitialUpdate) {
                // respond with complementing knowledge
                Collection<NodePropertyImpl> complementingKnowledge;
                synchronized (knowledgeLock) {
                    if (localNodeIsRelay) {
                        // relay: calculate complementing knowledge using the full knowledge set
                        complementingKnowledge = completeKnowledgeRegistry.getComplementingKnowledge(parsedUpdate.entries);
                        log.debug(StringUtils.format("Responding to initial node property exchange with %d complementing entries "
                            + "(out of %d in the complete set)",
                            complementingKnowledge.size(), completeKnowledgeRegistry.getEntryCount()));
                    } else {
                        // non-relay: calculate complementing knowledge using only the local entries set
                        complementingKnowledge = locallyPublishedKnowledgeRegistry.getComplementingKnowledge(parsedUpdate.entries);
                        log.debug(StringUtils.format("Responding to initial node property exchange with %d complementing entries "
                            + "(out of %d in the local set)", complementingKnowledge.size(),
                            locallyPublishedKnowledgeRegistry.getEntryCount()));
                    }
                }

                byte[] responseBody = constructMessageBody(MESSAGE_SUBTYPE_INCREMENTAL, complementingKnowledge);
                return NetworkResponseFactory.generateSuccessResponse(request, responseBody);
            } else {
                byte[] responseBody = new byte[0]; // dummy
                return NetworkResponseFactory.generateSuccessResponse(request, responseBody);
            }

        } catch (RuntimeException e) {
            throw new InternalMessagingException("Error processing node properties update", e);
        }
    }

    private Collection<NodePropertyImpl> mergeIntoFullKnowledgeAndGetEffectiveSubset(IncomingUpdate parsedUpdate) {
        Collection<NodePropertyImpl> effectiveSubset;
        synchronized (knowledgeLock) {
            Map<String, String> propertiesToRepublishOrCancel = checkForPropertiesToRepublishOrCancel(parsedUpdate.entries);
            if (!propertiesToRepublishOrCancel.isEmpty()) {
                log.debug("Publishing a cancel/republish set containing " + propertiesToRepublishOrCancel.size() + " entries");
                // note that this may result in the sender of an initial update receiving this as an incremental update *before*
                // receiving the complementing knowledge set for its initial message; this should not cause problems, though - misc_ro
                addOrUpdateLocalNodeProperties(propertiesToRepublishOrCancel);
            }

            // no relay/non-relay distinction here, as the result is only forwarded in relay mode
            effectiveSubset = completeKnowledgeRegistry.mergeAndGetEffectiveSubset(parsedUpdate.entries);
            // guard against modification
            effectiveSubset = Collections.unmodifiableCollection(effectiveSubset);
            // notify listeners
            reportImmutableDeltaToListeners(effectiveSubset);
            return effectiveSubset;
        }
    }

    private Map<String, String> checkForPropertiesToRepublishOrCancel(List<NodePropertyImpl> entries) {
        Map<String, String> result = new HashMap<String, String>();
        final String localNodeIdString = localNodeId.getIdString();
        for (NodePropertyImpl receivedProperty : entries) {
            if (localNodeIdString.equals(receivedProperty.getNodeIdString())) {
                final String key = receivedProperty.getKey();
                final NodeProperty existingProperty = locallyPublishedKnowledgeRegistry.getNodeProperty(localNodeId, key);
                if (existingProperty == null) {
                    log.debug("Received a node property for the local node with no local counterpart (a canceling "
                        + "update will be published): " + receivedProperty);
                    result.put(key, null);
                } else if (existingProperty.getSequenceNo() < receivedProperty.getSequenceNo()) {
                    // should not usually happen
                    log.warn("Received a node property for the local node that is 'newer' than the actual local state; "
                        + "is there a node with the same id in the network? (attempting to re-publish the local value)");
                    log.warn("Local property: " + existingProperty);
                    log.warn("Received property: " + receivedProperty);
                    result.put(key, existingProperty.getValue());
                }
            }
        }
        return result;
    }

    private void reportImmutableDeltaToListeners(final Collection<NodePropertyImpl> immutableDelta) {
        callbackManager.enqueueCallback(new AsyncCallback<RawNodePropertiesChangeListener>() {

            @Override
            public void performCallback(RawNodePropertiesChangeListener listener) {
                listener.onRawNodePropertiesAddedOrModified(immutableDelta);
            }
        });
    }

    /**
     * Forward to all *except* the given sender, unless the set is empty.
     * 
     * @param sender the sender that caused this update
     * @param effectiveSubset the subset of changes that caused local modifications (ie, that were unknown before)
     */
    private void forwardIfNotEmpty(NodeIdentifier sender, Collection<NodePropertyImpl> effectiveSubset) {
        // always forward all new aspects to other neighbors
        if (!effectiveSubset.isEmpty()) {
            // broadcastToAllNeighboursExcept(MESSAGE_SUBTYPE_INCREMENTAL, effectiveSubset, sender, -1);
            deltaBroadcastAggregator.enqueue(new UpdateDeltaForBroadcasting(effectiveSubset, sender)); // exclude sender
        } else {
            log.debug(localNodeId + ": node property update did not result in a local change; not forwarding)");
        }
    }

    @SuppressWarnings("unused")
    @Deprecated
    private void broadcastToAllNeighbours(String updateType, Collection<NodePropertyImpl> entries, int batchId) {
        broadcastToAllNeighboursExcept(updateType, entries, null, batchId); // null = no exclusion
    }

    private void broadcastToAllNeighboursExcept(String updateType, Collection<NodePropertyImpl> entries, NodeIdentifier exclusion,
        final int batchId) {
        log.debug("Broadcasting non-batched node properties update " + batchId);
        final Set<MessageChannel> channels = connectionService.getAllOutgoingChannels();
        NetworkRequest request = null;
        boolean firstRecipient = true;
        for (final MessageChannel channel : channels) {
            final NodeIdentifier remoteNodeId = channel.getRemoteNodeInformation().getNodeId();
            if (exclusion == null || !remoteNodeId.equals(exclusion)) {
                if (firstRecipient) {
                    // lazily construct request here in case there is no recipient at all
                    request = constructNetworkRequest(updateType, entries);
                    firstRecipient = false;
                } else {
                    // make request ids unique if there is more than one recipient, but don't regenerate the content payload
                    request = NetworkRequestFactory.cloneWithNewRequestId(request);
                }
                directMessagingSender.sendDirectMessageAsync(request, channel, new NetworkResponseHandler() {

                    @Override
                    public void onResponseAvailable(NetworkResponse response) {
                        if (!response.isSuccess()) {
                            log.warn(StringUtils.format("Failed to send node properties update %d to %s via channel %s: %s", batchId,
                                channel.getRemoteNodeInformation().getNodeId(), channel.getChannelId(),
                                response.getResultCode().toString()));
                        }
                    }
                });
            }
        }
    }

    private void broadcastIndividualMergedUpdates(String updateType, List<UpdateDeltaForBroadcasting> batch, final int batchId) {

        final Set<MessageChannel> channels = connectionService.getAllOutgoingChannels();
        final Map<MessageChannel, Map<String, NodePropertyImpl>> channelToMergedUpdateMap = new HashMap<>();

        for (UpdateDeltaForBroadcasting delta : batch) {
            if (delta.properties.isEmpty()) {
                // should have been filtered out before, so log a warning
                log.warn("Node properties batch update " + batchId + " contained an empty delta; ignoring");
                continue;
            }
            for (final MessageChannel channel : channels) {
                if (delta.recipientExclusion == null || channel.getRemoteNodeInformation().getNodeId() != delta.recipientExclusion) {
                    // channel should receive this delta; merge into outgoing state
                    Map<String, NodePropertyImpl> mergeMapForSingleRecipient = channelToMergedUpdateMap.get(channel);
                    if (mergeMapForSingleRecipient == null) {
                        // create merge map if not present yet (first delta for this recipient)
                        mergeMapForSingleRecipient = new HashMap<String, NodePropertyImpl>();
                        channelToMergedUpdateMap.put(channel, mergeMapForSingleRecipient);
                    }
                    // merge properties; note that once there is exactly one property update per composite key, their order is irrelevant
                    for (NodePropertyImpl incomingPropertyState : delta.properties) {
                        final String propertyKey = incomingPropertyState.getCompositeKey().getAsUniqueString();
                        // optimistically overwrite before checking sequence number, as this is the most frequent case
                        final NodePropertyImpl replacedPropertyState = mergeMapForSingleRecipient.put(propertyKey, incomingPropertyState);
                        if (replacedPropertyState != null
                            && replacedPropertyState.getSequenceNo() > incomingPropertyState.getSequenceNo()) {
                            // restore newer entry into map
                            mergeMapForSingleRecipient.put(propertyKey, replacedPropertyState);
                            // FIXME 7.0.0: set to WARN for testing; reduce to DEBUG before release
                            log.warn(StringUtils.format(
                                "Prevented an outdated property value from overwriting a newer one in batch aggregation: "
                                    + "prevented='%s', newer='%s'", incomingPropertyState, replacedPropertyState));
                        }
                    }
                }
            }
        }

        for (final MessageChannel channel : channels) {
            Map<String, NodePropertyImpl> mergeMapForSingleRecipient = channelToMergedUpdateMap.get(channel);
            NetworkRequest request;
            if (mergeMapForSingleRecipient != null) {
                // has map -> at least one delta was relevant
                if (mergeMapForSingleRecipient.isEmpty()) {
                    // consistency/sanity check failed
                    log.warn("Unexpected state: empty map of merged node property deltas, not sending an update via " + channel);
                    continue;
                }
                request = constructNetworkRequest(updateType, mergeMapForSingleRecipient.values());
                if (verboseLogging) {
                    log.debug(StringUtils.format("Sending aggregated node properties update %d to %s via channel %s",
                        batchId, channel.getRemoteNodeInformation().getNodeId(), channel.getChannelId()));
                }
                directMessagingSender.sendDirectMessageAsync(request, channel, new NetworkResponseHandler() {

                    @Override
                    public void onResponseAvailable(NetworkResponse response) {
                        if (!response.isSuccess()) {
                            log.warn(StringUtils.format("Failed to send aggregated node properties update %d to %s via channel %s: %s",
                                batchId, channel.getRemoteNodeInformation().getNodeId(),
                                channel.getChannelId(), response.getResultCode().toString()));
                        }
                    }
                });
            }
        }
    }

    private NetworkRequest constructNetworkRequest(String updateType, Collection<NodePropertyImpl> entries) {
        byte[] contentBytes = constructMessageBody(updateType, entries);
        NetworkRequest request = NetworkRequestFactory.createNetworkRequest(contentBytes,
            ProtocolConstants.VALUE_MESSAGE_TYPE_NODE_PROPERTIES_UPDATE, localNodeId, null);
        return request;
    }

    private byte[] constructMessageBody(String updateType, Collection<NodePropertyImpl> entries) {
        List<String> stringParts = new ArrayList<String>();
        stringParts.add(updateType);
        for (NodePropertyImpl entry : entries) {
            String compactForm = entry.toCompactForm();
            stringParts.add(compactForm);
        }
        String body = StringUtils.escapeAndConcat(stringParts.toArray(new String[stringParts.size()]));
        // log.debug(localNodeId + ": Constructed node property update: " + body);
        return MessageUtils.serializeSafeObject(body);
    }

}

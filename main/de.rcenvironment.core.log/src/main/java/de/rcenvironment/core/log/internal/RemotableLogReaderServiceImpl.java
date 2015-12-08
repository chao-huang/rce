/*
 * Copyright (C) 2006-2015 DLR, Germany, 2006-2010 Fraunhofer SCAI, Germany
 * 
 * All rights reserved
 * 
 * http://www.rcenvironment.de/
 */

package de.rcenvironment.core.log.internal;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.service.log.LogEntry;
import org.osgi.service.log.LogListener;
import org.osgi.service.log.LogReaderService;
import org.osgi.service.log.LogService;

import de.rcenvironment.core.log.RemotableLogReaderService;
import de.rcenvironment.core.log.SerializableLogEntry;
import de.rcenvironment.core.log.SerializableLogListener;
import de.rcenvironment.core.utils.common.StatsCounter;
import de.rcenvironment.core.utils.common.concurrent.AsyncCallbackExceptionPolicy;
import de.rcenvironment.core.utils.common.concurrent.AsyncOrderedExecutionQueue;
import de.rcenvironment.core.utils.common.concurrent.SharedThreadPool;
import de.rcenvironment.core.utils.common.concurrent.TaskDescription;
import de.rcenvironment.core.utils.common.rpc.RemoteOperationException;
import de.rcenvironment.core.utils.common.security.AllowRemoteAccess;

/**
 * Implementation of {@link RemotableLogReaderService}.
 * 
 * @author Doreen Seider
 * @author Mark Geiger
 * @author Robert Mischke
 */
public class RemotableLogReaderServiceImpl implements RemotableLogReaderService {

    /**
     * Implementation of the OSGi {@link LogListener} interface that asynchronously forwards each event to a given
     * {@link SerializableLogListener}.
     * 
     * @author Robert Mischke
     */
    private final class SingleReceiverOsgiLogForwarder implements LogListener {

        private static final String ASYNC_TASK_DESCRIPTION = "Forward log event to listener";

        private final SerializableLogListener externalListener;

        // TODO review exception policy; which is better?
        private final AsyncOrderedExecutionQueue orderedExecutionQueue = new AsyncOrderedExecutionQueue(
            AsyncCallbackExceptionPolicy.LOG_AND_CANCEL_LISTENER, SharedThreadPool.getInstance());

        private SingleReceiverOsgiLogForwarder(SerializableLogListener externalListener) {
            this.externalListener = externalListener;
        }

        @Override
        public void logged(final LogEntry entry) {

            if (entry.getLevel() == LogService.LOG_DEBUG) {
                return;
            }
            orderedExecutionQueue.enqueue(new Runnable() {

                @Override
                @TaskDescription(ASYNC_TASK_DESCRIPTION)
                public void run() {
                    try {
                        String exceptionString = "";
                        if (entry.getException() != null) {
                            exceptionString = entry.getException().toString();
                        }
                        externalListener.logged(new SerializableLogEntry(
                            entry.getBundle().getSymbolicName(),
                            entry.getLevel(),
                            entry.getMessage().replaceAll("\n", SerializableLogEntry.RCE_SEPARATOR),
                            entry.getTime(),
                            exceptionString));
                    } catch (RemoteOperationException e) {
                        final Log localLog = LogFactory.getLog(getClass());
                        localLog.error("Error while forwarding log event to listener "
                            + "(delivery of log events to this receiver will be cancelled): " + e.toString());
                        // TODO >7.0.0: these lines should be equivalent, but aren't; investigate why - misc_ro, Nov 2015
                        // orderedExecutionQueue.cancelAsync();
                        throw new RuntimeException("Error while forwarding log event to listener"); // workaround to restore behavior
                    }
                    // the @TaskDescription is not forwarded by AsyncOrderedExecutionQueue, so count a stats event for monitoring - misc_ro
                    StatsCounter.count(AsyncOrderedExecutionQueue.STATS_COUNTER_SHARED_CATEGORY_NAME, ASYNC_TASK_DESCRIPTION);
                }
            });
        }

        public void shutdown() {
            orderedExecutionQueue.cancelAsync();
        }
    }

    private static final long serialVersionUID = -7406557933348370062L;

    private LogReaderService osgiLogReaderService;

    private Map<SerializableLogListener, SingleReceiverOsgiLogForwarder> osgiLogForwardersByExternalListener =
        new HashMap<SerializableLogListener, SingleReceiverOsgiLogForwarder>();

    protected void bindLogReaderService(LogReaderService newLogReaderService) {
        osgiLogReaderService = newLogReaderService;
    }

    @Override
    @AllowRemoteAccess
    public void addLogListener(final SerializableLogListener externalListener) {
        SingleReceiverOsgiLogForwarder osgiLogForwarder = new SingleReceiverOsgiLogForwarder(externalListener);
        synchronized (osgiLogForwardersByExternalListener) {
            osgiLogForwardersByExternalListener.put(externalListener, osgiLogForwarder);
        }
        osgiLogReaderService.addLogListener(osgiLogForwarder);
    }

    @Override
    @AllowRemoteAccess
    public void removeLogListener(SerializableLogListener listener) {
        final SingleReceiverOsgiLogForwarder osgiLogForwarder;
        synchronized (osgiLogForwardersByExternalListener) {
            osgiLogForwarder = osgiLogForwardersByExternalListener.remove(listener);
        }
        if (osgiLogForwarder != null) {
            osgiLogForwarder.shutdown();
            // note: no need to cancel asynchronous listener events (?)
            osgiLogReaderService.removeLogListener(osgiLogForwarder);
        } else {
            LogFactory.getLog(getClass()).warn("Found no registered log forwarder for a remote listener");
        }
    }

    @Override
    @AllowRemoteAccess
    public List<SerializableLogEntry> getLog() {
        List<SerializableLogEntry> entries = new LinkedList<SerializableLogEntry>();
        @SuppressWarnings("unchecked") Enumeration<LogEntry> retrievedEntries = osgiLogReaderService.getLog();

        while (retrievedEntries.hasMoreElements()) {
            LogEntry entry = retrievedEntries.nextElement();

            if (entry.getLevel() != LogService.LOG_DEBUG) {
                String exceptionString = "";
                if (entry.getException() != null) {
                    exceptionString = entry.getException().toString();
                }
                entries.add(entries.size(), new SerializableLogEntry(
                    entry.getBundle().getSymbolicName(),
                    entry.getLevel(),
                    entry.getMessage().replaceAll("\n", SerializableLogEntry.RCE_SEPARATOR),
                    entry.getTime(),
                    exceptionString));
            }
        }
        return entries;
    }
}

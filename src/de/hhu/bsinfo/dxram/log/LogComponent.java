/*
 * Copyright (C) 2017 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science, Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxram.log;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import de.hhu.bsinfo.dxnet.core.NetworkException;
import de.hhu.bsinfo.dxram.DXRAMComponentOrder;
import de.hhu.bsinfo.dxram.backup.BackupComponent;
import de.hhu.bsinfo.dxram.backup.BackupPeer;
import de.hhu.bsinfo.dxram.backup.BackupRange;
import de.hhu.bsinfo.dxram.boot.AbstractBootComponent;
import de.hhu.bsinfo.dxram.chunk.ChunkBackupComponent;
import de.hhu.bsinfo.dxram.data.ChunkID;
import de.hhu.bsinfo.dxram.data.DataStructure;
import de.hhu.bsinfo.dxram.engine.AbstractDXRAMComponent;
import de.hhu.bsinfo.dxram.engine.DXRAMComponentAccessor;
import de.hhu.bsinfo.dxram.engine.DXRAMContext;
import de.hhu.bsinfo.dxram.engine.DXRAMJNIManager;
import de.hhu.bsinfo.dxram.log.header.AbstractLogEntryHeader;
import de.hhu.bsinfo.dxram.log.header.AbstractPrimLogEntryHeader;
import de.hhu.bsinfo.dxram.log.header.AbstractSecLogEntryHeader;
import de.hhu.bsinfo.dxram.log.header.ChecksumHandler;
import de.hhu.bsinfo.dxram.log.header.PrimLogEntryHeader;
import de.hhu.bsinfo.dxram.log.messages.InitBackupRangeRequest;
import de.hhu.bsinfo.dxram.log.messages.InitBackupRangeResponse;
import de.hhu.bsinfo.dxram.log.messages.InitRecoveredBackupRangeRequest;
import de.hhu.bsinfo.dxram.log.messages.InitRecoveredBackupRangeResponse;
import de.hhu.bsinfo.dxram.log.storage.LogCatalog;
import de.hhu.bsinfo.dxram.log.storage.PrimaryLog;
import de.hhu.bsinfo.dxram.log.storage.PrimaryWriteBuffer;
import de.hhu.bsinfo.dxram.log.storage.SecondaryLog;
import de.hhu.bsinfo.dxram.log.storage.SecondaryLogBuffer;
import de.hhu.bsinfo.dxram.log.storage.SecondaryLogsReorgThread;
import de.hhu.bsinfo.dxram.log.storage.TemporaryVersionsStorage;
import de.hhu.bsinfo.dxram.log.storage.Version;
import de.hhu.bsinfo.dxram.net.NetworkComponent;
import de.hhu.bsinfo.dxram.recovery.RecoveryMetadata;
import de.hhu.bsinfo.dxram.util.HarddriveAccessMode;
import de.hhu.bsinfo.dxram.util.NodeRole;
import de.hhu.bsinfo.dxutils.NodeID;
import de.hhu.bsinfo.dxutils.StringUtils;
import de.hhu.bsinfo.dxutils.jni.JNIFileRaw;

/**
 * This service provides access to the backend storage system.
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 03.02.2016
 */
public class LogComponent extends AbstractDXRAMComponent<LogComponentConfig> {
    // Constants
    private static final AbstractPrimLogEntryHeader PRIM_LOG_ENTRY_HEADER = new PrimLogEntryHeader();
    private static final int PAYLOAD_PRINT_LENGTH = 128;

    private HarddriveAccessMode m_mode;

    // component dependencies
    private NetworkComponent m_network;
    private AbstractBootComponent m_boot;
    private BackupComponent m_backup;
    private ChunkBackupComponent m_chunk;

    // private state
    private short m_nodeID;
    private boolean m_loggingIsActive;

    private long m_secondaryLogSize;

    private PrimaryWriteBuffer m_writeBuffer;
    private PrimaryLog m_primaryLog;
    private LogCatalog[] m_logCatalogs;

    private ReentrantReadWriteLock m_secondaryLogCreationLock;

    private SecondaryLogsReorgThread m_secondaryLogsReorgThread;

    private ReentrantLock m_flushLock;

    private String m_backupDirectory;

    private TemporaryVersionsStorage m_versionsForRecovery;

    /**
     * Creates the log component
     */
    public LogComponent() {
        super(DXRAMComponentOrder.Init.LOG, DXRAMComponentOrder.Shutdown.LOG, LogComponentConfig.class);
    }

    /**
     * Prints the metadata of one log entry
     *
     * @param p_payload
     *         buffer with payload
     * @param p_offset
     *         offset within buffer
     * @param p_length
     *         length of payload
     * @param p_version
     *         version of chunk
     * @param p_index
     *         index of log entry
     * @param p_logEntryHeader
     *         the log entry header
     */
    private static void printMetadata(final long p_chunkID, final byte[] p_payload, final int p_offset, final int p_length, final Version p_version,
            final int p_index, final AbstractSecLogEntryHeader p_logEntryHeader) {
        byte[] array;

        try {
            if (p_version.getVersion() != Version.INVALID_VERSION) {
                array = new String(Arrays.copyOfRange(p_payload, p_offset + p_logEntryHeader.getHeaderSize(p_payload, p_offset),
                        p_offset + p_logEntryHeader.getHeaderSize(p_payload, p_offset) + PAYLOAD_PRINT_LENGTH)).trim().getBytes();

                if (StringUtils.looksLikeUTF8(array)) {
                    System.out.println(
                            "Log Entry " + p_index + ": \t ChunkID - " + ChunkID.toHexString(p_chunkID) + ") \t Length - " + p_length + "\t Version - " +
                                    p_version.getEpoch() + ',' + p_version.getVersion() + " \t Payload - " + new String(array, "UTF-8"));
                } else {
                    System.out.println(
                            "Log Entry " + p_index + ": \t ChunkID - " + ChunkID.toHexString(p_chunkID) + ") \t Length - " + p_length + "\t Version - " +
                                    p_version.getEpoch() + ',' + p_version.getVersion() + " \t Payload is no String");
                }
            } else {
                System.out.println("Log Entry " + p_index + ": \t ChunkID - " + ChunkID.toHexString(p_chunkID) + ") \t Length - " + p_length + "\t Version - " +
                        p_version.getEpoch() + ',' + p_version.getVersion() + " \t Tombstones have no payload");
            }
        } catch (final UnsupportedEncodingException | IllegalArgumentException ignored) {
            System.out.println("Log Entry " + p_index + ": \t ChunkID - " + ChunkID.toHexString(p_chunkID) + ") \t Length - " + p_length + "\t Version - " +
                    p_version.getEpoch() + ',' + p_version.getVersion() + " \t Payload is no String");
        }
        // p_localID: -1 can only be printed as an int
    }

    /**
     * Get the segment size of the log
     *
     * @return Segment size of log in bytes
     */
    public int getSegmentSizeBytes() {
        return (int) getConfig().getLogSegmentSize().getBytes();
    }

    /**
     * Returns the Secondary Logs Reorganization Thread
     *
     * @return the instance of SecondaryLogsReorgThread
     */
    public SecondaryLogsReorgThread getReorganizationThread() {
        return m_secondaryLogsReorgThread;
    }

    /**
     * Returns the header size
     *
     * @param p_dataStructure
     *         the DataStructure
     * @return the header size
     */
    public short getApproxHeaderSize(final DataStructure p_dataStructure) {
        return getApproxHeaderSize(ChunkID.getCreatorID(p_dataStructure.getID()), ChunkID.getLocalID(p_dataStructure.getID()), p_dataStructure.sizeofObject());
    }

    /**
     * Returns the header size
     *
     * @param p_nodeID
     *         the NodeID
     * @param p_localID
     *         the LocalID
     * @param p_size
     *         the size of the Chunk
     * @return the header size
     */
    public short getApproxHeaderSize(final short p_nodeID, final long p_localID, final int p_size) {
        return AbstractSecLogEntryHeader.getApproxSecLogHeaderSize(m_boot.getNodeID() != p_nodeID, p_localID, p_size);
    }

    /**
     * Initializes a new backup range
     *
     * @param p_backupRange
     *         the backup range
     */
    public void initBackupRange(final BackupRange p_backupRange) {
        BackupPeer[] backupPeers;
        InitBackupRangeRequest request;
        InitBackupRangeResponse response;
        long time;

        backupPeers = p_backupRange.getBackupPeers();

        time = System.currentTimeMillis();
        if (backupPeers != null) {
            for (int i = 0; i < backupPeers.length; i++) {
                if (backupPeers[i] != null) {
                    // The last peer is new in a recovered backup range
                    request = new InitBackupRangeRequest(backupPeers[i].getNodeID(), p_backupRange.getRangeID());

                    try {
                        m_network.sendSync(request);
                    } catch (final NetworkException ignore) {
                        i--;
                        continue;
                    }

                    response = request.getResponse(InitBackupRangeResponse.class);

                    if (!response.getStatus()) {
                        i--;
                    }
                }
            }
        }
        // #if LOGGER == TRACE
        LOGGER.trace("Time to initialize range: %d", System.currentTimeMillis() - time);
        // #endif /* LOGGER == TRACE */
    }

    /**
     * Initializes a recovered backup range
     *
     * @param p_backupRange
     *         the backup range
     * @param p_oldBackupRange
     *         the old backup range on the failed peer
     * @param p_failedPeer
     *         the failed peer
     */
    public void initRecoveredBackupRange(final BackupRange p_backupRange, final short p_oldBackupRange, final short p_failedPeer, final short p_newBackupPeer) {
        BackupPeer[] backupPeers = p_backupRange.getBackupPeers();
        if (backupPeers != null) {
            for (int i = 0; i < backupPeers.length; i++) {
                if (backupPeers[i] != null) {
                    if (backupPeers[i].getNodeID() == p_newBackupPeer) {
                        initBackupRangeOnPeer(backupPeers[i].getNodeID(), p_backupRange.getRangeID(), p_oldBackupRange, p_failedPeer, true);
                    } else {
                        initBackupRangeOnPeer(backupPeers[i].getNodeID(), p_backupRange.getRangeID(), p_oldBackupRange, p_failedPeer, false);
                    }
                }
            }
        }
    }

    /**
     * Initializes a new backup range
     *
     * @param p_backupPeer
     *         the backup peer
     * @param p_rangeID
     *         the new range ID
     * @param p_originalRangeID
     *         the old range ID
     * @param p_originalOwner
     *         the failed peer
     * @param p_isNewPeer
     *         whether this backup range is new for given backup peer or already stored for failed peer
     */
    private void initBackupRangeOnPeer(final short p_backupPeer, final short p_rangeID, final short p_originalRangeID, final short p_originalOwner,
            final boolean p_isNewPeer) {
        InitRecoveredBackupRangeRequest request;
        InitRecoveredBackupRangeResponse response;
        long time;

        time = System.currentTimeMillis();
        request = new InitRecoveredBackupRangeRequest(p_backupPeer, p_rangeID, p_originalRangeID, p_originalOwner, p_isNewPeer);
        try {
            m_network.sendSync(request);
        } catch (final NetworkException ignore) {
        }

        response = request.getResponse(InitRecoveredBackupRangeResponse.class);

        if (response == null || !response.getStatus()) {
            // #if LOGGER == ERROR
            LOGGER.error("Backup range could not be initialized on 0x%X!", p_backupPeer);
            // #endif /* LOGGER == ERROR */
        }
        // #if LOGGER == TRACE
        LOGGER.trace("Time to initialize range: %d", System.currentTimeMillis() - time);
        // #endif /* LOGGER == TRACE */
    }

    /**
     * Removes the secondary log and buffer for given backup range
     *
     * @param p_owner
     *         the owner of the backup range
     * @param p_rangeID
     *         the RangeID
     */
    public void removeBackupRange(final short p_owner, final short p_rangeID) {
        LogCatalog cat;

        // Can be executed by application/network thread or writer thread
        m_secondaryLogCreationLock.writeLock().lock();
        cat = m_logCatalogs[p_owner & 0xFFFF];

        if (cat != null) {
            try {
                cat.removeAndCloseBufferAndLog(p_rangeID);
            } catch (IOException e) {
                // #if LOGGER == WARN
                LOGGER.trace("Backup range could not be removed from hard drive.");
                // #endif /* LOGGER == WARN */
            }
        }
        m_secondaryLogCreationLock.writeLock().unlock();
    }

    /**
     * Recovers all Chunks of given backup range
     *
     * @param p_owner
     *         the NodeID of the node whose Chunks have to be restored
     * @param p_rangeID
     *         the RangeID
     * @return the recovery metadata
     */
    public RecoveryMetadata recoverBackupRange(final short p_owner, final short p_rangeID) {
        RecoveryMetadata ret = null;
        SecondaryLogBuffer secLogBuffer;
        SecondaryLog secLog;

        try {
            long start = System.currentTimeMillis();
            m_secondaryLogsReorgThread.block();
            long timeToGetLock = System.currentTimeMillis() - start;

            secLogBuffer = getSecondaryLogBuffer(p_owner, p_rangeID);
            secLog = getSecondaryLog(p_owner, p_rangeID);
            if (secLogBuffer != null && secLog != null) {
                // Read all versions for recovery (must be done before flushing)
                long time = System.currentTimeMillis();
                if (m_versionsForRecovery == null) {
                    m_versionsForRecovery = new TemporaryVersionsStorage(m_secondaryLogSize);
                } else {
                    m_versionsForRecovery.clear();
                }
                long lowestCID = secLog.getCurrentVersions(m_versionsForRecovery, false);
                long timeToReadVersions = System.currentTimeMillis() - time;

                flushDataToPrimaryLog();
                secLogBuffer.flushSecLogBuffer();
                ret = secLog.recoverFromLog(m_versionsForRecovery, lowestCID, timeToGetLock, timeToReadVersions, m_chunk, true);
            } else {
                // #if LOGGER >= ERROR
                LOGGER.error("Backup range %d could not be recovered. Secondary log is missing!", p_rangeID);
                // #endif /* LOGGER >= ERROR */
            }
        } catch (final IOException | InterruptedException e) {
            // #if LOGGER >= ERROR
            LOGGER.error("Backup range recovery failed: %s", e);
            // #endif /* LOGGER >= ERROR */
        } finally {
            m_secondaryLogsReorgThread.unblock();
        }

        return ret;
    }

    /**
     * Recovers all Chunks of given backup range
     *
     * @param p_fileName
     *         the file name
     * @param p_path
     *         the path of the folder the file is in
     * @return the recovered Chunks
     */
    public DataStructure[] recoverBackupRangeFromFile(final String p_fileName, final String p_path) {
        DataStructure[] ret = null;

        try {
            ret = SecondaryLog
                    .recoverFromFile(p_fileName, p_path, getConfig().useChecksum(), m_secondaryLogSize, (int) getConfig().getLogSegmentSize().getBytes(),
                            m_mode);
        } catch (final IOException e) {
            // #if LOGGER >= ERROR
            LOGGER.error("Could not recover from file %s: %s", p_path, e);
            // #endif /* LOGGER >= ERROR */
        }

        return ret;
    }

    /**
     * Returns the secondary log buffer
     *
     * @param p_owner
     *         the owner NodeID
     * @param p_rangeID
     *         the RangeID
     * @return the secondary log buffer
     * @throws IOException
     *         if the secondary log buffer could not be returned
     * @throws InterruptedException
     *         if the secondary log buffer could not be returned
     */
    public SecondaryLogBuffer getSecondaryLogBuffer(final short p_owner, final short p_rangeID) throws IOException, InterruptedException {
        SecondaryLogBuffer ret = null;
        LogCatalog cat;

        // Can be executed by application/network thread or writer thread
        m_secondaryLogCreationLock.readLock().lock();
        cat = m_logCatalogs[p_owner & 0xFFFF];

        if (cat != null) {
            ret = cat.getBuffer(p_rangeID);
        }
        m_secondaryLogCreationLock.readLock().unlock();

        return ret;
    }

    /**
     * Grant the writer thread access to write buffer
     */
    public void grantAccessToWriterThread() {
        m_writeBuffer.grantAccessToWriterThread();
    }

    /**
     * Flushes all secondary log buffers
     *
     * @throws IOException
     *         if at least one secondary log could not be flushed
     * @throws InterruptedException
     *         if caller is interrupted
     */
    public void flushDataToSecondaryLogs() throws IOException, InterruptedException {
        LogCatalog cat;
        SecondaryLogBuffer[] buffers;

        if (m_flushLock.tryLock()) {
            try {
                for (int i = 0; i < Short.MAX_VALUE * 2 + 1; i++) {
                    cat = m_logCatalogs[i];
                    if (cat != null) {
                        buffers = cat.getAllBuffers();
                        for (int j = 0; j < buffers.length; j++) {
                            if (buffers[j] != null && !buffers[j].isBufferEmpty()) {
                                buffers[j].flushSecLogBuffer();
                            }
                        }
                    }
                }
            } finally {
                m_flushLock.unlock();
            }
        } else {
            // Another thread is flushing, wait until it is finished
            do {
                Thread.sleep(100);
            } while (m_flushLock.isLocked());
        }
    }

    /**
     * Returns all log catalogs
     *
     * @return the array of log catalogs
     */
    public LogCatalog[] getAllLogCatalogs() {
        return m_logCatalogs;
    }

    @Override
    protected boolean supportsSuperpeer() {
        return false;
    }

    @Override
    protected boolean supportsPeer() {
        return true;
    }

    @Override
    protected void resolveComponentDependencies(final DXRAMComponentAccessor p_componentAccessor) {
        m_network = p_componentAccessor.getComponent(NetworkComponent.class);
        m_boot = p_componentAccessor.getComponent(AbstractBootComponent.class);
        m_backup = p_componentAccessor.getComponent(BackupComponent.class);
        m_chunk = p_componentAccessor.getComponent(ChunkBackupComponent.class);
    }

    @Override
    protected boolean initComponent(final DXRAMContext.Config p_config) {

        // Set the segment size. Needed for log entry header to split large chunks (must be called before the first log entry header is created)
        AbstractLogEntryHeader.setSegmentSize((int) getConfig().getLogSegmentSize().getBytes());
        // Set the log entry header crc size (must be called before the first log entry header is created)
        ChecksumHandler.setCRCSize(getConfig().useChecksum());

        m_loggingIsActive = m_boot.getNodeRole() == NodeRole.PEER && m_backup.isActiveAndAvailableForBackup();
        if (m_loggingIsActive) {
            DXRAMJNIManager.loadJNIModule("JNINativeCRCGenerator");

            m_mode = HarddriveAccessMode.convert(getConfig().getHarddriveAccess());
            if (m_mode == HarddriveAccessMode.ODIRECT) {
                DXRAMJNIManager.loadJNIModule(HarddriveAccessMode.getJNIFileName(m_mode));
            } else if (m_mode == HarddriveAccessMode.RAW_DEVICE) {
                DXRAMJNIManager.loadJNIModule(HarddriveAccessMode.getJNIFileName(m_mode));
                JNIFileRaw.prepareRawDevice(getConfig().getRawDevicePath(), 0);
            }

            m_nodeID = m_boot.getNodeID();

            m_backupDirectory = m_backup.getConfig().getBackupDirectory();
            m_secondaryLogSize = m_backup.getConfig().getBackupRangeSize().getBytes() * 2;

            // Create primary log
            try {
                m_primaryLog = new PrimaryLog(this, m_backupDirectory, m_nodeID, getConfig().getPrimaryLogSize().getBytes(),
                        (int) getConfig().getFlashPageSize().getBytes(), m_mode);
            } catch (final IOException e) {
                // #if LOGGER >= ERROR
                LOGGER.error("Primary log creation failed", e);
                // #endif /* LOGGER >= ERROR */
            }
            // #if LOGGER == TRACE
            LOGGER.trace("Initialized primary log (%d)", (int) getConfig().getLogSegmentSize().getBytes());
            // #endif /* LOGGER == TRACE */

            // Create reorganization thread for secondary logs
            m_secondaryLogsReorgThread = new SecondaryLogsReorgThread(this, m_backup.getConfig().getBackupRangeSize().getBytes() * 2,
                    (int) getConfig().getLogSegmentSize().getBytes());
            m_secondaryLogsReorgThread.setName("Logging: Reorganization Thread");

            // Create primary log buffer
            m_writeBuffer = new PrimaryWriteBuffer(this, m_primaryLog, (int) getConfig().getWriteBufferSize().getBytes(),
                    (int) getConfig().getFlashPageSize().getBytes(), (int) m_secondaryLogSize, (int) getConfig().getLogSegmentSize().getBytes(),
                    getConfig().useChecksum(), getConfig().sortBufferPooling());

            // Create secondary log and secondary log buffer catalogs
            m_logCatalogs = new LogCatalog[Short.MAX_VALUE * 2 + 1];

            m_secondaryLogCreationLock = new ReentrantReadWriteLock(false);

            // Start secondary logs reorganization thread
            m_secondaryLogsReorgThread.start();

            m_flushLock = new ReentrantLock(false);
        }

        return true;
    }

    @Override
    protected boolean shutdownComponent() {
        LogCatalog cat;

        if (m_loggingIsActive) {
            // Close write buffer
            m_writeBuffer.closeWriteBuffer();
            m_writeBuffer = null;

            // Stop reorganization thread
            m_secondaryLogsReorgThread.interrupt();
            m_secondaryLogsReorgThread.shutdown();
            try {
                m_secondaryLogsReorgThread.join();
                // #if LOGGER >= INFO
                LOGGER.info("Shutdown of SecondaryLogsReorgThread successful");
                // #endif /* LOGGER >= INFO */
            } catch (final InterruptedException e1) {
                // #if LOGGER >= WARN
                LOGGER.warn("Could not wait for reorganization thread to finish. Interrupted");
                // #endif /* LOGGER >= WARN */
            }
            m_secondaryLogsReorgThread = null;

            // Close primary log
            if (m_primaryLog != null) {
                try {
                    m_primaryLog.close();
                } catch (final IOException e) {
                    // #if LOGGER >= WARN
                    LOGGER.warn("Could not close primary log!");
                    // #endif /* LOGGER >= WARN */
                }
                m_primaryLog = null;
            }

            // Clear secondary logs and buffers
            for (int i = 0; i < Short.MAX_VALUE * 2 + 1; i++) {
                try {
                    cat = m_logCatalogs[i];
                    if (cat != null) {
                        cat.closeLogsAndBuffers();
                    }
                } catch (final IOException e) {
                    // #if LOGGER >= WARN
                    LOGGER.warn("Could not close secondary log buffer %d", i);
                    // #endif /* LOGGER >= WARN */
                }
            }
            m_logCatalogs = null;
        }

        return true;
    }

    /**
     * Returns the current utilization of primary log and all secondary logs
     *
     * @return the current utilization
     */
    String getCurrentUtilization() {
        String ret;
        long allBytesAllocated = 0;
        long allBytesOccupied = 0;
        long counterAllocated;
        long counterOccupied;
        long occupiedInRange;
        SecondaryLog[] secondaryLogs;
        SecondaryLogBuffer[] secLogBuffers;
        LogCatalog cat;

        if (m_loggingIsActive) {
            ret = "***********************************************************************\n" + "*Primary log: " + m_primaryLog.getOccupiedSpace() +
                    " bytes\n" + "***********************************************************************\n\n" +
                    "+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n" + "+Secondary logs:\n";

            for (int i = 0; i < m_logCatalogs.length; i++) {
                cat = m_logCatalogs[i];
                if (cat != null) {
                    counterAllocated = 0;
                    counterOccupied = 0;
                    ret += "++Node " + NodeID.toHexString((short) i) + ":\n";
                    secondaryLogs = cat.getAllLogs();
                    secLogBuffers = cat.getAllBuffers();
                    for (int j = 0; j < secondaryLogs.length; j++) {
                        if (secondaryLogs[j] != null) {
                            ret += "+++Backup range " + j + ": ";
                            if (secondaryLogs[j].isAccessed()) {
                                ret += "#Active log# ";
                            }

                            counterAllocated += secondaryLogs[j].getLogFileSize() + secondaryLogs[j].getVersionsFileSize();
                            occupiedInRange = secondaryLogs[j].getOccupiedSpace();
                            counterOccupied += occupiedInRange;

                            ret += occupiedInRange + " bytes (in buffer: " + secLogBuffers[j].getOccupiedSpace() + " bytes)\n";
                            ret += secondaryLogs[j].getSegmentDistribution() + '\n';
                        }
                    }
                    ret += "++Bytes per node: allocated -> " + counterAllocated + ", occupied -> " + counterOccupied + '\n';
                    allBytesAllocated += counterAllocated;
                    allBytesOccupied += counterOccupied;
                }
            }
            ret += "Complete size: allocated -> " + allBytesAllocated + ", occupied -> " + allBytesOccupied + '\n';
            ret += "+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++\n";
        } else {
            ret = "Backup is deactivated!\n";
        }

        return ret;
    }

    /**
     * Initializes a new backup range
     *
     * @param p_rangeID
     *         the RangeID
     * @param p_owner
     *         the Chunks' owner
     * @return whether the operation was successful or not
     */
    boolean incomingInitBackupRange(final short p_rangeID, final short p_owner) {
        boolean ret = true;
        LogCatalog cat;
        SecondaryLog secLog;

        // Initialize a new backup range created by p_owner
        m_secondaryLogCreationLock.writeLock().lock();
        cat = m_logCatalogs[p_owner & 0xFFFF];
        if (cat == null) {
            cat = new LogCatalog();
            m_logCatalogs[p_owner & 0xFFFF] = cat;
        }
        try {
            if (!cat.exists(p_rangeID)) {
                // Create new secondary log
                secLog = new SecondaryLog(this, m_secondaryLogsReorgThread, p_owner, p_rangeID, m_backupDirectory, m_secondaryLogSize,
                        (int) getConfig().getFlashPageSize().getBytes(), (int) getConfig().getLogSegmentSize().getBytes(),
                        getConfig().getReorgUtilizationThreshold(), getConfig().useChecksum(), m_mode);
                // Insert range in log catalog
                cat.insertRange(p_rangeID, secLog, (int) getConfig().getSecondaryLogBufferSize().getBytes(), (int) getConfig().getLogSegmentSize().getBytes());
            }
        } catch (final IOException e) {
            // #if LOGGER >= ERROR
            LOGGER.error("Initialization of backup range %d failed: %s", p_rangeID, e);
            // #endif /* LOGGER >= ERROR */
            ret = false;
        }
        m_secondaryLogCreationLock.writeLock().unlock();

        return ret;
    }

    /**
     * Initializes a backup range after recovery (creating a new one or transferring the old)
     *
     * @param p_rangeID
     *         the RangeID
     * @param p_owner
     *         the Chunks' owner
     * @return whether the backup range is initialized or not
     */
    boolean incomingInitRecoveredBackupRange(final short p_rangeID, final short p_owner, final short p_originalRangeID, final short p_originalOwner,
            final boolean p_isNewBackupRange) {
        boolean ret = true;
        LogCatalog cat;
        SecondaryLog secLog;

        if (p_isNewBackupRange) {
            // This is a new backup peer determined during recovery (replicas will be sent shortly by p_owner)
            flushDataToPrimaryLog();
            m_secondaryLogCreationLock.writeLock().lock();
            cat = m_logCatalogs[p_owner & 0xFFFF];
            if (cat == null) {
                cat = new LogCatalog();
                m_logCatalogs[p_owner & 0xFFFF] = cat;
            }

            try {
                if (!cat.exists(p_rangeID)) {
                    // Create new secondary log
                    secLog = new SecondaryLog(this, m_secondaryLogsReorgThread, p_owner, p_originalOwner, p_rangeID, m_backupDirectory, m_secondaryLogSize,
                            (int) getConfig().getFlashPageSize().getBytes(), (int) getConfig().getLogSegmentSize().getBytes(),
                            getConfig().getReorgUtilizationThreshold(), getConfig().useChecksum(), m_mode);
                    // Insert range in log catalog
                    cat.insertRange(p_rangeID, secLog, (int) getConfig().getSecondaryLogBufferSize().getBytes(),
                            (int) getConfig().getLogSegmentSize().getBytes());
                } else {
                    // #if LOGGER >= WARN
                    LOGGER.warn("Transfer of backup range %d from 0x%X to 0x%X failed! Secondary log already exists!", p_originalRangeID, p_originalOwner,
                            p_owner);
                    // #endif /* LOGGER >= WARN */
                }
            } catch (final IOException e) {
                // #if LOGGER >= ERROR
                LOGGER.error("Transfer of backup range %d from 0x%X to 0x%X failed! %s", p_originalRangeID, p_originalOwner, p_owner, e);
                // #endif /* LOGGER >= ERROR */
                ret = false;
            }
            m_secondaryLogCreationLock.writeLock().unlock();
        } else {
            // Transfer recovered backup range from p_originalOwner to p_owner
            flushDataToPrimaryLog();
            m_secondaryLogCreationLock.writeLock().lock();
            cat = m_logCatalogs[p_originalOwner & 0xFFFF];
            secLog = cat.getLog(p_originalRangeID);
            if (secLog != null) {
                // This is an old backup peer (it has the entire data already)
                try {
                    cat.getBuffer(p_originalRangeID).flushSecLogBuffer();
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
                cat.removeBufferAndLog(p_originalRangeID);
                secLog.transferBackupRange(p_owner, p_rangeID);
                cat = m_logCatalogs[p_owner & 0xFFFF];
                if (cat == null) {
                    cat = new LogCatalog();
                    m_logCatalogs[p_owner & 0xFFFF] = cat;
                }

                if (!cat.exists(p_rangeID)) {
                    // Insert range in log catalog
                    cat.insertRange(p_rangeID, secLog, (int) getConfig().getSecondaryLogBufferSize().getBytes(),
                            (int) getConfig().getLogSegmentSize().getBytes());
                } else {
                    // #if LOGGER >= WARN
                    LOGGER.warn("Transfer of backup range %d from 0x%X to 0x%X failed! Secondary log already exists!", p_originalRangeID, p_originalOwner,
                            p_owner);
                    // #endif /* LOGGER >= WARN */
                }
            }
            m_secondaryLogCreationLock.writeLock().unlock();
        }

        return ret;
    }

    /**
     * Logs a buffer with Chunks on SSD
     *
     * @param p_buffer
     *         the Chunk buffer
     * @param p_owner
     *         the Chunks' owner
     */
    void incomingLogChunks(final ByteBuffer p_buffer, final short p_owner) {
        long chunkID;
        int length;
        byte[] logEntryHeader;
        final short rangeID = p_buffer.getShort();
        final int size = p_buffer.getInt();

        SecondaryLog secLog = getSecondaryLog(p_owner, rangeID);
        if (secLog == null) {
            // #if LOGGER >= ERROR
            LOGGER.error("Logging of chunks failed. SecondaryLog for range %s,%d is missing!", p_owner, rangeID);
            // #endif /* LOGGER >= ERROR */
            return;
        }

        short originalOwner = secLog.getOriginalOwner();
        for (int i = 0; i < size; i++) {
            chunkID = p_buffer.getLong();
            length = p_buffer.getInt();

            assert length > 0;

            try {
                logEntryHeader = PRIM_LOG_ENTRY_HEADER.createLogEntryHeader(chunkID, length, secLog.getNextVersion(chunkID), rangeID, p_owner, originalOwner);
                m_writeBuffer.putLogData(logEntryHeader, p_buffer, length);
            } catch (final InterruptedException e) {
                // #if LOGGER >= ERROR
                LOGGER.error("Logging of chunk 0x%X failed: %s", chunkID, e);
                // #endif /* LOGGER >= ERROR */
            }
        }

    }

    /**
     * Removes Chunks from log
     *
     * @param p_buffer
     *         the ChunkID buffer
     * @param p_owner
     *         the Chunks' owner
     */
    void incomingRemoveChunks(final ByteBuffer p_buffer, final short p_owner) {
        long chunkID;
        SecondaryLog secLog;
        final byte rangeID = p_buffer.get();
        final int size = p_buffer.getInt();

        for (int i = 0; i < size; i++) {
            chunkID = p_buffer.getLong();

            secLog = getSecondaryLog(p_owner, rangeID);
            if (secLog != null) {
                secLog.invalidateChunk(chunkID);
            } else {
                // #if LOGGER >= ERROR
                LOGGER.error("Removing of chunk 0x%X failed: %s. SecondaryLog is missing!", chunkID);
                // #endif /* LOGGER >= ERROR */
            }
        }
    }

    /**
     * Returns the secondary log
     *
     * @param p_owner
     *         the owner NodeID
     * @param p_rangeID
     *         the RangeID
     * @return the secondary log
     */
    private SecondaryLog getSecondaryLog(final short p_owner, final short p_rangeID) {
        SecondaryLog ret;
        LogCatalog cat;

        // Can be executed by application/network thread or writer thread
        m_secondaryLogCreationLock.readLock().lock();
        cat = m_logCatalogs[p_owner & 0xFFFF];

        if (cat == null) {
            // #if LOGGER >= ERROR
            LOGGER.error("Log catalog for peer 0x%X is empty!", p_owner);
            // #endif /* LOGGER >= ERROR */
            return null;
        }

        ret = cat.getLog(p_rangeID);
        m_secondaryLogCreationLock.readLock().unlock();

        return ret;
    }

    /**
     * Flushes the primary log write buffer
     */
    private void flushDataToPrimaryLog() {
        m_writeBuffer.signalWriterThreadAndFlushToPrimLog();
    }

}

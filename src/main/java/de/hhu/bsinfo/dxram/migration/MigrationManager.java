/*
 * Copyright (C) 2018 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science,
 * Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxram.migration;


import de.hhu.bsinfo.dxnet.MessageReceiver;
import de.hhu.bsinfo.dxnet.core.Message;
import de.hhu.bsinfo.dxnet.core.NetworkException;
import de.hhu.bsinfo.dxram.DXRAMMessageTypes;
import de.hhu.bsinfo.dxram.backup.BackupComponent;
import de.hhu.bsinfo.dxram.boot.AbstractBootComponent;
import de.hhu.bsinfo.dxram.chunk.ChunkMigrationComponent;
import de.hhu.bsinfo.dxram.data.DSByteArray;
import de.hhu.bsinfo.dxram.data.DataStructure;
import de.hhu.bsinfo.dxram.engine.DXRAMComponentAccessor;
import de.hhu.bsinfo.dxram.log.messages.RemoveMessage;
import de.hhu.bsinfo.dxram.lookup.LookupComponent;
import de.hhu.bsinfo.dxram.mem.MemoryManagerComponent;
import de.hhu.bsinfo.dxram.migration.messages.*;
import de.hhu.bsinfo.dxram.net.NetworkComponent;
import de.hhu.bsinfo.dxram.util.ArrayListLong;
import de.hhu.bsinfo.dxutils.NodeID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;

public class MigrationManager implements MessageReceiver, ChunkMigrator {

    private static final Logger log = LoggerFactory.getLogger(MigrationManager.class);

    private final ExecutorService m_executor;

    private final int m_workerCount;

    private final AbstractBootComponent m_boot;

    private final BackupComponent m_backup;

    private final ChunkMigrationComponent m_chunk;

    private final LookupComponent m_lookup;

    private final MemoryManagerComponent m_memoryManager;

    private final NetworkComponent m_network;

    public MigrationManager(int p_workerCount, final DXRAMComponentAccessor p_componentAccessor) {

        m_workerCount = p_workerCount;

        m_executor = Executors.newFixedThreadPool(p_workerCount, new MigrationThreadFactory());

        m_boot = p_componentAccessor.getComponent(AbstractBootComponent.class);

        m_backup = p_componentAccessor.getComponent(BackupComponent.class);

        m_chunk = p_componentAccessor.getComponent(ChunkMigrationComponent.class);

        m_lookup = p_componentAccessor.getComponent(LookupComponent.class);

        m_memoryManager = p_componentAccessor.getComponent(MemoryManagerComponent.class);

        m_network = p_componentAccessor.getComponent(NetworkComponent.class);
    }

    public void migrate(final short p_target, final long p_startId, final long p_endId) {

        MigrationTask[] tasks = createMigrationTasks(p_target, p_startId, p_endId);

        for (int i = 0; i < tasks.length; i++) {

            m_executor.execute(tasks[i]);
        }
    }

    public MigrationTask[] createMigrationTasks(final short p_target, final long p_startId, final long p_endId) {

        MigrationTask[] tasks = new MigrationTask[m_workerCount];

        long[] result = LongStream.rangeClosed(p_startId, p_endId).toArray();

        long[][] partitions = partitionChunks(result, m_workerCount);

        for (int i = 0; i < partitions.length; i++) {

            tasks[i] = new MigrationTask(this, p_target, partitions[i]);
        }

        return tasks;
    }

    // TODO(krakowski)
    //  Move this method to dxutils
    public static long[][] partitionChunks(long[] chunkIds, int partitions) {

        int partitionSize = (int) Math.ceil( (double) chunkIds.length / partitions );

        long[][] result = new long[partitions][];

        for (int i = 0; i < partitions; i++) {

            int index = i * partitionSize;

            int length = Math.min(chunkIds.length - index, partitionSize);

            result[i] = new long[length];

            System.arraycopy(chunkIds, index, result[i], 0, length);
        }

        return result;
    }

    @Override
    public void onIncomingMessage(final Message p_message) {

        if (p_message == null) {

            log.warn("Received null message");

            return;
        }

        if (p_message.getType() != DXRAMMessageTypes.MIGRATION_MESSAGES_TYPE) {

            log.warn("Received wrong message type {}", p_message.getType());
        }

        switch (p_message.getSubtype()) {

            case MigrationMessages.SUBTYPE_MIGRATION_PUSH:
                handle((MigrationPush) p_message);
                break;

            case MigrationMessages.SUBTYPE_MIGRATION_FINISH:
                handle((MigrationFinish) p_message);
                break;
        }
    }

    @Override
    public void onStatus(long[] p_chunkIds, Status p_result) {

        log.trace("Received Result {} for {} chunks", p_result, p_chunkIds.length);
    }

    @Override
    public Status migrate(final long[] p_chunkIds, final short p_target) {

        if (m_boot.getNodeID() == p_target) {

            log.error("The migration target has to be another node");

            return Status.INVALID_ARG;
        }

        byte[][] data = new byte[p_chunkIds.length][];

        for (int i = 0; i < p_chunkIds.length; i++) {

            m_memoryManager.lockAccess();

            // TODO(krakowski)
            //  Check if the requested chunk does exist

            // TODO(krakowski)
            //  Get a pointer pointing directly to the chunk's data
            data[i] = m_memoryManager.get(p_chunkIds[i]);

            m_memoryManager.unlockAccess();
        }

        ChunkCollection chunkCollection = new ChunkCollection(p_chunkIds, data);

        MigrationPush migrationPush = new MigrationPush(p_target, chunkCollection);

        try {

            m_network.sendMessage(migrationPush);

        } catch (NetworkException e) {

            log.error("Couldn't send migration push to target", e);

            return Status.NOT_SENT;
        }

        return Status.SENT;
    }

    private void handle(final MigrationPush p_migrationPush) {

        final MigrationFinish migrationFinish;

        final ChunkCollection chunkCollection = p_migrationPush.getChunkCollection();

        final long[] chunkIds = chunkCollection.getChunkIds();

        if (m_chunk.putMigratedChunks(chunkIds, chunkCollection.getData())) {

            // TODO(krakowski)
            //  Use asynchronous messages within lookup migration

//            short ownNodeId = m_boot.getNodeID();
//
//            for (int i = 0; i < chunkIds.length; i++) {
//
//                m_lookup.migrate(chunkIds[i], ownNodeId);
//            }

            migrationFinish = new MigrationFinish(p_migrationPush.getSource(), chunkCollection.getChunkIds(), true);

        } else {

            migrationFinish = new MigrationFinish(p_migrationPush.getSource(), chunkCollection.getChunkIds(), true);
        }

        try {

            m_network.sendMessage(migrationFinish);

        } catch (NetworkException e) {

            log.error("Couldn't send migration finish message", e);
        }
    }

    private void handle(final MigrationFinish p_migrationFinish) {

        final long[] chunkIds = p_migrationFinish.getChunkIds();

        if (!p_migrationFinish.isFinished()) {

            log.warn("Migration was not successful on node {}", NodeID.toHexString(p_migrationFinish.getSource()));
        }

        for (int i = 0; i < chunkIds.length; i++) {

            long chunkId = chunkIds[i];

            m_memoryManager.remove(chunkId, true);

            // TODO(krakowski)
            //  Send chunk sizes within MigrationFinish message
//            m_backup.deregisterChunk(chunkId, chunk.sizeofObject());
        }


        // TODO(krakowski)
        //  Handle backup
//        if (m_backup.isActive()) {
//
//            short[] backupPeers;
//
//            backupPeers = m_backup.getArrayOfBackupPeersForLocalChunks(p_chunkID);
//
//            if (backupPeers != null) {
//
//                for (int j = 0; j < backupPeers.length; j++) {
//
//                    if (backupPeers[j] != m_boot.getNodeID() && backupPeers[j] != NodeID.INVALID_ID) {
//
//                        try {
//
//                            m_network.sendMessage(new RemoveMessage(backupPeers[j], new ArrayListLong(p_chunkID)));
//
//                        } catch (final NetworkException ignored) {
//
//                        }
//                    }
//                }
//            }
//        }
    }

    public void registerMessages() {

        m_network.register(DXRAMMessageTypes.MIGRATION_MESSAGES_TYPE, MigrationMessages.SUBTYPE_MIGRATION_PUSH, this);

        m_network.register(DXRAMMessageTypes.MIGRATION_MESSAGES_TYPE, MigrationMessages.SUBTYPE_MIGRATION_FINISH, this);
    }

    private static class MigrationThreadFactory implements ThreadFactory {

        private static final AtomicInteger poolNumber = new AtomicInteger(1);

        private final ThreadGroup group;

        private final AtomicInteger threadNumber = new AtomicInteger(1);

        private final String namePrefix;

        MigrationThreadFactory() {

            SecurityManager s = System.getSecurityManager();

            group = (s != null) ? s.getThreadGroup() : Thread.currentThread().getThreadGroup();

            namePrefix = "migration-" + poolNumber.getAndIncrement() + "-thread-";
        }

        public Thread newThread(Runnable r) {

            Thread t = new Thread(group, r, namePrefix + threadNumber.getAndIncrement(), 0);

            if (t.isDaemon()) {

                t.setDaemon(false);
            }

            if (t.getPriority() != Thread.NORM_PRIORITY) {

                t.setPriority(Thread.NORM_PRIORITY);
            }

            return t;
        }
    }
}

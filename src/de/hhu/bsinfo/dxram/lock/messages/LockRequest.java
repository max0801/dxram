package de.hhu.bsinfo.dxram.lock.messages;

import java.nio.ByteBuffer;

import de.hhu.bsinfo.dxram.data.ChunkID;
import de.hhu.bsinfo.dxram.data.ChunkMessagesMetadataUtils;
import de.hhu.bsinfo.menet.AbstractRequest;

/**
 * Request for locking Chunks on a remote node
 * @author Florian Klein 09.03.2012
 * @author Stefan Nothaas <stefan.nothaas@hhu.de> 5.1.16
 */
public class LockRequest extends AbstractRequest {

	// Attributes
	private long m_chunkID = ChunkID.INVALID_ID;
	
	// Constructors
	/**
	 * Creates an instance of LockRequest as a receiver.
	 */
	public LockRequest() {
		super();
	}

	/**
	 * Creates an instance of LockRequest as a sender
	 * @param p_destination
	 *            the destination node ID.
	 * @param p_writeLock
	 *            True for write lock, false for read lock.
	 * @param p_dataStructures
	 *           Data structures to be locked.
	 */
	public LockRequest(final short p_destination, final boolean p_writeLock, final long p_chunkID) {
		super(p_destination, LockMessages.TYPE, LockMessages.SUBTYPE_LOCK_REQUEST);

		m_chunkID = p_chunkID;
		
		if (p_writeLock) {
			setStatusCode(ChunkMessagesMetadataUtils.setWriteLockFlag(getStatusCode(), true));
		} else {
			setStatusCode(ChunkMessagesMetadataUtils.setReadLockFlag(getStatusCode(), true));
		}
	}

	/**
	 * Get the chunk ID of this request (when receiving it).
	 * @return Chunk ID.
	 */
	public long getChunkID() {
		return m_chunkID;
	}

	/**
	 * Get the lock operation to execute (when receiving).
	 * @return True for write lock, false read lock.
	 */
	public boolean isWriteLockOperation() {
		if (ChunkMessagesMetadataUtils.isLockAcquireFlagSet(getStatusCode())) {
			if (ChunkMessagesMetadataUtils.isReadLockFlagSet(getStatusCode())) {
				return false;
			} else {
				return true;
			}
		} else {
			assert 1 == 2;
			return true;
		}
	}

	// Methods
	@Override
	protected final void writePayload(final ByteBuffer p_buffer) {
		p_buffer.putLong(m_chunkID);
	}

	@Override
	protected final void readPayload(final ByteBuffer p_buffer) {
		m_chunkID = p_buffer.getLong();
	}

	@Override
	protected final int getPayloadLengthForWrite() {
		return Long.BYTES;
	}

}
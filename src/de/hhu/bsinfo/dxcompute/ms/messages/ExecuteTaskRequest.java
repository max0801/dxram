
package de.hhu.bsinfo.dxcompute.ms.messages;

import java.nio.ByteBuffer;

import de.hhu.bsinfo.dxcompute.ms.AbstractTaskPayload;
import de.hhu.bsinfo.dxram.data.MessagesDataStructureImExporter;
import de.hhu.bsinfo.menet.AbstractRequest;

public class ExecuteTaskRequest extends AbstractRequest {

	private int m_barrierIdentifier = -1;
	private AbstractTaskPayload m_task;

	/**
	 * Creates an instance of ExecuteTaskRequest.
	 * This constructor is used when receiving this message.
	 */
	public ExecuteTaskRequest() {
		super();
	}

	/**
	 * Creates an instance of ExecuteTaskRequest.
	 * This constructor is used when sending this message.
	 * @param p_destination
	 *            the destination node id.
	 * @param p_subtype
	 *            Message subtype
	 * @param p_syncToken
	 *            Token to correctly identify responses to a sync message
	 * @param p_data
	 *            Some custom data.
	 */
	public ExecuteTaskRequest(final short p_destination, final int p_barrierIdentifier,
			final AbstractTaskPayload p_task) {
		super(p_destination, MasterSlaveMessages.TYPE, MasterSlaveMessages.SUBTYPE_EXECUTE_TASK_REQUEST);
		m_barrierIdentifier = p_barrierIdentifier;
		m_task = p_task;
	}

	public int getBarrierIdentifier() {
		return m_barrierIdentifier;
	}

	public AbstractTaskPayload getTask() {
		return m_task;
	}

	@Override
	protected final void writePayload(final ByteBuffer p_buffer) {
		MessagesDataStructureImExporter exporter = new MessagesDataStructureImExporter(p_buffer);

		p_buffer.putInt(m_barrierIdentifier);
		p_buffer.putShort(m_task.getTypeId());
		p_buffer.putShort(m_task.getSubtypeId());
		exporter.exportObject(m_task);
	}

	@Override
	protected final void readPayload(final ByteBuffer p_buffer) {
		MessagesDataStructureImExporter importer = new MessagesDataStructureImExporter(p_buffer);

		m_barrierIdentifier = p_buffer.getInt();
		short type = p_buffer.getShort();
		short subtype = p_buffer.getShort();
		m_task = AbstractTaskPayload.createInstance(type, subtype);
		if (m_task != null) {
			importer.importObject(m_task);
		}
	}

	@Override
	protected final int getPayloadLength() {
		if (m_task != null) {
			return 2 * Short.BYTES + Integer.BYTES + m_task.sizeofObject();
		} else {
			return 2 * Short.BYTES + Integer.BYTES;
		}
	}
}
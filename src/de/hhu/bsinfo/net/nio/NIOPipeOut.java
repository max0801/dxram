package de.hhu.bsinfo.net.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import de.hhu.bsinfo.net.NodeMap;
import de.hhu.bsinfo.net.core.AbstractFlowControl;
import de.hhu.bsinfo.net.core.AbstractPipeOut;
import de.hhu.bsinfo.net.core.NetworkException;

/**
 * Created by nothaas on 6/9/17.
 */
public class NIOPipeOut extends AbstractPipeOut {
    private static final Logger LOGGER = LogManager.getFormatterLogger(NIOPipeOut.class.getSimpleName());

    private SocketChannel m_outgoingChannel;
    private final ChangeOperationsRequest m_writeOperation;

    private final NIOSelector m_nioSelector;
    private final NodeMap m_nodeMap;
    private final ByteBuffer m_flowControlBytes;

    NIOPipeOut(final short p_ownNodeId, final short p_destinationNodeId, final int p_bufferSize, final AbstractFlowControl p_flowControl,
            final NIOSelector p_nioSelector, final NodeMap p_nodeMap, final NIOConnection p_parentConnection) {
        super(p_ownNodeId, p_destinationNodeId, p_bufferSize, p_flowControl, false);

        m_outgoingChannel = null;
        m_writeOperation = new ChangeOperationsRequest(p_parentConnection, NIOSelector.WRITE);

        m_nioSelector = p_nioSelector;
        m_nodeMap = p_nodeMap;
        m_flowControlBytes = ByteBuffer.allocateDirect(Integer.BYTES);
    }

    SocketChannel getChannel() {
        return m_outgoingChannel;
    }

    void createOutgoingChannel(final short p_nodeID) throws NetworkException {
        try {
            m_outgoingChannel = SocketChannel.open();
            m_outgoingChannel.configureBlocking(false);
            m_outgoingChannel.socket().setSoTimeout(0);
            m_outgoingChannel.socket().setTcpNoDelay(true);
            m_outgoingChannel.socket().setReceiveBufferSize(32);
            m_outgoingChannel.socket().setSendBufferSize(getBufferSize());
            int sendBufferSize = m_outgoingChannel.socket().getSendBufferSize();
            if (sendBufferSize < getBufferSize()) {
                // #if LOGGER >= WARN
                LOGGER.warn("Send buffer size could not be set properly. Check OS settings! Requested: %d, actual: %d", getBufferSize(), sendBufferSize);
                // #endif /* LOGGER >= WARN */
            }

            m_outgoingChannel.connect(m_nodeMap.getAddress(p_nodeID));
        } catch (final IOException ignored) {
            throw new NetworkException("Creating outgoing channel failed");
        }
    }

    /**
     * Writes to the given connection
     *
     * @return whether all data could be written
     * @throws IOException
     *         if the data could not be written
     */
    public boolean write() throws IOException {
        boolean ret = true;
        int bytes;
        ByteBuffer buffer;

        buffer = getOutgoingQueue().popFront();
        if (buffer != null) {
            while (buffer.remaining() > 0) {

                bytes = m_outgoingChannel.write(buffer);
                if (bytes == 0) {
                    // Read-buffer on the other side is full. Abort writing and schedule buffer for next write
                    getOutgoingQueue().pushFront(buffer);
                    ret = false;
                    break;
                }
            }
            // ThroughputStatistic.getInstance().outgoingExtern(writtenBytes - length);
            if (ret) {
                if (buffer.capacity() == getBufferSize()) {
                    getOutgoingQueue().returnBuffer(buffer);
                }
            }
        }

        return ret;
    }

    /**
     * Read flow control data
     */
    void readFlowControlBytes() throws IOException {
        int readBytes;
        int readAllBytes;

        // This is a flow control byte
        m_flowControlBytes.rewind();
        readAllBytes = 0;
        while (readAllBytes < Integer.BYTES) {
            readBytes = m_outgoingChannel.read(m_flowControlBytes);

            if (readBytes == -1) {
                // Channel was closed
                return;
            }

            readAllBytes += readBytes;
        }

        getFlowControl().handleFlowControlData(m_flowControlBytes.getInt(0));
    }

    @Override
    protected boolean isOpen() {
        return m_outgoingChannel != null && m_outgoingChannel.isOpen();
    }

    @Override
    protected void bufferPosted() {
        // Change operation (read <-> write) and/or connection
        m_nioSelector.changeOperationInterestAsync(m_writeOperation);
    }
}
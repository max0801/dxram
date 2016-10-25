
package de.hhu.bsinfo.dxram.lookup.overlay;

import java.util.ArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import de.hhu.bsinfo.dxram.logger.LoggerComponent;
import de.hhu.bsinfo.dxram.lookup.messages.*;
import de.hhu.bsinfo.dxram.net.NetworkComponent;
import de.hhu.bsinfo.dxram.net.NetworkErrorCodes;
import de.hhu.bsinfo.dxram.net.messages.DXRAMMessageTypes;
import de.hhu.bsinfo.ethnet.AbstractMessage;
import de.hhu.bsinfo.ethnet.NetworkHandler.MessageReceiver;
import de.hhu.bsinfo.ethnet.NodeID;

/**
 * Stabilizes superpeer overlay
 * @author Kevin Beineke
 *         03.06.2013
 */
class SuperpeerStabilizationThread extends Thread implements MessageReceiver {

	// Attributes
	private LoggerComponent m_logger;
	private NetworkComponent m_network;

	private OverlaySuperpeer m_superpeer;

	private int m_initialNumberOfSuperpeers;
	private ArrayList<Short> m_otherSuperpeers;
	private ReentrantReadWriteLock m_overlayLock;

	private short m_nodeID;
	private int m_sleepInterval;
	private int m_next;
	private boolean m_shutdown;

	private String m_overlayFigure;

	// Constructors
	/**
	 * Creates an instance of Worker
	 * @param p_superpeer
	 *            the overlay superpeer
	 * @param p_nodeID
	 *            the own NodeID
	 * @param p_overlayLock
	 *            the overlay lock
	 * @param p_initialNumberOfSuperpeers
	 *            the number of expected superpeers
	 * @param p_superpeers
	 *            all other superpeers
	 * @param p_sleepInterval
	 *            the ping interval
	 * @param p_logger
	 *            the logger component
	 * @param p_network
	 *            the network component
	 */
	protected SuperpeerStabilizationThread(final OverlaySuperpeer p_superpeer, final short p_nodeID,
			final ReentrantReadWriteLock p_overlayLock,
			final int p_initialNumberOfSuperpeers, final ArrayList<Short> p_superpeers, final int p_sleepInterval,
			final LoggerComponent p_logger,
			final NetworkComponent p_network) {
		m_superpeer = p_superpeer;

		m_logger = p_logger;
		m_network = p_network;

		m_otherSuperpeers = p_superpeers;
		m_overlayLock = p_overlayLock;

		m_nodeID = p_nodeID;
		m_sleepInterval = p_sleepInterval;
		m_next = 0;

		registerNetworkMessageListener();
	}

	/**
	 * Shutdown
	 */
	protected void shutdown() {
		m_shutdown = true;
	}

	/**
	 * When an object implementing interface <code>Runnable</code> is used
	 * to create a thread, starting the thread causes the object's <code>run</code> method to be called in that
	 * separately executing
	 * thread.
	 * <p>
	 * The general contract of the method <code>run</code> is that it may take any action whatsoever.
	 * @see java.lang.Thread#run()
	 */
	@Override
	public void run() {
		while (!m_shutdown) {
			try {
				Thread.sleep(m_sleepInterval * 1000);
			} catch (final InterruptedException e) {
				m_shutdown = true;
				break;
			}

			performStabilization();

			for (int i = 0; i < m_initialNumberOfSuperpeers / 300 || i < 1; i++) {
				fixSuperpeers();
			}

			if (!m_otherSuperpeers.isEmpty()) {
				backupMaintenance();

				m_overlayLock.writeLock().lock();
				m_superpeer.takeOverPeers(m_nodeID);
				m_overlayLock.writeLock().unlock();
			}

			pingPeers();

			printOverlay();
		}
	}

	/**
	 * Performs stabilization protocol
	 * @note without disappearing superpeers this method does not do anything important; All the setup is done with
	 *       joining
	 */
	private void performStabilization() {

		m_overlayLock.readLock().lock();
		while (-1 != m_superpeer.getPredecessor() && m_nodeID != m_superpeer.getPredecessor()) {
			// #if LOGGER == TRACE
			m_logger.trace(getClass(), "Performing stabilization by sending NodeID to predecessor="
					+ NodeID.toHexString(m_superpeer.getPredecessor()));
			// #endif /* LOGGER == TRACE */
			if (m_network.sendMessage(new NotifyAboutNewSuccessorMessage(m_superpeer.getPredecessor(),
					m_nodeID)) != NetworkErrorCodes.SUCCESS) {
				// Predecessor is not available anymore, repeat it. New predecessor will be determined by failure
				// handling, but lock must be released first.
				m_overlayLock.readLock().unlock();
				Thread.yield();
				m_overlayLock.readLock().lock();
				continue;
			}
			break;
		}

		while (-1 != m_superpeer.getSuccessor() && m_nodeID != m_superpeer.getSuccessor()) {
			// #if LOGGER == TRACE
			m_logger.trace(getClass(), "Performing stabilization by sending NodeID to successor="
					+ NodeID.toHexString(m_superpeer.getSuccessor()));
			// #endif /* LOGGER == TRACE */
			if (m_network.sendMessage(new NotifyAboutNewPredecessorMessage(m_superpeer.getSuccessor(),
					m_nodeID)) != NetworkErrorCodes.SUCCESS) {
				// Predecessor is not available anymore, repeat it. New predecessor will be determined by failure
				// handling, but lock must be released first.
				m_overlayLock.readLock().unlock();
				Thread.yield();
				m_overlayLock.readLock().lock();
				continue;
			}
			break;
		}
		m_overlayLock.readLock().unlock();
	}

	/**
	 * Fixes the superpeer array
	 */
	private void fixSuperpeers() {
		short contactSuperpeer = -1;
		short possibleSuccessor = -1;
		short hisSuccessor;

		AskAboutSuccessorRequest request;
		AskAboutSuccessorResponse response;

		m_overlayLock.readLock().lock();
		if (1 < m_otherSuperpeers.size()) {
			if (m_next + 1 < m_otherSuperpeers.size()) {
				contactSuperpeer = m_otherSuperpeers.get(m_next);
				possibleSuccessor = m_otherSuperpeers.get(m_next + 1);
			} else if (m_next + 1 == m_otherSuperpeers.size()) {
				contactSuperpeer = m_otherSuperpeers.get(m_next);
				possibleSuccessor = m_otherSuperpeers.get(0);
			} else {
				m_next = 0;
				m_overlayLock.readLock().unlock();
				fixSuperpeers();
				return;
			}

			if (contactSuperpeer == m_superpeer.getPredecessor()) {
				m_next++;
				m_overlayLock.readLock().unlock();
				fixSuperpeers();
				return;
			}
			m_overlayLock.readLock().unlock();

			m_next++;
			// #if LOGGER == TRACE
			m_logger.trace(getClass(),
					"Asking " + NodeID.toHexString(contactSuperpeer) + " about his successor to fix overlay");
			// #endif /* LOGGER == TRACE */
			request = new AskAboutSuccessorRequest(contactSuperpeer);
			if (m_network.sendSync(request) != NetworkErrorCodes.SUCCESS) {
				// Superpeer is not available anymore, remove from superpeer array and try next superpeer
				m_next--;
				fixSuperpeers();
				return;
			}

			response = request.getResponse(AskAboutSuccessorResponse.class);

			hisSuccessor = response.getSuccessor();

			if (hisSuccessor != possibleSuccessor && -1 != hisSuccessor) {
				m_overlayLock.writeLock().lock();
				OverlayHelper.insertSuperpeer(hisSuccessor, m_otherSuperpeers);
				m_overlayLock.writeLock().unlock();
			}
		} else {
			m_overlayLock.readLock().unlock();
		}
	}

	/**
	 * Pings all peers and sends current superpeer overlay
	 */
	private void pingPeers() {
		short peer;
		int i = 0;

		m_overlayLock.readLock().lock();
		final ArrayList<Short> peers = m_superpeer.getPeers();
		m_overlayLock.readLock().unlock();

		if (peers != null && peers.size() > 0) {
			while (true) {
				if (i < peers.size()) {
					peer = peers.get(i++);
				} else {
					break;
				}
				// #if LOGGER == TRACE
				m_logger.trace(getClass(), "Pinging " + NodeID.toHexString(peer) + " for heartbeat protocol");
				// #endif /* LOGGER == TRACE */
				if (m_network
						.sendMessage(new SendSuperpeersMessage(peer, m_otherSuperpeers)) != NetworkErrorCodes.SUCCESS) {
					// Peer is not available anymore, will be removed from peer array by failure handling
				}
			}
		}
	}

	/**
	 * Maintain backup replication
	 */
	private void backupMaintenance() {
		short[] responsibleArea;

		m_overlayLock.readLock().lock();
		responsibleArea = OverlayHelper.getResponsibleArea(m_nodeID, m_superpeer.getPredecessor(), m_otherSuperpeers);
		m_overlayLock.readLock().unlock();

		// #if LOGGER == TRACE
		m_logger.trace(getClass(), "Responsible backup area: " + NodeID.toHexString(responsibleArea[0])
				+ ", " + NodeID.toHexString(responsibleArea[1]));
		// #endif /* LOGGER == TRACE */

		gatherBackups(responsibleArea);

		m_overlayLock.writeLock().lock();
		m_superpeer.deleteUnnecessaryBackups(responsibleArea);
		m_overlayLock.writeLock().unlock();
	}

	/**
	 * Gather all missing metadata in the responsible area
	 * @param p_responsibleArea
	 *            the responsible area
	 */
	private void gatherBackups(final short[] p_responsibleArea) {
		short currentSuperpeer;
		short oldSuperpeer;
		ArrayList<Short> peers;
		int numberOfNameserviceEntries;
		int numberOfStorages;
		int numberOfBarriers;
		short[] currentResponsibleArea;

		AskAboutBackupsRequest request;
		AskAboutBackupsResponse response;

		m_overlayLock.readLock().lock();
		if (!m_otherSuperpeers.isEmpty()) {
			if (3 >= m_otherSuperpeers.size()) {
				oldSuperpeer = m_nodeID;
				currentSuperpeer = m_superpeer.getSuccessor();
			} else {
				oldSuperpeer = p_responsibleArea[0];
				currentSuperpeer = OverlayHelper.getResponsibleSuperpeer((short) (p_responsibleArea[0] + 1),
						m_otherSuperpeers, m_logger);
			}
			while (-1 != currentSuperpeer) {
				peers = m_superpeer.getPeersInResponsibleArea(oldSuperpeer, currentSuperpeer);

				// #if LOGGER == TRACE
				m_logger.trace(getClass(), "Gathering backups by requesting all backups in responsible area from "
						+ NodeID.toHexString(currentSuperpeer));
				// #endif /* LOGGER == TRACE */

				currentResponsibleArea = new short[] {oldSuperpeer, currentSuperpeer};
				numberOfNameserviceEntries = m_superpeer.getNumberOfNameserviceEntries(currentResponsibleArea);
				numberOfStorages = m_superpeer.getNumberOfStorages(currentResponsibleArea);
				numberOfBarriers = m_superpeer.getNumberOfBarriers(currentResponsibleArea);
				request = new AskAboutBackupsRequest(currentSuperpeer, peers, numberOfNameserviceEntries,
						numberOfStorages, numberOfBarriers);
				m_overlayLock.readLock().unlock();

				if (m_network.sendSync(request) != NetworkErrorCodes.SUCCESS) {
					// CurrentSuperpeer is not available anymore, will be removed from superpeer array by failure
					// handling
					m_overlayLock.readLock().lock();
					currentSuperpeer = OverlayHelper.getResponsibleSuperpeer((short) (oldSuperpeer + 1),
							m_otherSuperpeers, m_logger);
					peers.clear();
					continue;
				}

				response = request.getResponse(AskAboutBackupsResponse.class);

				m_overlayLock.writeLock().lock();
				m_superpeer.storeIncomingBackups(response.getMissingMetadata());
				// Lock downgrade
				m_overlayLock.readLock().lock();
				m_overlayLock.writeLock().unlock();

				if (currentSuperpeer == m_superpeer.getPredecessor()
						|| !OverlayHelper.isSuperpeerInRange(currentSuperpeer, p_responsibleArea[0], m_nodeID)) {
					// Second case is for predecessor failure
					break;
				}

				peers.clear();

				oldSuperpeer = currentSuperpeer;
				currentSuperpeer = OverlayHelper.getResponsibleSuperpeer((short) (currentSuperpeer + 1),
						m_otherSuperpeers, m_logger);
			}
		}
		m_overlayLock.readLock().unlock();
	}

	/**
	 * Prints the overlay if something has changed since last call
	 */
	private void printOverlay() {
		boolean printed = false;
		short superpeer;
		short peer;
		String superpeersFigure = "Superpeers: ";
		String peersFigure = "Peers: ";

		m_overlayLock.readLock().lock();
		for (int i = 0; i < m_otherSuperpeers.size(); i++) {
			superpeer = m_otherSuperpeers.get(i);
			if (!printed && superpeer > m_nodeID) {
				superpeersFigure += " \'" + NodeID.toHexString(m_nodeID) + "\'";
				printed = true;
			}
			superpeersFigure += " " + NodeID.toHexString(superpeer);
		}
		if (!printed) {
			superpeersFigure += " \'" + NodeID.toHexString(m_nodeID) + "\'";
		}

		final ArrayList<Short> peers = m_superpeer.getPeers();
		if (peers != null && peers.size() > 0) {
			for (int i = 0; i < peers.size(); i++) {
				peer = peers.get(i);
				peersFigure += " " + NodeID.toHexString(peer);
			}
		}
		m_overlayLock.readLock().unlock();

		if (!(superpeersFigure + peersFigure).equals(m_overlayFigure)) {
			// #if LOGGER >= INFO
			m_logger.info(getClass(), superpeersFigure);
			if (!peersFigure.equals("Peers: ")) {
				m_logger.info(getClass(), peersFigure);
			}
			// #endif /* LOGGER >= INFO */
		}
		m_overlayFigure = superpeersFigure + peersFigure;
	}

	/**
	 * Handles an incoming SendBackupsMessage
	 * @param p_sendBackupsMessage
	 *            the SendBackupsMessage
	 */
	private void incomingSendBackupsMessage(final SendBackupsMessage p_sendBackupsMessage) {

		// #if LOGGER == TRACE
		m_logger.trace(getClass(),
				"Got Message: SEND_BACKUPS_MESSAGE from " + NodeID.toHexString(p_sendBackupsMessage.getSource()));
		// #endif /* LOGGER == TRACE */

		m_overlayLock.writeLock().lock();
		m_superpeer.storeIncomingBackups(p_sendBackupsMessage.getMetadata());
		m_overlayLock.writeLock().unlock();
	}

	/**
	 * Handles an incoming AskAboutBackupsRequest
	 * @param p_askAboutBackupsRequest
	 *            the AskAboutBackupsRequest
	 */
	private void incomingAskAboutBackupsRequest(final AskAboutBackupsRequest p_askAboutBackupsRequest) {
		byte[] missingMetadata;

		// #if LOGGER == TRACE
		m_logger.trace(getClass(), "Got request: ASK_ABOUT_SUCCESSOR_REQUEST from "
				+ NodeID.toHexString(p_askAboutBackupsRequest.getSource()));
		// #endif /* LOGGER == TRACE */

		m_overlayLock.readLock().lock();
		missingMetadata = m_superpeer.compareAndReturnBackups(p_askAboutBackupsRequest.getPeers(),
				p_askAboutBackupsRequest.getNumberOfNameserviceEntries(),
				p_askAboutBackupsRequest.getNumberOfStorages(), p_askAboutBackupsRequest.getNumberOfBarriers());
		m_overlayLock.readLock().unlock();

		if (m_network.sendMessage(
				new AskAboutBackupsResponse(p_askAboutBackupsRequest, missingMetadata)) != NetworkErrorCodes.SUCCESS) {
			// Requesting superpeer is not available anymore, ignore request. Superpeer will be removed by failure
			// handling.
		}
	}

	/**
	 * Handles an incoming AskAboutSuccessorRequest
	 * @param p_askAboutSuccessorRequest
	 *            the AskAboutSuccessorRequest
	 */
	private void incomingAskAboutSuccessorRequest(final AskAboutSuccessorRequest p_askAboutSuccessorRequest) {
		short successor;

		// #if LOGGER == TRACE
		m_logger.trace(getClass(), "Got request: ASK_ABOUT_SUCCESSOR_REQUEST from "
				+ NodeID.toHexString(p_askAboutSuccessorRequest.getSource()));
		// #endif /* LOGGER == TRACE */

		m_overlayLock.readLock().lock();
		successor = m_superpeer.getSuccessor();
		m_overlayLock.readLock().unlock();
		if (m_network.sendMessage(
				new AskAboutSuccessorResponse(p_askAboutSuccessorRequest, successor)) != NetworkErrorCodes.SUCCESS) {
			// Requesting superpeer is not available anymore, ignore request. Superpeer will be removed by failure
			// handling.
		}
	}

	/**
	 * Handles an incoming NotifyAboutNewPredecessorMessage
	 * @param p_notifyAboutNewPredecessorMessage
	 *            the NotifyAboutNewPredecessorMessage
	 */
	private void incomingNotifyAboutNewPredecessorMessage(
			final NotifyAboutNewPredecessorMessage p_notifyAboutNewPredecessorMessage) {
		short possiblePredecessor;

		// #if LOGGER == TRACE
		m_logger.trace(getClass(), "Got Message: NOTIFY_ABOUT_NEW_PREDECESSOR_MESSAGE from "
				+ NodeID.toHexString(p_notifyAboutNewPredecessorMessage.getSource()));
		// #endif /* LOGGER == TRACE */

		possiblePredecessor = p_notifyAboutNewPredecessorMessage.getNewPredecessor();
		m_overlayLock.writeLock().lock();
		if (m_superpeer.getPredecessor() != possiblePredecessor) {
			if (OverlayHelper.isSuperpeerInRange(possiblePredecessor, m_superpeer.getPredecessor(), m_nodeID)) {
				m_superpeer.setPredecessor(possiblePredecessor);
			}
		}
		m_overlayLock.writeLock().unlock();
	}

	/**
	 * Handles an incoming NotifyAboutNewSuccessorMessage
	 * @param p_notifyAboutNewSuccessorMessage
	 *            the NotifyAboutNewSuccessorMessage
	 */
	private void incomingNotifyAboutNewSuccessorMessage(
			final NotifyAboutNewSuccessorMessage p_notifyAboutNewSuccessorMessage) {
		short possibleSuccessor;

		// #if LOGGER == TRACE
		m_logger.trace(getClass(), "Got Message: NOTIFY_ABOUT_NEW_SUCCESSOR_MESSAGE from "
				+ NodeID.toHexString(p_notifyAboutNewSuccessorMessage.getSource()));
		// #endif /* LOGGER == TRACE */

		possibleSuccessor = p_notifyAboutNewSuccessorMessage.getNewSuccessor();
		m_overlayLock.writeLock().lock();
		if (m_superpeer.getSuccessor() != possibleSuccessor) {
			if (OverlayHelper.isSuperpeerInRange(possibleSuccessor, m_nodeID, m_superpeer.getSuccessor())) {
				m_superpeer.setSuccessor(possibleSuccessor);
			}
		}
		m_overlayLock.writeLock().unlock();
	}

	/**
	 * Handles an incoming Message
	 * @param p_message
	 *            the Message
	 */
	@Override
	public void onIncomingMessage(final AbstractMessage p_message) {
		if (p_message != null) {
			if (p_message.getType() == DXRAMMessageTypes.LOOKUP_MESSAGES_TYPE) {
				switch (p_message.getSubtype()) {
					case LookupMessages.SUBTYPE_SEND_BACKUPS_MESSAGE:
						incomingSendBackupsMessage((SendBackupsMessage) p_message);
						break;
					case LookupMessages.SUBTYPE_ASK_ABOUT_BACKUPS_REQUEST:
						incomingAskAboutBackupsRequest((AskAboutBackupsRequest) p_message);
						break;
					case LookupMessages.SUBTYPE_ASK_ABOUT_SUCCESSOR_REQUEST:
						incomingAskAboutSuccessorRequest((AskAboutSuccessorRequest) p_message);
						break;
					case LookupMessages.SUBTYPE_NOTIFY_ABOUT_NEW_PREDECESSOR_MESSAGE:
						incomingNotifyAboutNewPredecessorMessage((NotifyAboutNewPredecessorMessage) p_message);
						break;
					case LookupMessages.SUBTYPE_NOTIFY_ABOUT_NEW_SUCCESSOR_MESSAGE:
						incomingNotifyAboutNewSuccessorMessage((NotifyAboutNewSuccessorMessage) p_message);
						break;
					case LookupMessages.SUBTYPE_PING_SUPERPEER_MESSAGE:
						break;
					default:
						break;
				}
			}
		}
	}

	// -----------------------------------------------------------------------------------

	/**
	 * Register network messages we want to listen to in here.
	 */
	private void registerNetworkMessageListener() {
		m_network.register(AskAboutBackupsRequest.class, this);
		m_network.register(AskAboutSuccessorRequest.class, this);
		m_network.register(SendBackupsMessage.class, this);
		m_network.register(NotifyAboutNewPredecessorMessage.class, this);
		m_network.register(NotifyAboutNewSuccessorMessage.class, this);
	}
}

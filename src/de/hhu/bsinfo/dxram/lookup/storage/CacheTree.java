
package de.hhu.bsinfo.dxram.lookup.storage;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import de.hhu.bsinfo.dxram.lookup.Locations;
import de.hhu.bsinfo.utils.Tools;

/**
 * Btree to cache ranges
 * @author Kevin Beineke
 *         11.07.2014
 */
public final class CacheTree {

	// Attributes
	private short m_minEntries;
	private short m_minChildren;
	private short m_maxEntries;
	private short m_maxChildren;

	private Node m_root;
	private int m_size;

	private Entry m_changedEntry;

	private ReadWriteLock m_lock;
	private TTLHandler m_ttlHandler;

	// Constructors
	/**
	 * Creates an instance of CacheTree
	 * @param p_order
	 *            order of the btree
	 */
	public CacheTree(final long p_cacheMaxSize, final short p_order) {
		Thread thread;
		long ttl;

		ttl = Math.max(p_cacheMaxSize, 1000);

		// too small order for BTree
		assert p_order > 1;

		m_minEntries = p_order;
		m_minChildren = (short) (m_minEntries + 1);
		m_maxEntries = (short) (2 * m_minEntries);
		m_maxChildren = (short) (m_maxEntries + 1);

		m_root = null;
		m_size = -1;

		m_changedEntry = null;

		createOrReplaceEntry(Long.MAX_VALUE, (short) -1);

		m_lock = new ReentrantReadWriteLock();
		m_ttlHandler = new TTLHandler(ttl);

		thread = new Thread(m_ttlHandler);
		thread.setName(TTLHandler.class.getSimpleName() + " for " + CacheTree.class.getSimpleName());
		thread.setDaemon(true);
		// thread.start();
	}

	// Methods
	/**
	 * Stops the TTLHandler
	 */
	public void close() {
		m_ttlHandler.stop();
	}

	/**
	 * Returns the primary peer for given object
	 * @param p_chunkID
	 *            ChunkID of requested object
	 * @return the NodeID of the primary peer for given object
	 */
	public short getPrimaryPeer(final long p_chunkID) {
		short ret;

		assert m_root != null;

		m_lock.readLock().lock();
		ret = getNodeIDOrSuccessorsNodeID(p_chunkID);
		m_lock.readLock().unlock();

		return ret;
	}

	/**
	 * Returns the range given ChunkID is in
	 * @param p_chunkID
	 *            ChunkID of requested object
	 * @return the first and last ChunkID of the range
	 */
	public Locations getMetadata(final long p_chunkID) {
		Locations ret = null;
		long[] range;
		short nodeID;
		int index;
		Node node;
		Entry predecessorEntry;

		assert m_root != null;

		m_lock.readLock().lock();
		node = getNodeOrSuccessorsNode(p_chunkID, true);
		if (node != null) {
			index = node.indexOf(p_chunkID);
			if (0 <= index) {
				// ChunkID was found: Store NodeID and determine successor
				range = new long[2];
				nodeID = node.getNodeID(index);
				range[1] = p_chunkID;
				// range[1] = getSuccessorsEntry(p_chunkID, node).getCID();
			} else {
				// ChunkID was not found, but successor: Store NodeID and ChunkID of successor
				range = new long[2];
				nodeID = node.getNodeID(index * -1 - 1);
				range[1] = node.getCID(index * -1 - 1);
			}
			// Determine ChunkID of predecessor
			predecessorEntry = getPredecessorsEntry(range[1], node);
			if (predecessorEntry != null) {
				range[0] = predecessorEntry.getCID() + 1;
			} else {
				range[0] = 0;
			}
			if (nodeID != -1) {
				ret = new Locations(nodeID, null, range);
			}
		}
		m_lock.readLock().unlock();

		return ret;
	}

	/**
	 * Caches a single ChunkID
	 * @param p_chunkID
	 *            the ChunkID
	 * @param p_nodeID
	 *            the primary peer
	 * @return true if insertion was successful
	 */
	public boolean cacheChunkID(final long p_chunkID, final short p_nodeID) {
		Node node;

		m_lock.writeLock().lock();
		node = createOrReplaceEntry(p_chunkID, p_nodeID);

		mergeWithPredecessorOrBound(p_chunkID, p_nodeID, node);

		mergeWithSuccessor(p_chunkID, p_nodeID);
		m_lock.writeLock().unlock();

		return true;
	}

	/**
	 * Caches a range
	 * @param p_startCID
	 *            the first ChunkID
	 * @param p_endCID
	 *            the last ChunkID
	 * @param p_nodeID
	 *            the primary peer
	 * @return true if insertion was successful
	 */
	public boolean cacheRange(final long p_startCID, final long p_endCID, final short p_nodeID) {
		Node startNode;

		if (p_startCID == p_endCID) {
			cacheChunkID(p_startCID, p_nodeID);
		} else {
			m_lock.writeLock().lock();
			startNode = createOrReplaceEntry(p_startCID, p_nodeID);

			mergeWithPredecessorOrBound(p_startCID, p_nodeID, startNode);

			createOrReplaceEntry(p_endCID, p_nodeID);

			removeEntriesWithinRange(p_startCID, p_endCID);

			mergeWithSuccessor(p_endCID, p_nodeID);
			m_lock.writeLock().unlock();
		}
		return true;
	}

	/**
	 * Removes given ChunkID from btree
	 * @param p_chunkID
	 *            the ChunkID
	 */
	public void invalidateChunkID(final long p_chunkID) {
		m_lock.writeLock().lock();
		removeEntry(p_chunkID);
		m_lock.writeLock().unlock();
	}

	/**
	 * Removes given range from btree
	 * @param p_startCID
	 *            the first ChunkID
	 * @param p_endCID
	 *            the last ChunkID
	 */
	public void invalidateRange(final long p_startCID, final long p_endCID) {
		Node node;
		short startNodeID;
		Entry successor;

		if (p_startCID == p_endCID) {
			m_lock.writeLock().lock();
			removeEntry(p_startCID);
			m_lock.writeLock().unlock();
		} else {
			m_lock.writeLock().lock();
			// Remove all ranges between p_startCID and p_endCID (excluding p_startCID)
			node = getNodeOrSuccessorsNode(p_endCID, false);
			if (-1 != node.getCID(node.indexOf(p_endCID))) {
				successor = getSuccessorsEntry(p_endCID, node);
				if (null != successor && -1 == successor.getNodeID()) {
					remove(p_endCID);
				} else {
					node.changeEntry(p_endCID, (short) -1, node.indexOf(p_endCID));
				}
			}

			startNodeID = getNodeIDOrSuccessorsNodeID(p_startCID);
			removeEntriesWithinRange(p_startCID, p_endCID);
			if (-1 != startNodeID) {
				createOrReplaceEntry(p_startCID, startNodeID);
			}
			m_lock.writeLock().unlock();
		}
	}

	/**
	 * Removes given ChunkID from btree
	 * @param p_chunkID
	 *            the ChunkID
	 * @note assumes m_lock has been locked
	 */
	public void removeEntry(final long p_chunkID) {
		int index;
		Node node;
		long currentCID;
		Entry currentEntry;
		Entry predecessor;
		Entry successor;

		if (null != m_root) {
			node = getNodeOrSuccessorsNode(p_chunkID, false);
			if (null != node) {
				currentCID = -1;

				index = node.indexOf(p_chunkID);
				if (0 <= index) {
					// Entry was found
					currentCID = node.getCID(index);
					predecessor = getPredecessorsEntry(p_chunkID, node);
					currentEntry = new Entry(currentCID, node.getNodeID(index));
					successor = getSuccessorsEntry(p_chunkID, node);
					if (-1 != currentEntry.getNodeID() && null != predecessor) {
						if (p_chunkID - 1 == predecessor.getCID()) {
							// Predecessor is direct neighbor: AB
							// Successor might be direct neighbor or not: ABC or AB___C
							if (-1 == successor.getNodeID()) {
								// Successor is barrier: ABC -> A_C or AB___C -> A___C
								remove(p_chunkID);
							} else {
								// Successor is no barrier: ABC -> AXC or AB___C -> AX___C
								node.changeEntry(p_chunkID, (short) -1, index);
							}
							if (-1 == predecessor.getNodeID()) {
								// Predecessor is barrier: A_C -> ___C or AXC -> ___XC
								// or A___C -> ___C or AX___C -> ___X___C
								remove(predecessor.getCID());
							}
						} else {
							// Predecessor is no direct neighbor: A___B
							if (-1 == successor.getNodeID()) {
								// Successor is barrier: A___BC -> A___C or A___B___C -> A___'___C
								remove(p_chunkID);
							} else {
								// Successor is no barrier: A___BC -> A___XC or A___B___C -> A___X___C
								node.changeEntry(p_chunkID, (short) -1, index);
							}
							// Predecessor is barrier: A___C -> A___(B-1)_C or A___XC -> ___(B-1)XC
							// or A___'___C -> A___(B-1)___C or A___X___C -> A___(B-1)X___C
							createOrReplaceEntry(p_chunkID - 1, currentEntry.getNodeID());
						}
					}
				} else {
					// Entry was not found
					index = index * -1 - 1;
					successor = new Entry(node.getCID(index), node.getNodeID(index));
					predecessor = getPredecessorsEntry(successor.getCID(), node);
					if (-1 != successor.getNodeID() && null != predecessor) {
						// Entry is in range
						if (p_chunkID - 1 == predecessor.getCID()) {
							// Predecessor is direct neighbor: A'B'
							// Successor might be direct neighbor or not: A'B'C -> AXC or A'B'___C -> AX___C
							createOrReplaceEntry(p_chunkID, (short) -1);
							if (-1 == predecessor.getNodeID()) {
								// Predecessor is barrier: AXC -> ___XC or AX___C -> ___X___C
								remove(p_chunkID - 1);
							}
						} else {
							// Predecessor is no direct neighbor: A___'B'
							// Successor might be direct neighbor or not: A___'B'C -> A___(B-1)XC
							// or A___'B'___C -> A___(B-1)X___C
							createOrReplaceEntry(p_chunkID, (short) -1);
							createOrReplaceEntry(p_chunkID - 1, successor.getNodeID());
						}
					}
				}
			}
		}
	}

	/**
	 * Creates a new entry or replaces the old one
	 * @param p_chunkID
	 *            the ChunkID
	 * @param p_nodeID
	 *            the NodeID
	 * @return the node in which the entry is stored
	 */
	private Node createOrReplaceEntry(final long p_chunkID, final short p_nodeID) {
		Node ret = null;
		Node node;
		int index;
		int size;

		if (null == m_root) {
			m_root = new Node(null, m_maxEntries, m_maxChildren);
			m_root.addEntry(p_chunkID, p_nodeID);
			ret = m_root;
		} else {
			node = m_root;
			while (true) {
				if (0 == node.getNumberOfChildren()) {
					index = node.indexOf(p_chunkID);
					if (0 <= index) {
						m_changedEntry = new Entry(node.getCID(index), node.getNodeID(index));
						node.changeEntry(p_chunkID, p_nodeID, index);
					} else {
						m_changedEntry = null;
						node.addEntry(p_chunkID, p_nodeID, index * -1 - 1);
						if (m_maxEntries < node.getNumberOfEntries()) {
							// Need to split up
							node = split(p_chunkID, node);
						}
					}
					break;
				} else {
					if (p_chunkID < node.getCID(0)) {
						node = node.getChild(0);
						continue;
					}

					size = node.getNumberOfEntries();
					if (p_chunkID > node.getCID(size - 1)) {
						node = node.getChild(size);
						continue;
					}

					index = node.indexOf(p_chunkID);
					if (0 <= index) {
						m_changedEntry = new Entry(node.getCID(index), node.getNodeID(index));
						node.changeEntry(p_chunkID, p_nodeID, index);
						break;
					} else {
						node = node.getChild(index * -1 - 1);
					}
				}
			}

			ret = node;
		}
		if (m_changedEntry == null) {
			m_size++;
		}
		m_changedEntry = null;

		return ret;
	}

	/**
	 * Merges the object or range with predecessor
	 * @param p_chunkID
	 *            the ChunkID
	 * @param p_nodeID
	 *            the NodeID
	 * @param p_node
	 *            anchor node
	 */
	private void mergeWithPredecessorOrBound(final long p_chunkID, final short p_nodeID, final Node p_node) {
		Entry predecessor;
		Entry successor;

		predecessor = getPredecessorsEntry(p_chunkID, p_node);
		if (null == predecessor) {
			createOrReplaceEntry(p_chunkID - 1, (short) -1);
		} else {
			if (p_chunkID - 1 == predecessor.getCID()) {
				if (p_nodeID == predecessor.getNodeID()) {
					remove(predecessor.getCID(), getPredecessorsNode(p_chunkID, p_node));
				}
			} else {
				successor = getSuccessorsEntry(p_chunkID, p_node);
				if (null == m_changedEntry) {
					// Successor is end of range
					if (p_nodeID != successor.getNodeID()) {
						createOrReplaceEntry(p_chunkID - 1, successor.getNodeID());
					} else {
						// New Object is in range that already was migrated to the same destination
						remove(p_chunkID, p_node);
					}
				} else {
					if (p_nodeID != m_changedEntry.getNodeID()) {
						createOrReplaceEntry(p_chunkID - 1, m_changedEntry.getNodeID());
					}
				}
			}
		}
	}

	/**
	 * Merges the object or range with successor
	 * @param p_chunkID
	 *            the ChunkID
	 * @param p_nodeID
	 *            the NodeID
	 */
	private void mergeWithSuccessor(final long p_chunkID, final short p_nodeID) {
		Node node;
		Entry successor;

		node = getNodeOrSuccessorsNode(p_chunkID, false);
		successor = getSuccessorsEntry(p_chunkID, node);
		if (null != successor && p_nodeID == successor.getNodeID()) {
			remove(p_chunkID, node);
		}
	}

	/**
	 * Removes all entries between start (inclusive) and end
	 * @param p_start
	 *            the first object in range
	 * @param p_end
	 *            the last object in range
	 */
	private void removeEntriesWithinRange(final long p_start, final long p_end) {
		long successor;

		remove(p_start, getNodeOrSuccessorsNode(p_start, false));

		successor = getCIDOrSuccessorsCID(p_start);
		while (-1 != successor && successor < p_end) {
			remove(successor);
			successor = getCIDOrSuccessorsCID(p_start);
		}
	}

	/**
	 * Returns the node in which the next entry to given ChunkID (could be the ChunkID itself) is stored
	 * @param p_chunkID
	 *            the ChunkID whose node is searched
	 * @param p_registerAccess
	 *            whether the access of any traversed node should be registered or not
	 * @return node in which the ChunkID is stored if ChunkID is in tree or successors node,
	 *         null if there is no successor
	 */
	private Node getNodeOrSuccessorsNode(final long p_chunkID, final boolean p_registerAccess) {
		Node ret;
		int size;
		int index;
		long greater;

		ret = m_root;

		while (true) {
			if (p_chunkID < ret.getCID(0)) {
				if (0 < ret.getNumberOfChildren()) {
					ret = ret.getChild(0);
					continue;
				} else {
					if (p_registerAccess) {
						ret.setLastAccess();
					}
					break;
				}
			}

			size = ret.getNumberOfEntries();
			greater = ret.getCID(size - 1);
			if (p_chunkID > greater) {
				if (size < ret.getNumberOfChildren()) {
					ret = ret.getChild(size);
					continue;
				} else {
					ret = getSuccessorsNode(greater, ret);
					if (p_registerAccess) {
						ret.setLastAccess();
					}
					break;
				}
			}

			index = ret.indexOf(p_chunkID);
			if (0 <= index) {
				break;
			} else {
				index = index * -1 - 1;
				if (index < ret.getNumberOfChildren()) {
					ret = ret.getChild(index);
					if (p_registerAccess) {
						ret.setLastAccess();
					}
				} else {
					if (p_registerAccess) {
						ret.setLastAccess();
					}
					break;
				}
			}
		}

		return ret;
	}

	/**
	 * Returns next ChunkID to given ChunkID (could be the ChunkID itself)
	 * @param p_chunkID
	 *            the ChunkID
	 * @return p_chunkID if p_chunkID is in btree or successor of p_chunkID, (-1) if there is no successor
	 */
	private long getCIDOrSuccessorsCID(final long p_chunkID) {
		long ret = -1;
		int index;
		Node node;

		node = getNodeOrSuccessorsNode(p_chunkID, false);
		if (node != null) {
			index = node.indexOf(p_chunkID);
			if (0 <= index) {
				ret = node.getCID(index);
			} else {
				ret = node.getCID(index * -1 - 1);
			}
		}

		return ret;
	}

	/**
	 * Returns the location and backup nodes of next ChunkID to given ChunkID (could be the ChunkID itself)
	 * @param p_chunkID
	 *            the ChunkID whose corresponding NodeID is searched
	 * @return NodeID for p_chunkID if p_chunkID is in btree or successors NodeID
	 */
	private short getNodeIDOrSuccessorsNodeID(final long p_chunkID) {
		short ret = -1;
		int index;
		Node node;

		node = getNodeOrSuccessorsNode(p_chunkID, true);
		if (node != null) {
			index = node.indexOf(p_chunkID);
			if (0 <= index) {
				ret = node.getNodeID(index);
			} else {
				ret = node.getNodeID(index * -1 - 1);
			}
		}

		return ret;
	}

	/**
	 * Returns the node in which the predecessor is
	 * @param p_chunkID
	 *            the ChunkID whose predecessor's node is searched
	 * @param p_node
	 *            anchor node
	 * @return the node in which the predecessor of p_chunkID is or null if there is no predecessor
	 */
	private Node getPredecessorsNode(final long p_chunkID, final Node p_node) {
		int index;
		Node ret = null;
		Node node;
		Node parent;

		assert p_node != null;

		node = p_node;

		if (p_chunkID == node.getCID(0)) {
			if (0 < node.getNumberOfChildren()) {
				// Get maximum in child tree
				node = node.getChild(0);
				while (node.getNumberOfEntries() < node.getNumberOfChildren()) {
					node = node.getChild(node.getNumberOfChildren() - 1);
				}
				ret = node;
			} else {
				parent = node.getParent();
				if (parent != null) {
					while (parent != null && p_chunkID < parent.getCID(0)) {
						parent = parent.getParent();
					}
					ret = parent;
				}
			}
		} else {
			index = node.indexOf(p_chunkID);
			if (0 <= index) {
				if (index <= node.getNumberOfChildren()) {
					// Get maximum in child tree
					node = node.getChild(index);
					while (node.getNumberOfEntries() < node.getNumberOfChildren()) {
						node = node.getChild(node.getNumberOfChildren() - 1);
					}
				}
				ret = node;
			}
		}

		return ret;
	}

	/**
	 * Returns the entry of the predecessor
	 * @param p_chunkID
	 *            the ChunkID whose predecessor is searched
	 * @param p_node
	 *            anchor node
	 * @return the entry of p_chunkID's predecessor or null if there is no predecessor
	 */
	private Entry getPredecessorsEntry(final long p_chunkID, final Node p_node) {
		Entry ret = null;
		Node predecessorsNode;
		long predecessorsCID;

		predecessorsNode = getPredecessorsNode(p_chunkID, p_node);
		if (predecessorsNode != null) {
			for (int i = predecessorsNode.getNumberOfEntries() - 1; i >= 0; i--) {
				predecessorsCID = predecessorsNode.getCID(i);
				if (p_chunkID > predecessorsCID) {
					ret = new Entry(predecessorsCID, predecessorsNode.getNodeID(i));
					break;
				}
			}
		}

		return ret;
	}

	/**
	 * Returns the node in which the successor is
	 * @param p_chunkID
	 *            the ChunkID whose successor's node is searched
	 * @param p_node
	 *            anchor node
	 * @return the node in which the successor of p_chunkID is or null if there is no successor
	 */
	private Node getSuccessorsNode(final long p_chunkID, final Node p_node) {
		int index;
		Node ret = null;
		Node node;
		Node parent;

		assert p_node != null;

		node = p_node;

		if (p_chunkID == node.getCID(node.getNumberOfEntries() - 1)) {
			if (node.getNumberOfEntries() < node.getNumberOfChildren()) {
				// Get minimum in child tree
				node = node.getChild(node.getNumberOfEntries());
				while (0 < node.getNumberOfChildren()) {
					node = node.getChild(0);
				}
				ret = node;
			} else {
				parent = node.getParent();
				if (parent != null) {
					while (parent != null && p_chunkID > parent.getCID(parent.getNumberOfEntries() - 1)) {
						parent = parent.getParent();
					}
					ret = parent;
				}
			}
		} else {
			index = node.indexOf(p_chunkID);
			if (0 <= index) {
				if (index < node.getNumberOfChildren()) {
					// Get minimum in child tree
					node = node.getChild(index + 1);
					while (0 < node.getNumberOfChildren()) {
						node = node.getChild(0);
					}
				}
				ret = node;
			}
		}

		return ret;
	}

	/**
	 * Returns the entry of the successor
	 * @param p_chunkID
	 *            the ChunkID whose successor is searched
	 * @param p_node
	 *            anchor node
	 * @return the entry of p_chunkID's successor or null if there is no successor
	 */
	private Entry getSuccessorsEntry(final long p_chunkID, final Node p_node) {
		Entry ret = null;
		Node successorsNode;
		long successorsCID;

		successorsNode = getSuccessorsNode(p_chunkID, p_node);
		if (successorsNode != null) {
			for (int i = 0; i < successorsNode.getNumberOfEntries(); i++) {
				successorsCID = successorsNode.getCID(i);
				if (p_chunkID < successorsCID) {
					ret = new Entry(successorsCID, successorsNode.getNodeID(i));
					break;
				}
			}
		}

		return ret;
	}

	/**
	 * Splits down the middle if node is greater than maxEntries
	 * @param p_chunkID
	 *            the new ChunkID that causes the splitting
	 * @param p_node
	 *            the node that has to be split
	 * @return the node in which p_chunkID must be inserted
	 */
	private Node split(final long p_chunkID, final Node p_node) {
		Node ret;
		Node node;

		int size;
		int medianIndex;
		long medianCID;
		short medianNodeID;

		Node left;
		Node right;
		Node parent;
		Node newRoot;

		node = p_node;

		size = node.getNumberOfEntries();
		medianIndex = size / 2;
		medianCID = node.getCID(medianIndex);
		medianNodeID = node.getNodeID(medianIndex);

		left = new Node(null, m_maxEntries, m_maxChildren);
		left.addEntries(node, 0, medianIndex, 0);
		if (0 < node.getNumberOfChildren()) {
			left.addChildren(node, 0, medianIndex + 1, 0);
		}

		right = new Node(null, m_maxEntries, m_maxChildren);
		right.addEntries(node, medianIndex + 1, size, 0);
		if (0 < node.getNumberOfChildren()) {
			right.addChildren(node, medianIndex + 1, node.getNumberOfChildren(), 0);
		}
		if (null == node.getParent()) {
			// New root, height of tree is increased
			newRoot = new Node(null, m_maxEntries, m_maxChildren);
			newRoot.addEntry(medianCID, medianNodeID, 0);
			node.setParent(newRoot);
			m_root = newRoot;
			node = m_root;
			node.addChild(left);
			node.addChild(right);
			parent = newRoot;
		} else {
			// Move the median ChunkID up to the parent
			parent = node.getParent();
			parent.addEntry(medianCID, medianNodeID);
			parent.removeChild(node);
			parent.addChild(left);
			parent.addChild(right);

			if (parent.getNumberOfEntries() > m_maxEntries) {
				split(p_chunkID, parent);
			}
		}

		if (p_chunkID < medianCID) {
			ret = left;
		} else if (p_chunkID > medianCID) {
			ret = right;
		} else {
			ret = parent;
		}

		return ret;
	}

	/**
	 * Removes given ChunkID
	 * @param p_chunkID
	 *            the ChunkID
	 * @return p_chunkID or (-1) if there is no entry for p_chunkID
	 */
	private long remove(final long p_chunkID) {
		long ret;
		Node node;

		node = getNodeOrSuccessorsNode(p_chunkID, false);
		ret = remove(p_chunkID, node);

		return ret;
	}

	/**
	 * Removes the p_chunkID from given node and checks invariants
	 * @param p_chunkID
	 *            the ChunkID
	 * @param p_node
	 *            the node in which p_chunkID should be stored
	 * @return p_chunkID or (-1) if there is no entry for p_chunkID
	 */
	private long remove(final long p_chunkID, final Node p_node) {
		long ret = -1;
		int index;
		Node greatest;
		long replaceCID;
		short replaceNodeID;

		assert p_node != null;

		index = p_node.indexOf(p_chunkID);
		if (0 <= index) {
			ret = p_node.removeEntry(p_chunkID);
			if (0 == p_node.getNumberOfChildren()) {
				// Leaf node
				if (null != p_node.getParent() && p_node.getNumberOfEntries() < m_minEntries) {
					combined(p_node);
				} else if (null == p_node.getParent() && 0 == p_node.getNumberOfEntries()) {
					// Removing root node with no keys or children
					m_root = null;
				}
			} else {
				// Internal node
				greatest = p_node.getChild(index);
				while (0 < greatest.getNumberOfChildren()) {
					greatest = greatest.getChild(greatest.getNumberOfChildren() - 1);
				}
				replaceCID = -1;
				replaceNodeID = -1;
				if (0 < greatest.getNumberOfEntries()) {
					replaceNodeID = greatest.getNodeID(greatest.getNumberOfEntries() - 1);
					replaceCID = greatest.removeEntry(greatest.getNumberOfEntries() - 1);
				}
				p_node.addEntry(replaceCID, replaceNodeID);
				if (null != greatest.getParent() && greatest.getNumberOfEntries() < m_minEntries) {
					combined(greatest);
				}
				if (greatest.getNumberOfChildren() > m_maxChildren) {
					split(p_chunkID, greatest);
				}
			}
			m_size--;
		}

		return ret;
	}

	/**
	 * Combines children entries with parent when size is less than minEntries
	 * @param p_node
	 *            the node
	 */
	private void combined(final Node p_node) {
		Node parent;
		int index;
		int indexOfLeftNeighbor;
		int indexOfRightNeighbor;
		Node rightNeighbor;
		int rightNeighborSize;
		Node leftNeighbor;
		int leftNeighborSize;

		long removeCID;
		int prev;
		short parentNodeID;
		long parentCID;

		short neighborNodeID;
		long neighborCID;

		parent = p_node.getParent();
		index = parent.indexOf(p_node);
		indexOfLeftNeighbor = index - 1;
		indexOfRightNeighbor = index + 1;

		rightNeighbor = null;
		rightNeighborSize = -m_minChildren;
		if (indexOfRightNeighbor < parent.getNumberOfChildren()) {
			rightNeighbor = parent.getChild(indexOfRightNeighbor);
			rightNeighborSize = rightNeighbor.getNumberOfEntries();
		}

		// Try to borrow neighbor
		if (null != rightNeighbor && rightNeighborSize > m_minEntries) {
			// Try to borrow from right neighbor
			removeCID = rightNeighbor.getCID(0);
			prev = parent.indexOf(removeCID) * -1 - 2;
			parentNodeID = parent.getNodeID(prev);
			parentCID = parent.removeEntry(prev);

			neighborNodeID = rightNeighbor.getNodeID(0);
			neighborCID = rightNeighbor.removeEntry(0);

			p_node.addEntry(parentCID, parentNodeID);
			parent.addEntry(neighborCID, neighborNodeID);
			if (0 < rightNeighbor.getNumberOfChildren()) {
				p_node.addChild(rightNeighbor.removeChild(0));
			}
		} else {
			leftNeighbor = null;
			leftNeighborSize = -m_minChildren;
			if (0 <= indexOfLeftNeighbor) {
				leftNeighbor = parent.getChild(indexOfLeftNeighbor);
				leftNeighborSize = leftNeighbor.getNumberOfEntries();
			}

			if (null != leftNeighbor && leftNeighborSize > m_minEntries) {
				// Try to borrow from left neighbor
				removeCID = leftNeighbor.getCID(leftNeighbor.getNumberOfEntries() - 1);
				prev = parent.indexOf(removeCID) * -1 - 1;
				parentNodeID = parent.getNodeID(prev);
				parentCID = parent.removeEntry(prev);

				neighborNodeID = leftNeighbor.getNodeID(leftNeighbor.getNumberOfEntries() - 1);
				neighborCID = leftNeighbor.removeEntry(leftNeighbor.getNumberOfEntries() - 1);

				p_node.addEntry(parentCID, parentNodeID);
				parent.addEntry(neighborCID, neighborNodeID);
				if (0 < leftNeighbor.getNumberOfChildren()) {
					p_node.addChild(leftNeighbor.removeChild(leftNeighbor.getNumberOfChildren() - 1));
				}
			} else if (null != rightNeighbor && 0 < parent.getNumberOfEntries()) {
				// Cannot borrow from neighbors, try to combined with right neighbor
				removeCID = rightNeighbor.getCID(0);
				prev = parent.indexOf(removeCID) * -1 - 2;
				parentNodeID = parent.getNodeID(prev);
				parentCID = parent.removeEntry(prev);
				parent.removeChild(rightNeighbor);
				p_node.addEntry(parentCID, parentNodeID);

				p_node.addEntries(rightNeighbor, 0, rightNeighbor.getNumberOfEntries(), p_node.getNumberOfEntries());
				p_node.addChildren(rightNeighbor, 0, rightNeighbor.getNumberOfChildren(), p_node.getNumberOfChildren());

				if (null != parent.getParent() && parent.getNumberOfEntries() < m_minEntries) {
					// Removing key made parent too small, combined up tree
					combined(parent);
				} else if (0 == parent.getNumberOfEntries()) {
					// Parent no longer has keys, make this node the new root which decreases the height of the tree
					p_node.setParent(null);
					m_root = p_node;
				}
			} else if (null != leftNeighbor && 0 < parent.getNumberOfEntries()) {
				// Cannot borrow from neighbors, try to combined with left neighbor
				removeCID = leftNeighbor.getCID(leftNeighbor.getNumberOfEntries() - 1);
				prev = parent.indexOf(removeCID) * -1 - 1;
				parentNodeID = parent.getNodeID(prev);
				parentCID = parent.removeEntry(prev);
				parent.removeChild(leftNeighbor);
				p_node.addEntry(parentCID, parentNodeID);
				p_node.addEntries(leftNeighbor, 0, leftNeighbor.getNumberOfEntries(), -1);
				p_node.addChildren(leftNeighbor, 0, leftNeighbor.getNumberOfChildren(), -1);

				if (null != parent.getParent() && parent.getNumberOfEntries() < m_minEntries) {
					// Removing key made parent too small, combined up tree
					combined(parent);
				} else if (0 == parent.getNumberOfEntries()) {
					// Parent no longer has keys, make this node the new root which decreases the height of the tree
					p_node.setParent(null);
					m_root = p_node;
				}
			}
		}
	}

	/**
	 * Returns the number of entries in btree
	 * @return the number of entries in btree
	 */
	public int size() {
		return m_size;
	}

	/**
	 * Validates the btree
	 * @return whether the tree is valid or not
	 */
	public boolean validate() {
		boolean ret = true;

		if (m_root != null) {
			ret = validateNode(m_root);
		}

		return ret;
	}

	/**
	 * Validates the node according to the btree invariants
	 * @param p_node
	 *            the node
	 * @return whether the node is valid or not
	 */
	private boolean validateNode(final Node p_node) {
		boolean ret = true;
		int numberOfEntries;
		long prev;
		long next;
		int childrenSize;
		Node first;
		Node last;
		Node child;

		numberOfEntries = p_node.getNumberOfEntries();

		if (1 < numberOfEntries) {
			// Make sure the keys are sorted
			for (int i = 1; i < numberOfEntries; i++) {
				prev = p_node.getCID(i - 1);
				next = p_node.getCID(i);
				if (prev > next) {
					ret = false;
					break;
				}
			}
		} else {
			childrenSize = p_node.getNumberOfChildren();
			if (null == p_node.getParent()) {
				// Root
				if (numberOfEntries > m_maxEntries) {
					// Check max key size. Root does not have a minimum key size
					ret = false;
				} else if (0 == childrenSize) {
					// If root, no children, and keys are valid
					ret = true;
				} else if (2 > childrenSize) {
					// Root should have zero or at least two children
					ret = false;
				} else if (childrenSize > m_maxChildren) {
					ret = false;
				}
			} else {
				// Non-root
				if (numberOfEntries < m_minEntries) {
					ret = false;
				} else if (numberOfEntries > m_maxEntries) {
					ret = false;
				} else if (0 == childrenSize) {
					ret = true;
				} else if (numberOfEntries != childrenSize - 1) {
					// If there are children, there should be one more child then keys
					ret = false;
				} else if (childrenSize < m_minChildren) {
					ret = false;
				} else if (childrenSize > m_maxChildren) {
					ret = false;
				}
			}

			first = p_node.getChild(0);
			// The first child's last key should be less than the node's first key
			if (first.getCID(first.getNumberOfEntries() - 1) > p_node.getCID(0)) {
				ret = false;
			}

			last = p_node.getChild(p_node.getNumberOfChildren() - 1);
			// The last child's first key should be greater than the node's last key
			if (last.getCID(0) < p_node.getCID(p_node.getNumberOfEntries() - 1)) {
				ret = false;
			}

			// Check that each node's first and last key holds it's invariance
			for (int i = 1; i < p_node.getNumberOfEntries(); i++) {
				prev = p_node.getCID(i - 1);
				next = p_node.getCID(i);
				child = p_node.getChild(i);
				if (prev > child.getCID(0)) {
					ret = false;
					break;
				}
				if (next < child.getCID(child.getNumberOfEntries() - 1)) {
					ret = false;
					break;
				}
			}

			for (int i = 0; i < p_node.getNumberOfChildren(); i++) {
				child = p_node.getChild(i);
				if (!validateNode(child)) {
					ret = false;
					break;
				}
			}
		}

		return ret;
	}

	/**
	 * Prints the btree
	 * @return String interpretation of the tree
	 */
	@Override
	public String toString() {
		String ret;

		if (null == m_root) {
			ret = "Btree has no nodes";
		} else {
			ret = "Size: " + m_size + "\n" + getString(m_root, "", true);
		}

		return ret;
	}

	/**
	 * Prints one node of the btree and walks down the btree recursively
	 * @param p_node
	 *            the current node
	 * @param p_prefix
	 *            the prefix to use
	 * @param p_isTail
	 *            defines wheter the node is the tail
	 * @return String interpretation of the tree
	 */
	private String getString(final Node p_node, final String p_prefix, final boolean p_isTail) {
		StringBuilder ret;
		Node obj;

		ret = new StringBuilder();

		ret.append(p_prefix);
		if (p_isTail) {
			ret.append("└── ");
		} else {
			ret.append("├── ");
		}
		ret.append("[" + p_node.getNumberOfEntries() + ", " + p_node.getNumberOfChildren() + "] ");
		for (int i = 0; i < p_node.getNumberOfEntries(); i++) {
			ret.append("(ChunkID: " + p_node.getCID(i) + " NodeID: " + p_node.getNodeID(i) + ")");
			if (i < p_node.getNumberOfEntries() - 1) {
				ret.append(", ");
			}
		}
		ret.append("\n");

		if (null != p_node.getChild(0)) {
			for (int i = 0; i < p_node.getNumberOfChildren() - 1; i++) {
				obj = p_node.getChild(i);
				if (p_isTail) {
					ret.append(getString(obj, p_prefix + "    ", false));
				} else {
					ret.append(getString(obj, p_prefix + "│   ", false));
				}
			}
			if (1 <= p_node.getNumberOfChildren()) {
				obj = p_node.getChild(p_node.getNumberOfChildren() - 1);
				if (p_isTail) {
					ret.append(getString(obj, p_prefix + "    ", true));
				} else {
					ret.append(getString(obj, p_prefix + "│   ", true));
				}
			}
		}
		return ret.toString();
	}

	/**
	 * A single node of the btree
	 * @author Kevin Beineke
	 *         13.06.2013
	 */
	private static final class Node implements Comparable<Node> {

		// Attributes
		private Node m_parent;

		private long[] m_keys;
		private short[] m_dataLeafs;
		private short m_numberOfEntries;

		private Node[] m_children;
		private short m_numberOfChildren;

		private long m_lastAccess;

		// Constructors
		/**
		 * Creates an instance of Node
		 * @param p_parent
		 *            the parent
		 * @param p_maxEntries
		 *            the number of entries that can be stored
		 * @param p_maxChildren
		 *            the number of children that can be stored
		 */
		private Node(final Node p_parent, final short p_maxEntries, final int p_maxChildren) {
			m_parent = p_parent;
			m_keys = new long[p_maxEntries + 1];
			m_dataLeafs = new short[p_maxEntries + 1];
			m_numberOfEntries = 0;
			m_children = new Node[p_maxChildren + 1];
			m_numberOfChildren = 0;

			m_lastAccess = System.currentTimeMillis();
		}

		/**
		 * Compares two nodes
		 * @param p_cmp
		 *            the node to compare with
		 * @return 0 if the nodes are equal, (-1) if p_cmp is larger, 1 otherwise
		 */
		@Override
		public int compareTo(final Node p_cmp) {
			int ret;

			if (getCID(0) < p_cmp.getCID(0)) {
				ret = -1;
			} else if (getCID(0) > p_cmp.getCID(0)) {
				ret = 1;
			} else {
				ret = 0;
			}

			return ret;
		}

		/**
		 * Returns the parent node
		 * @return the parent node
		 */
		private Node getParent() {
			return m_parent;
		}

		/**
		 * Returns the parent node
		 * @param p_parent
		 *            the parent node
		 */
		private void setParent(final Node p_parent) {
			m_parent = p_parent;
		}

		/**
		 * Returns the ChunkID to given index
		 * @param p_index
		 *            the index
		 * @return the ChunkID to given index
		 */
		private long getCID(final int p_index) {
			return m_keys[p_index];
		}

		/**
		 * Returns the data leaf to given index
		 * @param p_index
		 *            the index
		 * @return the data leaf to given index
		 */
		private short getNodeID(final int p_index) {
			return m_dataLeafs[p_index];
		}

		/**
		 * Returns time of the last access
		 * @return the timestamp
		 */
		private long getLastAccess() {
			return m_lastAccess;
		}

		/**
		 * Sets time of the last access
		 */
		private void setLastAccess() {
			m_lastAccess = System.currentTimeMillis();
		}

		/**
		 * Returns the index for given ChunkID. Uses the binary search algorithm from
		 * java.util.Arrays adapted to our needs
		 * @param p_chunkID
		 *            the ChunkID
		 * @return the index for given ChunkID, if it is contained in the array, (-(insertion point) - 1) otherwise
		 */
		private int indexOf(final long p_chunkID) {
			int ret = -1;
			int low;
			int high;
			int mid;
			long midVal;

			low = 0;
			high = m_numberOfEntries - 1;

			while (low <= high) {
				mid = low + high >>> 1;
			midVal = m_keys[mid];

			if (midVal < p_chunkID) {
				low = mid + 1;
			} else if (midVal > p_chunkID) {
				high = mid - 1;
			} else {
				ret = mid;
				break;
			}
			}
			if (-1 == ret) {
				ret = -(low + 1);
			}

			return ret;
		}

		/**
		 * Adds an entry
		 * @param p_chunkID
		 *            the ChunkID
		 * @param p_nodeID
		 *            the NodeID
		 */
		private void addEntry(final long p_chunkID, final short p_nodeID) {
			int index;

			index = this.indexOf(p_chunkID) * -1 - 1;

			System.arraycopy(m_keys, index, m_keys, index + 1, m_numberOfEntries - index);
			System.arraycopy(m_dataLeafs, index, m_dataLeafs, index + 1, m_numberOfEntries - index);

			m_keys[index] = p_chunkID;
			m_dataLeafs[index] = p_nodeID;

			m_numberOfEntries++;
		}

		/**
		 * Adds an entry
		 * @param p_chunkID
		 *            the ChunkID
		 * @param p_nodeID
		 *            the NodeID
		 * @param p_index
		 *            the index to store the element at
		 */
		private void addEntry(final long p_chunkID, final short p_nodeID, final int p_index) {
			System.arraycopy(m_keys, p_index, m_keys, p_index + 1, m_numberOfEntries - p_index);
			System.arraycopy(m_dataLeafs, p_index, m_dataLeafs, p_index + 1, m_numberOfEntries - p_index);

			m_keys[p_index] = p_chunkID;
			m_dataLeafs[p_index] = p_nodeID;

			m_numberOfEntries++;
		}

		/**
		 * Adds entries from another node
		 * @param p_node
		 *            the other node
		 * @param p_offsetSrc
		 *            the offset in source array
		 * @param p_endSrc
		 *            the end of source array
		 * @param p_offsetDst
		 *            the offset in destination array or -1 if the source array has to be prepended
		 */
		private void addEntries(final Node p_node, final int p_offsetSrc, final int p_endSrc, final int p_offsetDst) {
			long[] aux1;
			short[] aux2;

			if (-1 != p_offsetDst) {
				System.arraycopy(p_node.m_keys, p_offsetSrc, m_keys, p_offsetDst, p_endSrc - p_offsetSrc);
				System.arraycopy(p_node.m_dataLeafs, p_offsetSrc, m_dataLeafs, p_offsetDst, p_endSrc - p_offsetSrc);
				m_numberOfEntries = (short) (p_offsetDst + p_endSrc - p_offsetSrc);
			} else {
				aux1 = new long[m_keys.length];
				System.arraycopy(p_node.m_keys, 0, aux1, 0, p_node.m_numberOfEntries);
				System.arraycopy(m_keys, 0, aux1, p_node.m_numberOfEntries, m_numberOfEntries);
				m_keys = aux1;

				aux2 = new short[m_dataLeafs.length];
				System.arraycopy(p_node.m_dataLeafs, 0, aux2, 0, p_node.m_numberOfEntries);
				System.arraycopy(m_dataLeafs, 0, aux2, p_node.m_numberOfEntries, m_numberOfEntries);
				m_dataLeafs = aux2;

				m_numberOfEntries += p_node.m_numberOfEntries;
			}
		}

		/**
		 * Changes an entry
		 * @param p_chunkID
		 *            the ChunkID
		 * @param p_nodeID
		 *            the NodeID
		 * @param p_index
		 *            the index of given entry in this node
		 */
		private void changeEntry(final long p_chunkID, final short p_nodeID, final int p_index) {

			if (p_chunkID == getCID(p_index)) {
				m_keys[p_index] = p_chunkID;
				m_dataLeafs[p_index] = p_nodeID;
			}
		}

		/**
		 * Removes the entry with given ChunkID
		 * @param p_chunkID
		 *            the ChunkID of the entry that has to be deleted
		 * @return p_chunkID or (-1) if there is no entry for p_chunkID in this node
		 */
		private long removeEntry(final long p_chunkID) {
			long ret = -1;
			int index;

			index = this.indexOf(p_chunkID);
			if (0 <= index) {
				ret = getCID(index);

				System.arraycopy(m_keys, index + 1, m_keys, index, m_numberOfEntries - index);
				System.arraycopy(m_dataLeafs, index + 1, m_dataLeafs, index, m_numberOfEntries - index);
				m_numberOfEntries--;
			}

			return ret;
		}

		/**
		 * Removes the entry with given index
		 * @param p_index
		 *            the index of the entry that has to be deleted
		 * @return p_chunkID or (-1) if p_index is to large
		 */
		private long removeEntry(final int p_index) {
			long ret = -1;

			if (p_index < m_numberOfEntries) {
				ret = getCID(p_index);

				System.arraycopy(m_keys, p_index + 1, m_keys, p_index, m_numberOfEntries - p_index);
				System.arraycopy(m_dataLeafs, p_index + 1, m_dataLeafs, p_index, m_numberOfEntries - p_index);
				m_numberOfEntries--;
			}

			return ret;
		}

		/**
		 * Returns the number of entries
		 * @return the number of entries
		 */
		private int getNumberOfEntries() {
			return m_numberOfEntries;
		}

		/**
		 * Returns the child with given index
		 * @param p_index
		 *            the index
		 * @return the child with given index
		 */
		private Node getChild(final int p_index) {
			Node ret;

			if (p_index >= m_numberOfChildren) {
				ret = null;
			} else {
				ret = m_children[p_index];
			}

			return ret;
		}

		/**
		 * Returns the index of the given child. Uses the binary search algorithm from
		 * java.util.Arrays adapted to our needs
		 * @param p_child
		 *            the child
		 * @return the index of the given child, if it is contained in the array, (-(insertion point) - 1) otherwise
		 */
		private int indexOf(final Node p_child) {
			int ret = -1;
			int low;
			int high;
			int mid;
			long chunkID;
			long midVal;

			chunkID = p_child.getCID(0);
			low = 0;
			high = m_numberOfChildren - 1;

			while (low <= high) {
				mid = low + high >>> 1;
			midVal = m_children[mid].getCID(0);

			if (midVal < chunkID) {
				low = mid + 1;
			} else if (midVal > chunkID) {
				high = mid - 1;
			} else {
				ret = mid;
				break;
			}
			}
			if (-1 == ret) {
				ret = -(low + 1);
			}

			return ret;
		}

		/**
		 * Adds a child
		 * @param p_child
		 *            the child
		 */
		private void addChild(final Node p_child) {
			int index;

			index = this.indexOf(p_child) * -1 - 1;

			System.arraycopy(m_children, index, m_children, index + 1, m_numberOfChildren - index);
			m_children[index] = p_child;
			p_child.setParent(this);

			m_numberOfChildren++;
		}

		/**
		 * Adds children of another node
		 * @param p_node
		 *            the other node
		 * @param p_offsetSrc
		 *            the offset in source array
		 * @param p_endSrc
		 *            the end of source array
		 * @param p_offsetDst
		 *            the offset in destination array or -1 if the source array has to be prepended
		 */
		private void addChildren(final Node p_node, final int p_offsetSrc, final int p_endSrc, final int p_offsetDst) {
			Node[] aux;

			if (-1 != p_offsetDst) {
				System.arraycopy(p_node.m_children, p_offsetSrc, m_children, p_offsetDst, p_endSrc - p_offsetSrc);

				for (Node child : m_children) {
					if (null == child) {
						break;
					}
					child.setParent(this);
				}
				m_numberOfChildren = (short) (p_offsetDst + p_endSrc - p_offsetSrc);
			} else {
				aux = new Node[m_children.length];
				System.arraycopy(p_node.m_children, 0, aux, 0, p_node.m_numberOfChildren);

				for (Node child : aux) {
					if (null == child) {
						break;
					}
					child.setParent(this);
				}

				System.arraycopy(m_children, 0, aux, p_node.m_numberOfChildren, m_numberOfChildren);
				m_children = aux;

				m_numberOfChildren += p_node.m_numberOfChildren;
			}
		}

		/**
		 * Removes the given child
		 * @param p_child
		 *            the child
		 * @return true if the child was found and deleted, false otherwise
		 */
		private boolean removeChild(final Node p_child) {
			boolean ret = false;
			int index;

			index = this.indexOf(p_child);
			if (0 <= index) {
				System.arraycopy(m_children, index + 1, m_children, index, m_numberOfChildren - index);

				m_numberOfChildren--;
				ret = true;
			}

			return ret;
		}

		/**
		 * Removes the child with given index
		 * @param p_index
		 *            the index
		 * @return the deleted child
		 */
		private Node removeChild(final int p_index) {
			Node ret = null;

			if (p_index < m_numberOfChildren) {
				ret = m_children[p_index];
				System.arraycopy(m_children, p_index + 1, m_children, p_index, m_numberOfChildren - p_index);

				m_numberOfChildren--;
			}

			return ret;
		}

		/**
		 * Returns the number of children
		 * @return the number of children
		 */
		private int getNumberOfChildren() {
			return m_numberOfChildren;
		}

		/**
		 * Prints the node
		 * @return String interpretation of the node
		 */
		@Override
		public String toString() {
			StringBuilder ret;

			ret = new StringBuilder();

			ret.append("entries=[");
			for (int i = 0; i < getNumberOfEntries(); i++) {
				ret.append("(ChunkID: " + getCID(i) + " NodeID: " + getNodeID(i) + ")");
				if (i < getNumberOfEntries() - 1) {
					ret.append(", ");
				}
			}
			ret.append("]\n");

			if (null != m_parent) {
				ret.append("parent=[");
				for (int i = 0; i < m_parent.getNumberOfEntries(); i++) {
					ret.append("(ChunkID: " + getCID(i) + " NodeID: " + getNodeID(i) + ")");
					if (i < m_parent.getNumberOfEntries() - 1) {
						ret.append(", ");
					}
				}
				ret.append("]\n");
			}

			if (null != m_children) {
				ret.append("numberOfEntries=");
				ret.append(getNumberOfEntries());
				ret.append(" children=");
				ret.append(getNumberOfChildren());
				ret.append("\n");
			}

			return ret.toString();
		}
	}

	/**
	 * Auxiliary object to return ChunkID and NodeID at once
	 * @author Kevin Beineke
	 *         13.06.2013
	 */
	private static final class Entry {

		// Attributes
		private long m_chunkID;
		private short m_nodeID;

		// Constructors
		/**
		 * Creates an instance of Entry
		 * @param p_chunkID
		 *            the ChunkID
		 * @param p_nodeID
		 *            the NodeID
		 */
		Entry(final long p_chunkID, final short p_nodeID) {
			m_chunkID = p_chunkID;
			m_nodeID = p_nodeID;
		}

		/**
		 * Returns the ChunkID
		 * @return the ChunkID
		 */
		public long getCID() {
			return m_chunkID;
		}

		/**
		 * Returns the location
		 * @return the location
		 */
		public short getNodeID() {
			return m_nodeID;
		}

		/**
		 * Prints the entry
		 * @return String interpretation of the entry
		 */
		@Override
		public String toString() {
			return "(ChunkID: " + m_chunkID + ", NodeID: " + m_nodeID + ")";
		}
	}

	/**
	 * Manages the entry time-outs
	 * @author Florian Klein
	 *         09.03.2012
	 */
	private class TTLHandler implements Runnable {

		// Constants
		private static final long SLEEP_TIME = 1000;

		// Attributes
		private long m_ttl;

		private boolean m_running;

		// Constructors
		/**
		 * Creates an instance of TTLHandler
		 * @param p_ttl
		 *            the TTL value
		 */
		TTLHandler(final long p_ttl) {
			m_ttl = p_ttl;

			m_running = false;
		}

		// Methods
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
			long time;
			Node node;

			m_running = true;
			while (m_running) {
				try {
					Thread.sleep(SLEEP_TIME);
				} catch (final InterruptedException e) {}

				if (m_running) {
					time = System.currentTimeMillis();

					m_lock.writeLock().lock();
					node = m_root;
					while (node != null && node.getNumberOfEntries() > 1) {
						if (time - node.getLastAccess() > m_ttl) {
							invalidateRange(node.getCID(0), node.getCID(node.getNumberOfEntries() - 1));
							break;
						}
						node = node.getChild(Tools.getRandomValue(0, node.getNumberOfChildren()));
					}
					m_lock.writeLock().unlock();
				}
			}
		}

		/**
		 * Stops the TTLHandler
		 */
		public void stop() {
			m_running = false;
		}

	}
}
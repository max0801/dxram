package de.hhu.bsinfo.dxram.lookup.overlay.storage;

/**
 * Barrier id.
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 03.05.2016
 */
public final class BarrierID {
    public static final int INVALID_ID = -1;

    public static final int MAX_ID = 65535;

    /**
     * Hidden constructor
     */
    private BarrierID() {
    }

    /**
     * Get the owner node id of the barrier
     *
     * @param p_barrierId
     *     Barrier id
     * @return Owner node id of the barrier
     */
    public static short getOwnerID(final int p_barrierId) {
        assert p_barrierId != INVALID_ID;
        return (short) (p_barrierId >> 16);
    }

    /**
     * Convert the barrier id to a hex string
     *
     * @param p_barrierId
     *     Barrier id
     * @return Hex string representation
     */
    public static String toHexString(final int p_barrierId) {
        return "0x" + String.format("%08x", p_barrierId).toUpperCase();
    }

    /**
     * Get the (local) barrier id of the barrier id.
     *
     * @param p_barrierId
     *     Full barrier id to get the local part of.
     * @return Local id of the barrier id.
     */
    static int getBarrierID(final int p_barrierId) {
        assert p_barrierId != INVALID_ID;
        return (short) (p_barrierId & 0xFFFF);
    }

    /**
     * Create a barrier id from a separate nid and id
     *
     * @param p_nodeId
     *     Node id (owning the barrier)
     * @param p_id
     *     Id of the barrier
     * @return Barrier id
     */
    static int createBarrierId(final short p_nodeId, final int p_id) {
        return (p_nodeId & 0xFFFF) << 16 | p_id & 0xFFFF;
    }
}

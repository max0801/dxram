
package de.hhu.bsinfo.dxcompute.job;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Example for a job implementation.
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 03.02.2016
 */
public class JobNull extends AbstractJob {

    private static final Logger LOGGER = LogManager.getFormatterLogger(JobNull.class.getSimpleName());

    public static final short MS_TYPE_ID = 0;

    static {
        registerType(MS_TYPE_ID, JobNull.class);
    }

    /**
     * Constructor
     */
    public JobNull() {
        super(null);
    }

    @Override
    public short getTypeID() {
        return MS_TYPE_ID;
    }

    @Override
    protected void execute(final short p_nodeID, final long[] p_chunkIDs) {
        // #if LOGGER >= DEBUG
        LOGGER.debug("I am null job.");
        // #endif /* LOGGER >= DEBUG */
    }
}

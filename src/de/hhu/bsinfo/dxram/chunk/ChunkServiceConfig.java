package de.hhu.bsinfo.dxram.chunk;

import de.hhu.bsinfo.dxram.engine.DXRAMServiceConfig;

/**
 * Config for the ChunkService
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 24.05.2017
 */
public class ChunkServiceConfig extends DXRAMServiceConfig {
    /**
     * Constructor
     */
    public ChunkServiceConfig() {
        super(ChunkService.class, false, true);
    }
}
package de.hhu.bsinfo.dxram.engine;

/**
 * Dummy service implementation for testing.
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 26.01.2016
 */
public class NullService extends AbstractDXRAMService {

    /**
     * Constructor
     */
    public NullService() {
        super("null");
    }

    @Override
    protected void resolveComponentDependencies(final DXRAMComponentAccessor p_componentAccessor) {
        // no dependencies
    }

    @Override
    protected boolean startService(final DXRAMContext.EngineSettings p_engineEngineSettings) {
        return true;
    }

    @Override
    protected boolean shutdownService() {
        return true;
    }
}

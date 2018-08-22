package de.hhu.bsinfo.dxram.app;

import com.google.gson.annotations.Expose;
import de.hhu.bsinfo.dxram.engine.AbstractDXRAMServiceConfig;
import de.hhu.bsinfo.dxram.engine.DXRAMContext;

/**
 * Config for the ApplicationService
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 24.05.2017
 */
public class ApplicationServiceConfig extends AbstractDXRAMServiceConfig {

    @Expose
    private boolean m_autoStart = false;

    public boolean isAutostartEnabled() {
        return m_autoStart;
    }

    /**
     * Constructor
     */
    public ApplicationServiceConfig() {
        super(ApplicationService.class, false, true);
    }

    @Override
    protected boolean verify(final DXRAMContext.Config p_config) {
        return true;
    }
}

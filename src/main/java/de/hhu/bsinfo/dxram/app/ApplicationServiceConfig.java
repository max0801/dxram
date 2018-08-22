package de.hhu.bsinfo.dxram.app;

import com.google.gson.annotations.Expose;
import de.hhu.bsinfo.dxram.engine.AbstractDXRAMServiceConfig;
import de.hhu.bsinfo.dxram.engine.DXRAMContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Config for the ApplicationService
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 24.05.2017
 */
public class ApplicationServiceConfig extends AbstractDXRAMServiceConfig {

    @Expose
    private List<String> m_autoStart = Arrays.asList("TerminalServer");

    public boolean isAutostartEnabled(String p_name) {
        return m_autoStart.contains(p_name);
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

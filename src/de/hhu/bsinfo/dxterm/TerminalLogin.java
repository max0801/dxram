/*
 * Copyright (C) 2017 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science, Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxterm;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

import de.hhu.bsinfo.utils.NodeID;

public class TerminalLogin implements Serializable {
    private byte m_sessionId = -1;
    private short m_nodeId = NodeID.INVALID_ID;
    private List<String> m_cmdNames = Collections.emptyList();

    public TerminalLogin() {

    }

    public TerminalLogin(final byte p_sessionId, final short p_nodeId, final List<String> p_cmdNames) {
        m_sessionId = p_sessionId;
        m_nodeId = p_nodeId;
        m_cmdNames = p_cmdNames;
    }

    public byte getSessionId() {
        return m_sessionId;
    }

    public short getNodeId() {
        return m_nodeId;
    }

    public List<String> getCmdNames() {
        return m_cmdNames;
    }
}
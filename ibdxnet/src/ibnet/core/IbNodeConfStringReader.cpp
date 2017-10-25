/*
 * Copyright (C) 2017 Heinrich-Heine-Universitaet Duesseldorf,
 * Institute of Computer Science, Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

#include "IbNodeConfStringReader.h"

#include "ibnet/sys/StringUtils.h"

namespace ibnet {
namespace core {

IbNodeConfStringReader::IbNodeConfStringReader(const std::string& str) :
    m_str(str)
{

}

IbNodeConfStringReader::~IbNodeConfStringReader(void)
{

}

IbNodeConf IbNodeConfStringReader::Read(void)
{
    IbNodeConf conf;
    std::vector<std::string> tokens = sys::StringUtils::Split(m_str);

    for (auto& it : tokens) {
        conf.AddEntry(it);
    }

    return conf;
}

}
}
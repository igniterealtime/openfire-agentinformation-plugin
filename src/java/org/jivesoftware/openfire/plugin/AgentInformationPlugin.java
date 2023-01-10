/*
 * Copyright (C) 2023 Ignite Realtime Foundation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.openfire.plugin;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;

import java.io.File;

/**
 * An Openfire plugin that implements XEP-0094: Agent Information.
 *
 * @author Guus der Kinderen, guus@goodbytes.nl
 * @see <a href="https://xmpp.org/extensions/xep-0094.html">XEP-0094: Agent Information</a>
 */
public class AgentInformationPlugin implements Plugin
{
    private IQAgentInformationHandler handler;

    @Override
    public void initializePlugin(PluginManager manager, File pluginDirectory)
    {
        handler = new IQAgentInformationHandler();
        XMPPServer.getInstance().getIQRouter().addHandler(handler);
    }

    @Override
    public void destroyPlugin()
    {
        if (handler != null) {
            XMPPServer.getInstance().getIQRouter().removeHandler(handler);
            handler = null;
        }
    }
}

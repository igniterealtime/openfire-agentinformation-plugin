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

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.xmpp.packet.JID;

/**
 * Representation of an 'agent' as defined in XEP-0094: Agent Information.
 *
 * @author Guus der Kinderen, guus@goodbytes.nl
 */
public class AgentInformation
{
    public static final String NAMESPACE = "jabber:iq:agents";

    private final JID jid;
    private final String name;
    private final String description;
    private final boolean isTransport;
    private final boolean isGroupchat;
    private final String service;
    private final boolean supportsRegister;
    private final boolean supportsSearch;

    public AgentInformation(final JID jid, final String name, final String description, final boolean isTransport, final boolean isGroupchat, final String service, final boolean supportsRegister, final boolean supportsSearch)
    {
        this.jid = jid;
        this.name = name;
        this.description = description;
        this.isTransport = isTransport;
        this.isGroupchat = isGroupchat;
        this.service = service;
        this.supportsRegister = supportsRegister;
        this.supportsSearch = supportsSearch;
    }

    /**
     * Returns an XML element that represents the agent.
     *
     * @return an XML element.
     */
    public Element asElement()
    {
        final Element result = DocumentHelper.createElement(QName.get("agent", NAMESPACE));
        result.addAttribute("jid", jid.toString());
        if (name != null) {
            result.addElement("name").setText(name);
        }
        if (description != null) {
            result.addElement("description").setText(description);
        }
        if (isTransport) {
            result.addElement("transport");
        }
        if (isGroupchat) {
            result.addElement("groupchat");
        }
        if (service != null) {
            result.addElement("service").setText(service);
        }
        if (supportsRegister) {
            result.addElement("register");
        }
        if (supportsSearch) {
            result.addElement("search");
        }

        return result;
    }
}

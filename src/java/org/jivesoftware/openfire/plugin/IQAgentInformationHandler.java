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

import org.dom4j.Element;
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.IQRouter;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.disco.IQDiscoInfoHandler;
import org.jivesoftware.openfire.disco.IQDiscoItemsHandler;
import org.jivesoftware.openfire.disco.ServerFeaturesProvider;
import org.jivesoftware.openfire.handler.IQHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.component.IQResultListener;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An IQ Handler that processes IQ requests sent to the server that contain queries related to the protocol described
 * in XEP-0094: Agent Information.
 *
 * @author Guus der Kinderen, guus@goodbytes.nl
 * @see <a href="https://xmpp.org/extensions/xep-0094.html">XEP-0094: Agent Information</a>
 */
public class IQAgentInformationHandler extends IQHandler implements ServerFeaturesProvider
{
    private static final Logger Log = LoggerFactory.getLogger(IQAgentInformationHandler.class);

    private final IQHandlerInfo info;

    public IQAgentInformationHandler()
    {
        super("Agent Information handler");
        this.info = new IQHandlerInfo("query", "jabber:iq:agents");
    }

    @Override
    public IQ handleIQ(IQ packet) throws UnauthorizedException
    {
        Log.trace("Processing Agent Information request from {}", packet.getFrom());
        if (packet.isResponse()) {
            Log.debug("Silently ignoring IQ response stanza from {}", packet.getFrom());
            return null;
        }

        final IQ reply = IQ.createResultIQ(packet);
        reply.setChildElement(packet.getChildElement().createCopy());

        if (IQ.Type.set == packet.getType()) {
            Log.debug("Returning error to {}: request is of incorrect IQ type.", packet.getFrom());
            reply.setError(PacketError.Condition.feature_not_implemented);
            return reply;
        }

        final Set<AgentInformation> agents = findAgents(packet.getTo(), packet.getFrom());
        for (final AgentInformation agent : agents) {
            reply.getChildElement().add(agent.asElement());
        }
        return reply;
    }

    @Override
    public IQHandlerInfo getInfo()
    {
        return info;
    }

    @Override
    public Iterator<String> getFeatures()
    {
        return Collections.singleton(AgentInformation.NAMESPACE).iterator();
    }

    /**
     * Finds XEP-0094-defined 'agents' of a target entity, by performing XEP-0030-based Service Discovery requests on
     * behalf of the requester on the target entity. The requester address is provided to allow the Service Discovery
     * service to authorize the request, where applicable.
     *
     * @param target The entity for which to return agent information
     * @param requester The entity that requests agent information of an entity.
     * @return A collection of Agent Information entities.
     */
    public Set<AgentInformation> findAgents(final JID target, final JID requester)
    {
        Log.trace("Find agents of {} for {}", target, requester);
        final Set<AgentInformation> results = new HashSet<>();

        // Use Service Discovery to identify all items that are potential agents.
        final Collection<Element> itemElements = getDiscoItems(target, requester);

        for (final Element itemElement : itemElements) {
            final String jidValue = itemElement.attributeValue("jid");
            final JID jid;
            try {
                jid = new JID(jidValue);
            } catch (IllegalArgumentException e) {
                Log.debug("Silently ignoring a service discovery item of entity '{}' that has an invalid JID value: {}", target, jidValue);
                continue;
            }
            final String name = itemElement.attributeValue("name");

            // For each potential agent, use Service Discovery to identify features that would qualify the candidate as an actual agent.
            final Element infoElement = getDiscoInfo(jid, requester);

            final String description = getDescription(infoElement);
            final boolean isTransport = isCategory(infoElement, "gateway");
            final boolean isGroupchat = isCategory(infoElement, "conference");
            final String service;
            if (isTransport) {
                service = getGatewayType(infoElement);
            } else if (isUserDirectory(infoElement)) {
                service = "jud";
            } else {
                // The XEP defines that this value holds 'private' or 'public' for a conference service. Modern conference services do not have such an attribute.
                service = null;
            }
            final boolean supportsRegister = supportsFeature(infoElement, "jabber:iq:register");
            final boolean supportsSearch = supportsFeature(infoElement, "jabber:iq:search");

            final AgentInformation agent = new AgentInformation(jid, name, description, isTransport, isGroupchat, service, supportsRegister, supportsSearch);
            results.add(agent);
        }
        return results;
    }

    public Collection<Element> getDiscoItems(final JID target, final JID requester)
    {
        Log.trace("Perform disco#items request on {} on behalf of {}", target, requester);

        final IQ itemsRequest = new IQ(IQ.Type.get);
        itemsRequest.setTo(target);
        itemsRequest.setFrom(requester);
        itemsRequest.setChildElement("query", IQDiscoItemsHandler.NAMESPACE_DISCO_ITEMS);

        final IQ itemsResponse;
        if (XMPPServer.getInstance().getServerInfo().getXMPPDomain().equals(target.toString()) || sessionManager.getComponentSession(target.getDomain()) == null) {
            itemsResponse = XMPPServer.getInstance().getIQDiscoItemsHandler().handleIQ(itemsRequest);
        } else {
            itemsResponse = queryExternal(itemsRequest);
        }

        if (itemsResponse == null) {
            Log.debug("disco#info request was not responded to by: {}", target);
            return Collections.emptySet();
        }

        if (itemsResponse.getError() != null) {
            Log.debug("disco#items request was responded to with an error: {}", itemsResponse.getError().toXML());
            return Collections.emptySet();
        }
        final Element childElement = itemsResponse.getChildElement();
        if (childElement == null || !"query".equals(childElement.getName()) || !IQDiscoItemsHandler.NAMESPACE_DISCO_ITEMS.equals(childElement.getNamespaceURI())) {
            Log.debug("disco#items request was responded to using an unexpected or missing child element: {}", childElement == null ? "(null)" : childElement.asXML());
            return Collections.emptySet();
        }

        return childElement.elements("item");
    }

    public Element getDiscoInfo(final JID target, final JID requester)
    {
        Log.trace("Perform disco#info request on {} on behalf of {}", target, requester);

        final IQ infoRequest = new IQ(IQ.Type.get);
        infoRequest.setTo(target);
        infoRequest.setFrom(requester);
        infoRequest.setChildElement("query", IQDiscoInfoHandler.NAMESPACE_DISCO_INFO);

        // Obtain an IQ response. For internal components, we can short-cut through the local handler. For external components, perform an actual XMPP query.
        final IQ infoResponse;
        if (XMPPServer.getInstance().getServerInfo().getXMPPDomain().equals(target.toString()) || sessionManager.getComponentSession(target.getDomain()) == null) {
            infoResponse = XMPPServer.getInstance().getIQDiscoItemsHandler().handleIQ(infoRequest);
        } else {
            infoResponse = queryExternal(infoRequest);
        }

        if (infoResponse == null) {
            Log.debug("disco#info request was not responded to by: {}", target);
            return null;
        }

        if (infoResponse.getError() != null) {
            Log.debug("disco#info request was responded to with an error: {}", infoResponse.getError().toXML());
            return null;
        }

        final Element childElement = infoResponse.getChildElement();
        if (childElement == null || !"query".equals(childElement.getName()) || !IQDiscoInfoHandler.NAMESPACE_DISCO_INFO.equals(childElement.getNamespaceURI())) {
            Log.debug("disco#info request was responded to using an unexpected or missing child element: {}", childElement == null ? "(null)" : childElement.asXML());
            return null;
        }
        return childElement;
    }

    /**
     * Sends an IQ request and blocks for the response to be returned, or a timeout occurs.
     *
     * @param request The IQ request
     * @return the IQ response, or null.
     */
    public static IQ queryExternal(final IQ request)
    {
        if (!request.isRequest()) {
            throw new IllegalArgumentException("Argument 'request' must be an IQ request (but was not).");
        }
        Log.trace("Querying external entity: {}", request.getTo());
        final LinkedBlockingQueue<IQ> answer = new LinkedBlockingQueue<>(8);
        final IQRouter iqRouter = XMPPServer.getInstance().getIQRouter();
        iqRouter.addIQResultListener(request.getID(), new IQResultListener() {
            public void receivedAnswer(IQ packet) {
                answer.offer(packet);
            }

            public void answerTimeout(String packetId) {
                Log.warn("An answer to a previously sent IQ stanza was never received. Target: {}", request.getTo());
            }
        });

        iqRouter.route(request);
        try {
            return answer.poll(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
        }
        return null;
    }

    public static boolean isCategory(final Element discoInfoElement, final String categoryName)
    {
        if (discoInfoElement == null) {
            return false;
        }

        boolean result = false;
        for (final Element identityElement : discoInfoElement.elements("identity")) {
            if (categoryName.equals(identityElement.attributeValue("category"))) {
                result = true;
                break;
            }
        }
        Log.trace("Disco#info {} define an identify of category {}", result ? "does" : "doesn't", categoryName);
        return result;
    }

    public static boolean supportsFeature(final Element discoInfoElement, final String featureName)
    {
        if (discoInfoElement == null) {
            return false;
        }

        boolean result = false;
        for (final Element identityElement : discoInfoElement.elements("feature")) {
            if (featureName.equals(identityElement.attributeValue("var"))) {
                result = true;
                break;
            }
        }
        Log.trace("Disco#info {} define feature {}", result ? "does" : "doesn't", featureName);
        return result;
    }


    public static String getGatewayType(final Element discoInfoElement)
    {
        if (discoInfoElement == null) {
            return null;
        }

        for (final Element identityElement : discoInfoElement.elements("identity")) {
            if ("gateway".equals(identityElement.attributeValue("category"))) {
                final String result = identityElement.attributeValue("type");
                Log.trace("Disco#info defines a transport of type {}", result);
                return result;
            }
        }

        Log.trace("Disco#info does not define a transport.");
        return null;
    }

    public static boolean isUserDirectory(final Element discoInfoElement)
    {
        if (discoInfoElement == null) {
            return false;
        }

        boolean result = false;
        for (final Element identityElement : discoInfoElement.elements("identity")) {
            if ("directory".equals(identityElement.attributeValue("category"))) {
                if ("user".equals(identityElement.attributeValue("type"))) {
                    result = true;
                    break;
                }
            }
        }

        Log.trace("Disco#info {} define a user directory", result ? "does" : "doesn't");
        return result;
    }

    /**
     * Returns the first human-readable description of a disco#info identity, as defined in the official registry of
     * values for the 'category' and 'type' attributes of the <identity/> element within the
     * 'http://jabber.org/protocol/disco#info' namespace (see XEP-0030: Service Discovery), as registered with the XMPP
     * Registrar.
     *
     * Updated until revision 2021-10-06 of the Registry.
     *
     * @param discoInfoElement Element from which to take an identity
     * @return A human readable description.
     * @see <a href="https://xmpp.org/registrar/disco-categories.html">Official Registry</a>
     */
    public static String getDescription(final Element discoInfoElement) {
        if (discoInfoElement == null) {
            return null;
        }

        for (final Element identityElement : discoInfoElement.elements("identity")) {
            final String category = identityElement.attributeValue("category");
            final String type = identityElement.attributeValue("type");
            if (category == null || type == null) {
                continue;
            }

            switch (category) {
                case "account":
                    switch (type) {
                        case "admin": return "The user@host is an administrative account";
                        case "anonymous": return "The user@host is a \"guest\" account that allows anonymous login by any user";
                        case "registered": return "The user@host is a registered or provisioned account associated with a particular non-administrative user";
                        default: return null;
                    }
                case "auth":
                    switch (type) {
                        case "cert": return "A server component that authenticates based on external certificates";
                        case "generic": return "A server authentication component other than one of the registered types";
                        case "ldap": return "A server component that authenticates against an LDAP database";
                        case "ntlm": return "A server component that authenticates against an NT domain";
                        case "pam": return "A server component that authenticates against a PAM system";
                        case "radius": return "A server component that authenticates against a Radius system";
                        default: return null;
                    }
                case "authz":
                    switch (type) {
                        case "ephemeral": return "An authorization service that provides ephemeral identities.";
                        default: return null;
                    }
                case "automation":
                    switch (type) {
                        case "command-list": return "The node for a list of commands; valid only for the node \"http://jabber.org/protocol/commands\"";
                        case "command-node": return "A node for a specific command; the \"node\" attribute uniquely identifies the command";
                        case "rpc": return "An entity that supports Jabber-RPC.";
                        case "soap": return "An entity that supports the SOAP XMPP Binding.";
                        case "translation": return "An entity that provides automated translation services.";
                        default: return null;
                    }
                case "client":
                    switch (type) {
                        case "bot": return "An automated client that is not controlled by a human user";
                        case "console": return "Minimal non-GUI client used on dumb terminals or text-only screens";
                        case "game": return "A client running on a gaming console";
                        case "handheld": return "A client running on a PDA, RIM device, or other handheld";
                        case "pc": return "Standard full-GUI client used on desktops and laptops";
                        case "phone": return "A client running on a mobile phone or other telephony device";
                        case "sms": return "A client that is not actually using an instant messaging client; however, messages sent to this contact will be delivered as Short Message Service (SMS) messages";
                        case "tablet": return "A client running on a touchscreen device larger than a smartphone and without a physical keyboard permanently attached to it.";
                        case "web": return "A client operated from within a web browser";
                        default: return null;
                    }
                case "collaboration":
                    switch (type) {
                        case "whiteboard": return "Multi-user whiteboarding service";
                        default: return null;
                    }
                case "component":
                    switch (type) {
                        case "archive": return "A server component that archives traffic";
                        case "c2s": return "A server component that handles client connections";
                        case "generic": return "A server component other than one of the registered types";
                        case "load": return "A server component that handles load balancing";
                        case "log": return "A server component that logs server information";
                        case "presence": return "A server component that provides presence information";
                        case "router": return "A server component that handles core routing logic";
                        case "s2s": return "A server component that handles server connections";
                        case "sm": return "A server component that manages user sessions";
                        case "stats": return "A server component that provides server statistics";
                        default: return null;
                    }
                case "conference":
                    switch (type) {
                        case "irc": return "Internet Relay Chat service";
                        case "text": return "Text conferencing service";
                        default: return null;
                    }
                case "directory":
                    switch (type) {
                        case "chatroom": return "A directory of chatrooms";
                        case "group": return "A directory that provides shared roster groups";
                        case "user": return "A directory of end users (e.g., JUD)";
                        case "waitinglist": return "A directory of waiting list entries";
                        default: return null;
                    }
                case "gateway":
                    switch (type) {
                        case "aim": return "Gateway to AOL Instant Messenger";
                        case "facebook": return "Gateway to the Facebook IM service";
                        case "gadu-gadu": return "Gateway to the Gadu-Gadu IM service";
                        case "http-ws": return "Gateway that provides HTTP Web Services access";
                        case "icq": return "Gateway to ICQ";
                        case "irc": return "Gateway to IRC";
                        case "lcs": return "Gateway to Microsoft Live Communications Server";
                        case "mrim": return "Gateway to the mail.ru IM service";
                        case "msn": return "Gateway to MSN Messenger";
                        case "myspaceim": return "Gateway to the MySpace IM service";
                        case "ocs": return "Gateway to Microsoft Office Communications Server";
                        case "pstn": return "Gateway to the Public Switched Telephone Network (PSTN)";
                        case "qq": return "Gateway to the QQ IM service";
                        case "sametime": return "Gateway to IBM Lotus Sametime";
                        case "simple": return "Gateway to SIP for Instant Messaging and Presence Leveraging Extensions (SIMPLE)";
                        case "skype": return "Gateway to the Skype service";
                        case "sms": return "Gateway to Short Message Service";
                        case "smtp": return "Gateway to the SMTP (email) network";
                        case "telegram": return "Gateway to the Telegram IM service";
                        case "tlen": return "Gateway to the Tlen IM service";
                        case "xfire": return "Gateway to the Xfire gaming and IM service";
                        case "xmpp": return "Gateway to another XMPP service (NOT via native server-to-server communication)";
                        case "yahoo": return "Gateway to Yahoo! Instant Messenger";

                        default: return null;
                    }
                case "headline":
                    switch (type) {
                        case "newmail": return "Service that notifies a user of new email messages.";
                        case "rss": return "RSS notification service.";
                        case "weather": return "Service that provides weather alerts.";
                        default: return null;
                    }
                case "hierarchy":
                    switch (type) {
                        case "branch": return "A service discovery node that contains further nodes in the hierarchy.";
                        case "leaf": return "A service discovery node that does not contain further nodes in the hierarchy.";
                        default: return null;
                    }
                case "proxy":
                    switch (type) {
                        case "bytestreams": return "SOCKS5 bytestreams proxy service";
                        default: return null;
                    }
                case "pubsub":
                    switch (type) {
                        case "collection": return "A pubsub node of the \"collection\" type.";
                        case "leaf": return "A pubsub node of the \"leaf\" type.";
                        case "pep": return "A personal eventing service that supports the publish-subscribe subset defined in XEP-0163.";
                        case "service": return "A pubsub service that supports the functionality defined in XEP-0060.";
                        default: return null;
                    }
                case "server":
                    switch (type) {
                        case "im": return "Standard Jabber/XMPP server used for instant messaging and presence";
                        default: return null;
                    }
                case "store":
                    switch (type) {
                        case "berkeley": return "A server component that stores data in a Berkeley database";
                        case "file": return "A server component that stores data on the file system";
                        case "generic": return "A server data storage component other than one of the registered types";
                        case "ldap": return "A server component that stores data in an LDAP database";
                        case "mysql": return "A server component that stores data in a MySQL database";
                        case "oracle": return "A server component that stores data in an Oracle database";
                        case "postgres": return "A server component that stores data in a PostgreSQL database";
                        default: return null;
                    }
            }
        }

        return null;
    }
}

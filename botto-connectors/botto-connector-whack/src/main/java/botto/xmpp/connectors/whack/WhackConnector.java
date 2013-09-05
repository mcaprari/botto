package botto.xmpp.connectors.whack;

import botto.xmpp.botto.xmpp.connector.Connector;
import botto.xmpp.botto.xmpp.connector.BotConnection;
import botto.xmpp.botto.xmpp.connector.ConnectorException;
import org.jivesoftware.whack.ExternalComponentManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.component.ComponentException;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkNotNull;

public class WhackConnector extends Connector<WhackConnectorConfiguration> {

    private static final Logger Log = LoggerFactory.getLogger(WhackConnector.class);

    private final ExternalComponentManager manager;

    private final Map<String, WhackBotComponent> components = new ConcurrentHashMap<String, WhackBotComponent>();

    public WhackConnector(WhackConnectorConfiguration configuration) {
        super(configuration);
        checkNotNull(configuration);

        // TODO the connector should be configured with host, port, domain, secret and a map subdomain -> password
        manager = new ExternalComponentManager(configuration.getHost(), configuration.getPort());
    }

    @Override
    public BotConnection createConnection(JID address) throws ConnectorException {
        Log.debug("Creating Whack connection for {}", address);
        checkNotNull(address, "The address must not be null");
        verifyAddress(address);

        // get subdomain of bot
        String subdomain = getSubdomain(address);
        WhackBotComponent component = components.get(subdomain);
        if (component == null) {
            try {
                component = createComponent(subdomain, getConfiguration().getSecret(subdomain));
            } catch (ComponentException e) {
                throw new ConnectorException("Error while creating component for subdomain " + subdomain, e);
            }

        }

        WhackBotConnection connection = new WhackBotConnection(this, component, address);
        component.addConnection(connection);
        return connection;
    }

    @Override
    public void removeConnection(BotConnection connection) throws ConnectorException {
        checkNotNull(connection);
        if (!(connection instanceof WhackBotConnection)) {
            throw new ConnectorException(new IllegalArgumentException("Can only remove connections of type WhackBotConection"));
        }

        for(WhackBotComponent component : components.values()) {
            component.removeConnection((WhackBotConnection)connection);
            if (component.isEmpty()) {
                removeComponent(component);
            }
            return;
        }
    }

    private WhackBotComponent createComponent(String subdomain, String secret) throws ComponentException {
        checkNotNull(subdomain);
        checkNotNull(secret);

        WhackBotComponent component = new WhackBotComponent(this, subdomain);
        manager.setSecretKey(subdomain, secret);
        manager.setMultipleAllowed(subdomain, true);
        manager.removeComponent(subdomain);
        manager.addComponent(subdomain, component);
        component.setConnected(true);
        Log.info("Components Connected");

        return component;
    }

    private String getSubdomain(JID address) {
        return address.getDomain().substring(0, address.getDomain().indexOf("."));
    }

    private void verifyAddress(JID address) {
        // TODO: enforce address has subdomain and matches domain for this connector
    }

    private void removeComponent(WhackBotComponent component) throws ConnectorException {
        try {
            manager.removeComponent(component.getSubdomain());
        } catch (ComponentException e) {
            throw new ConnectorException("Exception while trying to remove component " + component, e);
        }
    }

    @Override
    public void doStart() throws ConnectorException {

    }

    @Override
    public void doStop() throws ConnectorException {

    }

    @Override
    public void doSend(BotConnection connection, Packet packet) {
        // TODO: cast? really?
        WhackBotConnection conn = (WhackBotConnection)connection;
        conn.send(packet);
    }

    public void receiveFromComponent(WhackBotConnection connection, Packet packet) throws ConnectorException {
        receive(connection, packet);
    }
}
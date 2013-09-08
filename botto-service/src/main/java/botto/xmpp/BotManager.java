package botto.xmpp;

import botto.xmpp.annotations.PacketOutput;
import botto.xmpp.botto.xmpp.connector.*;

import botto.xmpp.service.dispatcher.ListenableConfirmation;

import com.google.common.util.concurrent.*;
import net.caprazzi.reusables.common.FormattedRuntimeException;
import net.caprazzi.reusables.common.Managed;


import net.caprazzi.reusables.threading.ExecutorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BotManager implements Managed {

    private final Logger Log = LoggerFactory.getLogger(BotManager.class);

    private final ChannelRegistry channels = new ChannelRegistry();

    private Map<ConnectorId, Connector> connectors = new HashMap<ConnectorId, Connector>();

    private final AtomicInteger connectorCount = new AtomicInteger();

    // executes connector.openChannel, connector.closeChannel, connector.send, bot.receive
    private final ListeningExecutorService executor = MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());

    private BotManager() { }

    public static BotManager create() {
        return new BotManager();
    }

    // TODO: if the manager has already started, also start the connector
    public synchronized ConnectorId registerConnector(final Connector connector) throws Exception {

        if(connectors.containsValue(connector)) {
            // TODO: any way to not duplicate the error message?
            Log.error("Could not register connector {} beacause it has already been registered");
            throw new ConnectorException("Could not register connector {} because it has already been registered", connector);
        }

        final ConnectorId connectorId = new ConnectorId(connectorCount.getAndIncrement(), connector.getClass(), connector.getName());
        connectors.put(connectorId, connector);

        connector.addChannelListener(new ConnectorChannelListener(connector, connectorId));

        Log.info("Registered connector {} with id {}", connector, connectorId);

        return connectorId;
    }

    private void receive(final Connector connector, final Channel channel, final Packet packet, final Meters.ConnectorMetrics meter) {
        // async deliver to bot
        ListenableFuture<Packet> execute = deliverToBot(packet, channels.getBot(channel), meter);
        Futures.addCallback(execute, new FutureCallback<Packet>() {
            public void onSuccess(Packet packet) {
                if (packet != null) {
                    meter.countBotResponse();
                    send(connector, channel, packet);
                }
            }
            public void onFailure(Throwable t) {
                Log.error("Error while delivering packet {} to {} on {}", packet, channel, connector);
                meter.countDeliveryError();
            }
        });
    }

    public synchronized void removeConnector(ConnectorId connectorId) {
        // TODO: implement removal code
        // TODO: remove my listeners
        // TODO: should also un-register meters for removed connectors?
        // TODO: stop it if started
    }

    public ListenableConfirmation addBot(final ConnectorId connectorId, final JID address, final AbstractBot bot) {

        final ListenableConfirmation confirmation = new ListenableConfirmation();
        final Connector connector = connectors.get(connectorId);
        if (connector == null) {
            return ListenableConfirmation.failed(new ConnectorException("Connector not found for {}", connectorId));
        }

        // asynchronously open channel
        ListenableFuture<Channel> openChannel =  openChannel(connector, address);
        Futures.addCallback(openChannel, new FutureCallback<Channel>() {
            @Override
            public void onSuccess(final Channel channel) {
                channels.addChannel(channel, bot);
                bot.setPacketOutput(new PacketOutput() {
                    @Override
                    public void send(Packet packet) {
                        BotManager.this.send(connector, channel, packet);
                    }
                });
                confirmation.setSuccess();
            }

            @Override
            public void onFailure(Throwable t) {
                // TODO: log
                confirmation.setFailure(new ConnectorException(t, "Failed to open channel {}, {}, {}", connectorId, address, bot));
            }
        });

        return confirmation;
    }

    public ListenableConfirmation removeBot(final ConnectorId connectorId, final JID address, final AbstractBot bot) {

        final ListenableConfirmation confirmation = new ListenableConfirmation();
        final Connector connector = connectors.get(connectorId);
        if (connector == null) {
            return ListenableConfirmation.failed(new ConnectorException("Connector not found for {}", connectorId));
        }

        final Channel channel = channels.getChannel(address);
        if (channel == null) {
            return ListenableConfirmation.failed(new ConnectorException("Channel not found for ...."));
        }

        // asynchronously close channel
        ListenableFuture<Channel> closeChannel = closeChannel(connector, channel);
        Futures.addCallback(closeChannel, new FutureCallback<Channel>() {
            public void onSuccess(Channel channel) {
                // TODO: should remove the channel from the registry anyway?
                channels.removeChannel(channel);
                confirmation.setSuccess();
            }
            public void onFailure(Throwable t) {
                confirmation.setFailure(new ConnectorException(t, "Failed to close channel {}, {}, {}", connectorId, address, bot));
            }
        });

        return confirmation;
    }

    private ListenableFuture<Channel> openChannel(final Connector connector, final JID address) {
        return executor.submit(new Callable<Channel>() {
            @Override
            public Channel call() throws Exception {
                return connector.openChannel(address);
            }
        });
    }

    private ListenableFuture<Packet> deliverToBot(final Packet packet, final AbstractBot bot, final Meters.ConnectorMetrics metrics) {
        return executor.submit(new Callable<Packet>() {
            public Packet call() throws Exception {
                long start = metrics.startBotDelivery();
                try {
                    return bot.receive(packet);
                } catch (Exception ex) {
                    throw new FormattedRuntimeException(ex, "Failed to deliver packet {} to bot {}", packet, bot);
                }
                finally {
                    metrics.timeBotDelivery(start);
                }
            }
        });
    }

    private ListenableFuture<Channel> closeChannel(final Connector connector, final Channel channel) {
        return executor.submit(new Callable<Channel>() {
            public Channel call() throws Exception {
                connector.closeChannel(channel);
                return channel;
            }
        });
    }

    private void send(final Connector connector, final Channel channel, final Packet packet) {
        ListenableFuture<?> send = executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    // TODO: mind that not all connectors are thread safe
                    connector.send(channel, packet);
                } catch (ConnectorException e) {
                    throw new RuntimeException("Exception while submitting packet to channel " + channel + ": " + packet, e);
                }
            }
        });

        Futures.addCallback(send, new FutureCallback<Object>() {
            @Override
            public void onSuccess(Object result) {
                // packet sent to connector OK
            }

            @Override
            public void onFailure(Throwable t) {
                // TODO: count failure
                // TODO: send error to bot
                // channels.getBot(channel).receiveError(packet, t);
            }
        });
    }


    @Override
    public void start() {
        // TODO: start all non-started connectors
    }

    @Override
    public void stop() {
        // TODO: stop all started connectors
        ExecutorUtils.shutdown(Log, executor, 2, TimeUnit.SECONDS);
    }

    private class ConnectorChannelListener implements ChannelListener {

        private final Meters.ConnectorMetrics meter;
        private final Connector connector;

        public ConnectorChannelListener(Connector connector, ConnectorId connectorId) {
            this.connector = connector;
            this.meter = Meters.connectors.forConnector(connectorId);
        }

        @Override
        public void onChannelOpen(Channel channel) {
            meter.countOpenChannel();
        }

        @Override
        public void onChannelClose(Channel channel) {
            meter.countClosedChannel();
        }

        @Override
        public void onIncomingPacket(Channel channel, Packet packet) {
            meter.countIncoming(packet);
            receive(connector, channel, packet, this.meter);
        }

        @Override
        public void onOutgoingPacket(Channel channel, Packet packet) {
            meter.countOutgoing(packet);
        }
    }

}

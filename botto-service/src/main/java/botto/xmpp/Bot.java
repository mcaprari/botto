package botto.xmpp;

import org.xmpp.packet.Packet;

public interface Bot {
    public Packet receive(Packet packet);
}


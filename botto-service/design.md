Botto Design Notes
==================

## Botto Engine

### Connector

Implementations of Connector provide a specific connection method (xmpp client, xmpp component, xmpp connection manager)
using a specific library (Smack, Whack, ConnectionManager). Connectors generate and manage BotConnection objects.

### BotConnection

A BotConnection provides a binding between an AbstractBot, a JID and a Connector

### ConnectionManager

ConnectionManager holds a reference to all existing BotConnection and uses Connectors to create and destroy BotConnections.
Connectors also have the responsibility to update a Bot's ConnectionInfo.

### Dispatcher

Dispatcher collects all incoming Packets from all Connectors and delivers each incoming Packet to the correct AbstractBot.
Dispatcher also collects all Packets generated by Bots and sends them out using the correct BotConnection

## Botto Service

Botto service is the frontend for the botto engine, providing semantics for configuring and controlling the Botto Engine.
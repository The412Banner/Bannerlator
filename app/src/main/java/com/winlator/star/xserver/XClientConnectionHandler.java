package com.winlator.star.xserver;

import com.winlator.star.xconnector.Client;
import com.winlator.star.xconnector.ConnectionHandler;

public class XClientConnectionHandler implements ConnectionHandler {
    private final XServer xServer;

    public XClientConnectionHandler(XServer xServer) {
        this.xServer = xServer;
    }

    @Override
    public void handleNewConnection(Client client) {
        client.createIOStreams();
        client.setTag(new XClient(xServer, client.getInputStream(), client.getOutputStream()));
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        ((XClient)client.getTag()).freeResources();
    }
}
package com.winlator.star.xenvironment.components;

import com.winlator.star.xenvironment.EnvironmentComponent;
import com.winlator.star.xconnector.XConnectorEpoll;
import com.winlator.star.xconnector.UnixSocketConfig;
import com.winlator.star.xserver.XClientConnectionHandler;
import com.winlator.star.xserver.XClientRequestHandler;
import com.winlator.star.xserver.XServer;

public class XServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;

    public XServerComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, new XClientConnectionHandler(xServer), new XClientRequestHandler());
        connector.setInitialInputBufferCapacity(262144);
        connector.setCanReceiveAncillaryMessages(true);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }

    public XServer getXServer() {
        return xServer;
    }
}

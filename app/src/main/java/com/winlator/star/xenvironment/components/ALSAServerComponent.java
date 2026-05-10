package com.winlator.star.xenvironment.components;

import com.winlator.star.alsaserver.ALSAClientConnectionHandler;
import com.winlator.star.alsaserver.ALSARequestHandler;
import com.winlator.star.xconnector.UnixSocketConfig;
import com.winlator.star.xconnector.XConnectorEpoll;
import com.winlator.star.xenvironment.EnvironmentComponent;

public class ALSAServerComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    private final UnixSocketConfig socketConfig;

    public ALSAServerComponent(UnixSocketConfig socketConfig) {
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, new ALSAClientConnectionHandler(), new ALSARequestHandler());
        connector.setMultithreadedClients(true);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }
}

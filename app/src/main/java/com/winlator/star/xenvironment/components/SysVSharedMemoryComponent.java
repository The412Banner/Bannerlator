package com.winlator.star.xenvironment.components;

import com.winlator.star.sysvshm.SysVSHMConnectionHandler;
import com.winlator.star.sysvshm.SysVSHMRequestHandler;
import com.winlator.star.sysvshm.SysVSharedMemory;
import com.winlator.star.xconnector.UnixSocketConfig;
import com.winlator.star.xconnector.XConnectorEpoll;
import com.winlator.star.xenvironment.EnvironmentComponent;
import com.winlator.star.xserver.SHMSegmentManager;
import com.winlator.star.xserver.XServer;

public class SysVSharedMemoryComponent extends EnvironmentComponent {
    private XConnectorEpoll connector;
    public final UnixSocketConfig socketConfig;
    private SysVSharedMemory sysVSharedMemory;
    private final XServer xServer;

    public SysVSharedMemoryComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        sysVSharedMemory = new SysVSharedMemory();
        connector = new XConnectorEpoll(socketConfig, new SysVSHMConnectionHandler(sysVSharedMemory), new SysVSHMRequestHandler());
        connector.start();

        xServer.setSHMSegmentManager(new SHMSegmentManager(sysVSharedMemory));
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.stop();
            connector = null;
        }

        sysVSharedMemory.deleteAll();
    }
}

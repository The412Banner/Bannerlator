package com.winlator.star.xserver.extensions;

import com.winlator.star.xconnector.XInputStream;
import com.winlator.star.xconnector.XOutputStream;
import com.winlator.star.xserver.XClient;
import com.winlator.star.xserver.errors.XRequestError;

import java.io.IOException;

public interface Extension {
    String getName();

    byte getMajorOpcode();

    byte getFirstErrorId();

    byte getFirstEventId();

    void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError;
}

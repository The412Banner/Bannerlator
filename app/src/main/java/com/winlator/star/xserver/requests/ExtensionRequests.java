package com.winlator.star.xserver.requests;

import static com.winlator.star.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.star.xconnector.XInputStream;
import com.winlator.star.xconnector.XOutputStream;
import com.winlator.star.xconnector.XStreamLock;
import com.winlator.star.xserver.XClient;
import com.winlator.star.xserver.errors.XRequestError;
import com.winlator.star.xserver.extensions.Extension;

import java.io.IOException;

public abstract class ExtensionRequests {
    public static void queryExtension(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        short length = inputStream.readShort();
        inputStream.skip(2);
        String name = inputStream.readString8(length);
        Extension extension = client.xServer.getExtensionByName(name);
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);

            if (extension != null) {
                outputStream.writeByte((byte)1);
                outputStream.writeByte(extension.getMajorOpcode());
                outputStream.writeByte(extension.getFirstEventId());
                outputStream.writeByte(extension.getFirstErrorId());
                outputStream.writePad(20);
            }
            else {
                outputStream.writeByte((byte)0);
                outputStream.writePad(23);
            }
        }
    }
}

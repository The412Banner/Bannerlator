package com.winlator.star.xserver.requests;

import static com.winlator.star.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.star.xconnector.XInputStream;
import com.winlator.star.xconnector.XOutputStream;
import com.winlator.star.xconnector.XStreamLock;
import com.winlator.star.xserver.Atom;
import com.winlator.star.xserver.XClient;
import com.winlator.star.xserver.errors.BadAtom;
import com.winlator.star.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class AtomRequests {
    public static void internAtom(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        boolean onlyIfExists = client.getRequestData() == 1;
        short length = inputStream.readShort();
        inputStream.skip(2);
        String name = inputStream.readString8(length);
        int id = onlyIfExists ? Atom.getId(name) : Atom.internAtom(name);
        if (id < 0) throw new BadAtom(id);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(id);
            outputStream.writePad(20);
        }
    }
    public static void getAtomName(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int id = inputStream.readInt();
        // Atom.getName does atoms.get(id); id==0 returns null (→ NPE) and id>=size throws
        // IndexOutOfBounds, so the old `id < 0` guard let a positive out-of-range atom crash
        // instead of returning BadAtom. isValid covers both (id > 0 && id < size).
        if (!Atom.isValid(id)) throw new BadAtom(id);
        String name = Atom.getName(id);
        short length = (short) name.length();

        // GetAtomName reply (X11 core): after the 4-byte reply-length comes the CARD16 name
        // length, then 22 bytes of pad, then the name. The old code omitted the name-length
        // field and put the pad after the name, frame-shifting every reply and corrupting the
        // client's stream; its reply-length also wrongly counted the fixed 22-byte pad. The
        // reply-length counts only the (padded) name in 4-byte units: writeString8 pads the
        // name to a 4-byte boundary, so that is ceil(n/4) = (n + 3) / 4.
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((length + 3) / 4);
            outputStream.writeShort(length);
            outputStream.writePad(22);
            outputStream.writeString8(name);
        }
    }
}

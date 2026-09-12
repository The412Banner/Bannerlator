package com.winlator.star.xserver.requests;

import com.winlator.star.xserver.Colormap;
import com.winlator.star.xserver.XClient;
import com.winlator.star.xconnector.XInputStream;
import com.winlator.star.xconnector.XOutputStream;

// ERL bug report #7. Extracted into its own class (rather than inlined into
// XClientRequestHandler's switch dispatcher) deliberately — inlining new logic into that
// giant switch risks a dex-verifier register-type conflict that only surfaces as a
// VerifyError at class-load on a real device, passing a normal build step silently.
public class ColormapRequests {
    public static void createColormap(XClient client, XInputStream inputStream, XOutputStream outputStream) {
        int mid = inputStream.readInt();
        int window = inputStream.readInt();
        int visual = inputStream.readInt();
        Colormap colormap = client.xServer.colormapManager.createColormap(mid, window, visual);
        if (colormap != null) client.registerAsOwnerOfResource(colormap);
    }

    public static void freeColormap(XClient client, XInputStream inputStream, XOutputStream outputStream) {
        client.xServer.colormapManager.freeColormap(inputStream.readInt());
    }
}

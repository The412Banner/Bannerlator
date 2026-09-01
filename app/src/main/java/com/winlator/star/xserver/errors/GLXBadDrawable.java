/*
 * Ported from Pipetto-crypto/winlator (branch winlator_bionic),
 * app/src/main/java/com/winlator/cmod/xserver/errors/GLXBadDrawable.java.
 * Original work Copyright (c) 2023 BrunoSX and contributors; MIT License.
 * Adapted for com.winlator.star: package rename.
 * See THIRD-PARTY-LICENSES.md.
 */
package com.winlator.star.xserver.errors;

public class GLXBadDrawable extends XRequestError {
    public GLXBadDrawable(int id) {
        super(GLXError.BASE_ERROR_CODE + 2, id);
    }
}

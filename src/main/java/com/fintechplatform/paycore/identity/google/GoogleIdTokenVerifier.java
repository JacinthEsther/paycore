package com.fintechplatform.paycore.identity.google;

/**
 * Checks that an ID token was issued by Google, for PayCore, and has not
 * expired, and returns its claims.
 */
public interface GoogleIdTokenVerifier {

    /**
     * @throws com.fintechplatform.paycore.identity.exception.InvalidGoogleTokenException
     *         if the token is forged, expired, for another app or malformed
     */
    GoogleIdentity verify(String idToken);
}

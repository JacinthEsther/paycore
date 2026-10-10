package com.fintechplatform.paycore.identity.google;

public interface GoogleIdTokenVerifier {

    GoogleIdentity verify(String idToken);
}

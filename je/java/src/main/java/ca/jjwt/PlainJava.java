package ca.jjwt;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.json.JSONObject;

/*
 * The data is encrypted with the private key, and decrypted when needed with the public key. Or vise verse.
 *
 * Digital signing works oppositely. The data is signed by hashing the message with a hashing algorithm and the sender’s private key.
 *
 * 1. Sender as system hold privateKey (sign and encrypt)
 * 2. Receiver as system hold publicKey (verify sign, and decrpt)
 */
public class PlainJava {

  // make sure both OS clock are sync
  // enable OAuth Server Authentication Handler:
  // http://localhost:4502/system/console/configMgr/com.adobe.granite.oauth.server.auth.impl.OAuth2ServerAuthenticationHandler
  // enable revoke servlet:
  // http://localhost:4502/system/console/configMgr/com.adobe.granite.oauth.server.impl.OAuth2ClientRevocationServlet
  // http://localhost:4502/system/console/configMgr/com.adobe.granite.oauth.server.impl.OAuth2RevocationEndpointServlet
  // http://localhost:4502/libs/granite/oauth/content/tokens.html [to revoke a token]
  // download from AEM: http://localhost:4502/libs/granite/oauth/content/clients.html
  // openssl pkcs12 -in store.p12 -out store.crt.pem -clcerts -nokeys
  // openssl pkcs12 -in store.p12 -passin pass:notasecret -nocerts -nodes -out store.private.key.txt
  public static void main(String[] args) throws Exception {
    JSONObject header = new JSONObject();
    header.put("alg", "RS256");
    header.put("typ", "JWT");
    String encodedHeader = Util.encode(header);

    JSONObject payload = new JSONObject();
    payload.put("iss", "3ple0j4pf8sh2qqp8u3s65v1q5-lkxnuo6k");
    payload.put("aud", "http://localhost:4502/oauth/token");
    payload.put("sub", "admin");
    payload.put("scope", "profile,offline_access"); // ("scope", "content_read")
    payload.put("iat", LocalDateTime.now().toEpochSecond(ZoneOffset.ofHours(-4)));
    payload.put("exp", LocalDateTime.now().plusMinutes(1).toEpochSecond(ZoneOffset.ofHours(-4)));
    String encodedPayload = Util.encode(payload);

    String data = encodedHeader + "." + encodedPayload;
    PrivateKey privateKey = Util.loadFromPrivatePemFile("store.private.key.txt");
    // privateKey = Util.loadPrivateFromP12("store.p12");
    // sign always need to use privatekey
    String sig = Util.signSHA256withRSA(data, privateKey);
    String jwt = data + "." + sig;
    String access_token = Util.getBearerToken(jwt);
    Util.getUserProfile(access_token);

    // verify sig
    Certificate publicCert = Util.loadFromCertPemFile("store.crt.pem");
    PublicKey publicKey = publicCert.getPublicKey();
    System.out.println("verify sig using publicKey: " + Util.verifySignature(publicKey, jwt));

    testEnc_Dec(publicKey, privateKey);
  }

  private static void testEnc_Dec(PublicKey publicKey, PrivateKey privateKey) throws Exception {
    byte[] encryptedData = Util.encrypt(publicKey, "1. hi this is Visruth here".getBytes());
    byte[] decryptedData = Util.decrypt(privateKey, encryptedData);
    System.out.println(new String(decryptedData));

    encryptedData = Util.encrypt(privateKey, "2. hi this is Visruth here".getBytes());
    decryptedData = Util.decrypt(publicKey, encryptedData);
    System.out.println(new String(decryptedData));
  }
}

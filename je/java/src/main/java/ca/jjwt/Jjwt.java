package ca.jjwt;

import java.security.Key;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

/**
 * The data can be encrypted with the private key, and decrypted with the public key. Or vise verse.
 *
 * Digital signing is signed by hashing the message with a hashing algorithm and the sender’s private key.
 *
 * Generate JWT from headerJson, payloadJson, and a secret_key (or private key)
 */
public class Jjwt {
  public static void main(String[] args) throws Exception {

    KeyPair rs256Key = Keys.keyPairFor(SignatureAlgorithm.RS256); // JWA Algorithm (RS256, PS256, RS512, PS512)
    Key hSecretKey = Keys.secretKeyFor(SignatureAlgorithm.HS256); // HMAC-SHA algorithms (HS256, HS384, HS512)

    // enable OAuth Server Authentication Handler:
    // http://localhost:4502/system/console/configMgr/com.adobe.granite.oauth.server.auth.impl.OAuth2ServerAuthenticationHandler
    // download from AEM: http://localhost:4502/libs/granite/oauth/content/clients.html
    // openssl pkcs12 -in store.p12 -out store.crt.pem -clcerts -nokeys
    // openssl pkcs12 -in store.p12 -passin pass:notasecret -nocerts -nodes -out store.private.key.txt
    PrivateKey privateKey = Util.loadFromPrivatePemFile("store.private.key.txt");
    Certificate publicCert = Util.loadFromCertPemFile("store.crt.pem");

    // RSA signing keys must be PrivateKey instances
    String jwt = Jwts.builder().setIssuer("3ple0j4pf8sh2qqp8u3s65v1q5-lkxnuo6k").setAudience("http://localhost:4502/oauth/token").setSubject("admin")
            .claim("scope", "profile,offline_access").claim("iat", LocalDateTime.now().toEpochSecond(ZoneOffset.ofHours(-4)))
            .claim("exp", LocalDateTime.now().plusSeconds(10).toEpochSecond(ZoneOffset.ofHours(-4))).signWith(privateKey, SignatureAlgorithm.RS256)
            .compact();

    System.out.println(jwt);

    Jws<Claims> jwsc = Jwts.parserBuilder().setSigningKey(publicCert.getPublicKey()).build().parseClaimsJws(jwt);

    System.out.println("verify header: " + jwsc.getHeader().toString());
    System.out.println("verify body: " + jwsc.getBody().toString());
    System.out.println("verify sig: " + jwsc.getSignature());

    String access_token = Util.getBearerToken(jwt);

    Util.getUserProfile(access_token);
  }

}

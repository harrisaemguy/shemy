package ca.jjwt;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;

import javax.crypto.Cipher;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Util {
  public static PrivateKey loadFromPrivatePemFile(String filename) throws Exception {
    FileInputStream fis = new FileInputStream(filename);
    List<String> lines = IOUtils.readLines(fis, "utf-8");
    StringBuilder buf = new StringBuilder();
    boolean started = false;
    for (String line : lines) {
      if (line.startsWith("-----BEGIN PRIVATE KEY-----")) {
        started = true;
      } else if (line.startsWith("-----END PRIVATE KEY-----")) {
        break;
      } else if (started) {
        buf.append(line);
      }
    }
    String keyString = buf.toString();
    byte[] decoded = Base64.getDecoder().decode(keyString);
    PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decoded);
    KeyFactory kf = KeyFactory.getInstance("RSA");
    PrivateKey privKey = kf.generatePrivate(keySpec);
    return privKey;
  }

  // Not working with current java
  public static PrivateKey loadPrivateFromP12(String p12filename) throws Exception {
    String KeyPassword = "notasecret";
    File file = new File(p12filename);
    InputStream stream = new FileInputStream(file);
    KeyStore kspkcs12 = KeyStore.getInstance("PKCS12");
    kspkcs12.load(stream, KeyPassword.toCharArray());
    Enumeration<String> e = kspkcs12.aliases();
    String alias = e.nextElement();
    PrivateKey key = (PrivateKey) kspkcs12.getKey(alias, KeyPassword.toCharArray());
    return key;
  }

  public static Certificate loadFromCertPemFile(String filename) throws Exception {
    FileInputStream fis = new FileInputStream(filename);
    List<String> lines = IOUtils.readLines(fis, "utf-8");
    StringBuilder buf = new StringBuilder();
    boolean started = false;
    for (String line : lines) {
      if (line.startsWith("-----BEGIN CERTIFICATE-----")) {
        started = true;
      } else if (line.startsWith("-----END CERTIFICATE-----")) {
        break;
      } else if (started) {
        buf.append(line);
      }
    }

    String keyString = buf.toString();
    byte[] decoded = Base64.getDecoder().decode(keyString);
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    Certificate cert = cf.generateCertificate(new ByteArrayInputStream(decoded));
    return cert;
  }

  public static PublicKey loadFromPublicPemFile(String filename) throws Exception {
    FileInputStream fis = new FileInputStream(filename);
    List<String> lines = IOUtils.readLines(fis, "utf-8");
    StringBuilder buf = new StringBuilder();
    boolean started = false;
    for (String line : lines) {
      if (line.startsWith("-----BEGIN PUBLIC KEY-----")) {
        started = true;
      } else if (line.startsWith("------END PUBLIC KEY-----")) {
        break;
      } else if (started) {
        buf.append(line);
      }
    }

    String keyString = buf.toString();
    System.out.println(keyString);
    byte[] decoded = Base64.getDecoder().decode(keyString);
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decoded);

    return keyFactory.generatePublic(keySpec);
  }

  // PEM is a base-64 encoding mechanism of a DER certificate (txt)
  // DER is binary format to store X.509 certificates and/or PKCS8 private keys
  public static String signSHA256withRSA(String data, PrivateKey privateKey) throws Exception {

    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(privateKey);
    signature.update(data.getBytes("UTF-8"));
    byte[] signedBytes = signature.sign();
    return encode(signedBytes);
  }

  public static boolean verifySignature(PublicKey key, String jwt) throws Exception {
    String[] parts = jwt.split("\\.");
    String sig = parts[2];
    String data = parts[0] + "." + parts[1];

    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initVerify(key);
    signature.update(data.getBytes("UTF-8"));
    return signature.verify(decode(sig));
  }

  private static final String ALGORITHM = "RSA";

  public static byte[] encrypt(Key enckey, byte[] inputData) throws Exception {

    Cipher cipher = Cipher.getInstance(ALGORITHM);
    cipher.init(Cipher.ENCRYPT_MODE, enckey);

    byte[] encryptedBytes = cipher.doFinal(inputData);

    return encryptedBytes;
  }

  public static byte[] decrypt(Key decKey, byte[] inputData) throws Exception {

    Cipher cipher = Cipher.getInstance(ALGORITHM);
    cipher.init(Cipher.DECRYPT_MODE, decKey);

    byte[] decryptedBytes = cipher.doFinal(inputData);

    return decryptedBytes;
  }

  public static String getBearerToken(String jws) throws Exception {

    HttpPost method = new HttpPost("http://localhost:4502/oauth/token");
    ArrayList<NameValuePair> nvs = new ArrayList<NameValuePair>();
    nvs.add(new BasicNameValuePair("assertion", jws));
    nvs.add(new BasicNameValuePair("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer"));
    // nvs.add(new BasicNameValuePair("redirect_uri", "http://localhost:8080"));
    // nvs.add(new BasicNameValuePair("client_id", "3ple0j4pf8sh2qqp8u3s65v1q5-lkxnuo6k"));
    // nvs.add(new BasicNameValuePair("client_secret", "tgfqa3ihnm4a7ah1ratjjvpog6"));
    method.setEntity(new UrlEncodedFormEntity(nvs, "UTF-8"));

    CloseableHttpClient client = HttpClients.createDefault();
    HttpResponse httpResp = client.execute(method);
    if (httpResp.getStatusLine().getStatusCode() == 200) {
      // {"response" : {"success":"true","ticket":"1630411923oP6305Jr1seKaAuz93vddb7qVs2rpF"}}
      HttpEntity entity = httpResp.getEntity();
      String respBody = entity != null ? EntityUtils.toString(entity) : null;
      EntityUtils.consume(entity);
      System.out.println("http resp: " + respBody);

      ObjectNode json = new ObjectMapper().readValue(respBody, ObjectNode.class);

      return json.get("access_token").asText();
    } else {
      System.out.println("http resp: " + httpResp.getStatusLine().toString());
      return "";
    }
  }

  public static ObjectNode getUserProfile(String access_token) throws Exception {

    HttpGet method = new HttpGet("http://localhost:4502/libs/oauth/profile");
    // "http://localhost:4502/content/forms/af/dc-sandbox/helloworld.xml"
    method.addHeader("Authorization", "Bearer " + access_token);

    CloseableHttpClient client = HttpClients.createDefault();
    HttpResponse httpResp = client.execute(method);
    if (httpResp.getStatusLine().getStatusCode() == 200) {

      HttpEntity entity = httpResp.getEntity();
      String respBody = entity != null ? EntityUtils.toString(entity) : null;
      EntityUtils.consume(entity);
      System.out.println("http resp: " + respBody);

      ObjectNode json = new ObjectMapper().readValue(respBody, ObjectNode.class);

      return json;
    } else {
      System.out.println("http resp: " + httpResp.getStatusLine().toString());
      return null;
    }
  }

  public static String encode(JSONObject obj) {
    return encode(obj.toString().getBytes(StandardCharsets.UTF_8));
  }

  public static String encode(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static byte[] decode(String encodedString) {
    return Base64.getUrlDecoder().decode(encodedString);
  }
}

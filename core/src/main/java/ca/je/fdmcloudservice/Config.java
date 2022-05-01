package ca.je.fdmcloudservice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.crypto.CryptoSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component(service = Servlet.class, property = { "sling.servlet.paths=/bin/dc/odata" })
public class Config extends SlingAllMethodsServlet {

  private static final long serialVersionUID = 1L;
  private final Logger log = LoggerFactory.getLogger(getClass());
  private ObjectMapper objectMapper = new ObjectMapper();

  @Reference
  private CryptoSupport cryptoSupport;

  @Reference
  private OdataHelper odataHelper;

  @Override
  protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {
    response.setContentType("application/json");

    // /conf/JE/settings/cloudconfigs/fdm/je-odata-cloud-service/jcr:content
    String ODATA_REST_dspath = request.getParameter("ODATA_REST_dspath");
    ObjectNode tokenInfo = odataHelper.getAccessToken(ODATA_REST_dspath, request.getResourceResolver());
    String rootUrl = tokenInfo.has("rootUrl") ? tokenInfo.get("rootUrl").asText() : "";
    String access_token = tokenInfo.has("access_token") ? tokenInfo.get("access_token").asText() : "";

    response.getWriter().write("{}");

  }

  @Override
  protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws ServletException, IOException {

    response.setContentType("text/plain");
    try {
      String configurationPath = request.getParameter("configurationPath");
      String secret = request.getParameter("secret");
      if (StringUtils.isNotBlank(secret) && StringUtils.isNotBlank(configurationPath) && configurationPath.startsWith("/")) {
        configurationPath += "/jcr:content";
        ObjectNode secretNd = objectMapper.readValue(secret, ObjectNode.class);
        String refresh_token_uri = secretNd.get("refresh_token_uri").asText();
        String grant_type = "password";
        String username = secretNd.get("username").asText();
        String password = secretNd.get("password").asText();
        String client_id = secretNd.get("client_id").asText();
        String resource = secretNd.get("resource").asText();
        String client_secret = secretNd.get("client_secret").asText();

        HttpPost httpPost = new HttpPost(refresh_token_uri);
        httpPost.setHeader("Accept", "application/json");
        ArrayList<NameValuePair> nvs = new ArrayList<NameValuePair>();
        nvs.add(new BasicNameValuePair("grant_type", grant_type));
        nvs.add(new BasicNameValuePair("username", username));
        nvs.add(new BasicNameValuePair("password", password));
        nvs.add(new BasicNameValuePair("client_id", client_id));
        nvs.add(new BasicNameValuePair("resource", resource));
        nvs.add(new BasicNameValuePair("client_secret", client_secret));

        httpPost.setEntity(new UrlEncodedFormEntity(nvs, "UTF-8"));
        HttpResponse httpResp = this.odataHelper.getHttpClient().execute(httpPost);
        String resp = EntityUtils.toString(httpResp.getEntity());
        ObjectNode respNd = objectMapper.readValue(resp, ObjectNode.class);
        String refresh_token = respNd.get("refresh_token").asText();
        EntityUtils.consume(httpResp.getEntity());

        ResourceResolver resourceResolver = request.getResourceResolver();
        Node dsJcr = resourceResolver.getResource(configurationPath).adaptTo(Node.class);
        Iterator<String> fldNames = secretNd.fieldNames();
        while (fldNames.hasNext()) {
          String fldName = fldNames.next();
          String fldValue = secretNd.get(fldName).asText();
          if (fldName.equals("username") || fldName.equals("password")) {
            continue;
          }
          if (fldName.equals("client_secret")) {
            fldValue = this.cryptoSupport.protect(fldValue);
          }

          dsJcr.setProperty(fldName, fldValue);
        }
        dsJcr.setProperty("refresh_token", this.cryptoSupport.protect(refresh_token));
        resourceResolver.commit();
      } else if (StringUtils.isNotBlank(configurationPath) && configurationPath.startsWith("/")) {

        configurationPath += "/jcr:content";
        ResourceResolver resourceResolver = request.getResourceResolver();
        Node dsJcr = resourceResolver.getResource(configurationPath).adaptTo(Node.class);
        ObjectNode result = objectMapper.createObjectNode();
        PropertyIterator pIts = dsJcr.getProperties();
        while (pIts.hasNext()) {
          Property property = pIts.nextProperty();
          if (property.getType() == PropertyType.STRING) {
            result.put(property.getName(), property.getString());
          }
        }

        if (result.has("client_secret")) {
          result.put("client_secret", this.cryptoSupport.unprotect(result.get("client_secret").asText()));
        }

        result.remove("refresh_token");
        result.remove("jcr:createdBy");
        result.remove("cq:lastModifiedBy");
        result.remove("jcr:lastModifiedBy");
        System.err.println(result.toString());
        response.getWriter().write(result.toString());
      }


      response.getWriter().write("Hello World!");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}

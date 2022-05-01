package ca.je.fdmcloudservice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeMap;

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpVersion;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class OdataUtil {

  private final static Logger log = LoggerFactory.getLogger(OdataUtil.class);

  private static void addToken(HttpRequestBase request, String access_token) {
    request.setProtocolVersion(HttpVersion.HTTP_1_1);
    request.setHeader("Accept", "application/json");
    request.setHeader("OData-MaxVersion", "4.0");
    request.setHeader("OData-Version", "4.0");
    request.setHeader("Authorization", "Bearer " + access_token);
    if (request.getURI().toString().endsWith("$metadata")) {
      request.setHeader("Accept", "application/xml");
    } else {
      request.setHeader("Accept", "application/json");
    }
  }

  protected static String get(String rootUrl, String resources, String id, CloseableHttpClient httpClient, String access_token) {

    String getUrl = rootUrl + resources + "(" + id + ")";
    HttpGet request = new HttpGet(getUrl);
    addToken(request, access_token);
    log.info("GET " + request.getURI().toString());
    try {
      String resp = httpClient.execute(request, new ApiResponseHandler());
      if (!resp.contains("\"No response\"")) {
        // wrap single item with [] for FDM compatible
        return "[" + resp + "]";
      }
      return resp;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (request != null) {
        request.releaseConnection();
      }
    }
  }

  protected static String getNN(String rootUrl, String resources, String NN_name, String id1, CloseableHttpClient httpClient, String access_token) {
    // Get [URL]/egcs_fc_profiles(d9dbfa0b-30b2-ea11-a812-000d3af43929)/je_egcs_fc_profile_je_educationalactivity
    String getUrl = rootUrl + resources + "(" + id1 + ")/" + NN_name;
    HttpGet request = new HttpGet(getUrl);
    addToken(request, access_token);
    log.info("GET " + request.getURI().toString());
    try {
      String resp = httpClient.execute(request, new ApiResponseHandler());
      if (!resp.contains("\"No response\"")) {
        // wrap single item with [] for FDM compatible
        return resp;
      }
      return resp;
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (request != null) {
        request.releaseConnection();
      }
    }
  }

  // limited filter available, use CRM field name directly
  protected static String list(String rootUrl, String resources, String filter, CloseableHttpClient httpClient, String access_token,
      String selector) {
    HttpGet request = null;
    try {
      String listUrl = rootUrl + resources;
      URIBuilder uriBuilder = new URIBuilder(listUrl);
      if (StringUtils.isNotBlank(filter)) {
        uriBuilder.addParameter("$filter", filter);
      }
      if (StringUtils.isNotBlank(selector)) {
        uriBuilder.addParameter("$select", selector);
      }

      request = new HttpGet(uriBuilder.build());
      addToken(request, access_token);
      log.info("GETs " + request.getURI().toString());

      return httpClient.execute(request, new ApiResponseHandler());
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (request != null) {
        request.releaseConnection();
      }
    }
  }

  protected static String post(String rootUrl, String resources, String jsonBody, CloseableHttpClient httpClient, String access_token) {
    HttpPost request = null;
    try {
      String postUrl = rootUrl + resources;
      request = new HttpPost(postUrl);
      addToken(request, access_token);
      StringEntity requestEntity = new StringEntity(jsonBody, ContentType.APPLICATION_JSON);
      request.setEntity(requestEntity);
      log.info("POST " + request.getURI().toString() + jsonBody.toString());

      return httpClient.execute(request, new ApiResponseHandler());
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (request != null) {
        request.releaseConnection();
      }
    }
  }

  // Post[URL]/egcs_fc_profiles(d9dbfa0b-30b2-ea11-a812-000d3af43929)/je_egcs_fc_profile_je_educationalactivity/$ref
  // {"@odata.id":"https://devedmsp.api.crm3.dynamics.com/api/data/v9.1/je_educationalactivities(db53ab8e-a19a-ea11-a812-000d3af46757)"}
  protected static void postNN(String rootUrl, String resources, String NN_name, String tgtEntypes, String id1, String id2,
      CloseableHttpClient httpClient, String access_token) {

    HttpPost request = null;
    try {
      String postUrl = rootUrl + resources + "(" + id1 + ")/" + NN_name + "/$ref";
      request = new HttpPost(postUrl);
      addToken(request, access_token);

      String jsonBody = "{\"@odata.id\":\"" + rootUrl + tgtEntypes + "(" + id2 + ")\"}";
      StringEntity requestEntity = new StringEntity(jsonBody, ContentType.APPLICATION_JSON);
      request.setEntity(requestEntity);
      log.info("POST " + request.getURI().toString() + jsonBody.toString());

      httpClient.execute(request);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (request != null) {
        request.releaseConnection();
      }
    }
  }

  protected static void patch(String rootUrl, String resources, String jsonBody, String id, CloseableHttpClient httpClient, String access_token,
      ArrayList<String> properties_deletion) {
    HttpPatch request = null;
    try {
      String patchUrl = rootUrl + resources + "(" + id + ")";
      request = new HttpPatch(patchUrl);
      addToken(request, access_token);
      StringEntity requestEntity = new StringEntity(jsonBody, ContentType.APPLICATION_JSON);
      request.setEntity(requestEntity);
      log.info("PATCH " + request.getURI().toString() + jsonBody);

      if (properties_deletion.size() > 0) {
        // disassociate each single property value
        httpClient.execute(request, new ApiResponseHandler());
        for (String propName : properties_deletion) {
          delete(rootUrl, resources, id, httpClient, access_token, propName);
        }
      } else {
        httpClient.execute(request, new ApiResponseHandler());
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (request != null) {
        request.releaseConnection();
      }
    }
  }

  protected static void delete(String rootUrl, String resources, String id, CloseableHttpClient httpClient, String access_token, String... propName) {
    HttpDelete request = null;
    try {
      String delUrl = rootUrl + resources + "(" + id + ")";
      if (propName.length > 0) {
        delUrl += "/" + propName[0];
      }
      request = new HttpDelete(delUrl);
      addToken(request, access_token);
      log.info("DELETE " + request.getURI().toString());

      httpClient.execute(request);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (request != null) {
        request.releaseConnection();
      }
    }
  }

  // [URL]/egcs_fc_profiles(d9dbfa0b-30b2-ea11-a812-000d3af43929)/je_egcs_fc_profile_je_educationalactivity(db53ab8e-a19a-ea11-a812-000d3af46757)/$ref
  protected static void deleteNN(String rootUrl, String resources, String NN_name, String id1, String id2, CloseableHttpClient httpClient,
      String access_token) {

    HttpDelete request = null;
    try {
      String delUrl = rootUrl + resources + "(" + id1 + ")/" + NN_name + "(" + id2 + ")/$ref";
      request = new HttpDelete(delUrl);
      addToken(request, access_token);
      log.info("DELETE " + request.getURI().toString());

      httpClient.execute(request);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (request != null) {
        request.releaseConnection();
      }
    }
  }

  protected static ObjectNode getMetadata(String rootUrl, CloseableHttpClient httpClient, String access_token) {
    HttpGet request = null;
    try {
      String listUrl = rootUrl + "$metadata";
      request = new HttpGet(listUrl);
      addToken(request, access_token);
      log.info(request.getURI().toString());

      String resp = httpClient.execute(request, new ApiResponseHandler());
      return parseMetadata(resp);
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (request != null) {
        request.releaseConnection();
      }
    }
  }

  // return entity: {read:write,read:write, pluralName:, id:}
  private static ObjectNode parseMetadata(String metaResp) throws Exception {

    Document document = DocumentHelper.parseText(metaResp);
    Element rootEle = document.getRootElement();
    Element schema = rootEle.element("DataServices").element("Schema");
    List<Element> entityTypes = schema.elements("EntityType");
    List<Element> EntityContainers = schema.elements("EntityContainer");
    ObjectMapper objectMapper = new ObjectMapper();
    ObjectNode odata = objectMapper.createObjectNode();

    HashMap<String, String> entityPlurals = new HashMap<String, String>();
    // collect plu: <EntitySet Name="accounts"
    // EntityType="Microsoft.Dynamics.CRM.account">
    for (Element entityContainer : EntityContainers) {
      // EntityContainer/EntitySet
      List<Element> entitySets = entityContainer.elements("EntitySet");
      for (Element entitySet : entitySets) {
        String name = entitySet.attributeValue("EntityType");
        String names = entitySet.attributeValue("Name");
        if (StringUtils.isBlank(names) || StringUtils.isBlank(name)) {
          continue;
        }
        name = name.replace("Microsoft.Dynamics.CRM.", "");
        entityPlurals.put(name, names);
      }
    }

    for (Element entityType : entityTypes) {
      String entityName = entityType.attributeValue("Name");
      if (entityName == null) {
        continue;
      }

      TreeMap<String, String> propeties = new TreeMap<String, String>();
      List<Element> enItems = entityType.elements();
      for (Element enItem : enItems) {
        String eleName = enItem.getName();
        propeties.put("pluralName", entityPlurals.get(entityName));
        if ("Key".equals(eleName)) {
          String pk = enItem.element("PropertyRef").attributeValue("Name");
          propeties.put("id", pk);
        } else if ("Property".equals(eleName)) {
          String propName = enItem.attributeValue("Name");
          String propType = enItem.attributeValue("Type");
          if (propeties.get(propName) == null) {
            propeties.put(propName, propName);
            if ("Edm.Boolean".equals(propType)) {
              // override with @boolean
              propeties.put(propName, propName + "@boolean");
            } else if ("Edm.DateTimeOffset".equals(propType)) {
              // override with @DateTime
              propeties.put(propName, propName + "@DateTime");
            } else if ("Edm.Date".equals(propType)) {
              // override with @DateTime
              propeties.put(propName, propName + "@Date");
            } else if ("Edm.Guid".equals(propType)) {
              // fields of Guid can't be simply mapped
              // propeties.put(propName, propName + "@Guid");
            } else if ("Edm.String".equals(propType)) {
              // override with @String
              propeties.put(propName, propName + "@String");
            } else if ("Edm.Int64".equals(propType)) {
              // override with @Int64
              propeties.put(propName, propName + "@Int64");
            } else if ("Edm.Int32".equals(propType)) {
              // override with @Int32
              propeties.put(propName, propName + "@Int32");
            }
          }
        } else if ("NavigationProperty".equals(eleName)) {
          String navName = enItem.attributeValue("Name");
          String navType = enItem.attributeValue("Type");
          Element referentialConstraint = enItem.element("ReferentialConstraint");
          if (referentialConstraint != null) {
            String readName = referentialConstraint.attributeValue("Property");
            if (StringUtils.isNotBlank(navType) && StringUtils.isNotBlank(readName)) {
              navType = navType.substring(6);
              navType = entityPlurals.get(navType) != null ? entityPlurals.get(navType) : navType;
              propeties.put(readName, navName + "@odata.bind:/" + navType + "(%)");
            }
          } else if (navType.startsWith("Collection(mscrm.")) {

            String tgtEntype = navType.substring("Collection(mscrm.".length(), navType.length() - 1);
            String tgtEntypes = entityPlurals.get(tgtEntype);
            // Ex: {"je_egcs_fc_profile_je_educationalactivity" : "/je_educationalactivities(%)"}
            propeties.put(navName, tgtEntypes);

            // example to call N:N get/post/delete
            // 1. get {"id1":"1234", "NNName" : "je_egcs_fc_profile_je_educationalactivity"}
            // Get [URL]/egcs_fc_profiles(d9dbfa0b-30b2-ea11-a812-000d3af43929)/je_egcs_fc_profile_je_educationalactivity

            // 2. Del {"id1":"1234", "id2:":"22", "NNName" : "je_egcs_fc_profile_je_educationalactivity"}
            // Del
            // [URL]/egcs_fc_profiles(d9dbfa0b-30b2-ea11-a812-000d3af43929)/je_egcs_fc_profile_je_educationalactivity(db53ab8e-a19a-ea11-a812-000d3af46757)/$ref

            // 3. Post {"id1":"1234", "id2:":"22", "NNName" : "je_egcs_fc_profile_je_educationalactivity"}
            // Post[URL]/egcs_fc_profiles(d9dbfa0b-30b2-ea11-a812-000d3af43929)/je_egcs_fc_profile_je_educationalactivity/$ref
            // {"@odata.id":"https://devedmsp.api.crm3.dynamics.com/api/data/v9.1/je_educationalactivities(db53ab8e-a19a-ea11-a812-000d3af46757)"}
          }
        }
      }

      // sort out
      ObjectNode entityNode = odata.putObject(entityName);
      NavigableSet<String> nkeys = propeties.navigableKeySet();
      for (String k : nkeys) {
        entityNode.put(k, propeties.get(k));
      }
    }

    return odata;
  }
}

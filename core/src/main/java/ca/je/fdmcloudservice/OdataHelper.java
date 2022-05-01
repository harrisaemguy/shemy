package ca.je.fdmcloudservice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.query.Query;

import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.crypto.CryptoSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component(service = OdataHelper.class)
public class OdataHelper {
  private final Logger log = LoggerFactory.getLogger(getClass());

  // @Reference
  // private SlingRepository repository;

  @Reference
  private CryptoSupport cryptoSupport;

  private PoolingHttpClientConnectionManager cm;
  protected CloseableHttpClient httpClient;
  private ObjectMapper objectMapper = new ObjectMapper();
  private HashMap<String, ObjectNode> metadatas = new HashMap<String, ObjectNode>();

  public OdataHelper() throws Exception {

    cm = new PoolingHttpClientConnectionManager();
    // Increase max total connection to 200
    cm.setMaxTotal(200);
    // Increase default max connection per route to 20
    cm.setDefaultMaxPerRoute(50);

    this.httpClient = HttpClients.custom().setConnectionManager(cm).build();
  }

  public CloseableHttpClient getHttpClient() {
    this.cm.closeExpiredConnections();
    this.cm.closeIdleConnections(120, TimeUnit.SECONDS);
    return this.httpClient;
  }

  private void shutdown() throws Exception {
    getHttpClient().close();
  }

  /**
   * @param fdmPath            "/content/dam/formsanddocuments-fdm/edmsp/caricom-faculty.executeDermisQuery.json?"
   * @param operationName      "GET egcs_fc_profile /egcs_fc_profiles_15906839249450"
   * @param operationArguments "{}"
   * @return null, single, or array
   * @throws Exception
   */
  public JsonNode exec(String fdmPath, String operationName, String operationArguments, ResourceResolver resourceResolver, String je_submitteddate)
          throws Exception {
    JsonNode fdmConfig = readFdmConfig(fdmPath, operationName, resourceResolver); // method,crmResource,inputKey,jsonBodyname,configurationPath,entityProps
    ObjectNode metadata = readMetadata(fdmConfig, resourceResolver);
    String crmResource = fdmConfig.get("crmResource").asText();
    String inputKey = fdmConfig.has("inputKey") ? fdmConfig.get("inputKey").asText() : "";
    String jsonBodyname = fdmConfig.has("jsonBodyname") ? fdmConfig.get("jsonBodyname").asText() : "";
    String crmResources = metadata.get(crmResource).get("pluralName").asText();
    String pkName = metadata.get(crmResource).get("id").asText();
    ObjectNode rwMap = (ObjectNode) metadata.get(crmResource);
    ObjectNode arguments = this.objectMapper.readValue(operationArguments, ObjectNode.class);
    ObjectNode accessInfo = getAccessToken(fdmConfig.get("configurationPath").asText(), resourceResolver);
    String rootUrl = accessInfo.get("rootUrl").asText();
    String access_token = accessInfo.get("access_token").asText();

    JsonNode result = null;

    switch (fdmConfig.get("method").asText()) {
    case "GET":
      log.info("exec get: " + operationArguments);
      String[] entityProps = fdmConfig.get("entityProps").asText().split(",");
      JsonNode entityGet = fdmConfig.get("entityMapUp");
      List<String> propList = Arrays.asList(entityProps);
      result = get(rootUrl, crmResources, arguments, pkName, access_token, propList, rwMap);
      break;
    case "POST":
      log.info("exec post: " + operationArguments);
      JsonNode entityPost = fdmConfig.get("entityMapDown");
      result = post(rootUrl, crmResources, arguments, jsonBodyname, access_token, rwMap);
      break;
    case "DELETE":
      log.info("exec delete: " + operationArguments);
      delete(rootUrl, crmResources, arguments, inputKey, access_token);
      break;
    default:
      log.info("exec patch: " + operationArguments);
      JsonNode entityPatch = fdmConfig.get("entityMapDown");
      patch(rootUrl, crmResources, arguments, inputKey, jsonBodyname, access_token, rwMap, je_submitteddate);
    }
    return result;
  }

  private JsonNode get(String rootUrl, String pluralName, ObjectNode arguments, String pkName, String access_token, List<String> entityProps,
          ObjectNode rwMap) throws Exception {
    String jsonStr = "{}";

    log.info("????:" + arguments.toString());

    if (arguments.has("NNName")) {
      String NN_name = arguments.get("NNName").asText();
      String id1 = arguments.get("id1").asText();
      jsonStr = OdataUtil.getNN(rootUrl, pluralName, NN_name, id1, this.httpClient, access_token);
    } else if (arguments.has(pkName)) {
      // unprotect
      String guid = arguments.get(pkName).asText();
      log.info("get by id: " + guid);
      jsonStr = OdataUtil.get(rootUrl, pluralName, guid, this.httpClient, access_token);
    } else {
      Iterator<String> names = arguments.fieldNames();
      StringBuilder filter = new StringBuilder();
      while (names.hasNext()) {
        String name = names.next();
        String nameType = rwMap.has(name) ? rwMap.get(name).asText() : "";
        if (StringUtils.isBlank(nameType)) {
          // skip invalid filter name
          continue;
        }

        if (filter.length() > 0) {
          filter.append(" and ");
        }

        if (nameType.endsWith("@String")) {
          filter.append(name).append(" eq '").append(arguments.get(name).asText()).append("'");
        } else {
          filter.append(name).append(" eq ").append(arguments.get(name).asText());
        }
      }
      log.info("get by filter: " + filter.toString());
      String selector = "";
      if (entityProps.size() < 20) {
        selector = String.join(",", entityProps);
      }
      jsonStr = OdataUtil.list(rootUrl, pluralName, filter.toString(), httpClient, access_token, selector);
    }

    // remove extra fields
    JsonNode fromCrm = this.objectMapper.readTree(jsonStr);
    // escapeHtml(fromCrm);
    if (fromCrm.has("value") && JsonNodeType.ARRAY == fromCrm.get("value").getNodeType()) {
      // list result format
      fromCrm = fromCrm.get("value");
      if (arguments.has("NNName")) {
        return fromCrm;
      }
      ArrayNode crmNodes = (ArrayNode) fromCrm;
      for (int i = 0; i < crmNodes.size(); i++) {
        JsonNode item = crmNodes.get(i);
        Iterator<Entry<String, JsonNode>> fieldIts = item.fields();
        while (fieldIts.hasNext()) {
          Entry<String, JsonNode> field = fieldIts.next();
          if (field.getKey().startsWith("_je_") || field.getKey().startsWith("je_") || entityProps.contains(field.getKey())) {
            // keep it
          } else {
            fieldIts.remove();
          }
        }
      }
    } else if (JsonNodeType.ARRAY == fromCrm.getNodeType()) {
      // modified get result format
      ArrayNode crmNodes = (ArrayNode) fromCrm;
      for (int i = 0; i < crmNodes.size(); i++) {
        JsonNode item = crmNodes.get(i);
        Iterator<Entry<String, JsonNode>> fieldIts = item.fields();
        while (fieldIts.hasNext()) {
          Entry<String, JsonNode> field = fieldIts.next();
          if (field.getKey().startsWith("_je_") || field.getKey().startsWith("je_") || entityProps.contains(field.getKey())) {
            // keep it
          } else {
            fieldIts.remove();
          }

          // string 'null' into null
          String keyName = field.getKey();
          String nameType = rwMap.has(keyName) ? rwMap.get(keyName).asText() : "";
          if (nameType.endsWith("@String") && field.getValue().asText().equals("null")) {
            field.setValue(NullNode.getInstance());
          }
        }
      }
    } else {
      // default get result format
      Iterator<Entry<String, JsonNode>> fieldIts = fromCrm.fields();
      while (fieldIts.hasNext()) {
        Entry<String, JsonNode> field = fieldIts.next();
        if (field.getKey().startsWith("_je_") || field.getKey().startsWith("je_") || entityProps.contains(field.getKey())) {
          // keep it
        } else {
          fieldIts.remove();
        }
      }
    }

    return fromCrm;
  }

  // input as object or array
  private void escapeHtml(JsonNode node) throws Exception {
    if (node.getNodeType() == JsonNodeType.OBJECT) {
      ObjectNode objectNode = (ObjectNode) node;
      Iterator<Entry<String, JsonNode>> fldIts = objectNode.fields();
      while (fldIts.hasNext()) {
        Entry<String, JsonNode> item = fldIts.next();
        JsonNode fldNode = item.getValue();
        if (fldNode.getNodeType() == JsonNodeType.STRING) {
          objectNode.put(item.getKey(), StringEscapeUtils.escapeHtml(fldNode.asText()));
        } else if (fldNode.getNodeType() == JsonNodeType.OBJECT || fldNode.getNodeType() == JsonNodeType.ARRAY) {
          escapeHtml(fldNode);
        } else {
          continue;
        }
      }
    } else if (node.getNodeType() == JsonNodeType.ARRAY) {
      ArrayNode arrayNode = (ArrayNode) node;
      for (int i = 0; i < arrayNode.size(); i++) {
        escapeHtml(arrayNode.get(i));
      }
    }
  }

  private JsonNode post(String rootUrl, String pluralName, ObjectNode arguments, String jsonBodyname, String access_token, final ObjectNode rwMap)
          throws Exception {
    log.info("input json: " + arguments.toString());
    log.info("check if using body key: " + jsonBodyname);
    if (arguments.has("NNName")) {
      String NN_name = arguments.get("NNName").asText();
      String id1 = arguments.get("id1").asText();
      String id2 = arguments.get("id2").asText();
      String tgtEntypes = rwMap.get(NN_name).asText();
      OdataUtil.postNN(rootUrl, pluralName, NN_name, tgtEntypes, id1, id2, httpClient, access_token);
      return null;
    } else {
      ObjectNode bodyNode = (ObjectNode) arguments.get(jsonBodyname);
      ArrayList<String> properties_deletion = new ArrayList<String>();
      String bodyStr = mapDown(bodyNode, rwMap, properties_deletion);
      String pk = OdataUtil.post(rootUrl, pluralName, bodyStr, httpClient, access_token);
      ObjectNode result = this.objectMapper.createObjectNode();
      result.put("id", pk);
      return result;
    }
  }

  private void patch(String rootUrl, String pluralName, ObjectNode arguments, String pkName, String jsonBodyname, String access_token,
          final ObjectNode rwMap, String je_submitteddate) throws Exception {
    log.info("input json: " + arguments.toString());
    log.info("check if using pk: " + pkName);
    log.info("check if using body key: " + jsonBodyname);
    String pk = arguments.get(pkName).asText();
    ObjectNode bodyNode = (ObjectNode) arguments.get(jsonBodyname);
    bodyNode.remove(pkName);
    ArrayList<String> properties_deletion = new ArrayList<String>();
    String bodyStr = mapDown(bodyNode, rwMap, properties_deletion);
    if (StringUtils.isBlank(je_submitteddate)) {
      // only delete on submit
      properties_deletion.clear();
    }
    OdataUtil.patch(rootUrl, pluralName, bodyStr, pk, httpClient, access_token, properties_deletion);
  }

  private String mapDown(ObjectNode srcNode, final ObjectNode rwMap, ArrayList<String> properties_deletion) throws Exception {
    ObjectNode destNode = this.objectMapper.createObjectNode();
    Iterator<String> srcNames = srcNode.fieldNames();
    while (srcNames.hasNext()) {
      String srcName = srcNames.next();
      String destName = rwMap.has(srcName) ? rwMap.get(srcName).asText() : "";
      if (StringUtils.isBlank(destName)) {
        // prevent CRM error
        log.warn("unknown crm field name: " + srcName);
        throw new Exception("unknown crm field name: " + srcName);
        // continue;
      }

      if ("null".equals(srcNode.get(srcName).asText())) {
        // client could send "null" to disassociate
        properties_deletion.add(srcName);
      } else if (destName.contains("@odata.bind")) {
        String[] token = destName.split(":");
        String nVal = token[1].replace("%", srcNode.get(srcName).asText());
        destNode.put(token[0], nVal);
      } else if (destName.endsWith("@boolean")) {
        String srcVal = srcNode.get(srcName).asText();
        String newName = destName.replace("@boolean", "");
        destNode.put(newName, BooleanUtils.toBoolean(srcVal));
      } else if (destName.endsWith("@String")) {
        String srcVal = srcNode.get(srcName).asText();
        String newName = destName.replace("@String", "");
        destNode.put(newName, srcVal);
      } else if (destName.endsWith("@Int64")) {
        long srcVal = srcNode.get(srcName).asLong();
        String newName = destName.replace("@Int64", "");
        destNode.put(newName, srcVal);
      } else if (destName.endsWith("@Int32")) {
        int srcVal = srcNode.get(srcName).asInt();
        String newName = destName.replace("@Int32", "");
        destNode.put(newName, srcVal);
      } else if (destName.endsWith("@DateTime")) {
        String srcVal = srcNode.get(srcName).asText();
        String newName = destName.replace("@DateTime", "");
        if (srcNode.get(srcName).getNodeType() == JsonNodeType.NULL || StringUtils.isBlank(srcVal)) {
          destNode.set(newName, NullNode.getInstance());
        } else {
          destNode.set(newName, srcNode.get(srcName));
        }
      } else if (destName.endsWith("@Date")) {
        String srcVal = srcNode.get(srcName).asText();
        String newName = destName.replace("@Date", "");
        if (srcNode.get(srcName).getNodeType() == JsonNodeType.NULL || StringUtils.isBlank(srcVal)) {
          destNode.set(newName, NullNode.getInstance());
        } else {
          destNode.put(newName, srcVal.substring(0, 10));
        }
      } else {
        destNode.set(destName, srcNode.get(srcName));
      }
    }
    return destNode.toString();
  }

  private void delete(String rootUrl, String pluralName, ObjectNode arguments, String pkName, String access_token) throws Exception {
    if (arguments.has("NNName")) {
      String NN_name = arguments.get("NNName").asText();
      String id1 = arguments.get("id1").asText();
      String id2 = arguments.get("id2").asText();
      OdataUtil.deleteNN(rootUrl, pluralName, NN_name, id1, id2, httpClient, access_token);
    } else {
      String pk = arguments.get(pkName).asText();
      OdataUtil.delete(rootUrl, pluralName, pk, httpClient, access_token);
    }
  }

  // read and cache by rootUrl
  private ObjectNode readMetadata(JsonNode fdmConfig, final ResourceResolver resourceResolver) throws Exception {
    String configurationPath = fdmConfig.get("configurationPath").asText();
    log.debug("configurationPath: " + configurationPath);
    ObjectNode dsInfo = readFdmcloudserviceConfig(configurationPath, resourceResolver);
    log.debug(dsInfo.toString());
    String rootUrl = dsInfo.get("rootUrl").asText();
    ObjectNode metadata = this.metadatas.get(rootUrl);
    if (metadata != null && (metadata.get("timestamp").asLong() + 3600000) > System.currentTimeMillis()) {
      // expire in 1 hour
      return metadata;
    }

    ObjectNode accessInfo = getAccessToken(configurationPath, resourceResolver);
    metadata = OdataUtil.getMetadata(rootUrl, this.httpClient, accessInfo.get("access_token").asText());
    metadata.put("timestamp", System.currentTimeMillis());
    this.metadatas.put(rootUrl, metadata);
    return metadata;
  }

  private JsonNode readFdmConfig(String fdmPath, String operationName, final ResourceResolver resourceResolver) throws Exception {
    if (fdmPath.contains(".executeDermisQuery.json")) {
      fdmPath = fdmPath.substring(0, fdmPath.indexOf("."));
    }
    // SELECT * FROM [nt:base] AS s WHERE ISDESCENDANTNODE([/content/dam/formsanddocuments-fdm/edmsp/caricom-faculty])
    // AND [id]='egcs_fc_profiles/Microsoft.Dynamics.CRM.je_aem_ELAP_Coordinator_Program_Canadian_Inst()_15900822255380'
    String sql2 = "SELECT * FROM [nt:base] AS s WHERE ISDESCENDANTNODE([" + fdmPath + "]) AND [id] ='" + operationName + "'";
    // Session session = repository.loginService("configLoader", repository.getDefaultWorkspace());

    Iterator<Resource> resIts = resourceResolver.findResources(sql2, Query.JCR_SQL2);
    while (resIts.hasNext()) {
      Resource res = resIts.next();
      Node node = res.adaptTo(Node.class);
      ObjectNode link = objectMapper.createObjectNode();
      link.put("method", node.getProperty("method").getString());
      // href is local copy of link, which should not be used
      // link.put("rootUrl", node.getProperty("href").getString());
      String crmResource = node.getProperty("name").getString().split("\\s")[1];
      link.put("crmResource", crmResource);
      String src1 = node.getProperty("fdm:source").getString();

      if ("PUT".equals(link.get("method").asText()) || "DELETE".equals(link.get("method").asText())) {

        Node putProps = node.getNode("schema").getNode("properties");
        NodeIterator pits = putProps.getNodes();
        while (pits.hasNext()) {
          Node p = pits.nextNode();
          if (p.hasProperty("fdm:in") && p.getProperty("fdm:in").getString().equals("body")) {
            link.put("jsonBodyname", p.getProperty("name").getString());
          } else if (p.hasProperty("fdm:in") && p.getProperty("fdm:in").getString().equals("key")) {
            link.put("inputKey", p.getProperty("name").getString());
          }
        }
      } else if ("POST".equals(link.get("method").asText())) {

        Node putProps = node.getNode("schema").getNode("properties");
        NodeIterator pits = putProps.getNodes();
        while (pits.hasNext()) {
          Node p = pits.nextNode();
          if (p.hasProperty("type") && p.getProperty("type").getString().equals("object")) {
            link.put("jsonBodyname", p.getProperty("name").getString());
            break;
          }
        }
      } else if ("GET".equals(link.get("method").asText())) {

        String definitionName = node.getNode("targetSchema").getNode("items").getProperty("$ref").getString();
        String definitionNodePath = fdmPath + "/jcr:content/renditions/fdm-json/jcr:content/definitions";
        Iterator<Resource> defs = resourceResolver.getResource(definitionNodePath).getChildren().iterator();
        StringBuilder entityProps = new StringBuilder();
        while (defs.hasNext()) {
          Node def = defs.next().adaptTo(Node.class);
          if (def.hasProperty("id") && definitionName.equals(def.getProperty("id").getString())) {
            NodeIterator fields = def.getNode("properties").getNodes();
            while (fields.hasNext()) {
              Node field = fields.nextNode();
              link.with("entityMapDown").put(field.getProperty("name").getString(), field.getProperty("fdm:bindRef").getString());
              link.with("entityMapUp").put(field.getProperty("fdm:bindRef").getString(), field.getProperty("name").getString());
              if (field.hasProperty("fdm:bindRef")) {
                if (entityProps.length() > 0) {
                  entityProps.append(",");
                }
                entityProps.append(field.getProperty("fdm:bindRef").getString());
              }
            }
          }
        }
        link.put("entityProps", entityProps.toString());
      }

      String src1Path = fdmPath + "/jcr:content/sources/" + src1;
      link.put("configurationPath", resourceResolver.getResource(src1Path).adaptTo(Node.class).getProperty("configurationPath").getString());
      return link;
    }

    throw new Exception("Service not available: " + sql2);
  }

  // configurationPath: abspath, or relative path
  public ObjectNode getAccessToken(String configurationPath, final ResourceResolver resourceResolver) {
    HttpPost request = null;
    ObjectNode result = null;
    try {
      result = readFdmcloudserviceConfig(configurationPath, resourceResolver);
      String postUrl = result.has("refresh_token_uri") ? result.get("refresh_token_uri").asText() : "";
      String client_id = result.has("client_id") ? result.get("client_id").asText() : "";
      String resource = result.has("resource") ? result.get("resource").asText() : "";
      String client_secret = result.has("client_secret") ? result.get("client_secret").asText() : "";
      String refresh_token = result.has("refresh_token") ? result.get("refresh_token").asText() : "";
      if (StringUtils.isNotBlank(postUrl)) {
        request = new HttpPost(postUrl);
        ArrayList<NameValuePair> params = new ArrayList<NameValuePair>();
        params.add(new BasicNameValuePair("client_id", client_id));
        params.add(new BasicNameValuePair("resource", resource));
        params.add(new BasicNameValuePair("client_secret", client_secret));
        params.add(new BasicNameValuePair("refresh_token", refresh_token));
        params.add(new BasicNameValuePair("grant_type", "refresh_token"));
        request.setEntity(new UrlEncodedFormEntity(params));

        String jsonStr = httpClient.execute(request, new ApiResponseHandler());
        JsonNode respNode = this.objectMapper.readValue(jsonStr, JsonNode.class);
        if (respNode.has("access_token")) {
          ((ObjectNode) result).set("access_token", respNode.get("access_token"));
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      if (request != null) {
        request.releaseConnection();
      }
    }

    return result;
  }

  /**
   *
   * @param rootUrl           ex: https://devedmsp.api.crm3.dynamics.com/api/data/v9.1/
   * @param configurationPath ex: je-odata-cloud-service or absolute path
   * @return {refresh_token_uri, (client_id, resource, client_secret, refresh_token), rootUrl}
   * @throws Exception
   */
  private ObjectNode readFdmcloudserviceConfig(String configurationPath, final ResourceResolver resourceResolver) throws Exception {
    // fd/fdm/gui/components/admin/fdmcloudservice/rest

    // Session session = repository.loginService("configLoader", repository.getDefaultWorkspace());

    String cloudDS = "";
    if (configurationPath.startsWith("/")) {
      cloudDS = configurationPath + "/jcr:content";
    } else {
      cloudDS = queryCloudDS_path(configurationPath, resourceResolver);
    }

    log.info("... use cloud from: " + cloudDS);

    if (StringUtils.isNotBlank(cloudDS)) {
      Node dsJcr = resourceResolver.getResource(cloudDS).adaptTo(Node.class);
      ObjectNode result = objectMapper.createObjectNode();
      if (dsJcr.hasProperty("refresh_token_uri")) {
        result.put("refresh_token_uri", dsJcr.getProperty("refresh_token_uri").getString());
      }
      if (dsJcr.hasProperty("client_id")) {
        result.put("client_id", dsJcr.getProperty("client_id").getString());
      }
      if (dsJcr.hasProperty("resource")) {
        result.put("resource", dsJcr.getProperty("resource").getString());
      }
      if (dsJcr.hasProperty("client_secret")) {
        String encode = dsJcr.getProperty("client_secret").getString();
        result.put("client_secret", this.cryptoSupport.unprotect(encode));
      }
      if (dsJcr.hasProperty("refresh_token")) {
        String encode = dsJcr.getProperty("refresh_token").getString();
        result.put("refresh_token", this.cryptoSupport.unprotect(encode));
      }
      if (dsJcr.hasProperty("url")) {
        result.put("rootUrl", dsJcr.getProperty("url").getString().trim());
      }

      return result;
    }

    return null;
  }

  private String queryCloudDS_path(String configurationPath, final ResourceResolver resourceResolver) throws Exception {
    // query is not reliable when result is over 2000
    String sql2 = "SELECT * FROM [cq:PageContent] AS s WHERE ISDESCENDANTNODE([/conf]) AND [sling:resourceType] = 'fd/fdm/gui/components/admin/fdmcloudservice/rest'";
    Iterator<Resource> resIts = resourceResolver.findResources(sql2, Query.JCR_SQL2);
    while (resIts.hasNext()) {
      Node node = resIts.next().adaptTo(Node.class);
      if (node.hasProperty("name") && node.hasProperty("url")) {
        if (configurationPath.equalsIgnoreCase(node.getProperty("name").getString())) {
          return node.getPath();
        }
      }
    }
    return null;
  }

  @Activate
  protected void activate() {
  }

  @Deactivate
  protected void deactivate() throws Exception {
    this.shutdown();
  }
}

package ca.je.fdmcloudservice;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.util.EntityUtils;

/**
 * { "data":, "errors":, "links":}
 *
 * @author xilu
 */
public class ApiResponseHandler implements ResponseHandler<String> {

  @Override
  public String handleResponse(HttpResponse response) throws ClientProtocolException, IOException {
    try {
      StatusLine statusLine = response.getStatusLine();
      int statusCode = statusLine.getStatusCode();
      if (statusCode == 204) {
        // read header OData-EntityId
        String entityId = response.getFirstHeader("OData-EntityId").getValue();
        Pattern pattern = Pattern.compile("\\((.*)\\)");
        Matcher m = pattern.matcher(entityId);
        if (m.find()) {
          return m.group(1);
        } else {
          return entityId;
        }
      } else if (statusCode >= 400) {
        HttpEntity entity = response.getEntity();
        String body = entity != null ? EntityUtils.toString(entity) : null;
        int msgLen = body.length() > 1000 ? 1000 : body.length();
        body = body.substring(0, msgLen).replace("\"", "").replace("'", "");
        String error = "{\"statuscode\":" + statusCode + ",\"error\":\"" + body + "\"}";
        throw new IOException(error);
      } else {
        // statusCode > 199 && statusCode < 300, and other
        HttpEntity entity = response.getEntity();
        String body = entity != null ? EntityUtils.toString(entity) : null;
        return body;
      }
    } finally {
      EntityUtils.consumeQuietly(response.getEntity());
      if (response instanceof CloseableHttpResponse) {
        ((CloseableHttpResponse) response).close();
      }
    }
  }
}

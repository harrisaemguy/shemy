package ca.je.fdmcloudservice;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.engine.EngineConstants;
import org.apache.sling.serviceusermapping.ServicePrincipalsValidator;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.apache.sling.serviceusermapping.ServiceUserMapper;
import org.apache.sling.serviceusermapping.ServiceUserValidator;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.wcm.api.WCMMode;
import com.fasterxml.jackson.databind.JsonNode;

// get session without login as admin, see org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended-je.xml
// resourceResolver = resourceResolverFactory.getServiceResourceResolver(map)
// session = slingRepository.loginService(String subServiceName, String workspace)

@Component(service = Filter.class, property = { EngineConstants.SLING_FILTER_SCOPE + "=" + EngineConstants.FILTER_SCOPE_REQUEST,
    Constants.SERVICE_RANKING + "=-700" })
@Designate(ocd = JeFdmFilter.Config.class)
public class JeFdmFilter implements Filter {

  private final Logger log = LoggerFactory.getLogger(getClass());

  // prevent component get activated if service user does not exist
  @Reference(name = "fd-service")
  private ServiceUserMapped serviceUserMapped;

  @Reference
  private ServiceUserMapper serviceUserMapper;

  @Reference
  private ServiceUserValidator serviceUserValidator;

  @Reference
  private ServicePrincipalsValidator servicePrincipalsValidator;

  @Reference
  private ResourceResolverFactory resourceResolverFactory;

  @Reference
  private OdataHelper odataHelper;

  private String[] fdmPathPrefixs;

  private String formPathPrefix;

  @Override
  public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain filterChain)
      throws IOException, ServletException {

    // ResourceUtil.getOrCreateResource(((SlingHttpServletRequest) request).getResourceResolver(), "/conf/JE/settings/cloudconfigs",
    // Collections.singletonMap("jcr:primaryType", "nt:folder"), "nt:folder", false);

    // get from osgi configuration
    String systemUser = serviceUserMapper.getServiceUserID(FrameworkUtil.getBundle(this.getClass()), null);
    Iterable<String> systemUsers = serviceUserMapper.getServicePrincipalNames(FrameworkUtil.getBundle(this.getClass()), null);
    log.info("The bundle is mapped to a serviceUser : " + systemUser);
    if (systemUsers != null) {
      log.info("The bundle is mapped to a serviceUsers : " + systemUsers.toString());
    }
    // check whether specified systemUser has mapped to this bundle
    boolean x = serviceUserValidator.isValid("fd-service", FrameworkUtil.getBundle(this.getClass()).getSymbolicName(), null);
    log.info("This bundle is mapped to serviceUser \"fd-service\" : " + x);
    // check default mapping, serviceuser--<bundleId>[--<subservice-name>]
    String defaultMapId = "serviceuser--" + FrameworkUtil.getBundle(this.getClass()).getBundleId();
    log.info("defaultMapId: " + defaultMapId);

    // real implementation
    HttpServletRequest servletRequest = (HttpServletRequest) request;
    HttpServletResponse servletResponse = (HttpServletResponse) response;
    String reqUri = servletRequest.getRequestURI();
    WCMMode wcmMode = WCMMode.fromRequest(servletRequest);
    log.debug("wcmmode: " + wcmMode.toString() + ", reqUri=" + reqUri);

    // String currentPageUrl = new URI(req.getHeader("referer")).getPath();
    // && (WCMMode.PREVIEW.equals(wcmMode) || WCMMode.DISABLED.equals(wcmMode))
    for (String fdmPathPrefix : this.fdmPathPrefixs) {
      String operationName = servletRequest.getParameter("operationName");
      if (!(reqUri.startsWith(fdmPathPrefix) || reqUri.startsWith(formPathPrefix) && reqUri.endsWith("/jcr:content/guideContainer.af.dermis"))
          || StringUtils.isBlank(operationName) || !(operationName.startsWith("GET ") || operationName.startsWith("POST ")
              || operationName.startsWith("DELETE ") || operationName.startsWith("PUT "))) {
        continue;
      }

      String fdmPath = reqUri;
      String operationArguments = servletRequest.getParameter("operationArguments");
      if (reqUri.endsWith("/jcr:content/guideContainer.af.dermis")) {
        // read fdmPath, operationName, operationArguments from invokeFDMOperation
        fdmPath = servletRequest.getParameter("formDataModelId");
        operationArguments = servletRequest.getParameter("input");
      }

      Map<String, Object> map = new HashMap<String, Object>();
      map.put(ResourceResolverFactory.SUBSERVICE, "fd-service");
      ResourceResolver resourceResolver = null;
      try {
        log.info("...fdmPath: " + fdmPath);
        log.info("...operationName: " + operationName);
        log.info("...operationArguments: " + operationArguments);
        resourceResolver = resourceResolverFactory.getServiceResourceResolver(map);
        log.info(resourceResolver.getUserID());
        String jetimeStamp = servletRequest.getHeader("jetimeStamp");

        // checking Integrity only for these fdm
        if ((operationName.startsWith("PUT ") || operationName.startsWith("POST ")) && StringUtils.isBlank(jetimeStamp)) {
          // not allowed without signature
          log.warn("operationArguments not cerfified!");
        } else if (StringUtils.isNotBlank(jetimeStamp)) {
          String tmp = operationArguments;// .replace("\n", "").replace("\r", "");
          String md5OperationArguments = DigestUtils.md5Hex(tmp);
          if (md5OperationArguments.contentEquals(jetimeStamp)) {
            // good
          } else {
            throw new Exception("Data corrupted!");
          }
        }

        String je_submitteddate = servletRequest.getHeader("je_submitteddate");
        JsonNode respNode = odataHelper.exec(fdmPath, operationName, operationArguments, resourceResolver, je_submitteddate);
        if (respNode != null) {
          // put/delete does not have response
          servletResponse.setContentType("application/json");
          servletResponse.setCharacterEncoding("UTF-8");
          servletResponse.getWriter().write(respNode.toString());
        }
      } catch (Exception e) {
        String msg = e.getMessage();
        log.error(msg, e);
        servletResponse.getWriter().write(msg);
        servletResponse.setStatus(500);
      } finally {
        resourceResolver.close();
      }

      return;
    }

    filterChain.doFilter(request, response);
  }

  @Override
  public void init(FilterConfig filterConfig) {
  }

  @Override
  public void destroy() {
  }

  @Activate
  protected void activate(Config config) {
    this.fdmPathPrefixs = config.fdmPathPrefixs();
    this.formPathPrefix = config.formPathPrefix();
  }

  @ObjectClassDefinition(name = "ca.je.fdmcloudservice.JeFdmFilter", description = "")
  public @interface Config {
    @AttributeDefinition
    String[] fdmPathPrefixs() default { "/content/dam/formsanddocuments-fdm/edmsp" };

    @AttributeDefinition
    String formPathPrefix() default "/content/forms/af/edmsp";
  }
}
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.contexts.ISDKUserContext;
import com.appdynamics.instrumentation.sdk.template.AEntry;
import com.appdynamics.instrumentation.sdk.template.AGenericInterceptor;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.ReflectorException;
import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.ServletContext;
import com.appdynamics.agent.api.EntryTypes;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.apm.appagent.api.DataScope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Interceptor to correlate or originate business transaction for ping access.
 * Interceptor is applied on
 * com.pingidentity.pa.core.interceptor.HTTPClientInterceptor#handleRequest
 * Vikash Kumar
 * March 12, 2020

 * John Southerland
 * July 6, 2020
 * Added reflection to build a ServletContext and changed AEntryPoint to AGenericInterceptor
 * Aug 5, 2020: Added custom data to identify Proxy Name in snapshot and analytics, can disable analytics support with jvm command line:
 *      -DdisablePingAccessAnalytics=true
 */

public class PingAccessEntryPointInterceptor extends AGenericInterceptor {

    private static final Object CORRELATION_HEADER_KEY = "singularityheader";

    Set<DataScope> dataScopes;

    IReflector getRequestReflector;
    IReflector getHeadersReflector;
    IReflector getFirstValueReflector;
    IReflector getUriReflector;
    IReflector getHostReflector;
    IReflector getSchemeReflector;
    IReflector getCookiesReflector;
    IReflector getHeaderFieldsReflector;
    IReflector getHeaderNameReflector;
    IReflector getValueReflector;
    IReflector mapKeySetReflector, mapGetReflector;
    IReflector getUserAgentHostReflector;
    IReflector getResponseTargetHostReflector;
    IReflector getQueryStringParamsReflector;
    IReflector getMethodReflector;
    IReflector getProxyReflector;
    IReflector getNameReflector;

    public PingAccessEntryPointInterceptor() {
        super();

        dataScopes = new HashSet<DataScope>();

        dataScopes.add(DataScope.SNAPSHOTS);
        if( System.getProperty("disablePingAccessAnalytics","false").equalsIgnoreCase("false") ) {
            dataScopes.add(DataScope.ANALYTICS);
            this.getLogger().info("Enabling Analytics Collection of Ping Access Proxy Name Data, to disable add JVM property -DdisablePingAccessAnalytics=true");
        }

        getRequestReflector = getNewReflectionBuilder().invokeInstanceMethod("getRequest", true).build();
        getHeadersReflector = getNewReflectionBuilder().invokeInstanceMethod("getHeaders", true).build();
        getFirstValueReflector = getNewReflectionBuilder().invokeInstanceMethod("getFirstValue", true,
                new String[]{String.class.getCanonicalName()}).build();
        getUriReflector = getNewReflectionBuilder().invokeInstanceMethod("getUri", true).build();
        getHostReflector = getNewReflectionBuilder().invokeInstanceMethod("getHost", true).build();
        getSchemeReflector = getNewReflectionBuilder().invokeInstanceMethod("getScheme", true).build();
        getCookiesReflector = getNewReflectionBuilder().invokeInstanceMethod("getCookies", true).build();
        getHeaderFieldsReflector = getNewReflectionBuilder().invokeInstanceMethod("getHeaderFields", true).build();
        getHeaderNameReflector = getNewReflectionBuilder().invokeInstanceMethod("getHeaderName", true).build();
        getValueReflector = getNewReflectionBuilder().invokeInstanceMethod("getValue", true).build();
        mapKeySetReflector = getNewReflectionBuilder().invokeInstanceMethod("keySet", true).build();
        mapGetReflector = getNewReflectionBuilder().invokeInstanceMethod("get", true, new String[]{String.class.getCanonicalName()}).build();
        getUserAgentHostReflector = getNewReflectionBuilder().invokeInstanceMethod("getUserAgentHost", true).build();
        getResponseTargetHostReflector = getNewReflectionBuilder().invokeInstanceMethod("getResponseTargetHost", true).build();
        getQueryStringParamsReflector = getNewReflectionBuilder().invokeInstanceMethod("getQueryStringParams", true).build();
        getMethodReflector = getNewReflectionBuilder().invokeInstanceMethod("getMethod", true).build();

        getProxyReflector = getNewReflectionBuilder().invokeInstanceMethod("getProxy", true).build();
        getNameReflector = getNewReflectionBuilder().invokeInstanceMethod("getName", true).build();
    }


    public Object onMethodBegin(Object object, String className, String methodName, Object[] params) {
        this.getLogger().debug("PingAccessEntryPointInterceptor.onMethodBegin() start");
        Object exchangeImpl = params[0];
        Transaction transaction;
        if( "com.pingidentity.pa.core.interceptor.HTTPClientInterceptor".equals(className) ) {
          transaction = AppdynamicsAgent.startServletTransaction(buildServletContext(exchangeImpl), EntryTypes.HTTP, getCorrelationID(exchangeImpl), false);
        } else {
          //String[] classNameParts = className.split(".");
          StringBuilder btName = new StringBuilder(className);
          transaction = AppdynamicsAgent.startTransaction(btName.toString(), getCorrelationID(exchangeImpl), EntryTypes.POJO, false);
        }
        try {
          Object proxy = getProxyReflector.execute(exchangeImpl.getClass().getClassLoader(), exchangeImpl);
          if( proxy != null ) {
            Object name = getNameReflector.execute(proxy.getClass().getClassLoader(), proxy );
            if( name != null ) transaction.collectData("PingAccess-ProxyName", name.toString(), dataScopes);
          }
        } catch( ReflectorException rex ) {
          this.getLogger().warn("ReflectorException in Exchange.getProxy().getName() retrieval: "+ rex, rex);
        }
        this.getLogger().debug("PingAccessEntryPointInterceptor.onMethodBegin() end");
        return this;
    }

    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        this.getLogger().debug("PingAccessEntryPointInterceptor.onMethodEnd() start");
        if( exception != null ) { 
          AppdynamicsAgent.getTransaction().markAsError( exception.getMessage() );
        }
        AppdynamicsAgent.getTransaction().end();

        this.getLogger().debug("PingAccessEntryPointInterceptor.onMethodEnd() end");
    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<Rule>();

        rules.add(new Rule.Builder(
                "com.pingidentity.pa.core.interceptor.HTTPClientInterceptor")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("handleRequest").build());

        rules.add(new Rule.Builder(
                "com.pingidentity.pa.sdk.policy.RuleInterceptor")
                .classMatchType(SDKClassMatchType.IMPLEMENTS_INTERFACE)
                .methodMatchString("handleRequest").build());

        return rules;
    }

    private ServletContext buildServletContext( Object exchange ) {
      this.getLogger().debug("Entering into buildServletContext");
      ServletContext.ServletContextBuilder builder = new ServletContext.ServletContextBuilder();
      Object request = null;

      try {
        request = getRequestReflector.execute(exchange.getClass().getClassLoader(), exchange);
        if(request != null){
            Object uri = getUriReflector.execute(request.getClass().getClassLoader(), request);
            Object host = getHostReflector.execute(exchange.getClass().getClassLoader(), exchange);
            Object scheme = getSchemeReflector.execute(exchange.getClass().getClassLoader(), exchange);
            if( host == null ) host = "UNKNOWN_HOST";
            if( scheme == null ) scheme = "http";
            if(uri != null){
              try {
                builder.withURL( scheme.toString() +"://"+ host.toString() + uri.toString() );
                this.getLogger().debug("URL Set to: "+ scheme.toString() +"://"+ host.toString() + uri.toString() );
              } catch( java.net.MalformedURLException ex ) {
                this.getLogger().warn("MalformedURLException: url == "+ scheme.toString() +"://"+ host.toString() + uri.toString());
              }
            }
          }
      } catch( ReflectorException rex ) {
        this.getLogger().warn("ReflectorException in URL retrieval: "+ rex, rex);
      }
      
      if( request == null ) {
        this.getLogger().warn("Abandoning attempt to build ServletContext, request object still null");
        return builder.build();
      }  

      Object headers = null;

      try {
        headers = getHeadersReflector.execute(request.getClass().getClassLoader(),request);
        if(headers != null){ 
          HashMap<java.lang.String,java.lang.String> appdHeaders = new HashMap<String,String>();
          Object headerFields = getHeaderFieldsReflector.execute(headers.getClass().getClassLoader(), headers);
          for (Object field : (List<Object>) headerFields ) {
            Object headerName = getHeaderNameReflector.execute(field.getClass().getClassLoader(), field);
            Object value = getValueReflector.execute(field.getClass().getClassLoader(), field);
            if( headerName != null && value != null ) {
              appdHeaders.put( headerName.toString(), value.toString() );
              this.getLogger().debug("Header added: "+ headerName.toString() +"="+ value.toString());
            }
          }
          builder.withHeaders( appdHeaders );
        }
      } catch( ReflectorException rex ) {
        this.getLogger().warn("ReflectorException in Header retrieval: "+ rex, rex);
      }

      try {
        if( headers != null ) {
          Object cookies = getCookiesReflector.execute(headers.getClass().getClassLoader(), headers);
          if( cookies != null ) {
            java.util.Map<java.lang.String,java.lang.Object> appdCookies = new HashMap<String,Object>();
            
            Object keySet = mapKeySetReflector.execute(cookies.getClass().getClassLoader(), cookies);
            if( keySet != null ) {
              for( String key : (Set<String>)keySet ) { 
                Object cookie = mapGetReflector.execute(cookies.getClass().getClassLoader(), cookies, new Object[]{key});
                if( cookie != null ) {
                  appdCookies.put(key, cookie); 
                  this.getLogger().debug("Cookie added: "+ key +"="+ cookie);
                }
              }
              builder.withCookies( appdCookies );
            }
          }
        }
      } catch( ReflectorException rex ) {
        this.getLogger().warn("ReflectorException in Cookies retrieval: "+ rex, rex);
      }

      try{
        Object queryStringParams = getQueryStringParamsReflector.execute(request.getClass().getClassLoader(), request);
        if( queryStringParams != null ) {
          builder.withParameters( (Map<String,String[]>)queryStringParams );
          this.getLogger().debug("Made it into set Parameters, reflection makes printing annoying");
        }
      } catch( ReflectorException rex ) {
        this.getLogger().warn("ReflectorException in Query Parameters retrieval: "+ rex, rex);
      }

      try {
        Object method = getMethodReflector.execute(request.getClass().getClassLoader(), request);
        if( method != null ) {
          builder.withRequestMethod( method.toString() );
          this.getLogger().debug("Request Method set to: "+ method);
        }
      } catch( ReflectorException rex ) {
        this.getLogger().warn("ReflectorException in Request Method retrieval: "+ rex, rex);
      }

      try {
        Object userAgentHost = getUserAgentHostReflector.execute( exchange.getClass().getClassLoader(), exchange );
        if( userAgentHost != null ) builder.withHostOriginatingAddress( (String)userAgentHost );
        this.getLogger().debug("Host Originating Address set to: "+ userAgentHost);
      } catch( ReflectorException rex ) {
        this.getLogger().warn("ReflectorException in Host Originating Address retrieval: "+ rex, rex);
      }

      try {
        Object responseTargetHost = getResponseTargetHostReflector.execute( exchange.getClass().getClassLoader(), exchange );
        if( responseTargetHost != null ) builder.withHostValue( responseTargetHost.toString() ); 
        this.getLogger().debug("Host Value set to: "+ responseTargetHost);
      } catch( ReflectorException rex ) {
        this.getLogger().warn("ReflectorException in Host Value retrieval: "+ rex, rex);
      }
        
      ServletContext sc = builder.build();
      this.getLogger().debug("ServletContext to return: "+ sc.toString());
      this.getLogger().debug("Returning from buildServletContext");
      return sc;
    }

  private String getCorrelationID( Object exchange ) {
    try {
      Object request = getRequestReflector.execute(exchange.getClass().getClassLoader(), exchange);

      if(request != null){
              Object headers = getHeadersReflector.execute(request.getClass().getClassLoader(),request);
              if(headers != null){

                  Object singularityHeader =  getFirstValueReflector.execute(headers.getClass().getClassLoader()
                  , headers, new Object[]{CORRELATION_HEADER_KEY});

                  if(singularityHeader != null){
                      if(getLogger().isDebugEnabled()){
                          getLogger().debug("Reading correlation header from ping access"+singularityHeader);
                      }
                      return (String) singularityHeader;
                  }
              }
      }
    } catch( ReflectorException rex ) {
      this.getLogger().warn("ReflectorException in Correlation Header retrieval: "+ rex, rex);
    }
    return null;
  }

}

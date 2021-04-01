import com.appdynamics.agent.api.*;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.contexts.ISDKUserContext;
import com.appdynamics.instrumentation.sdk.template.AExit;
import com.appdynamics.instrumentation.sdk.template.AGenericInterceptor;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.ReflectorException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

//import com.pingidentity.pa.sdk.http.Response;

/**
 *
 *
 * Vikash Kumar
 * Mar 12, 2020
 *
 * John Southerland
 * Aug 5, 2020 : Changed from extending an AExit to extending an AGenericInterceptor; Start is the same from an InternalHttpClient,
 * but then adding a concurrency CompletionStage.whenCompleteAsync lambda function to end the exit call. This should be more in line with the iSDK
 *
 */
public class PingAccessExitPointInterceptor extends AGenericInterceptor {


    private static final String CORRELATION_HEADER_KEY = "singularityheader";

    IReflector getRequestReflector;
    IReflector getHeadersReflector;
    IReflector addReflector;
    IReflector getUriReflector;
    IReflector getHostReflector;
    IReflector getSchemeReflector;
    IReflector getPortReflector;

    public PingAccessExitPointInterceptor(){
        super();

        getRequestReflector = getNewReflectionBuilder().invokeInstanceMethod("getRequest", true).build();
        getHeadersReflector = getNewReflectionBuilder().invokeInstanceMethod("getHeaders", true).build();
        addReflector = getNewReflectionBuilder().invokeInstanceMethod("add", true,
                new String[]{String.class.getCanonicalName(), String.class.getCanonicalName()}).build();

        getUriReflector = getNewReflectionBuilder().invokeInstanceMethod("getUri", true).build();
        getHostReflector = getNewReflectionBuilder().invokeInstanceMethod("getHost", true).build();
        getSchemeReflector = getNewReflectionBuilder().invokeInstanceMethod("getScheme", true).build();
        getPortReflector = getNewReflectionBuilder().invokeInstanceMethod("getPort", true).build();
    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<Rule>();
        //private CompletionStage<Response> call(ExchangeImpl exc, InetAddress targetAddr, TargetHost targetHost,
        // AvailabilityTimeoutConfig timeoutConfig, HttpClientConfiguration config,
        // HttpClientProxyConfiguration proxyConfig, InetAddress proxyAddr) {
        rules.add(new Rule.Builder("com.pingidentity.pa.core.transport.http.InternalHttpClient")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("call")
                .withParams("com.pingidentity.pa.core.exchange.ExchangeImpl", "java.net.InetAddress", "com.pingidentity.pa.sdk.http.TargetHost", "com.pingidentity.pa.core.ha.availability.AvailabilityTimeoutConfig", "com.pingidentity.pa.api.transport.HttpClientConfiguration", "com.pingidentity.pa.api.transport.HttpClientProxyConfiguration", "java.net.InetAddress" )
                .build());
        return rules;
    }

    public Object onMethodBegin(Object object, String className, String methodName, Object[] params) {
        this.getLogger().debug("PingAccessExitPointInterceptor.onMethodBegin() start");
        Map<String, String> properties = new HashMap<String, String>();
        Object exchange = params[0];
        Object request = null;
        Object targetHost = params[2];
        getLogger().debug("found targethost "+targetHost);

        try {
            if(targetHost != null){
                Object host = getHostReflector.execute(targetHost.getClass().getClassLoader(), targetHost);
                getLogger().debug("Found host "+host);
                if(host != null){
                    properties.put("HOST", (String)host);
                }

                Object port = getPortReflector.execute(targetHost.getClass().getClassLoader(), targetHost);
                getLogger().debug("Found Port "+port);
                if(port != null){
                    properties.put("PORT", String.valueOf(port));
                }
            }
        }catch (Exception e){
            if(getLogger().isDebugEnabled()) {
                getLogger().debug("Problem extracting host and port:" + object.getClass().getName() + ". in ping access backend properties ", e);
            }
            properties.put("HOST", "UNKNOWN-HOST");
            properties.put("PORT", "UNKNOWN-PORT");
        }
        try {
            request = getRequestReflector.execute(exchange.getClass().getClassLoader(), exchange);
            if (request != null) {
                Object uri = getUriReflector.execute(request.getClass().getClassLoader(), request);
                Object host = getHostReflector.execute(exchange.getClass().getClassLoader(), exchange);
                Object scheme = getSchemeReflector.execute(exchange.getClass().getClassLoader(), exchange);
                if (host == null) host = properties.get("HOST");
                if (scheme == null) scheme = "http";
                if (uri != null) {
                    properties.put("URL", scheme.toString() +"://"+ host.toString() + uri.toString() );
                }
            }
        } catch( Exception e) {
            getLogger().debug("Problem extracting URL from request; Exception: "+ e);
            properties.put("URL", "UNKNOWN-URL");
        }
        if(properties.isEmpty()){
            //Hardcode the backend properties
            //getLogger().debug("Unable to extract outbound url, host, and port, hardcoding backend properties"+ " exchange class "+targetHost.getClass().getName());
            properties.put("HOST", "UNKNOWN-HOST");
            properties.put("PORT", "UNKNOWN-PORT");
            properties.put("URL", "UNKNOWN-URL");
        }
        ExitCall exitCall;
        if( ! "UNKNOWN-URL".equals(properties.get("URL")) ) {
            exitCall = AppdynamicsAgent.getTransaction().startExitCall( properties, properties.get("URL"), EntryTypes.HTTP, true);
        } else {
            exitCall = AppdynamicsAgent.getTransaction().startExitCall( properties, properties.get("HOST"), EntryTypes.POJO, true);
        }
        if(request != null) {
            Object headers = null;
            try{
                headers = getHeadersReflector.execute(request.getClass().getClassLoader(), request);
                if (headers != null) {
                    getLogger().debug("ExitCall correlation header "+exitCall.getCorrelationHeader());
                    getLogger().debug("Adding correlation header to "+headers.getClass().getName());
                    addReflector.execute(headers.getClass().getClassLoader(), headers, new String[]{CORRELATION_HEADER_KEY, (String) exitCall.getCorrelationHeader()});
                }
            }catch (Exception e){
                getLogger().warn("Problem injecting header into exit call, exception: "+ e,e);
                getLogger().warn("ExitCall correlation header "+exitCall.getCorrelationHeader());
                if(headers != null) getLogger().warn("Adding correlation header to "+headers.getClass().getName());
            }
        }
        this.getLogger().debug("PingAccessExitPointInterceptor.onMethodBegin() end");
        return exitCall;
    }

    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        this.getLogger().debug("PingAccessExitPointInterceptor.onMethodEnd() start");
        ExitCall exitCall = (ExitCall) state;
        if(exitCall == null ) {
            getLogger().warn("State is null in onMethodEnd?!?!? Aborting this exitcall");
            return;
        }
        if( exception != null ) {
            this.getLogger().debug("PingExitPointInterceptor.onMethodEnd() exception found: "+ exception.toString() );
            AppdynamicsAgent.getTransaction().markAsError( exception.toString() );
        }
        Transaction transaction = AppdynamicsAgent.getTransaction();
        CompletionStage<Object> completionStage = (CompletionStage<Object>) returnVal;
        completionStage.whenCompleteAsync( (response, cause ) -> { //this may be in another thread
            if( cause != null ) {
                transaction.markAsError( cause.toString() );
            }
            exitCall.end();
        });
        returnVal = completionStage;
        this.getLogger().debug("PingAccessExitPointInterceptor.onMethodEnd() end");
    }


}

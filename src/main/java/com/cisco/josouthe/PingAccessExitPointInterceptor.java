package com.cisco.josouthe;

import com.appdynamics.agent.api.*;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
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
 * John Southerland
 * Nov 18, 2021 : refactored to use my latest techniques, and help in troubleshooting a customer on v6.1.5
 */
public class PingAccessExitPointInterceptor extends MyBaseInterceptor {

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
        this.getLogger().debug(String.format("onMethodBegin() start method: %s.%s()",className,methodName));
        Transaction transaction = AppdynamicsAgent.getTransaction();
        if( isFakeTransaction(transaction) ) {
            getLogger().info("Oops, No transaction is active right now?");
            return null;
        }
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
            exitCall = transaction.startExitCall( properties, properties.get("URL"), EntryTypes.HTTP, true);
        } else {
            exitCall = transaction.startExitCall( properties, properties.get("HOST"), EntryTypes.POJO, true);
        }
        if(request != null) {
            Object headers = null;
            try{
                headers = getHeadersReflector.execute(request.getClass().getClassLoader(), request);
                if (headers != null) {
                    getLogger().debug("ExitCall correlation header "+exitCall.getCorrelationHeader());
                    getLogger().debug("Adding correlation header to "+headers.getClass().getName());
                    addReflector.execute(headers.getClass().getClassLoader(), headers, new String[]{AppdynamicsAgent.TRANSACTION_CORRELATION_HEADER, (String) exitCall.getCorrelationHeader()});
                }
            }catch (Exception e){
                getLogger().warn("Problem injecting header into exit call, exception: "+ e,e);
                getLogger().warn("ExitCall correlation header "+exitCall.getCorrelationHeader());
                if(headers != null) getLogger().warn("Adding correlation header to "+headers.getClass().getName());
            }
        }
        this.getLogger().debug(String.format("onMethodBegin() end method: %s.%s() exitCall: %s",className,methodName,exitCall.getCorrelationHeader()));
        return new State( transaction, exitCall);
    }

    public void onMethodEnd(Object state, Object object, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        if( state == null ) return;
        Transaction transaction = ((State)state).transaction;
        ExitCall exitCall = ((State)state).exitCall;
        this.getLogger().debug(String.format("onMethodEnd() start method: %s.%s() exitCall: %s",className,methodName,exitCall.getCorrelationHeader()));
        if( exception != null ) {
            this.getLogger().debug("PingExitPointInterceptor.onMethodEnd() exception found: "+ exception.toString() );
            transaction.markAsError( exception.toString() );
        }
        CompletionStage<Object> completionStage = (CompletionStage<Object>) returnVal;
        completionStage.whenCompleteAsync( (response, cause ) -> { //this may be in another thread
            if( cause != null ) {
                transaction.markAsError( cause.toString() );
            }
            exitCall.end();
        });
        returnVal = completionStage;
        this.getLogger().debug(String.format("onMethodEnd() start method: %s.%s() exitCall: completed",className,methodName));
    }

    public class State {
        public Transaction transaction;
        public ExitCall exitCall;
        public State( Transaction transaction, ExitCall exitCall) {
            this.transaction=transaction;
            this.exitCall=exitCall;
        }
    }

}

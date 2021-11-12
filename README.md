Ping Access Agent Plugin
==================================
## Purpose
This AppDynamics iSDK Agent Plugin provides configuration support to create Business Transactions for PingAccess requests and continue correlation both inbound and outbound for the same.
It also supplies useful analytics data, which can be disabled. The volumes can be quite high on that data and it may max out injestion limits for a customer. YMMV

## Required
- Agent version 21.0+
- Java 8


## Deployment steps
- Copy PingAccessPlugin.jar file under <agent-install-dir>/ver.x.x.x.x/sdk-plugins

- Optionally, you can disable analytics collection of the Proxy Name a transaction is a part of, if you don't have analytics you can ignore this, otherwise make sure you want this data:

    -DdisablePingAccessAnalytics=true
    
## Change Log:
# V1.0 - First support BT creation and backend mapping
# V2.0 - Support for Analytics custom data "PingAccess-ProxyName", Backend Async Transaction Mapping, Backend URL naming

Ping Access Agent Plugin
==================================
## Required
- Agent version 21.0+
- Java 8


## Deployment steps
- Copy PingAccessPlugin.jar file under <agent-install-dir>/ver.x.x.x.x/sdk-plugins

- Optionally, you can disable analytics collection of the Proxy Name a transaction is a part of, if you don't have analytics you can ignore this, otherwise make sure you want this data:

    -DdisablePingAccessAnalytics=true
    
Change Log:
V1.0 - First support BT creation and backend mapping
V2.0 - Support for Analytics custom data "PingAccess-ProxyName", Backend Async Transaction Mapping, Backend URL naming

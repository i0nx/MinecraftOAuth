# MinecraftOAuth

A Java library for Minecraft authentication using Microsoft accounts through the OAuth 2.0 flow.

## Overview

MinecraftOAuth provides a simple way to authenticate with Minecraft using Microsoft accounts. It offers three different authentication methods:

1. **AuthenticatorWithGUI** - Uses a Swing-based GUI to guide users through the authentication process
2. **AutomaticAuthenticator** - Opens a browser window and uses a local server to capture the authorization code
3. **MinecraftAuthenticator** - Command-line based authentication requiring manual input of the redirect URL

## Features

- Microsoft account authentication using OAuth 2.0
- Xbox Live and Minecraft authentication
- Retrieval of Minecraft profile information (username, UUID)
- Session token generation for use with Minecraft launchers
- Multiple authentication interfaces to suit different application needs

## Requirements

- Java 8 or higher
- Maven (for building)
- Dependencies:
  - Google Gson (2.10.1)
  - JavaFX (17.0.2)
  - Apache HttpClient (4.5.13)

## Installation

Add the following to your `pom.xml`:

```xml
<dependency>
    <groupId>xyz.zeyso</groupId>
    <artifactId>MinecraftOAuth</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```
## Usage
### GUI Authentication
import xyz.zeyso.AuthenticatorWithGUI;
```
public class Example {
    public static void main(String[] args) {
        try {
            AuthenticatorWithGUI authenticator = new AuthenticatorWithGUI();
            authenticator.authenticate();

            // Retrieve authentication results
            String token = authenticator.getMinecraftToken();
            String uuid = authenticator.getMinecraftUUID();
            String username = authenticator.getMinecraftUsername();

            // Get session JSON for use with launchers
            String sessionJson = authenticator.getSessionJson();

            System.out.println("Logged in as: " + username);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```
### Automatic Authentication
```
import xyz.zeyso.AutomaticAuthenticator;

public class Example {
    public static void main(String[] args) {
        try {
            AutomaticAuthenticator authenticator = new AutomaticAuthenticator();
            authenticator.authenticate();

            String token = authenticator.getMinecraftToken();
            String uuid = authenticator.getMinecraftUUID();
            String username = authenticator.getMinecraftUsername();

            System.out.println("Logged in as: " + username);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```
### Command-line Authentication
```
import xyz.zeyso.MinecraftAuthenticator;

public class Example {
    public static void main(String[] args) {
        try {
            MinecraftAuthenticator authenticator = new MinecraftAuthenticator();
            authenticator.authenticate();

            String token = authenticator.getMinecraftToken();
            String uuid = authenticator.getMinecraftUUID();
            String username = authenticator.getMinecraftUsername();

            System.out.println("Logged in as: " + username);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```
## Authentication Flow
1. User logs in with Microsoft account
2. Microsoft provides an authorization code
3.  Code is exchanged for a Microsoft access token
4. Token is used to authenticate with Xbox Live
5. Xbox Live token is used to get an XSTS token
6. XSTS token is used to authenticate with Minecraft
7. Minecraft profile information is retrieved

## Custom Client ID
By default, the library uses Microsoft's official Minecraft client ID. 
You can provide your own client ID if needed:
```
AutomaticAuthenticator authenticator = new AutomaticAuthenticator("your-client-id");
```
## License
This project is open source. Please respect Microsoft's and Mojang's terms of service when using this library.
## Notes
This authentication flow is the official Microsoft OAuth flow used by the Minecraft Launcher.

When using this library in production applications, handle exceptions properly and provide appropriate feedback to users

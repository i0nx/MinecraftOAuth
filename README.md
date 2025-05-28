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

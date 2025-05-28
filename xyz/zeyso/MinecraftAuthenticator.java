package xyz.zeyso;


import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class MinecraftAuthenticator {
    // Microsoft's official Minecraft client ID - public and safe to use
    private static final String CLIENT_ID = "00000000441cc96b";
    // Official redirect URI for Minecraft launcher
    private static final String REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";
    private static final int PORT = 8080;

    private String accessToken;
    private String xboxToken;
    private String xstsToken;
    private String minecraftToken;
    private String minecraftUUID;
    private String minecraftUsername;
    private CompletableFuture<String> authCodeFuture = new CompletableFuture<>();
    private HttpServer server;

    public void authenticate() throws Exception {
        // 1. Start local server and get Microsoft OAuth code
        startLocalServer();
        String authCode = authCodeFuture.get(); // Wait for auth code

        // 2. Exchange authorization code for Microsoft token
        exchangeAuthorizationCode(authCode);

        // 3. Authenticate with Xbox Live
        authenticateWithXboxLive();

        // 4. Get XSTS token
        getXSTSToken();

        // 5. Authenticate with Minecraft
        authenticateWithMinecraft();

        // 6. Get Minecraft profile
        fetchMinecraftProfile();

        System.out.println("Authentication successful!");
        System.out.println("Username: " + minecraftUsername);
        System.out.println("UUID: " + minecraftUUID);
        System.out.println("Session Token: " + minecraftToken);
    }

    private void startLocalServer() throws IOException {
        // We'll use a local server to capture the authorization code
        server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/callback", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String response;

            if (query != null && query.contains("code=")) {
                String authCode = query.substring(query.indexOf("code=") + 5);
                if (authCode.contains("&")) {
                    authCode = authCode.substring(0, authCode.indexOf("&"));
                }

                authCodeFuture.complete(authCode);
                response = "<html><body><h1>Authentication successful!</h1><p>You can close this window now.</p></body></html>";
            } else {
                authCodeFuture.completeExceptionally(new RuntimeException("No authorization code received"));
                response = "<html><body><h1>Authentication failed!</h1><p>No authorization code received.</p></body></html>";
            }

            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        server.start();

        // Generate a random state for security
        String state = UUID.randomUUID().toString();

        // Create the authorization URL with the official redirect URI
        // After auth, Microsoft will redirect to this URI with the code in the URL
        String authUrl = "https://login.live.com/oauth20_authorize.srf" +
                "?client_id=" + CLIENT_ID +
                "&response_type=code" +
                "&redirect_uri=" + REDIRECT_URI +
                "&scope=XboxLive.signin%20offline_access" +
                "&state=" + state;

        // Open browser for login
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(authUrl));
            System.out.println("1. A browser window should have opened. Please log in to your Microsoft account.");
            System.out.println("2. After logging in, you'll be redirected to a blank page.");
            System.out.println("3. Copy the ENTIRE URL of that blank page and paste it here:");

            // Read the redirected URL from console
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String redirectedUrl = reader.readLine();

            // Extract the authorization code from the URL
            if (redirectedUrl.contains("code=")) {
                String authCode = redirectedUrl.substring(redirectedUrl.indexOf("code=") + 5);
                if (authCode.contains("&")) {
                    authCode = authCode.substring(0, authCode.indexOf("&"));
                }
                authCodeFuture.complete(authCode);
            } else {
                authCodeFuture.completeExceptionally(new RuntimeException("No authorization code in the URL"));
            }
        } catch (Exception e) {
            System.out.println("Please open this URL in your browser: " + authUrl);
            System.out.println("After logging in, copy the redirected URL and paste it here:");

            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String redirectedUrl = reader.readLine();

            if (redirectedUrl.contains("code=")) {
                String authCode = redirectedUrl.substring(redirectedUrl.indexOf("code=") + 5);
                if (authCode.contains("&")) {
                    authCode = authCode.substring(0, authCode.indexOf("&"));
                }
                authCodeFuture.complete(authCode);
            } else {
                authCodeFuture.completeExceptionally(new RuntimeException("No authorization code in the URL"));
            }
        }
    }

    private void exchangeAuthorizationCode(String authCode) throws IOException {
        URL url = new URL("https://login.live.com/oauth20_token.srf");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setDoOutput(true);

        String requestBody = "client_id=" + CLIENT_ID +
                "&code=" + authCode +
                "&grant_type=authorization_code" +
                "&redirect_uri=" + REDIRECT_URI;

        try (OutputStream os = connection.getOutputStream()) {
            os.write(requestBody.getBytes());
        }

        if (connection.getResponseCode() != 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                throw new IOException("Failed to exchange code: " + connection.getResponseCode() + " - " + response);
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();
            accessToken = jsonResponse.get("access_token").getAsString();
        }

        if (server != null) {
            server.stop(0); // Stop the local server
        }
    }

    private void authenticateWithXboxLive() throws IOException {
        URL url = new URL("https://user.auth.xboxlive.com/user/authenticate");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        String requestBody = "{"
                + "\"Properties\": {"
                + "\"AuthMethod\": \"RPS\","
                + "\"SiteName\": \"user.auth.xboxlive.com\","
                + "\"RpsTicket\": \"d=" + accessToken + "\""
                + "},"
                + "\"RelyingParty\": \"http://auth.xboxlive.com\","
                + "\"TokenType\": \"JWT\""
                + "}";

        try (OutputStream os = connection.getOutputStream()) {
            os.write(requestBody.getBytes());
        }

        if (connection.getResponseCode() != 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                throw new IOException("Xbox Live authentication failed: " + connection.getResponseCode() + " - " + response);
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();
            xboxToken = jsonResponse.get("Token").getAsString();

            // Get user hash (uhs) from DisplayClaims
            String uhs = jsonResponse.getAsJsonObject("DisplayClaims")
                    .getAsJsonArray("xui")
                    .get(0).getAsJsonObject()
                    .get("uhs").getAsString();

            // Store the user hash for later use
            xstsToken = uhs;
        }
    }

    private void getXSTSToken() throws IOException {
        URL url = new URL("https://xsts.auth.xboxlive.com/xsts/authorize");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        String requestBody = "{"
                + "\"Properties\": {"
                + "\"SandboxId\": \"RETAIL\","
                + "\"UserTokens\": [\"" + xboxToken + "\"]"
                + "},"
                + "\"RelyingParty\": \"rp://api.minecraftservices.com/\","
                + "\"TokenType\": \"JWT\""
                + "}";

        try (OutputStream os = connection.getOutputStream()) {
            os.write(requestBody.getBytes());
        }

        if (connection.getResponseCode() != 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                throw new IOException("XSTS authentication failed: " + connection.getResponseCode() + " - " + response);
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();
            String token = jsonResponse.get("Token").getAsString();
            String uhs = xstsToken; // Using the previously stored user hash

            // Format the identity token for Minecraft authentication
            xboxToken = "XBL3.0 x=" + uhs + ";" + token;
        }
    }

    private void authenticateWithMinecraft() throws IOException {
        URL url = new URL("https://api.minecraftservices.com/authentication/login_with_xbox");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        String requestBody = "{\"identityToken\": \"" + xboxToken + "\"}";

        try (OutputStream os = connection.getOutputStream()) {
            os.write(requestBody.getBytes());
        }

        if (connection.getResponseCode() != 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                throw new IOException("Minecraft authentication failed: " + connection.getResponseCode() + " - " + response);
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();
            minecraftToken = jsonResponse.get("access_token").getAsString();
        }
    }

    private void fetchMinecraftProfile() throws IOException {
        URL url = new URL("https://api.minecraftservices.com/minecraft/profile");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + minecraftToken);

        if (connection.getResponseCode() != 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                throw new IOException("Failed to fetch Minecraft profile: " + connection.getResponseCode() + " - " + response);
            }
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            JsonObject jsonResponse = JsonParser.parseString(response.toString()).getAsJsonObject();
            minecraftUUID = jsonResponse.get("id").getAsString();
            minecraftUsername = jsonResponse.get("name").getAsString();
        }
    }

    public String getMinecraftToken() {
        return minecraftToken;
    }

    public String getMinecraftUUID() {
        return minecraftUUID;
    }

    public String getMinecraftUsername() {
        return minecraftUsername;
    }
}

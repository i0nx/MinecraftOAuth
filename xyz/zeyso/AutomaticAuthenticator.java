package xyz.zeyso;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutomaticAuthenticator {
    // Default client ID for Xbox Live
    private static final String DEFAULT_CLIENT_ID = "00000000441cc96b";
    private static final String LOCAL_REDIRECT_URI = "http://localhost:8123/callback";
    private static final Pattern CODE_PATTERN = Pattern.compile("[?&]code=([^&]+)");

    private final String clientId;
    private final String clientSecret;
    private final boolean useClientSecret;

    private String accessToken;
    private String xboxToken;
    private String userHash;
    private String xstsToken;
    private String minecraftToken;
    private String minecraftUUID;
    private String minecraftUsername;

    private com.sun.net.httpserver.HttpServer server;
    private final int PORT = 8123;
    private final HttpClient httpClient;

    /**
     * Creates an authenticator with default Xbox client ID and no client secret
     */
    public AutomaticAuthenticator() {
        this(DEFAULT_CLIENT_ID, null);
    }

    /**
     * Creates an authenticator with the specified client ID and no client secret
     *
     * @param clientId The client ID to use for authentication
     */
    public AutomaticAuthenticator(String clientId) {
        this(clientId, null);
    }

    /**
     * Creates an authenticator with the specified client ID and client secret
     *
     * @param clientId The client ID to use for authentication
     * @param clientSecret The client secret to use for authentication (can be null)
     */
    public AutomaticAuthenticator(String clientId, String clientSecret) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.useClientSecret = clientSecret != null && !clientSecret.isEmpty();
        this.httpClient = HttpClients.createDefault();
    }

    public void authenticate() throws Exception {
        System.out.println("Starting authentication...");

        String authCode = getMicrosoftAuthCode();
        System.out.println("Authorization code received");

        exchangeAuthorizationCode(authCode);
        authenticateWithXboxLive();
        getXSTSToken();
        authenticateWithMinecraft();
        fetchMinecraftProfile();

        System.out.println("Authentication successful!");
        System.out.println("Username: " + minecraftUsername);
        System.out.println("UUID: " + minecraftUUID);
    }

    private String getMicrosoftAuthCode() throws Exception {
        String state = UUID.randomUUID().toString();
        String encodedRedirect = URLEncoder.encode(LOCAL_REDIRECT_URI, StandardCharsets.UTF_8.name());

        // Build the auth URL - using localhost as redirect
        String authUrl = "https://login.live.com/oauth20_authorize.srf" +
                "?client_id=" + clientId +
                "&response_type=code" +
                "&redirect_uri=" + encodedRedirect +
                "&scope=XboxLive.signin%20offline_access" +
                "&state=" + state;

        if (useClientSecret) {
            authUrl += "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8.name());
        }

        CompletableFuture<String> authCodeFuture = new CompletableFuture<>();

        // Start local server to receive the redirect
        startLocalServer(authCodeFuture);

        try {
            System.out.println("Please authorize in your browser...");
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(authUrl));
            } else {
                System.out.println("Please open this URL manually: " + authUrl);
            }

            return authCodeFuture.get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new IOException("Authentication timed out after 5 minutes", e);
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    private void startLocalServer(CompletableFuture<String> authCodeFuture) throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(PORT), 0);

        // Handle the callback route
        server.createContext("/callback", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String response;

            if (query != null && query.contains("code=")) {
                Matcher matcher = CODE_PATTERN.matcher(query);
                if (matcher.find() && !authCodeFuture.isDone()) {
                    String code = matcher.group(1);
                    authCodeFuture.complete(code);
                    response = "<!DOCTYPE html><html><head><title>Authentication Successful</title></head>" +
                            "<body><h1>Authentication Successful</h1>" +
                            "<p>You can close this window now.</p>" +
                            "<script>window.close();</script></body></html>";
                } else {
                    response = "<!DOCTYPE html><html><head><title>Authentication Error</title></head>" +
                            "<body><h1>Authentication Error</h1>" +
                            "<p>Failed to extract authorization code.</p></body></html>";
                }
            } else {
                response = "<!DOCTYPE html><html><head><title>Authentication Error</title></head>" +
                        "<body><h1>Authentication Error</h1>" +
                        "<p>No authorization code received.</p></body></html>";
            }

            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });

        // Serve a basic page for the root path
        server.createContext("/", exchange -> {
            String response = "<!DOCTYPE html><html><head><title>Minecraft Authentication</title></head>" +
                    "<body><h1>Minecraft Authentication Server</h1>" +
                    "<p>This is a local authentication server for Minecraft login.</p></body></html>";

            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes(StandardCharsets.UTF_8));
            }
        });

        server.setExecutor(null);
        server.start();
        System.out.println("Local authentication server running on http://localhost:" + PORT);
    }

    private void exchangeAuthorizationCode(String authCode) throws IOException {
        System.out.println("Exchanging authorization code for access token...");

        HttpPost request = new HttpPost("https://login.live.com/oauth20_token.srf");
        request.setHeader("Content-Type", "application/x-www-form-urlencoded");

        String encodedRedirect = URLEncoder.encode(LOCAL_REDIRECT_URI, StandardCharsets.UTF_8.name());
        StringBuilder requestBodyBuilder = new StringBuilder();
        requestBodyBuilder.append("client_id=").append(clientId)
                .append("&code=").append(authCode)
                .append("&grant_type=authorization_code")
                .append("&redirect_uri=").append(encodedRedirect);

        if (useClientSecret) {
            requestBodyBuilder.append("&client_secret=").append(clientSecret);
        }

        request.setEntity(new StringEntity(requestBodyBuilder.toString()));

        HttpResponse response = httpClient.execute(request);
        int statusCode = response.getStatusLine().getStatusCode();

        if (statusCode != 200) {
            String errorResponse = EntityUtils.toString(response.getEntity());
            throw new IOException("Failed to exchange code: " + statusCode + " - " + errorResponse);
        }

        String responseBody = EntityUtils.toString(response.getEntity());
        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
        accessToken = jsonResponse.get("access_token").getAsString();

        System.out.println("Access token received successfully");
    }

    private void authenticateWithXboxLive() throws IOException {
        System.out.println("Authenticating with Xbox Live...");

        HttpPost request = new HttpPost("https://user.auth.xboxlive.com/user/authenticate");
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Accept", "application/json");

        String requestBody = "{" +
                "\"Properties\": {" +
                "\"AuthMethod\": \"RPS\"," +
                "\"SiteName\": \"user.auth.xboxlive.com\"," +
                "\"RpsTicket\": \"d=" + accessToken + "\"" +
                "}," +
                "\"RelyingParty\": \"http://auth.xboxlive.com\"," +
                "\"TokenType\": \"JWT\"" +
                "}";
        request.setEntity(new StringEntity(requestBody));

        HttpResponse response = httpClient.execute(request);
        int statusCode = response.getStatusLine().getStatusCode();

        if (statusCode != 200) {
            String errorResponse = EntityUtils.toString(response.getEntity());
            throw new IOException("Xbox Live authentication failed: " + statusCode + " - " + errorResponse);
        }

        String responseBody = EntityUtils.toString(response.getEntity());
        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
        xboxToken = jsonResponse.get("Token").getAsString();
        userHash = jsonResponse.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui")
                .get(0)
                .getAsJsonObject()
                .get("uhs")
                .getAsString();

        System.out.println("Xbox Live authentication successful");
    }

    private void getXSTSToken() throws IOException {
        System.out.println("Getting XSTS token...");

        HttpPost request = new HttpPost("https://xsts.auth.xboxlive.com/xsts/authorize");
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Accept", "application/json");

        String requestBody = "{" +
                "\"Properties\": {" +
                "\"SandboxId\": \"RETAIL\"," +
                "\"UserTokens\": [\"" + xboxToken + "\"]" +
                "}," +
                "\"RelyingParty\": \"rp://api.minecraftservices.com/\"," +
                "\"TokenType\": \"JWT\"" +
                "}";
        request.setEntity(new StringEntity(requestBody));

        HttpResponse response = httpClient.execute(request);
        int statusCode = response.getStatusLine().getStatusCode();

        if (statusCode != 200) {
            String errorResponse = EntityUtils.toString(response.getEntity());
            throw new IOException("XSTS authentication failed: " + statusCode + " - " + errorResponse);
        }

        String responseBody = EntityUtils.toString(response.getEntity());
        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
        xstsToken = jsonResponse.get("Token").getAsString();

        System.out.println("XSTS token received");
    }

    private void authenticateWithMinecraft() throws IOException {
        System.out.println("Authenticating with Minecraft services...");

        HttpPost request = new HttpPost("https://api.minecraftservices.com/authentication/login_with_xbox");
        request.setHeader("Content-Type", "application/json");
        request.setHeader("Accept", "application/json");

        String identityToken = "XBL3.0 x=" + userHash + ";" + xstsToken;
        String requestBody = "{\"identityToken\": \"" + identityToken + "\"}";
        request.setEntity(new StringEntity(requestBody));

        HttpResponse response = httpClient.execute(request);
        int statusCode = response.getStatusLine().getStatusCode();

        if (statusCode != 200) {
            String errorResponse = EntityUtils.toString(response.getEntity());
            throw new IOException("Minecraft authentication failed: " + statusCode + " - " + errorResponse);
        }

        String responseBody = EntityUtils.toString(response.getEntity());
        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
        minecraftToken = jsonResponse.get("access_token").getAsString();

        System.out.println("Minecraft authentication successful");
    }

    private void fetchMinecraftProfile() throws IOException {
        System.out.println("Fetching Minecraft profile...");

        HttpGet request = new HttpGet("https://api.minecraftservices.com/minecraft/profile");
        request.setHeader("Authorization", "Bearer " + minecraftToken);

        HttpResponse response = httpClient.execute(request);
        int statusCode = response.getStatusLine().getStatusCode();

        if (statusCode != 200) {
            String errorResponse = EntityUtils.toString(response.getEntity());
            throw new IOException("Failed to fetch Minecraft profile: " + statusCode + " - " + errorResponse);
        }

        String responseBody = EntityUtils.toString(response.getEntity());
        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
        minecraftUUID = jsonResponse.get("id").getAsString();
        minecraftUsername = jsonResponse.get("name").getAsString();

        System.out.println("Minecraft profile retrieved successfully");
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

    public String getSessionJson() {
        JsonObject session = new JsonObject();
        session.addProperty("accessToken", minecraftToken);

        String formattedUuid = minecraftUUID;
        if (minecraftUUID != null && minecraftUUID.length() == 32) {
            formattedUuid = minecraftUUID.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                    "$1-$2-$3-$4-$5");
        }

        session.addProperty("uuid", formattedUuid);
        session.addProperty("username", minecraftUsername);
        return session.toString();
    }
}
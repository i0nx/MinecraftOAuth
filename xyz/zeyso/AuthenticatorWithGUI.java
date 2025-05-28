package xyz.zeyso;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuthenticatorWithGUI {
    // Microsoft's official client ID for Minecraft
    private static final String CLIENT_ID = "00000000441cc96b";
    // Using official Minecraft redirect
    private static final String REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";
    // Pattern to extract code from URL
    private final Pattern CODE_PATTERN = Pattern.compile("[?&]code=([^&]+)");

    private String accessToken;
    private String xboxToken;
    private String userHash;
    private String xstsToken;
    private String minecraftToken;
    private String minecraftUUID;
    private String minecraftUsername;
    private JFrame browserFrame;
    private CompletableFuture<String> authCodeFuture = new CompletableFuture<>();
    private boolean hasLoadedPage = false;

    public void authenticate() throws Exception {
        System.out.println("Starting Minecraft authentication...");

        // Open custom browser and get auth code
        String authCode = getMicrosoftAuthCode();

        // Exchange code for access token
        exchangeAuthorizationCode(authCode);

        // Get Xbox Live token
        authenticateWithXboxLive();

        // Get XSTS token
        getXSTSToken();

        // Get Minecraft token
        authenticateWithMinecraft();

        // Get profile info
        fetchMinecraftProfile();

        System.out.println("Authentication successful!");
        System.out.println("Username: " + minecraftUsername);
        System.out.println("UUID: " + minecraftUUID);
    }

    private String getMicrosoftAuthCode() throws Exception {
        // Create Microsoft OAuth URL with official redirect URI
        String encodedRedirect = URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8.name());
        String authUrl = "https://login.live.com/oauth20_authorize.srf" +
                "?client_id=" + CLIENT_ID +
                "&response_type=code" +
                "&redirect_uri=" + encodedRedirect +
                "&scope=XboxLive.signin%20offline_access";

        System.out.println("Opening system browser for Microsoft authentication...");
        System.out.println("Auth URL: " + authUrl);

        // Create a simple UI to prompt for the auth code
        JFrame frame = new JFrame("Minecraft Authentication");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(600, 400);
        frame.setLocationRelativeTo(null);

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JTextArea instructions = new JTextArea();
        instructions.setText("1. Click the button below to open the Microsoft login page in your browser\n" +
                "2. Login with your Microsoft account\n" +
                "3. After successful login, you'll be redirected to a page\n" +
                "4. Copy the ENTIRE URL from your browser's address bar\n" +
                "5. Paste it in the text field below and click Submit");
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setBackground(panel.getBackground());
        instructions.setFont(new Font("Arial", Font.PLAIN, 14));
        panel.add(instructions, BorderLayout.NORTH);

        JButton openBrowserButton = new JButton("Open Login Page in Browser");
        openBrowserButton.setFont(new Font("Arial", Font.BOLD, 14));
        openBrowserButton.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(new URI(authUrl));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame,
                        "Error opening browser: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.add(openBrowserButton, BorderLayout.NORTH);

        JLabel urlLabel = new JLabel("Paste the redirect URL here:");
        urlLabel.setFont(new Font("Arial", Font.PLAIN, 14));

        JTextField urlField = new JTextField();
        urlField.setFont(new Font("Arial", Font.PLAIN, 14));

        JButton submitButton = new JButton("Submit");
        submitButton.setFont(new Font("Arial", Font.BOLD, 14));
        submitButton.addActionListener(e -> {
            String url = urlField.getText().trim();
            if (url.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please enter the redirect URL",
                        "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Matcher matcher = CODE_PATTERN.matcher(url);
            if (matcher.find()) {
                String authCode = matcher.group(1);
                System.out.println("Authorization code captured!");
                authCodeFuture.complete(authCode);
                frame.dispose();
            } else {
                JOptionPane.showMessageDialog(frame,
                        "Could not find authorization code in URL. Make sure you copied the entire URL.",
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        JPanel urlInputPanel = new JPanel(new BorderLayout(5, 5));
        urlInputPanel.add(urlLabel, BorderLayout.NORTH);
        urlInputPanel.add(urlField, BorderLayout.CENTER);
        urlInputPanel.add(submitButton, BorderLayout.EAST);

        centerPanel.add(urlInputPanel, BorderLayout.CENTER);
        panel.add(centerPanel, BorderLayout.CENTER);

        JLabel statusLabel = new JLabel("Waiting for authorization...");
        statusLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        panel.add(statusLabel, BorderLayout.SOUTH);

        frame.add(panel);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!authCodeFuture.isDone()) {
                    authCodeFuture.completeExceptionally(
                            new IOException("Authentication window closed by user"));
                }
            }
        });

        frame.setVisible(true);

        // Wait for the auth code (timeout after 10 minutes)
        try {
            System.out.println("Waiting for user to paste the redirect URL...");
            return authCodeFuture.get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            if (frame.isDisplayable()) {
                frame.dispose();
            }
            throw new IOException("Authentication timed out or failed: " + e.getMessage());
        }
    }

    private void exchangeAuthorizationCode(String authCode) throws IOException {
        System.out.println("Exchanging authorization code for access token...");

        URL url = new URL("https://login.live.com/oauth20_token.srf");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setDoOutput(true);

        String encodedRedirect = URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8.name());
        String requestBody = "client_id=" + CLIENT_ID +
                "&code=" + authCode +
                "&grant_type=authorization_code" +
                "&redirect_uri=" + encodedRedirect;

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

        // Dispose browser window if still open
        if (browserFrame != null && browserFrame.isDisplayable()) {
            browserFrame.dispose();
            browserFrame = null;
        }

        System.out.println("Access token received successfully!");
    }

    private void authenticateWithXboxLive() throws IOException {
        System.out.println("Authenticating with Xbox Live...");

        URL url = new URL("https://user.auth.xboxlive.com/user/authenticate");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        String requestBody = "{" +
                "\"Properties\": {" +
                "\"AuthMethod\": \"RPS\"," +
                "\"SiteName\": \"user.auth.xboxlive.com\"," +
                "\"RpsTicket\": \"d=" + accessToken + "\"" +
                "}," +
                "\"RelyingParty\": \"http://auth.xboxlive.com\"," +
                "\"TokenType\": \"JWT\"" +
                "}";

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

            // Get user hash from the response
            userHash = jsonResponse.getAsJsonObject("DisplayClaims")
                    .getAsJsonArray("xui")
                    .get(0).getAsJsonObject()
                    .get("uhs").getAsString();
        }

        System.out.println("Xbox Live authentication successful!");
    }

    private void getXSTSToken() throws IOException {
        System.out.println("Getting XSTS token...");

        URL url = new URL("https://xsts.auth.xboxlive.com/xsts/authorize");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        String requestBody = "{" +
                "\"Properties\": {" +
                "\"SandboxId\": \"RETAIL\"," +
                "\"UserTokens\": [\"" + xboxToken + "\"]" +
                "}," +
                "\"RelyingParty\": \"rp://api.minecraftservices.com/\"," +
                "\"TokenType\": \"JWT\"" +
                "}";

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
            xstsToken = jsonResponse.get("Token").getAsString();
        }

        System.out.println("XSTS token received!");
    }

    private void authenticateWithMinecraft() throws IOException {
        System.out.println("Authenticating with Minecraft services...");

        URL url = new URL("https://api.minecraftservices.com/authentication/login_with_xbox");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);

        // Create the identity token in the format expected by Minecraft
        String identityToken = "XBL3.0 x=" + userHash + ";" + xstsToken;
        String requestBody = "{\"identityToken\": \"" + identityToken + "\"}";

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

        System.out.println("Minecraft authentication successful!");
    }

    private void fetchMinecraftProfile() throws IOException {
        System.out.println("Fetching Minecraft profile...");

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

        System.out.println("Minecraft profile retrieved successfully!");
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

        // Format UUID with hyphens as commonly required by launchers
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
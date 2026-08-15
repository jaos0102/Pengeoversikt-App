import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class Main {

    private static final String ACCOUNT_UID = "00f52b9e-cbab-4935-a112-f552755f3569";
    private static final String APP_ID = "4c12479b-44bd-4d93-b161-03623c8bf939";
    
    private static final String ACCOUNT_NAME = "min_brukskonto_nordea";
    private static final String FILENAME = "transactions_" + ACCOUNT_NAME + ".json";

    public static void main(String[] args) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Path filePath = Paths.get(FILENAME);
            
            Map<String, JsonNode> transactionMap = new LinkedHashMap<>();
            String dateFrom = "2026-01-01"; 

            // 1. LES EKSISTERENDE FIL
            if (Files.exists(filePath)) {
                System.out.println("Fant eksisterende fil. Leser inn data...");
                JsonNode root = mapper.readTree(Files.readAllBytes(filePath));
                JsonNode existingTransactions = root.get("transactions");
                
                String maxDate = "2000-01-01";
                
                if (existingTransactions != null && existingTransactions.isArray()) {
                    for (JsonNode t : existingTransactions) {
                        String id = getTransactionId(t);
                        transactionMap.put(id, t);
                        
                        if (t.hasNonNull("booking_date")) {
                            String bDate = t.get("booking_date").asText();
                            if (bDate.compareTo(maxDate) > 0) {
                                maxDate = bDate;
                            }
                        }
                    }
                }
                
                if (!maxDate.equals("2000-01-01")) {
                    dateFrom = maxDate; 
                    System.out.println("Henter nye hendelser fra: " + dateFrom);
                }
            } else {
                System.out.println("Oppretter ny database-fil...");
            }

            // 2. HENT FRA BANKEN
            RSAPrivateKey privateKey = readPrivateKey("private_key.pem");
            long now = Instant.now().getEpochSecond();
            Algorithm algorithm = Algorithm.RSA256(null, privateKey);
            String jwt = JWT.create()
                    .withKeyId(APP_ID)
                    .withIssuer("enablebanking.com")
                    .withAudience("api.enablebanking.com")
                    .withIssuedAt(Instant.ofEpochSecond(now))
                    .withExpiresAt(Instant.ofEpochSecond(now + 3600))
                    .sign(algorithm);

            String baseUrl = "https://api.enablebanking.com/accounts/" + ACCOUNT_UID + "/transactions?date_from=" + dateFrom;
            HttpClient client = HttpClient.newHttpClient();
            String continuationKey = null;
            int nyeTransaksjoner = 0;

            System.out.println("--- STARTER HENTING AV TRANSAKSJONER ---");

            do {
                String url = baseUrl;
                if (continuationKey != null && !continuationKey.isEmpty()) {
                    url += "&continuation_key=" + continuationKey;
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + jwt)
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode rootNode = mapper.readTree(response.body());
                    JsonNode transactions = rootNode.get("transactions");
                    
                    if (transactions != null && transactions.isArray()) {
                        for (JsonNode t : transactions) {
                            String id = getTransactionId(t);
                            if (!transactionMap.containsKey(id)) {
                                nyeTransaksjoner++;
                            }
                            transactionMap.put(id, t);
                        }
                    }

                    JsonNode contKeyNode = rootNode.get("continuation_key");
                    if (contKeyNode != null && !contKeyNode.isNull() && !contKeyNode.asText().isEmpty()) {
                        continuationKey = contKeyNode.asText();
                    } else {
                        continuationKey = null; 
                    }
                } else {
                    System.out.println("Feil ved henting: " + response.body());
                    break;
                }
            } while (continuationKey != null);

            // 3. LAGRE FILEN
            ArrayNode allTransactions = mapper.createArrayNode();
            for (JsonNode t : transactionMap.values()) {
                allTransactions.add(t);
            }

            ObjectNode finalJson = mapper.createObjectNode();
            finalJson.set("transactions", allTransactions);
            
            String finalJsonString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(finalJson);
            Files.write(filePath, finalJsonString.getBytes());
            
            System.out.println("\nFerdig!");
            System.out.println("Lagt til / oppdatert " + nyeTransaksjoner + " transaksjoner i denne kjøringen.");
            System.out.println("Filen '" + FILENAME + "' inneholder nå totalt " + transactionMap.size() + " transaksjoner.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- NY OG FORBEDRET ID-HENTER ---
    private static String getTransactionId(JsonNode t) {
        // 1. Sjekk om transaction_id finnes og er gyldig
        if (t.hasNonNull("transaction_id") && !t.get("transaction_id").asText().isEmpty()) {
            return t.get("transaction_id").asText();
        }
        // 2. Fallback: Noen banker bruker entry_reference
        if (t.hasNonNull("entry_reference") && !t.get("entry_reference").asText().isEmpty()) {
            return t.get("entry_reference").asText();
        }
        // 3. Siste utvei: Lag en unik signatur basert på innholdet i transaksjonen
        return String.valueOf(t.toString().hashCode());
    }

    private static RSAPrivateKey readPrivateKey(String filename) throws Exception {
        String key = new String(Files.readAllBytes(Paths.get(filename)));
        key = key.replace("-----BEGIN PRIVATE KEY-----", "")
                 .replace("-----END PRIVATE KEY-----", "")
                 .replaceAll("\\s+", "");

        byte[] keyBytes = Base64.getDecoder().decode(key);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) kf.generatePrivate(spec);
    }
}
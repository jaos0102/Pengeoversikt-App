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
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

public class Main {

    private static final String ACCOUNT_UID = "00f52b9e-cbab-4935-a112-f552755f3569";
    private static final String APP_ID = "4c12479b-44bd-4d93-b161-03623c8bf939";

    public static void main(String[] args) {
        try {
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

            String dateFrom = "2026-01-01";
            String baseUrl = "https://api.enablebanking.com/accounts/" + ACCOUNT_UID + "/transactions?date_from=" + dateFrom;
            
            HttpClient client = HttpClient.newHttpClient();
            ObjectMapper mapper = new ObjectMapper();
            
            // Her skal vi samle opp alle transaksjonene fra alle sidene
            ArrayNode allTransactions = mapper.createArrayNode();
            String continuationKey = null;
            int pageCounter = 1;

            System.out.println("--- STARTER HENTING AV TRANSAKSJONER FRA NORDEA ---");

            // Løkken kjører så lenge vi har en continuationKey
            do {
                String url = baseUrl;
                if (continuationKey != null && !continuationKey.isEmpty()) {
                    // Legger til nøkkelen i URL-en for å hente neste side
                    url += "&continuation_key=" + continuationKey;
                }

                System.out.println("Henter side " + pageCounter + "...");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + jwt)
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode rootNode = mapper.readTree(response.body());
                    
                    // Trekk ut transaksjonene for denne siden og legg dem til i hovedlisten
                    JsonNode transactions = rootNode.get("transactions");
                    if (transactions != null && transactions.isArray()) {
                        allTransactions.addAll((ArrayNode) transactions);
                    }

                    // Se etter ny continuation_key for neste runde
                    JsonNode contKeyNode = rootNode.get("continuation_key");
                    if (contKeyNode != null && !contKeyNode.isNull() && !contKeyNode.asText().isEmpty()) {
                        continuationKey = contKeyNode.asText();
                    } else {
                        // Ingen nøkkel = vi har nådd slutten!
                        continuationKey = null; 
                    }
                } else {
                    System.out.println("Feil ved henting av side " + pageCounter + " (Status " + response.statusCode() + "):");
                    System.out.println(response.body());
                    break; // Avbryter løkken ved feil
                }
                
                pageCounter++;

            } while (continuationKey != null);

            // Til slutt: Lagre alle innsamlede transaksjoner til filen
            ObjectNode finalJson = mapper.createObjectNode();
            finalJson.set("transactions", allTransactions);
            
            // Bruker .writerWithDefaultPrettyPrinter() for at JSON-filen skal bli pen og lesbar
            String finalJsonString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(finalJson);
            Files.write(Paths.get("transactions.json"), finalJsonString.getBytes());
            
            System.out.println("\nSuksess! Hentet totalt " + allTransactions.size() + " transaksjoner.");
            System.out.println("All data er nå samlet og lagret i filen: transactions.json");

        } catch (Exception e) {
            e.printStackTrace();
        }
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
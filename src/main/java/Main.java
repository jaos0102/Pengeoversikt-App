import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    // === LIM INN KONTO-ID / UID HER FRA SVARET DU FIKK I STED ===
    // Du finner den inni "accounts"-listen i terminalen din
    private static final String ACCOUNT_UID = "dcbba9a3-240d-4d48-8062-79788673c749";

public static void main(String[] args) {
        try {
            String applicationId = "cf657643-df14-4bb1-b1b9-c4288953b78b";
            RSAPrivateKey privateKey = readPrivateKey("private_key.pem");

            long now = Instant.now().getEpochSecond();

            Algorithm algorithm = Algorithm.RSA256(null, privateKey);
            String jwt = JWT.create()
                    .withKeyId(applicationId)
                    .withIssuer("enablebanking.com")
                    .withAudience("api.enablebanking.com")
                    .withIssuedAt(Instant.ofEpochSecond(now))
                    .withExpiresAt(Instant.ofEpochSecond(now + 3600))
                    .sign(algorithm);

            System.out.println("Henter transaksjoner...\n");
            getTransactions(jwt, ACCOUNT_UID);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void getTransactions(String jwt, String accountUid) throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.enablebanking.com/accounts/" + accountUid + "/transactions"))
                .header("Authorization", "Bearer " + jwt)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            printTransactionsTable(response.body());
        } else {
            System.err.println("Feil ved henting (Status " + response.statusCode() + "):");
            System.err.println(response.body());
        }
    }

    /**
     * Leser JSON fra Enable Banking og skriver ut som en oversiktlig tabell
     */
    private static void printTransactionsTable(String jsonResponse) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(jsonResponse);
        JsonNode transactions = rootNode.get("transactions");

        System.out.println("=========================================================================================");
        System.out.printf("%-12s | %-12s | %-6s | %-45s\n", "DATO", "BELØP", "VALUTA", "BESKRIVELSE");
        System.out.println("=========================================================================================");

        if (transactions != null && transactions.isArray() && transactions.size() > 0) {
            for (JsonNode t : transactions) {
                // Dato
                String date = t.has("booking_date") ? t.get("booking_date").asText() : "-";

                // Beløp og valuta
                String amount = "0.00";
                String currency = "";
                if (t.has("transaction_amount")) {
                    JsonNode amtNode = t.get("transaction_amount");
                    if (amtNode.has("amount")) amount = amtNode.get("amount").asText();
                    if (amtNode.has("currency")) currency = amtNode.get("currency").asText();
                }

                // Beskrivelse / Tekst
                String description = "Ingen beskrivelse";
                if (t.has("remittance_information_unstructured")) {
                    description = t.get("remittance_information_unstructured").asText();
                } else if (t.has("debtor_name")) {
                    description = t.get("debtor_name").asText();
                } else if (t.has("creditor_name")) {
                    description = t.get("creditor_name").asText();
                }

                // Kutt lang tekst slik at tabellen holder seg pen
                if (description.length() > 42) {
                    description = description.substring(0, 42) + "...";
                }

                System.out.printf("%-12s | %-12s | %-6s | %-45s\n", date, amount, currency, description);
            }
        } else {
            System.out.println("Ingen transaksjoner funnet.");
        }

        System.out.println("=========================================================================================");
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
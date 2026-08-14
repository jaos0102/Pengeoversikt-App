import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

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

import java.nio.file.Files;
import java.nio.file.Paths;

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

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.enablebanking.com/accounts/" + ACCOUNT_UID + "/transactions"))
                    .header("Authorization", "Bearer " + jwt)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            System.out.println("--- RÅ DATA FRA NORDHÄ ---");
            System.out.println(response.body());

            if (response.statusCode() == 200) {
                String jsonData = response.body();
                
                // Lagrer dataen til en fil
                Files.write(Paths.get("transactions.json"), jsonData.getBytes());
                
                System.out.println("Data er nå lagret i filen: transactions.json");
            } else {
                System.out.println("Feil ved henting: " + response.statusCode());
            }

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
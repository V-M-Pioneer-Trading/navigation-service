package de.vnm.navigation.config;

import de.vnm.navigation.auth.ClerkAuthFilter;
import de.vnm.navigation.auth.ClerkVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wires the Clerk trust anchor from the environment, the same way every other service in
 * the fleet does: an inline {@code CLERK_JWT_KEY} wins, else {@code CLERK_JWT_KEY_FILE} is
 * read; neither set, or an empty file, refuses to start. There is no "auth optional"
 * mode (auth-design.md decision 10).
 */
@Configuration
public class ClerkConfig {

    @Bean
    public ClerkVerifier clerkVerifier(@Value("${clerk.jwt-key:}") String inlineKey,
                                       @Value("${clerk.jwt-key-file:}") String keyFile,
                                       @Value("${clerk.issuer:}") String issuer) {
        return new ClerkVerifier(resolveKey(inlineKey, keyFile), issuer);
    }

    @Bean
    public ClerkAuthFilter clerkAuthFilter(ClerkVerifier verifier) {
        return new ClerkAuthFilter(verifier);
    }

    static String resolveKey(String inlineKey, String keyFile) {
        if (inlineKey != null && !inlineKey.isBlank()) {
            return inlineKey;
        }
        if (keyFile != null && !keyFile.isBlank()) {
            try {
                String pem = Files.readString(Path.of(keyFile)).trim();
                if (pem.isEmpty()) {
                    throw new IllegalStateException("CLERK_JWT_KEY_FILE (" + keyFile + ") is empty");
                }
                return pem;
            } catch (IOException e) {
                throw new UncheckedIOException("CLERK_JWT_KEY_FILE (" + keyFile + ") could not be read", e);
            }
        }
        throw new IllegalStateException("CLERK_JWT_KEY or CLERK_JWT_KEY_FILE must be set");
    }
}

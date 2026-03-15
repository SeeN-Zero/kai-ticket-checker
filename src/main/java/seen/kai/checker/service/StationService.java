package seen.kai.checker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class StationService {
    private static final Logger LOG = Logger.getLogger(StationService.class);
    private static final URI STATIONS_URI = URI.create("https://booking.kai.id/api/stations2");

    private final ObjectMapper objectMapper;

    @ConfigProperty(name = "kai.stations.cache-ttl-seconds", defaultValue = "86400")
    long cacheTtlSeconds;

    private volatile Cache cache = new Cache(Collections.emptyMap(), Instant.EPOCH);

    public StationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<Station> findByCode(String code) {
        String normalized = normalizeCode(code);
        if (normalized == null) {
            return Optional.empty();
        }
        Map<String, Station> stations = getStationsByCode();
        return Optional.ofNullable(stations.get(normalized));
    }

    public Station requireByCode(String code) {
        return findByCode(code).orElseThrow(() -> new IllegalArgumentException("Kode stasiun tidak ditemukan: " + code));
    }

    private Map<String, Station> getStationsByCode() {
        Cache current = cache;
        Instant now = Instant.now();
        if (Duration.between(current.fetchedAt(), now).getSeconds() <= cacheTtlSeconds && !current.byCode().isEmpty()) {
            return current.byCode();
        }
        synchronized (this) {
            Cache afterLock = cache;
            if (Duration.between(afterLock.fetchedAt(), now).getSeconds() <= cacheTtlSeconds && !afterLock.byCode().isEmpty()) {
                return afterLock.byCode();
            }
            Cache refreshed = fetchStationsBestEffort(afterLock);
            cache = refreshed;
            return refreshed.byCode();
        }
    }

    private Cache fetchStationsBestEffort(Cache fallback) {
        try {
            HttpURLConnection connection = (HttpURLConnection) STATIONS_URI.toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "kai-ticket-checker");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.connect();

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                LOG.warnf("Gagal fetch stations KAI. status=%d", status);
                return fallback;
            }

            try (InputStream in = connection.getInputStream()) {
                JsonNode root = objectMapper.readTree(in);
                JsonNode stationsNode = root;
                if (root != null && root.isObject() && root.hasNonNull("value")) {
                    stationsNode = root.get("value");
                }
                if (stationsNode == null || !stationsNode.isArray()) {
                    LOG.warn("Format stations KAI tidak sesuai (bukan array), memakai cache lama.");
                    return fallback;
                }

                List<Station> stations = objectMapper.convertValue(stationsNode, new TypeReference<>() {
                });
                Map<String, Station> byCode = new HashMap<>();
                for (Station station : stations) {
                    if (station == null) {
                        continue;
                    }
                    String normalized = normalizeCode(station.code());
                    if (normalized == null) {
                        continue;
                    }
                    byCode.put(normalized, station);
                }
                if (byCode.isEmpty()) {
                    LOG.warn("Stations list kosong dari KAI, memakai cache lama.");
                    return fallback;
                }
                LOG.infof("Loaded %d stations from KAI.", byCode.size());
                return new Cache(Collections.unmodifiableMap(byCode), Instant.now());
            }
        } catch (Exception e) {
            LOG.warn("Gagal fetch stations KAI, memakai cache lama.", e);
            return fallback;
        }
    }

    private String normalizeCode(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    public record Station(String code, String name, String city, String cityname) {
    }

    private record Cache(Map<String, Station> byCode, Instant fetchedAt) {
    }
}

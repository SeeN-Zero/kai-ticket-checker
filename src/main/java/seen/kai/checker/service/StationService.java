package seen.kai.checker.service;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class StationService {
    private static final Logger LOG = Logger.getLogger(StationService.class);
    private static final URI STATIONS_URI = URI.create("https://booking.kai.id/api/stations2");

    public StationService() {
    }

    public List<String> findAllCityNames() {
        return getAll().stream()
                .map(Station::cityname)
                .distinct()
                .sorted()
                .toList();
    }

    public Optional<Station> findStationsByCode(String code) {
        String normalized = normalize(code);
        if (normalized == null) {
            return Optional.empty();
        }
        Map<String, Station> stations = getStationsByCode();
        return Optional.ofNullable(stations.get(normalized));
    }

    public Optional<Station> findAllStationsByName(String name) {
        String normalized = normalize(name);
        if (normalized == null) {
            return Optional.empty();
        }
        Map<String, Station> stations = getStationsByName(name);
        return Optional.ofNullable(stations.get(normalized));
    }

    private Map<String, Station> getStationsByName(String name) {
        return getAll().stream()
                .filter(station -> station.name().equalsIgnoreCase(name))
                .collect(Collectors.toMap(Station::code, station -> station));
    }

    public Station requireByName(String name) {
        return findAllStationsByName(name).orElseThrow(() -> new IllegalArgumentException("Stasiun tidak ditemukan: " + name));
    }

    public Station requireByCode(String code) {
        return findStationsByCode(code).orElseThrow(() -> new IllegalArgumentException("Kode stasiun tidak ditemukan: " + code));
    }

    public List<Station> findStationNamesByCityName(String cityName) {
       var list = getAll().stream()
                .filter(station -> station.cityname().contains(cityName.trim()))
                .collect(Collectors.toList());
       LOG.infof("Found %d stations in %s", list.size(), cityName);
       return list;
    }

    @CacheResult(cacheName = "stations-cache")
    public List<Station> getAll() {
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
                return List.of();
            }

            try (InputStream in = connection.getInputStream(); JsonReader reader = Json.createReader(in)) {
                JsonValue root = reader.read();
                JsonArray stationsArray;

                if (root.getValueType() == JsonValue.ValueType.OBJECT) {
                    JsonObject rootObj = root.asJsonObject();
                    if (rootObj.containsKey("value") && !rootObj.isNull("value")) {
                        JsonValue value = rootObj.get("value");
                        if (value.getValueType() == JsonValue.ValueType.ARRAY) {
                            stationsArray = value.asJsonArray();
                        } else {
                            LOG.warn("Format stations KAI tidak sesuai (value bukan array).");
                            return List.of();
                        }
                    } else {
                        LOG.warn("Format stations KAI tidak sesuai (objek tanpa value).");
                        return List.of();
                    }
                } else if (root.getValueType() == JsonValue.ValueType.ARRAY) {
                    stationsArray = root.asJsonArray();
                } else {
                    LOG.warn("Format stations KAI tidak sesuai (bukan array atau objek).");
                    return List.of();
                }

                Set<Station> stations = new HashSet<>();
                for (JsonValue val : stationsArray) {
                    if (val.getValueType() == JsonValue.ValueType.OBJECT) {
                        JsonObject obj = val.asJsonObject();
                        stations.add(new Station(
                                obj.getString("code", null),
                                obj.getString("name", null),
                                obj.getString("city", null),
                                obj.getString("cityname", null)
                        ));
                    }
                }
                LOG.infof("Loaded %d stations from KAI.", stations.size());
                return List.copyOf(stations);
            }
        } catch (Exception e) {
            LOG.warn("Gagal fetch stations KAI.", e);
            return List.of();
        }
    }

    public Map<String, Station> getStationsByCode() {
        List<Station> stations = this.getAll();
        Map<String, Station> byCode = new HashMap<>();
        for (Station station : stations) {
            if (station == null) {
                continue;
            }
            String normalized = normalize(station.code());
            if (normalized == null) {
                continue;
            }
            byCode.put(normalized, station);
        }
        if (byCode.isEmpty()) {
            LOG.warn("Stations list kosong dari KAI.");
            return Map.of();
        }
        LOG.infof("Loaded %d stations from KAI.", byCode.size());
        return Map.copyOf(byCode);
    }

    private String normalize(String code) {
        if (code == null) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    public record Station(String code, String name, String city, String cityname) {
    }
}

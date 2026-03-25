package seen.kai.checker.service;

import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import seen.kai.checker.entity.StationEntity;
import seen.kai.checker.repository.StationRepository;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
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

    @Inject
    StationRepository stationRepository;

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
                .collect(Collectors.toMap(Station::name, station -> station));
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
                LOG.warnf("Gagal fetch stations KAI. status=%d. Mencoba ambil dari database...", status);
                return getAllFromDatabase();
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
                            LOG.warn("Format stations KAI tidak sesuai (value bukan array). Mencoba ambil dari database...");
                            return getAllFromDatabase();
                        }
                    } else {
                        LOG.warn("Format stations KAI tidak sesuai (objek tanpa value). Mencoba ambil dari database...");
                        return getAllFromDatabase();
                    }
                } else if (root.getValueType() == JsonValue.ValueType.ARRAY) {
                    stationsArray = root.asJsonArray();
                } else {
                    LOG.warn("Format stations KAI tidak sesuai (bukan array atau objek). Mencoba ambil dari database...");
                    return getAllFromDatabase();
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
                
                List<Station> stationList = List.copyOf(stations);
                syncToDatabase(stationList);
                return stationList;
            }
        } catch (Exception e) {
            LOG.warn("Gagal fetch stations KAI. Mencoba ambil dari database...", e);
            return getAllFromDatabase();
        }
    }

    private List<Station> getAllFromDatabase() {
        try {
            List<StationEntity> entities = stationRepository.listAll();
            if (entities.isEmpty()) {
                LOG.warn("Database stasiun kosong.");
                return List.of();
            }
            LOG.infof("Loaded %d stations from database fallback.", entities.size());
            return entities.stream()
                    .map(e -> new Station(e.getCode(), e.getName(), e.getCity(), e.getCityname()))
                    .toList();
        } catch (Exception e) {
            LOG.error("Gagal mengambil stasiun dari database.", e);
            return List.of();
        }
    }

    @Transactional
    public void syncToDatabase(List<Station> stations) {
        if (stations == null || stations.isEmpty()) {
            return;
        }
        try {
            for (Station s : stations) {
                if (s.code() == null || s.code().isBlank()) continue;
                
                StationEntity entity = stationRepository.findById(s.code());
                if (entity == null) {
                    entity = new StationEntity();
                    entity.setCode(s.code());
                }
                entity.setName(s.name());
                entity.setCity(s.city());
                entity.setCityname(s.cityname());
                stationRepository.persist(entity);
            }
            LOG.infof("Berhasil sinkronisasi %d stasiun ke database.", stations.size());
        } catch (Exception e) {
            LOG.error("Gagal sinkronisasi stasiun ke database.", e);
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

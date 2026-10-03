import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/* ===================== EMBER API (public, no login needed) ===================== */

/** A bookable stop / stop area returned by the Ember locations API. */
record Place(int id, String name, String region, double lat, double lon) {
    boolean hasCoords() { return !Double.isNaN(lat) && !Double.isNaN(lon); }

    /** Short label for the timer page: the town/city if we have one ("Glasgow"), else the stop name. */
    String shortName() { return region == null || region.isBlank() ? name : region; }

    @Override public String toString() {
        return region == null || region.isBlank() || name.toLowerCase().contains(region.toLowerCase())
            ? name : name + ", " + region;
    }
}

/** One scheduled coach departure for a chosen origin/destination. */
record Departure(Instant departs, Instant arrives, String operator) {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM HH:mm");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    int minutes() { return (int) Math.max(1, Duration.between(departs, arrives).toMinutes()); }

    @Override public String toString() {
        ZoneId z = ZoneId.systemDefault();
        return departs.atZone(z).format(DAY) + " → " + arrives.atZone(z).format(HM) + "  ·  " + minutes() + " min";
    }
}

final class EmberApi {
    /** Override with -Dember.base=http://localhost:8080 to point at a test server. */
    static final String BASE = System.getProperty("ember.base", "https://api.ember.to");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** Search stops by name. With an origin, only destinations reachable from it are returned. */
    List<Place> search(String query, Integer origin) throws IOException, InterruptedException {
        String path = "/v1/locations/search/?type=STOP_AREA&limit=12&query=" + enc(query == null ? "" : query.trim())
            + (origin != null ? "&origin=" + origin : "");
        return parseLocations(get(path));
    }

    /** Upcoming scheduled departures between two places (next 48 hours). */
    List<Departure> departures(Place from, Place to) throws IOException, InterruptedException {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String path = "/v1/quotes/?origin=" + from.id() + "&destination=" + to.id() + "&adult=1"
            + "&departure_date_from=" + enc(now.toString())
            + "&departure_date_to=" + enc(now.plus(Duration.ofHours(48)).toString());
        return parseQuotes(get(path));
    }

    private String get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(12))
            .header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /* ---- parsing (static so it can be unit-tested without a network) ---- */

    static List<Place> parseLocations(String body) {
        List<Place> out = new ArrayList<>();
        if (!(Json.parse(body) instanceof List<?> items)) return out;
        for (Object o : items) {
            double id = Json.num(Json.get(o, "id"));
            String name = Json.str(Json.get(o, "name"));
            if (name == null) name = Json.str(Json.get(o, "detailed_name"));
            if (Double.isNaN(id) || name == null) continue;
            out.add(new Place((int) id, name, Json.str(Json.get(o, "region_name")),
                Json.num(Json.get(o, "lat")), Json.num(Json.get(o, "lon"))));
        }
        return out;
    }

    static List<Departure> parseQuotes(String body) {
        List<Departure> out = new ArrayList<>();
        if (!(Json.get(Json.parse(body), "quotes") instanceof List<?> quotes)) return out;
        for (Object q : quotes) {
            if (!(Json.get(q, "legs") instanceof List<?> legs)) continue;
            for (Object leg : legs) {
                if (!"scheduled_transit".equals(Json.str(Json.get(leg, "type")))) continue;
                Instant d = time(Json.get(leg, "departure", "scheduled")), a = time(Json.get(leg, "arrival", "scheduled"));
                if (d == null || a == null || !a.isAfter(d)) continue;
                out.add(new Departure(d, a, Json.str(Json.get(leg, "description", "operator"))));
                break;
            }
        }
        out.sort(Comparator.comparing(Departure::departs));
        return out;
    }

    private static Instant time(Object s) {
        try { return s instanceof String str ? OffsetDateTime.parse(str).toInstant() : null; }
        catch (RuntimeException e) { return null; }
    }

    /** Straight-line distance between two coordinates. */
    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}

/** Tiny JSON reader (objects -> Map, arrays -> List, numbers -> Double) so the app needs no libraries. */
final class Json {
    private final String s;
    private int i;
    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        try { return new Json(text).value(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Unexpected response from server"); }
    }

    /** Walks nested objects; returns null if any step is missing. */
    static Object get(Object o, String... path) {
        for (String k : path) {
            if (!(o instanceof Map<?, ?> m)) return null;
            o = m.get(k);
        }
        return o;
    }
    static String str(Object o) { return o instanceof String x ? x : null; }
    static double num(Object o) { return o instanceof Number n ? n.doubleValue() : Double.NaN; }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        ws();
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj();
            case '[': return arr();
            case '"': return string();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default:  return number();
        }
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws(); i++;                       // ':'
            m.put(k, value());
            ws();
            if (s.charAt(i++) == '}') return m;
        }
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            l.add(value());
            ws();
            if (s.charAt(i++) == ']') return l;
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++;                                 // opening quote
        char c;
        while ((c = s.charAt(i++)) != '"') {
            if (c != '\\') { sb.append(c); continue; }
            c = s.charAt(i++);
            switch (c) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default:  sb.append(c);
            }
        }
        return sb.toString();
    }

    private Double number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return Double.parseDouble(s.substring(st, i));
    }
}

package demo.logscraper;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based masking of customer data before anything leaves Log Scraper. It is not blind: a 16-digit number is only
 * masked as a NIK when it has a real province code and birth date, and as a card number when it passes the Luhn check,
 * so order and reference numbers stay readable.
 */
public final class Masker {
    public enum Kind { NIK, KARTU, TELEPON, RAHASIA, EMAIL, NPWP, REKENING, NAMA, ALAMAT, TGL_LAHIR, KARTU_CVV, IP, KUSTOM }

    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|pin|otp|token|secret|api[_-]?key|authorization)(\\s*[=:]\\s*)(bearer\\s+)?(\"?)([^\\s\",;}&]+)");
    private static final Pattern CODE = Pattern.compile("(?i)(kode\\s+(?:otp|referensi|verifikasi)\\s*:?\\s*)(\\d{4,8})");
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<![\\d*])\\d{13,19}(?![\\d*])");
    private static final Pattern PHONE = Pattern.compile("(?<![\\d*])(?:\\+62|62|0)8\\d{7,11}(?![\\d*])");
    private static final Pattern EMAIL = Pattern.compile("\\b([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b");
    private static final Pattern NPWP = Pattern.compile("(?<![\\d*])\\d{2}\\.\\d{3}\\.\\d{3}\\.\\d-\\d{3}\\.\\d{3}(?![\\d*])");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d))\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?![\\d.])");
    // Free text cannot be recognised by shape, so these are masked by the field name that precedes them.
    private static final Pattern ACCOUNT = keyed("account|acct|rekening|no[_-]?rek|norek|va|virtual[_-]?account|iban");
    private static final Pattern NAME = keyed("customer|nama|name|nama[_-]?ibu[_-]?kandung|ibu[_-]?kandung|mother[_-]?name|holder|owner|sender|receiver|beneficiary");
    private static final Pattern ADDRESS = keyed("alamat|address|addr");
    private static final Pattern DOB = keyed("dob|birth[_-]?date|tgl[_-]?lahir|tanggal[_-]?lahir|date[_-]?of[_-]?birth");
    private static final Pattern CVV = keyed("cvv|cvc|cvv2");
    private static final Set<String> PROVINCES = Set.of("11", "12", "13", "14", "15", "16", "17", "18", "19", "21",
            "31", "32", "33", "34", "35", "36", "51", "52", "53", "61", "62", "63", "64", "65",
            "71", "72", "73", "74", "75", "76", "81", "82", "91", "92", "93", "94", "95", "96");

    /** One user-defined regex rule: whatever it matches is masked, optionally keeping the first and last characters. */
    public record CustomRule(String name, String regex, int keepHead, int keepTail) {
        public CustomRule {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("masking rule needs a name");
            Pattern.compile(regex); // fail at startup, not on the first search
            keepHead = Math.max(0, keepHead); keepTail = Math.max(0, keepTail);
        }
    }

    /** What to mask: built-in filters can be switched off, and extra field names / regex rules added. Set in application.yml. */
    public record Rules(Set<Kind> disabled, List<String> fields, List<CustomRule> custom) {
        public static final Rules DEFAULT = new Rules(Set.of(), List.of(), List.of());
        public Rules {
            disabled = disabled == null ? Set.of() : Set.copyOf(disabled);
            fields = fields == null ? List.of() : List.copyOf(fields);
            custom = custom == null ? List.of() : List.copyOf(custom);
            for (String f : fields) if (!f.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("bad masking field name: " + f);
        }
        boolean on(Kind k) { return !disabled.contains(k); }
    }

    /** Masked text plus how many items of each kind were hidden. */
    public record Result(String text, Map<Kind, Integer> counts) {}

    public static Result mask(String text) {
        return mask(text, Rules.DEFAULT);
    }

    public static Result mask(String text, Rules rules) {
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        String s = text;
        if (rules.on(Kind.RAHASIA)) {
            s = replace(SECRET, s, m -> m.group(1) + m.group(2) + (m.group(3) == null ? "" : m.group(3)) + m.group(4) + "****",
                    Kind.RAHASIA, counts);
            s = replace(CODE, s, m -> m.group(1) + "******", Kind.RAHASIA, counts);
        }
        if (rules.on(Kind.KARTU_CVV)) s = replace(CVV, s, Masker::keyedMask, Kind.KARTU_CVV, counts);
        if (rules.on(Kind.NPWP)) s = replace(NPWP, s, m -> keep(m.group(), 2, 4), Kind.NPWP, counts);
        s = replaceDigits(s, counts, rules);
        if (rules.on(Kind.TELEPON)) s = replace(PHONE, s, m -> keep(m.group(), 4, 3), Kind.TELEPON, counts);
        if (rules.on(Kind.EMAIL)) s = replace(EMAIL, s, m -> m.group(1) + "***@" + m.group(2), Kind.EMAIL, counts);
        if (rules.on(Kind.REKENING)) s = replace(ACCOUNT, s, m -> keyedMaskKeepTail(m, 3), Kind.REKENING, counts);
        if (rules.on(Kind.NAMA)) s = replace(NAME, s, Masker::keyedMask, Kind.NAMA, counts);
        if (rules.on(Kind.ALAMAT)) s = replace(ADDRESS, s, Masker::keyedMask, Kind.ALAMAT, counts);
        if (rules.on(Kind.TGL_LAHIR)) s = replace(DOB, s, Masker::keyedMask, Kind.TGL_LAHIR, counts);
        if (rules.on(Kind.IP)) s = replace(IPV4, s, m -> m.group(1) + ".***", Kind.IP, counts);
        if (!rules.fields().isEmpty() && rules.on(Kind.KUSTOM)) {
            s = replace(keyed(String.join("|", rules.fields().stream().map(Pattern::quote).toList())), s,
                    Masker::keyedMask, Kind.KUSTOM, counts);
        }
        if (rules.on(Kind.KUSTOM)) {
            for (CustomRule r : rules.custom()) {
                s = replace(Pattern.compile(r.regex()), s, m -> keepOrAll(m.group(), r.keepHead(), r.keepTail()), Kind.KUSTOM, counts);
            }
        }
        return new Result(s, counts);
    }

    private static String keepOrAll(String v, int head, int tail) {
        return v.length() <= head + tail + 1 ? "*".repeat(Math.max(4, v.length())) : keep(v, head, tail);
    }

    public static boolean isNik(String d) {
        if (d.length() != 16 || !d.chars().allMatch(Character::isDigit)) return false;
        if (!PROVINCES.contains(d.substring(0, 2))) return false;
        int day = Integer.parseInt(d.substring(6, 8));
        int month = Integer.parseInt(d.substring(8, 10));
        boolean dayOk = (day >= 1 && day <= 31) || (day >= 41 && day <= 71);
        return dayOk && month >= 1 && month <= 12 && !d.substring(12).equals("0000");
    }

    public static boolean luhnOk(String d) {
        int sum = 0; boolean dbl = false;
        for (int i = d.length() - 1; i >= 0; i--) {
            int n = d.charAt(i) - '0';
            if (dbl) { n *= 2; if (n > 9) n -= 9; }
            sum += n; dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static String replaceDigits(String s, Map<Kind, Integer> counts, Rules rules) {
        Matcher m = LONG_DIGITS.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String d = m.group();
            String rep = d;
            if (isNik(d) && rules.on(Kind.NIK)) { rep = keep(d, 4, 4); counts.merge(Kind.NIK, 1, Integer::sum); }
            else if (luhnOk(d) && rules.on(Kind.KARTU)) { rep = keep(d, 4, 4); counts.merge(Kind.KARTU, 1, Integer::sum); }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String replace(Pattern p, String s, java.util.function.Function<Matcher, String> f, Kind kind,
                                  Map<Kind, Integer> counts) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m)));
            counts.merge(kind, 1, Integer::sum);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** key=value, key="quoted value" or key: value; group 1 = key and separator (with opening quote), group 2 = value. */
    private static Pattern keyed(String keys) {
        return Pattern.compile("(?i)\\b((?:" + keys + ")\\s*[=:]\\s*\"?)((?<=\")[^\"]+|[^\\s\",;}&]+)");
    }

    private static String keyedMask(Matcher m) {
        return m.group(1) + "****";
    }

    private static String keyedMaskKeepTail(Matcher m, int tail) {
        String v = m.group(2);
        if (v.matches(".*\\*.*") || v.length() <= tail + 2) return m.group(1) + "****";
        return m.group(1) + "*".repeat(v.length() - tail) + v.substring(v.length() - tail);
    }

    private static String keep(String s, int head, int tail) {
        return s.substring(0, head) + "*".repeat(s.length() - head - tail) + s.substring(s.length() - tail);
    }

    private Masker() {}
}

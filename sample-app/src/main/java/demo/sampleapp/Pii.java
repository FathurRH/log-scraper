package demo.sampleapp;

import java.util.concurrent.ThreadLocalRandom;

/** Generates realistic but fake Indonesian customer data, so the logs carry the kind of data that must be masked. */
final class Pii {
    private static final String[] NAMES = {"Siti Rahayu", "Budi Santoso", "Dewi Lestari", "Agus Pratama", "Rina Wulandari",
            "Andi Saputra", "Maya Sari", "Hendra Gunawan", "Putri Ayu", "Rizky Ramadhan"};
    private static final String[] AREA = {"3174", "3171", "3275", "3578", "3216", "3603"};

    static String name() { return NAMES[r().nextInt(NAMES.length)]; }

    /** 16-digit NIK: area(4) + district(2) + DDMMYY (day+40 for women) + serial(4). */
    static String nik() {
        int day = 1 + r().nextInt(28);
        if (r().nextBoolean()) day += 40;
        int month = 1 + r().nextInt(12);
        int year = 70 + r().nextInt(30);
        return AREA[r().nextInt(AREA.length)] + String.format("%02d%02d%02d%02d%04d",
                1 + r().nextInt(20), day, month, year % 100, 1 + r().nextInt(2000));
    }

    /** Luhn-valid 16-digit card number. */
    static String card() {
        StringBuilder sb = new StringBuilder("4" + (r().nextBoolean() ? "21" : "62") + "7");
        while (sb.length() < 15) sb.append(r().nextInt(10));
        sb.append(luhnDigit(sb.toString()));
        return sb.toString();
    }

    /** 16-digit reference that is neither a valid card (Luhn) nor a valid NIK, to show masking is not blind. */
    static String ref() {
        while (true) {
            StringBuilder sb = new StringBuilder("88");
            while (sb.length() < 16) sb.append(r().nextInt(10));
            String s = sb.toString();
            if (!luhnOk(s)) return s; // area code 88xx is not a real province, so never a NIK
        }
    }

    static String phone() {
        StringBuilder sb = new StringBuilder("08" + (1 + r().nextInt(9)));
        int len = 9 + r().nextInt(2);
        for (int i = 0; i < len; i++) sb.append(r().nextInt(10));
        return sb.toString();
    }

    static String token() {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder("eyJ");
        for (int i = 0; i < 28; i++) sb.append(abc.charAt(r().nextInt(abc.length())));
        return sb.toString();
    }

    static String otp() { return String.format("%06d", r().nextInt(1_000_000)); }

    static boolean luhnOk(String digits) {
        int sum = 0; boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) { d *= 2; if (d > 9) d -= 9; }
            sum += d; dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static int luhnDigit(String partial) {
        for (int d = 0; d < 10; d++) if (luhnOk(partial + d)) return d;
        throw new IllegalStateException();
    }

    private static ThreadLocalRandom r() { return ThreadLocalRandom.current(); }
    private Pii() {}
}

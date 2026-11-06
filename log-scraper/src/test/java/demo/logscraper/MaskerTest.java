package demo.logscraper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskerTest {
    @Test
    void masksValidNikAndKeepsEnds() {
        var r = Masker.mask("nik=3174051208900001 amount=100");
        assertThat(r.text()).isEqualTo("nik=3174********0001 amount=100");
        assertThat(r.counts()).containsEntry(Masker.Kind.NIK, 1);
    }

    @Test
    void femaleNikDayPlus40IsStillANik() {
        assertThat(Masker.isNik("3275055203950123")).isTrue();
    }

    @Test
    void sixteenDigitsWithFakeProvinceOrBadDateAreNotNik() {
        assertThat(Masker.isNik("8812345678901234")).isFalse();  // no province 88
        assertThat(Masker.isNik("3174053513900001")).isFalse();  // month 13
        assertThat(Masker.isNik("3174053201900001")).isFalse();  // day 32
    }

    @Test
    void masksLuhnValidCardButLeavesReferenceNumbers() {
        var r = Masker.mask("card=4111111111111111 merchantRef=8800123456789013");
        assertThat(Masker.luhnOk("8800123456789013")).isFalse();
        assertThat(r.text()).isEqualTo("card=4111********1111 merchantRef=8800123456789013");
        assertThat(r.counts()).containsEntry(Masker.Kind.KARTU, 1).doesNotContainKey(Masker.Kind.NIK);
    }

    @Test
    void masksPhoneNumbersInAllCommonForms() {
        assertThat(Masker.mask("phone=081234567890").text()).isEqualTo("phone=0812*****890");
        assertThat(Masker.mask("to +6281234567890 ok").text()).isEqualTo("to +628*******890 ok");
    }

    @Test
    void masksSecretsAndBearerTokens() {
        var r = Masker.mask("Authorization: Bearer eyJabc.def password=\"hunter2\" pin=1234");
        assertThat(r.text()).isEqualTo("Authorization: Bearer **** password=\"****\" pin=****");
        assertThat(r.counts()).containsEntry(Masker.Kind.RAHASIA, 3);
    }

    @Test
    void masksOtpInsideSmsText() {
        assertThat(Masker.mask("text=\"Transfer berhasil. Kode referensi: 482913\"").text())
                .isEqualTo("text=\"Transfer berhasil. Kode referensi: ******\"");
    }

    @Test
    void masksEmailKeepingDomain() {
        assertThat(Masker.mask("email=siti.rahayu@example.co.id").text()).isEqualTo("email=s***@example.co.id");
    }

    @Test
    void doesNotTouchTransactionIdsOrTimestamps() {
        String line = "2026-11-02 21:15:03.123 trxId=TRX-88231 elapsed=2106ms ref=CB12345678";
        assertThat(Masker.mask(line).text()).isEqualTo(line);
        assertThat(Masker.mask(line).counts()).isEmpty();
    }

    @Test
    void masksNpwp() {
        assertThat(Masker.mask("npwp 01.234.567.8-912.345 ok").text()).isEqualTo("npwp 01**************.345 ok");
        assertThat(Masker.mask("x").counts()).isEmpty();
    }

    @Test
    void masksNamesAndAddressesByFieldName() {
        var r = Masker.mask("customer=\"Budi Santoso\" alamat=\"Jl. Melati No. 5, Jakarta\" nama_ibu_kandung=Siti");
        assertThat(r.text()).isEqualTo("customer=\"****\" alamat=\"****\" nama_ibu_kandung=****");
        assertThat(r.counts()).containsEntry(Masker.Kind.NAMA, 2).containsEntry(Masker.Kind.ALAMAT, 1);
    }

    @Test
    void masksAccountNumberKeepingLastThree() {
        assertThat(Masker.mask("account=1234567890 amount=100").text()).isEqualTo("account=*******890 amount=100");
    }

    @Test
    void masksBirthDateCvvAndIp() {
        var r = Masker.mask("dob=1990-08-12 cvv=123 from 10.20.30.40");
        assertThat(r.text()).isEqualTo("dob=**** cvv=**** from 10.20.30.***");
        assertThat(r.counts()).containsKeys(Masker.Kind.TGL_LAHIR, Masker.Kind.KARTU_CVV, Masker.Kind.IP);
    }

    @Test
    void keyedFiltersDoNotTouchUnrelatedFields() {
        String line = "trxId=TRX-88231 status=FAILED nameless=1 accountable=yes version=1.2.3.4";
        assertThat(Masker.mask(line).text()).isEqualTo(line.replace("1.2.3.4", "1.2.3.***"));
    }

    @Test
    void builtInFilterCanBeSwitchedOff() {
        var rules = new Masker.Rules(java.util.Set.of(Masker.Kind.IP, Masker.Kind.EMAIL), null, null);
        assertThat(Masker.mask("from 10.20.30.40 a@b.co", rules).text()).isEqualTo("from 10.20.30.40 a@b.co");
        assertThat(Masker.mask("cvv=123", rules).text()).isEqualTo("cvv=****");
    }

    @Test
    void extraFieldNamesAreMasked() {
        var rules = new Masker.Rules(null, java.util.List.of("kodeAgen"), null);
        var r = Masker.mask("kodeAgen=AG-77 kodeAgenX=1", rules);
        assertThat(r.text()).isEqualTo("kodeAgen=**** kodeAgenX=1");
        assertThat(r.counts()).containsEntry(Masker.Kind.KUSTOM, 1);
    }

    @Test
    void customRegexRuleKeepsConfiguredEnds() {
        var rules = new Masker.Rules(null, null, java.util.List.of(new Masker.CustomRule("polis", "POL-\\d{8}", 4, 2)));
        assertThat(Masker.mask("polis POL-12345678 ok", rules).text()).isEqualTo("polis POL-******78 ok");
    }

    @Test
    void badRegexOrFieldNameFailsAtStartup() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> new Masker.CustomRule("x", "(", 0, 0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Masker.Rules(null, java.util.List.of("a b|c"), null));
    }
}

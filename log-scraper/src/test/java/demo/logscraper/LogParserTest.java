package demo.logscraper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogParserTest {
    private static final String GREP = """
            2026-11-02 21:15:03.100  INFO [http-exec-3] c.d.payment.PaymentService : received trxId=TRX-88231 amount=100
            2026-11-02 21:15:03.200 DEBUG [http-exec-4] c.d.payment.FraudCheck : trxId=TRX-11111 score=0.2
            2026-11-02 21:15:07.300 ERROR [http-exec-3] c.d.payment.PaymentService : trxId=TRX-88231 FAILED after retry
            java.lang.IllegalStateException: CoreBankingTimeoutException
            \tat demo.bank.Main.pay(Main.java:120)
            Caused by: java.net.http.HttpTimeoutException: request timed out
            \t... 5 more
            2026-11-02 21:15:07.400  INFO [http-exec-5] c.d.payment.PaymentService : received trxId=TRX-22222
            --
            2026-11-02 21:20:00.000  WARN [main] c.d.x : trx-88231 lowercase hit
            """;

    @Test
    void keepsStackTraceWithItsLineAndDropsContextLines() {
        var entries = LogParser.parse("app-02", GREP, "TRX-88231");
        assertThat(entries).hasSize(3);
        assertThat(entries.get(1).level()).isEqualTo("ERROR");
        assertThat(entries.get(1).extra()).hasSize(4).first().asString().startsWith("java.lang.IllegalStateException");
        assertThat(entries.get(0).extra()).isEmpty();
        assertThat(entries.get(2).message()).contains("lowercase");
        assertThat(entries).allMatch(e -> e.server().equals("app-02"));
    }

    @Test
    void ignoresGarbageAndEmptyOutput() {
        assertThat(LogParser.parse("app-01", "", "TRX-1")).isEmpty();
        assertThat(LogParser.parse("app-01", "random text TRX-1\nmore", "TRX-1")).isEmpty();
    }

    @Test
    void readsJsonBracketTomcatAndIsoLines() {
        String out = String.join("\n",
                "{\"@timestamp\":\"2026-11-06T09:14:07.415+07:00\",\"level\":\"ERROR\",\"thread\":\"exec-3\",\"logger\":\"id.bank.pay.CoreClient\",\"message\":\"core debit timeout\",\"ref\":\"TRX-55120\",\"stack_trace\":\"java.net.SocketTimeoutException: Read timed out\\n\\tat x.Y.z(Y.java:1)\"}",
                "[2026-11-06 09:14:09,102] [INFO ] [pool-2-thread-7] CoreAdapter - debit POSTED ref=TRX-55120",
                "06-Nov-2026 09:14:07.530 INFO [exec-1] id.bank.notif.Sms.send SMS gagal ref=TRX-55120",
                "2026-11-06T09:14:08.000+07:00  INFO 1 --- [app] [nio-8080-exec-1] c.b.Foo : done ref=TRX-55120");
        var r = LogParser.parseDetailed("srv", out, "TRX-55120");
        assertThat(r.entries()).hasSize(4);
        assertThat(r.unrecognized()).isZero();
        var json = r.entries().get(0);
        assertThat(json.level()).isEqualTo("ERROR");
        assertThat(json.message()).contains("core debit timeout").contains("ref=TRX-55120");
        assertThat(json.extra()).hasSize(2);
        assertThat(r.entries().get(1).thread()).isEqualTo("pool-2-thread-7");
        assertThat(r.entries().get(1).logger()).isEqualTo("CoreAdapter");
        assertThat(r.entries().get(2).time().toString()).startsWith("2026-11-06T09:14:07.530");
        assertThat(r.entries().get(3).thread()).isEqualTo("nio-8080-exec-1");
    }

    @Test
    void countsLinesItCannotReadInsteadOfDroppingThem() {
        var r = LogParser.parseDetailed("srv", "Nov  6 09:14:07 host app[12]: ref=TRX-1 failed\nBinary file /x/a.gz matches\n", "TRX-1");
        assertThat(r.entries()).isEmpty();
        assertThat(r.unrecognized()).isEqualTo(1);
        assertThat(r.samples()).hasSize(1);
    }

    @Test
    void detectsTheFormatOfASample() {
        var d = LogParser.detect("[2026-11-06 09:14:02,455] [INFO ] [t] A - one\n\tat stack line\n[2026-11-06 09:14:03,455] [WARN ] [t] A - two\nplain junk at the end");
        assertThat(d.best()).isEqualTo(LogParser.Format.BRACKET);
        assertThat(d.recognized()).isEqualTo(2);
        assertThat(d.unrecognized()).isZero(); // stack lines and trailing text after a recognised line belong to it
    }
}

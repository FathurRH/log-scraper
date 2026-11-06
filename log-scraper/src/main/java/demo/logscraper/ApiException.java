package demo.logscraper;

/** A settings request that cannot be done, with the HTTP status to answer and a message a person can act on. */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}

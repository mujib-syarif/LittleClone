package app.littleclone.engine;

/**
 * Thrown when an APK (or one of its parts) is not structured the way the engine
 * expects, or uses a feature the engine deliberately does not support.
 * The app shows {@link #getMessage()} to the user, so keep messages readable.
 */
public final class MalformedApkException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MalformedApkException(String message) {
        super(message);
    }

    public MalformedApkException(String message, Throwable cause) {
        super(message, cause);
    }
}

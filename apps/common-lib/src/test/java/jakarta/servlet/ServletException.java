package jakarta.servlet;

/**
 * Test stub to satisfy class verification of Spring's ResponseEntityExceptionHandler
 * without introducing servlet container runtime dependencies into common-lib.
 */
public class ServletException extends Exception {

    public ServletException() {
        super();
    }

    public ServletException(String message) {
        super(message);
    }

    public ServletException(String message, Throwable rootCause) {
        super(message, rootCause);
    }

    public ServletException(Throwable rootCause) {
        super(rootCause);
    }

    public Throwable getRootCause() {
        return getCause();
    }
}

package org.endeavourhealth.sftpreader.sources;

public class SftpConnectionException extends Exception {
    static final long serialVersionUID = 0L;

    public SftpConnectionException() {
        super();
    }
    public SftpConnectionException(String message) {
        super(message);
    }
    public SftpConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
    public SftpConnectionException(Throwable cause) {
        super(cause);
    }
}

package com.coachraffael.demos.common;

/** Fachliche Fehler, die die API als 404 bzw. 409 meldet. */
public final class Errors {

    private Errors() {
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** Verstoß gegen eine Geschäftsregel (z. B. Gate rot, Demo läuft schon). */
    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    public static NotFound notFound(String what, Object id) {
        return new NotFound(what + " " + id + " nicht gefunden");
    }
}

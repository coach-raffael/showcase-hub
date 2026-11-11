package com.coachraffael.demos.manifest;

import java.util.List;

/** demo.yaml verletzt den Demo-Vertrag; {@link #problems()} nennt jede Stelle einzeln. */
public class ManifestException extends IllegalArgumentException {

    private final List<String> problems;

    public ManifestException(List<String> problems) {
        super("demo.yaml ist ungültig: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}

package com.neuringo.neuringobe.ai.application.structured.output;

import java.util.List;

public record RevisionInstruction(
        List<String> keep, List<String> change, List<String> avoid, List<String> required) {

    public RevisionInstruction {
        keep = keep == null ? List.of() : List.copyOf(keep);
        change = change == null ? List.of() : List.copyOf(change);
        avoid = avoid == null ? List.of() : List.copyOf(avoid);
        required = required == null ? List.of() : List.copyOf(required);
    }
}

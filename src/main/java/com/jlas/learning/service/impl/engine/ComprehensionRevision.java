package com.jlas.learning.service.impl.engine;

import com.jlas.learning.dto.ComprehensionExercise;
import com.jlas.learning.enums.LearningActivityType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public final class ComprehensionRevision {
    private ComprehensionRevision() { }

    public static String combined(LearningActivityType type, List<ComprehensionExercise> exercises) {
        String material = type.name() + "|" + exercises.stream()
                .map(exercise -> exercise.fingerprint() + ":" + exercise.sourceRevision())
                .reduce("", (left, right) -> left + "|" + right);
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

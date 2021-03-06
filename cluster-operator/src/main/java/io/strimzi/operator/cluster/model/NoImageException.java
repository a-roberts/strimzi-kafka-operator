/*
 * Copyright 2019, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

/**
 * Thrown to indicate that an image could not be found for a given Kafka version or custom resource.
 */
public class NoImageException extends Exception {
    public NoImageException(String message) {
        super(message);
    }
}

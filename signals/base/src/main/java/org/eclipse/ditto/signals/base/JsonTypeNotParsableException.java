/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.signals.base;

import java.net.URI;
import java.text.MessageFormat;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;

import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeExceptionBuilder;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.json.JsonParsableException;

/**
 * Thrown if a JSON string or object could not be parsed as it was unexpected/unknown.
 */
@Immutable
@JsonParsableException(errorCode = JsonTypeNotParsableException.ERROR_CODE)
public final class JsonTypeNotParsableException extends DittoRuntimeException {

    /**
     * Error code of this exception.
     */
    public static final String ERROR_CODE = "json.type.notparsable";

    private static final String MESSAGE_TEMPLATE = "The JSON type ''{0}'' is not supported by registry ''{1}''.";

    private static final String DEFAULT_DESCRIPTION = "Check if you sent the JSON with the correct type.";

    private static final long serialVersionUID = 1764112162089631698L;

    private JsonTypeNotParsableException(final DittoHeaders dittoHeaders, final String message,
            final String description, final Throwable cause, final URI href) {
        super(ERROR_CODE, HttpStatusCode.BAD_REQUEST, dittoHeaders, message, description, cause, href);
    }

    /**
     * A mutable builder for a {@code JsonTypeNotParsableException}.
     *
     * @param type the not supported type.
     * @param registryName the name of the registry which did not support the type.
     * @return the builder.
     */
    public static Builder newBuilder(final String type, final String registryName) {
        return new Builder(type, registryName);
    }

    /**
     * Constructs a new {@code JsonTypeNotParsableException} object with given message.
     *
     * @param message detail message. This message can be later retrieved by the {@link #getMessage()} method.
     * @param dittoHeaders the headers of the command which resulted in this exception.
     * @return the new JsonTypeNotParsableException.
     * @throws NullPointerException if {@code dittoHeaders} is {@code null}.
     */
    public static JsonTypeNotParsableException fromMessage(@Nullable final String message,
            final DittoHeaders dittoHeaders) {
        return DittoRuntimeException.fromMessage(message, dittoHeaders, new Builder());
    }

    /**
     * Constructs a new {@code JsonTypeNotParsableException} object with the exception message extracted from the
     * given JSON object.
     *
     * @param jsonObject the JSON to read the {@link JsonFields#MESSAGE} field from.
     * @param dittoHeaders the headers of the command which resulted in this exception.
     * @return the new JsonTypeNotParsableException.
     * @throws NullPointerException if any argument is {@code null}.
     * @throws IllegalArgumentException if {@code jsonObject} is empty.
     * @throws org.eclipse.ditto.json.JsonMissingFieldException if this JsonObject did not contain an error message.
     * @throws org.eclipse.ditto.json.JsonParseException if the passed in {@code jsonObject} was not in the expected
     * format.
     */
    public static JsonTypeNotParsableException fromJson(final JsonObject jsonObject,
            final DittoHeaders dittoHeaders) {
        return DittoRuntimeException.fromJson(jsonObject, dittoHeaders, new Builder());
    }

    /**
     * A mutable builder with a fluent API for a {@link JsonTypeNotParsableException}.
     */
    @NotThreadSafe
    public static final class Builder extends DittoRuntimeExceptionBuilder<JsonTypeNotParsableException> {

        private Builder() {
            description(DEFAULT_DESCRIPTION);
        }

        private Builder(final String type, final String registryName) {
            this();
            message(MessageFormat.format(MESSAGE_TEMPLATE, type, registryName));
        }

        @Override
        protected JsonTypeNotParsableException doBuild(final DittoHeaders dittoHeaders, final String message,
                final String description, final Throwable cause, final URI href) {
            return new JsonTypeNotParsableException(dittoHeaders, message, description, cause, href);
        }
    }
}

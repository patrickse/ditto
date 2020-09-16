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
package org.eclipse.ditto.signals.commands.thingsearch.exceptions;

import java.net.URI;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeExceptionBuilder;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.json.JsonParsableException;
import org.eclipse.ditto.model.thingsearch.ThingSearchException;

/**
 * Thrown if an option for a search is invalid.
 */
@JsonParsableException(errorCode = InvalidOptionException.ERROR_CODE)
public class InvalidOptionException extends DittoRuntimeException implements ThingSearchException {

    /**
     * Error code of this exception.
     */
    public static final String ERROR_CODE = ERROR_CODE_PREFIX + "search.option.invalid";

    static final String DEFAULT_DESCRIPTION = "At least one provided option is invalid.";

    static final HttpStatusCode STATUS_CODE = HttpStatusCode.BAD_REQUEST;

    private static final long serialVersionUID = -2341639110057194874L;

    private InvalidOptionException(final DittoHeaders dittoHeaders,
            @Nullable final String message,
            @Nullable final String description,
            @Nullable final Throwable cause,
            @Nullable final URI href) {
        super(ERROR_CODE, STATUS_CODE, dittoHeaders, message, description, cause, href);
    }

    @Override
    protected DittoRuntimeExceptionBuilder<? extends DittoRuntimeException> getEmptyBuilder() {
        return new Builder();
    }

    /**
     * A mutable builder for a {@code InvalidOptionException}.
     *
     * @return the builder.
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Constructs a new {@code InvalidOptionException} object with given message.
     *
     * @param message detail message. This message can be later retrieved by the {@link #getMessage()} method.
     * @param dittoHeaders the headers of the command which resulted in this exception.
     * @return the new InvalidOptionException.
     * @throws NullPointerException if {@code dittoHeaders} is {@code null}.
     */
    public static InvalidOptionException fromMessage(@Nullable final String message, final DittoHeaders dittoHeaders) {
        return DittoRuntimeException.fromMessage(message, dittoHeaders, new Builder());
    }

    /**
     * Constructs a new {@code InvalidOptionException} object with the exception message extracted from the
     * given JSON object.
     *
     * @param jsonObject the JSON to read the {@link JsonFields#MESSAGE} field from.
     * @param dittoHeaders the headers of the command which resulted in this exception.
     * @return the new InvalidOptionException.
     * @throws NullPointerException if any argument is {@code null}.
     * @throws IllegalArgumentException if {@code jsonObject} is empty.
     * @throws org.eclipse.ditto.json.JsonMissingFieldException if this JsonObject did not contain an error message.
     * @throws org.eclipse.ditto.json.JsonParseException if the passed in {@code jsonObject} was not in the expected
     * format.
     */
    public static InvalidOptionException fromJson(final JsonObject jsonObject, final DittoHeaders dittoHeaders) {
        return DittoRuntimeException.fromJson(jsonObject, dittoHeaders, new Builder());
    }

    /**
     * A mutable builder with a fluent API for a {@link InvalidOptionException}.
     */
    @NotThreadSafe
    public static final class Builder extends DittoRuntimeExceptionBuilder<InvalidOptionException> {

        private Builder() {
            description(DEFAULT_DESCRIPTION);
        }

        @Override
        protected InvalidOptionException doBuild(final DittoHeaders dittoHeaders,
                @Nullable final String message,
                @Nullable final String description,
                @Nullable final Throwable cause,
                @Nullable final URI href) {
            return new InvalidOptionException(dittoHeaders, message, description, cause, href);
        }
    }
}

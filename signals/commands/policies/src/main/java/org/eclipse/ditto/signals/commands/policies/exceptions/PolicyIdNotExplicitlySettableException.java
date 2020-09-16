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
package org.eclipse.ditto.signals.commands.policies.exceptions;

import java.net.URI;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.NotThreadSafe;

import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeExceptionBuilder;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.json.JsonParsableException;
import org.eclipse.ditto.model.policies.PolicyException;

/**
 * Thrown if either for a REST PUT request for creating a Policy it was tried to set an explicit {@code policyId}
 * in the JSON body.
 */
@Immutable
@JsonParsableException(errorCode = PolicyIdNotExplicitlySettableException.ERROR_CODE)
public final class PolicyIdNotExplicitlySettableException extends DittoRuntimeException implements PolicyException {

    /**
     * Error code of this exception.
     */
    public static final String ERROR_CODE = ERROR_CODE_PREFIX + "id.notsettable";

    private static final String MESSAGE_TEMPLATE =
            "The Policy ID in the request body is not equal to the Policy ID in the request URL.";

    private static final String DEFAULT_DESCRIPTION =
            "Either delete the Policy ID from the request body or use the same Policy ID as in the request URL.";


    private PolicyIdNotExplicitlySettableException(final DittoHeaders dittoHeaders,
            @Nullable final String message,
            @Nullable final String description,
            @Nullable final Throwable cause,
            @Nullable final URI href) {
        super(ERROR_CODE, HttpStatusCode.BAD_REQUEST, dittoHeaders, message, description, cause, href);
    }

    private static final long serialVersionUID = 2497658333219185859L;

    /**
     * A mutable builder for a {@code PolicyIdNotExplicitlySettableException}.
     *
     * @return the builder.
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    /**
     * Constructs a new {@code PolicyIdNotExplicitlySettableException} object with the given exception message.
     *
     * @param message detail message. This message can be later retrieved by the {@link #getMessage()} method.
     * @param dittoHeaders the headers of the command which resulted in this exception.
     * @return the new PolicyIdNotExplicitlySettableException.
     * @throws NullPointerException if {@code dittoHeaders} is {@code null}.
     */
    public static PolicyIdNotExplicitlySettableException fromMessage(@Nullable final String message,
            final DittoHeaders dittoHeaders) {
        return DittoRuntimeException.fromMessage(message, dittoHeaders, new Builder());
    }

    /**
     * Constructs a new {@code PolicyIdNotExplicitlySettableException} object with the exception message extracted from
     * the
     * given JSON object.
     *
     * @param jsonObject the JSON to read the {@link JsonFields#MESSAGE} field from.
     * @param dittoHeaders the headers of the command which resulted in this exception.
     * @return the new PolicyIdNotExplicitlySettableException.
     * @throws NullPointerException if any argument is {@code null}.
     * @throws IllegalArgumentException if {@code jsonObject} is empty.
     * @throws org.eclipse.ditto.json.JsonMissingFieldException if this JsonObject did not contain an error message.
     * @throws org.eclipse.ditto.json.JsonParseException if the passed in {@code jsonObject} was not in the expected
     * format.
     */
    public static PolicyIdNotExplicitlySettableException fromJson(final JsonObject jsonObject,
            final DittoHeaders dittoHeaders) {
        return DittoRuntimeException.fromJson(jsonObject, dittoHeaders, new Builder());
    }

    /**
     * A mutable builder with a fluent API for a {@link PolicyIdNotExplicitlySettableException}.
     */
    @NotThreadSafe
    public static final class Builder extends DittoRuntimeExceptionBuilder<PolicyIdNotExplicitlySettableException> {

        private Builder() {
            message(MESSAGE_TEMPLATE);
            description(DEFAULT_DESCRIPTION);
        }

        @Override
        protected PolicyIdNotExplicitlySettableException doBuild(final DittoHeaders dittoHeaders,
                @Nullable final String message,
                @Nullable final String description,
                @Nullable final Throwable cause,
                @Nullable final URI href) {
            return new PolicyIdNotExplicitlySettableException(dittoHeaders, message, description, cause, href);
        }
    }


}

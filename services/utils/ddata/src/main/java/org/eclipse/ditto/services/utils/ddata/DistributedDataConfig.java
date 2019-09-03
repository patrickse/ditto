/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.utils.ddata;

import java.time.Duration;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.services.utils.config.KnownConfigValue;

/**
 * Provides configuration settings for {@link DistributedData}.
 */
@Immutable
public interface DistributedDataConfig {

    /**
     * Returns the timeout of GET-messages of the replicator.
     *
     * @return the timeout of reads.
     */
    Duration getReadTimeout();

    /**
     * Returns the timeout of UPDATE-messages of the replicator.
     *
     * @return the timeout of writes.
     */
    Duration getWriteTimeout();

    /**
     * Returns the config to use for creating the {@link akka.cluster.ddata.Replicator}.
     *
     * @return the Akka replicator config to use.
     */
    AkkaReplicatorConfig getAkkaReplicatorConfig();

    /**
     * An enumeration of the known config path expressions and their associated default values for
     * {@code DistributedDataConfig}.
     */
    enum DistributedDataConfigValue implements KnownConfigValue {

        /**
         * The read timeout duration: the timeout of GET-messages of the replicator.
         */
        READ_TIMEOUT("read-timeout", Duration.ofSeconds(5)),

        /**
         * The write timeout duration: the timeout of UPDATE-messages of the replicator.
         */
        WRITE_TIMEOUT("write-timeout", Duration.ofSeconds(25));

        private final String path;
        private final Object defaultValue;

        DistributedDataConfigValue(final String thePath, final Object theDefaultValue) {
            path = thePath;
            defaultValue = theDefaultValue;
        }

        @Override
        public String getConfigPath() {
            return path;
        }

        @Override
        public Object getDefaultValue() {
            return defaultValue;
        }

    }

}
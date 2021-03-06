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
package org.eclipse.ditto.services.connectivity.messaging;

import static org.eclipse.ditto.model.connectivity.MetricType.DROPPED;
import static org.eclipse.ditto.model.connectivity.MetricType.MAPPED;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.eclipse.ditto.json.JsonField;
import org.eclipse.ditto.json.JsonFieldSelector;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.acks.AbstractCommandAckRequestSetter;
import org.eclipse.ditto.model.base.acks.AcknowledgementRequest;
import org.eclipse.ditto.model.base.acks.FilteredAcknowledgementRequest;
import org.eclipse.ditto.model.base.auth.AuthorizationContext;
import org.eclipse.ditto.model.base.common.ConditionChecker;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.entity.id.EntityId;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.exceptions.SignalEnrichmentFailedException;
import org.eclipse.ditto.model.base.headers.DittoHeaderDefinition;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.headers.DittoHeadersBuilder;
import org.eclipse.ditto.model.base.headers.WithDittoHeaders;
import org.eclipse.ditto.model.base.json.JsonSchemaVersion;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionId;
import org.eclipse.ditto.model.connectivity.ConnectionSignalIdEnforcementFailedException;
import org.eclipse.ditto.model.connectivity.ConnectivityInternalErrorException;
import org.eclipse.ditto.model.connectivity.EnforcementFilter;
import org.eclipse.ditto.model.connectivity.FilteredTopic;
import org.eclipse.ditto.model.connectivity.LogCategory;
import org.eclipse.ditto.model.connectivity.LogType;
import org.eclipse.ditto.model.connectivity.MetricDirection;
import org.eclipse.ditto.model.connectivity.MetricType;
import org.eclipse.ditto.model.connectivity.Target;
import org.eclipse.ditto.model.placeholders.ExpressionResolver;
import org.eclipse.ditto.model.placeholders.PlaceholderFilter;
import org.eclipse.ditto.model.query.criteria.Criteria;
import org.eclipse.ditto.model.query.filter.QueryFilterCriteriaFactory;
import org.eclipse.ditto.model.query.things.ThingPredicateVisitor;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.protocoladapter.ProtocolAdapter;
import org.eclipse.ditto.protocoladapter.TopicPath;
import org.eclipse.ditto.services.base.config.limits.DefaultLimitsConfig;
import org.eclipse.ditto.services.base.config.limits.LimitsConfig;
import org.eclipse.ditto.services.connectivity.mapping.ConnectivitySignalEnrichmentProvider;
import org.eclipse.ditto.services.connectivity.mapping.MappingConfig;
import org.eclipse.ditto.services.connectivity.messaging.BaseClientActor.PublishMappedMessage;
import org.eclipse.ditto.services.connectivity.messaging.config.DittoConnectivityConfig;
import org.eclipse.ditto.services.connectivity.messaging.config.MonitoringConfig;
import org.eclipse.ditto.services.connectivity.messaging.internal.ConnectionFailure;
import org.eclipse.ditto.services.connectivity.messaging.internal.ImmutableConnectionFailure;
import org.eclipse.ditto.services.connectivity.messaging.monitoring.ConnectionMonitor;
import org.eclipse.ditto.services.connectivity.messaging.monitoring.DefaultConnectionMonitorRegistry;
import org.eclipse.ditto.services.connectivity.messaging.monitoring.logs.InfoProviderFactory;
import org.eclipse.ditto.services.connectivity.util.ConnectivityMdcEntryKey;
import org.eclipse.ditto.services.models.acks.AcknowledgementAggregatorActor;
import org.eclipse.ditto.services.models.acks.AcknowledgementAggregatorActorStarter;
import org.eclipse.ditto.services.models.concierge.streaming.StreamingType;
import org.eclipse.ditto.services.models.connectivity.ExternalMessage;
import org.eclipse.ditto.services.models.connectivity.OutboundSignal;
import org.eclipse.ditto.services.models.connectivity.OutboundSignal.Mapped;
import org.eclipse.ditto.services.models.connectivity.OutboundSignalFactory;
import org.eclipse.ditto.services.models.signalenrichment.SignalEnrichmentFacade;
import org.eclipse.ditto.services.utils.akka.controlflow.AbstractGraphActor;
import org.eclipse.ditto.services.utils.akka.logging.DittoLoggerFactory;
import org.eclipse.ditto.services.utils.akka.logging.ThreadSafeDittoLogger;
import org.eclipse.ditto.services.utils.config.DefaultScopedConfig;
import org.eclipse.ditto.signals.acks.base.Acknowledgement;
import org.eclipse.ditto.signals.acks.base.Acknowledgements;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.base.WithId;
import org.eclipse.ditto.signals.commands.base.Command;
import org.eclipse.ditto.signals.commands.base.CommandResponse;
import org.eclipse.ditto.signals.commands.base.ErrorResponse;
import org.eclipse.ditto.signals.commands.connectivity.ConnectivityErrorResponse;
import org.eclipse.ditto.signals.commands.messages.MessageCommand;
import org.eclipse.ditto.signals.commands.messages.acks.MessageCommandAckRequestSetter;
import org.eclipse.ditto.signals.commands.things.ThingCommand;
import org.eclipse.ditto.signals.commands.things.ThingErrorResponse;
import org.eclipse.ditto.signals.commands.things.acks.ThingLiveCommandAckRequestSetter;
import org.eclipse.ditto.signals.commands.things.acks.ThingModifyCommandAckRequestSetter;
import org.eclipse.ditto.signals.commands.things.exceptions.ThingNotAccessibleException;
import org.eclipse.ditto.signals.commands.thingsearch.ThingSearchCommand;
import org.eclipse.ditto.signals.events.things.ThingEventToThingConverter;

import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Status;
import akka.japi.Pair;
import akka.japi.pf.PFBuilder;
import akka.japi.pf.ReceiveBuilder;
import akka.pattern.Patterns;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.SourceQueue;
import scala.PartialFunction;
import scala.util.Either;
import scala.util.Left;
import scala.util.Right;

/**
 * This Actor processes incoming {@link Signal}s and dispatches them.
 */
public final class MessageMappingProcessorActor
        extends AbstractGraphActor<MessageMappingProcessorActor.OutboundSignalWithId, OutboundSignal> {

    /**
     * The name of this Actor in the ActorSystem.
     */
    public static final String ACTOR_NAME = "messageMappingProcessor";

    /**
     * The name of the dispatcher that runs all mapping tasks and all message handling of this actor and its children.
     */
    private static final String MESSAGE_MAPPING_PROCESSOR_DISPATCHER = "message-mapping-processor-dispatcher";

    private final ThreadSafeDittoLogger logger;

    private final ActorRef clientActor;
    private final MessageMappingProcessor messageMappingProcessor;
    private final ConnectionId connectionId;
    private final ActorRef proxyActor;
    private final ActorRef connectionActor;
    private final MappingConfig mappingConfig;
    private final DefaultConnectionMonitorRegistry connectionMonitorRegistry;
    private final ConnectionMonitor responseDispatchedMonitor;
    private final ConnectionMonitor responseDroppedMonitor;
    private final ConnectionMonitor responseMappedMonitor;
    private final SignalEnrichmentFacade signalEnrichmentFacade;
    private final int processorPoolSize;
    private final SourceQueue<ExternalMessageWithSender> inboundSourceQueue;
    private final DittoRuntimeExceptionToErrorResponseFunction toErrorResponseFunction;
    private final AcknowledgementAggregatorActorStarter ackregatorStarter;

    @SuppressWarnings("unused")
    private MessageMappingProcessorActor(final ActorRef proxyActor,
            final ActorRef clientActor,
            final MessageMappingProcessor messageMappingProcessor,
            final Connection connection,
            final ActorRef connectionActor,
            final int processorPoolSize) {

        super(OutboundSignal.class);

        this.proxyActor = proxyActor;
        this.clientActor = clientActor;
        this.messageMappingProcessor = messageMappingProcessor;
        connectionId = connection.getId();

        logger = DittoLoggerFactory.getThreadSafeLogger(MessageMappingProcessorActor.class)
                .withMdcEntry(ConnectivityMdcEntryKey.CONNECTION_ID, connectionId);

        this.connectionActor = connectionActor;

        final DefaultScopedConfig dittoScoped =
                DefaultScopedConfig.dittoScoped(getContext().getSystem().settings().config());

        final DittoConnectivityConfig connectivityConfig = DittoConnectivityConfig.of(dittoScoped);
        final MonitoringConfig monitoringConfig = connectivityConfig.getMonitoringConfig();
        mappingConfig = connectivityConfig.getMappingConfig();
        final LimitsConfig limitsConfig = DefaultLimitsConfig.of(dittoScoped);

        connectionMonitorRegistry = DefaultConnectionMonitorRegistry.fromConfig(monitoringConfig);
        responseDispatchedMonitor = connectionMonitorRegistry.forResponseDispatched(connectionId);
        responseDroppedMonitor = connectionMonitorRegistry.forResponseDropped(connectionId);
        responseMappedMonitor = connectionMonitorRegistry.forResponseMapped(connectionId);
        signalEnrichmentFacade =
                ConnectivitySignalEnrichmentProvider.get(getContext().getSystem()).getFacade(connectionId);
        this.processorPoolSize = determinePoolSize(processorPoolSize, mappingConfig.getMaxPoolSize());
        inboundSourceQueue = materializeInboundStream(this.processorPoolSize);
        toErrorResponseFunction = DittoRuntimeExceptionToErrorResponseFunction.of(limitsConfig.getHeadersMaxSize());
        ackregatorStarter = AcknowledgementAggregatorActorStarter.of(getContext(),
                connectivityConfig.getConnectionConfig().getAcknowledgementConfig(),
                messageMappingProcessor.getHeaderTranslator(),
                ThingModifyCommandAckRequestSetter.getInstance(),
                ThingLiveCommandAckRequestSetter.getInstance(),
                MessageCommandAckRequestSetter.getInstance());
    }

    private int determinePoolSize(final int connectionPoolSize, final int maxPoolSize) {
        if (connectionPoolSize > maxPoolSize) {
            logger.info("Configured pool size <{}> is greater than the configured max pool size <{}>." +
                    " Will use max pool size <{}>.", connectionPoolSize, maxPoolSize, maxPoolSize);
            return maxPoolSize;
        }
        return connectionPoolSize;
    }

    /**
     * Creates Akka configuration object for this actor.
     *
     * @param proxyActor the actor used to send signals into the ditto cluster.
     * @param clientActor the client actor that created this mapping actor
     * @param processor the MessageMappingProcessor to use.
     * @param connection the connection
     * @param connectionActor the connection actor acting as the grandparent of this actor.
     * @param processorPoolSize how many message processing may happen in parallel per direction (incoming or outgoing).
     * @return the Akka configuration Props object.
     */
    public static Props props(final ActorRef proxyActor,
            final ActorRef clientActor,
            final MessageMappingProcessor processor,
            final Connection connection,
            final ActorRef connectionActor,
            final int processorPoolSize) {

        return Props.create(MessageMappingProcessorActor.class,
                proxyActor,
                clientActor,
                processor,
                connection,
                connectionActor,
                processorPoolSize).withDispatcher(MESSAGE_MAPPING_PROCESSOR_DISPATCHER);
    }

    @Override
    protected int getBufferSize() {
        return mappingConfig.getBufferSize();
    }

    @Override
    protected void preEnhancement(final ReceiveBuilder receiveBuilder) {
        receiveBuilder
                // Incoming messages are handled in a separate stream parallelized by this actor's own dispatcher
                .match(ExternalMessage.class, this::handleInboundMessage)
                .match(Acknowledgement.class, this::handleNotExpectedAcknowledgement)
                // Outgoing responses and signals go through the signal enrichment stream
                .match(CommandResponse.class, response -> handleCommandResponse(response, null, getSender()))
                .match(Signal.class, signal -> handleSignal(signal, getSender()))
                .match(IncomingSignal.class, this::dispatchIncomingSignal)
                .match(Status.Failure.class, f -> logger.warn("Got failure with cause {}: {}",
                        f.cause().getClass().getSimpleName(), f.cause().getMessage()));
    }

    private void handleNotExpectedAcknowledgement(final Acknowledgement acknowledgement) {
        // this Acknowledgement seems to have been mis-routed:
        logger.withCorrelationId(acknowledgement)
                .warn("Received Acknowledgement where non was expected, discarding it: {}", acknowledgement);
    }

    private SourceQueue<ExternalMessageWithSender> materializeInboundStream(final int processorPoolSize) {
        return Source.<ExternalMessageWithSender>queue(getBufferSize(), OverflowStrategy.dropNew())
                // parallelize potentially CPU-intensive payload mapping on this actor's dispatcher
                .mapAsync(processorPoolSize, externalMessage -> CompletableFuture.supplyAsync(
                        () -> mapInboundMessage(externalMessage),
                        getContext().getDispatcher())
                )
                .flatMapConcat(this::handleIncomingMappedSignal)
                .toMat(Sink.foreach(incomingSignal -> getSelf().tell(incomingSignal, ActorRef.noSender())), Keep.left())
                .run(materializer);
    }

    /**
     * Handle incoming signals that request acknowledgements in the actor's thread, since creating the necessary
     * acknowledgement aggregators is not thread-safe.
     *
     * @param incomingSignal the signal requesting acknowledgements together with its original sender,
     * the response collector actor.
     */
    private void dispatchIncomingSignal(final IncomingSignal incomingSignal) {
        final Signal<?> signal = incomingSignal.signal;
        final ActorRef sender = incomingSignal.sender;
        if (incomingSignal.isAckRequesting) {
            try {
                startAckregatorAndForwardSignal(signal, sender);
            } catch (final DittoRuntimeException e) {
                handleErrorDuringStartingOfAckregator(e, signal.getDittoHeaders(), sender);
            }
        } else {
            if (isLive(signal)) {
                final DittoHeaders originalHeaders = signal.getDittoHeaders();
                Patterns.ask(proxyActor, signal, originalHeaders.getTimeout().orElse(Duration.ofSeconds(10)))
                        .thenApply(response -> {
                            if (response instanceof WithDittoHeaders<?>) {
                                return AcknowledgementAggregatorActor.restoreCommandConnectivityHeaders(
                                        (WithDittoHeaders<?>) response,
                                        originalHeaders);
                            } else {
                                final String messageTemplate =
                                        "Expected response <%s> to be of type <%s> but was of type <%s>.";
                                final String errorMessage =
                                        String.format(messageTemplate, response, WithDittoHeaders.class.getName(),
                                                response.getClass().getName());
                                final ConnectivityInternalErrorException dre =
                                        ConnectivityInternalErrorException.newBuilder()
                                                .cause(new ClassCastException(errorMessage))
                                                .build();
                                return ConnectivityErrorResponse.of(dre, originalHeaders);
                            }
                        })
                        .thenAccept(response -> sender.tell(response, ActorRef.noSender()));
            } else {
                proxyActor.tell(signal, sender);
            }
        }
    }

    private static boolean isLive(final Signal<?> signal) {
        return (signal instanceof MessageCommand ||
                (signal instanceof ThingCommand && ProtocolAdapter.isLiveSignal(signal)));
    }

    private void handleErrorDuringStartingOfAckregator(final DittoRuntimeException e, final DittoHeaders dittoHeaders,
            final ActorRef sender) {

        logger.withCorrelationId(dittoHeaders)
                .info("Got 'DittoRuntimeException' during 'startAcknowledgementAggregator': {}: <{}>",
                        e.getClass().getSimpleName(), e.getMessage());
        responseMappedMonitor.getLogger()
                .failure("Got exception {0} when processing external message: {1}",
                        e.getErrorCode(), e.getMessage());
        final ErrorResponse<?> errorResponse = toErrorResponseFunction.apply(e, null);
        // tell sender the error response for consumer settlement
        sender.tell(errorResponse, getSelf());
        // publish error response
        handleErrorResponse(e, errorResponse.setDittoHeaders(dittoHeaders), ActorRef.noSender());
    }

    private void startAckregatorAndForwardSignal(final Signal<?> signal, final ActorRef sender) {
        ackregatorStarter.doStart(signal,
                responseSignal -> {
                    // potentially publish response/aggregated acks to reply target
                    if (signal.getDittoHeaders().isResponseRequired()) {
                        getSelf().tell(responseSignal, getSelf());
                    }

                    // forward acks to the original sender for consumer settlement
                    sender.tell(responseSignal, ActorRef.noSender());
                },
                ackregator -> {
                    proxyActor.tell(signal, ackregator);
                    return null;
                });
    }

    private Source<IncomingSignal, ?> handleIncomingMappedSignal(
            final Pair<Source<Signal<?>, ?>, ActorRef> mappedSignalsWithSender) {

        final Source<Signal<?>, ?> mappedSignals = mappedSignalsWithSender.first();
        final ActorRef sender = mappedSignalsWithSender.second();
        final Sink<IncomingSignal, CompletionStage<Integer>> wireTapSink =
                Sink.fold(0, (i, s) -> i + (s.isAckRequesting ? 1 : 0));
        return mappedSignals.flatMapConcat(onIncomingMappedSignal(sender)::apply)
                .wireTapMat(wireTapSink, (otherMat, ackRequestingSignalCountFuture) -> {
                    ackRequestingSignalCountFuture.thenAccept(ackRequestingSignalCount ->
                            sender.tell(ResponseCollectorActor.setCount(ackRequestingSignalCount), getSelf())
                    );
                    return otherMat;
                });
    }

    private PartialFunction<Signal<?>, Source<IncomingSignal, NotUsed>> onIncomingMappedSignal(final ActorRef sender) {
        final PartialFunction<Signal<?>, Signal<?>> appendConnectionId = new PFBuilder<Signal<?>, Signal<?>>()
                .match(Acknowledgements.class, acks -> appendConnectionIdToAcknowledgements(acks, connectionId))
                .match(CommandResponse.class, ack -> appendConnectionIdToAcknowledgementOrResponse(ack, connectionId))
                .matchAny(x -> x)
                .build();

        final PartialFunction<Signal<?>, Source<IncomingSignal, NotUsed>> dispatchSignal =
                new PFBuilder<Signal<?>, Source<IncomingSignal, NotUsed>>()
                        .match(Acknowledgement.class, ack -> forwardToConnectionActor(ack, sender))
                        .match(Acknowledgements.class, acks -> forwardToConnectionActor(acks, sender))
                        .match(CommandResponse.class, ProtocolAdapter::isLiveSignal, liveResponse ->
                                forwardToConnectionActor(liveResponse, sender)
                        )
                        .match(ThingSearchCommand.class, cmd -> forwardToConnectionActor(cmd, sender))
                        .matchAny(baseSignal -> ackregatorStarter.preprocess(baseSignal,
                                (signal, isAckRequesting) -> Source.single(new IncomingSignal(signal,
                                        isAckRequesting ? sender : getReturnAddress(signal),
                                        isAckRequesting)),
                                headerInvalidException -> {
                                    // tell the response collector to settle negatively without redelivery
                                    sender.tell(headerInvalidException, ActorRef.noSender());
                                    // tell self to publish the error response
                                    getSelf().tell(ThingErrorResponse.of(headerInvalidException), ActorRef.noSender());
                                    return Source.empty();
                                }))
                        .build();

        return appendConnectionId.andThen(dispatchSignal);
    }

    /**
     * Only special Signals must be forwarded to the {@code ConnectionPersistenceActor}:
     * <ul>
     * <li>{@code Acknowledgement}s which were received via an incoming connection source</li>
     * <li>live {@code CommandResponse}s which were received via an incoming connection source</li>
     * <li>{@code SearchCommand}s which were received via an incoming connection source</li>
     * </ul>
     *
     * @param signal the Signal to forward to the connectionActor
     * @param sender the sender which shall receive the response
     * @param <T> type of elements for the next step..
     * @return an empty source of Signals
     */
    private <T> Source<T, NotUsed> forwardToConnectionActor(final Signal<?> signal, final ActorRef sender) {
        connectionActor.tell(signal, sender);
        return Source.empty();
    }

    private ActorRef getReturnAddress(final Signal<?> signal) {
        final boolean publishResponse = signal instanceof Command<?> && signal.getDittoHeaders().isResponseRequired();
        return publishResponse ? getSelf() : ActorRef.noSender();
    }

    private static Signal<?> appendConnectionAcknowledgementsToSignal(final ExternalMessage message,
            final Signal<?> signal) {

        if (!canRequestAcks(signal)) {
            return signal;
        }
        final Set<AcknowledgementRequest> additionalAcknowledgementRequests = message.getSource()
                .flatMap(org.eclipse.ditto.model.connectivity.Source::getAcknowledgementRequests)
                .map(FilteredAcknowledgementRequest::getIncludes)
                .orElse(Collections.emptySet());
        final String filter = message.getSource()
                .flatMap(org.eclipse.ditto.model.connectivity.Source::getAcknowledgementRequests)
                .flatMap(FilteredAcknowledgementRequest::getFilter)
                .orElse(null);

        if (additionalAcknowledgementRequests.isEmpty()) {
            // do not change the signal's header if no additional acknowledgementRequests are defined in the Source
            // to preserve the default behavior for signals without the header 'requested-acks'
            return filterAcknowledgements(signal, filter);
        } else {
            // The Source's acknowledgementRequests get appended to the requested-acks DittoHeader of the mapped signal
            final Collection<AcknowledgementRequest> combinedRequestedAcks =
                    new HashSet<>(signal.getDittoHeaders().getAcknowledgementRequests());
            combinedRequestedAcks.addAll(additionalAcknowledgementRequests);

            final DittoHeaders headersWithAcknowledgementRequests = DittoHeaders.newBuilder(signal.getDittoHeaders())
                    .acknowledgementRequests(combinedRequestedAcks)
                    .build();

            return filterAcknowledgements(signal.setDittoHeaders(headersWithAcknowledgementRequests), filter);
        }
    }

    static Signal<?> filterAcknowledgements(final Signal<?> signal, final @Nullable String filter) {
        if (filter != null) {
            final String requestedAcks = DittoHeaderDefinition.REQUESTED_ACKS.getKey();
            final boolean headerDefined = signal.getDittoHeaders().containsKey(requestedAcks);
            final String fullFilter = "header:" + requestedAcks + "|fn:default('[]')|" + filter;
            final ExpressionResolver resolver = Resolvers.forSignal(signal);
            final Optional<String> resolverResult = resolver.resolveAsPipelineElement(fullFilter).toOptional();
            if (resolverResult.isEmpty()) {
                // filter tripped: set requested-acks to []
                return signal.setDittoHeaders(DittoHeaders.newBuilder(signal.getDittoHeaders())
                        .acknowledgementRequests(Collections.emptySet())
                        .build());
            } else if (headerDefined) {
                // filter not tripped, header defined
                return signal.setDittoHeaders(DittoHeaders.newBuilder(signal.getDittoHeaders())
                        .putHeader(requestedAcks, resolverResult.orElseThrow())
                        .build());
            } else {
                // filter not tripped, header not defined:
                // - evaluate filter again against unresolved and set requested-acks accordingly
                // - if filter is not resolved, then keep requested-acks undefined for the default behavior
                final Optional<String> unsetFilterResult =
                        resolver.resolveAsPipelineElement(filter).toOptional();
                return unsetFilterResult.<Signal<?>>map(newAckRequests ->
                        signal.setDittoHeaders(DittoHeaders.newBuilder(signal.getDittoHeaders())
                                .putHeader(requestedAcks, newAckRequests)
                                .build()))
                        .orElse(signal);
            }
        }
        return signal;
    }

    @Override
    protected void handleDittoRuntimeException(final DittoRuntimeException exception) {
        final ErrorResponse<?> errorResponse = toErrorResponseFunction.apply(exception, null);
        handleErrorResponse(exception, errorResponse, getSender());
    }

    @Override
    protected OutboundSignalWithId mapMessage(final OutboundSignal message) {
        if (message instanceof OutboundSignalWithId) {
            // message contains original sender already
            return (OutboundSignalWithId) message;
        } else {
            return OutboundSignalWithId.of(message, getSender());
        }
    }

    @Override
    protected Flow<OutboundSignalWithId, OutboundSignalWithId, NotUsed> processMessageFlow() {
        return Flow.create();
    }

    @Override
    protected Sink<OutboundSignalWithId, ?> processedMessageSink() {
        // Enrich outbound signals by extra fields if necessary.
        final Flow<OutboundSignalWithId, OutboundSignal.MultiMapped, ?> flow = Flow.<OutboundSignalWithId>create()
                .mapAsync(processorPoolSize, outbound -> toMultiMappedOutboundSignal(
                        Source.single(outbound)
                                .via(splitByTargetExtraFieldsFlow())
                                .mapAsync(mappingConfig.getParallelism(), this::enrichAndFilterSignal)
                                .mapConcat(x -> x)
                                .map(this::handleOutboundSignal)
                                .flatMapConcat(x -> x)
                ))
                .mapConcat(x -> x);
        return flow.to(Sink.foreach(this::forwardToPublisherActor));
    }

    /**
     * Create a flow that splits 1 outbound signal into many as follows.
     * <ol>
     * <li>
     *   Targets with matching filtered topics without extra fields are grouped into 1 outbound signal, followed by
     * </li>
     * <li>one outbound signal for each target with a matching filtered topic with extra fields.</li>
     * </ol>
     * The matching filtered topic is attached in the latter case.
     * Consequently, for each outbound signal leaving this flow, if it has a filtered topic attached,
     * then it has 1 unique target with a matching topic with extra fields.
     * This satisfies the precondition of {@code this#enrichAndFilterSignal}.
     *
     * @return the flow.
     */
    private static Flow<OutboundSignalWithId, Pair<OutboundSignalWithId, FilteredTopic>, NotUsed> splitByTargetExtraFieldsFlow() {
        return Flow.<OutboundSignalWithId>create()
                .mapConcat(outboundSignal -> {
                    final Pair<List<Target>, List<Pair<Target, FilteredTopic>>> splitTargets =
                            splitTargetsByExtraFields(outboundSignal);

                    final boolean shouldSendSignalWithoutExtraFields =
                            !splitTargets.first().isEmpty() ||
                                    isTwinCommandResponseWithReplyTarget(outboundSignal.getSource()) ||
                                    outboundSignal.getTargets().isEmpty(); // no target - this is an error response
                    final Stream<Pair<OutboundSignalWithId, FilteredTopic>> outboundSignalWithoutExtraFields =
                            shouldSendSignalWithoutExtraFields
                                    ? Stream.of(Pair.create(outboundSignal.setTargets(splitTargets.first()), null))
                                    : Stream.empty();

                    final Stream<Pair<OutboundSignalWithId, FilteredTopic>> outboundSignalWithExtraFields =
                            splitTargets.second().stream()
                                    .map(targetAndSelector -> Pair.create(
                                            outboundSignal.setTargets(
                                                    Collections.singletonList(targetAndSelector.first())),
                                            targetAndSelector.second()));

                    return Stream.concat(outboundSignalWithoutExtraFields, outboundSignalWithExtraFields)
                            .collect(Collectors.toList());
                });
    }


    // Called inside stream; must be thread-safe
    // precondition: whenever filteredTopic != null, it contains an extra fields
    private CompletionStage<Collection<OutboundSignalWithId>> enrichAndFilterSignal(
            final Pair<OutboundSignalWithId, FilteredTopic> outboundSignalWithExtraFields) {

        final OutboundSignalWithId outboundSignal = outboundSignalWithExtraFields.first();
        final FilteredTopic filteredTopic = outboundSignalWithExtraFields.second();
        final Optional<JsonFieldSelector> extraFieldsOptional =
                Optional.ofNullable(filteredTopic).flatMap(FilteredTopic::getExtraFields);
        if (extraFieldsOptional.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.singletonList(outboundSignal));
        }
        final JsonFieldSelector extraFields = extraFieldsOptional.get();
        final Target target = outboundSignal.getTargets().get(0);

        final ThingId thingId = ThingId.of(outboundSignal.getEntityId());
        final DittoHeaders headers = DittoHeaders.newBuilder()
                .authorizationContext(target.getAuthorizationContext())
                // the correlation-id MUST NOT be set! as the DittoHeaders are used as a caching key in the Caffeine
                //  cache this would break the cache loading
                // schema version is always the latest for connectivity signal enrichment.
                .schemaVersion(JsonSchemaVersion.LATEST)
                .build();
        final CompletionStage<JsonObject> extraFuture =
                signalEnrichmentFacade.retrievePartialThing(thingId, extraFields, headers, outboundSignal.getSource());

        return extraFuture.thenApply(outboundSignal::setExtra)
                .thenApply(outboundSignalWithExtra -> applyFilter(outboundSignalWithExtra, filteredTopic))
                .exceptionally(error -> {
                    logger.withCorrelationId(outboundSignal.getSource())
                            .warn("Could not retrieve extra data due to: {} {}", error.getClass().getSimpleName(),
                                    error.getMessage());
                    // recover from all errors to keep message-mapping-stream running despite enrichment failures
                    return Collections.singletonList(recoverFromEnrichmentError(outboundSignal, target, error));
                });
    }

    // Called inside future; must be thread-safe
    private OutboundSignalWithId recoverFromEnrichmentError(final OutboundSignalWithId outboundSignal,
            final Target target, final Throwable error) {

        // show enrichment failure in the connection logs
        logEnrichmentFailure(outboundSignal, connectionId, error);
        // show enrichment failure in service logs according to severity
        if (error instanceof ThingNotAccessibleException) {
            // This error should be rare but possible due to user action; log on INFO level
            logger.withCorrelationId(outboundSignal.getSource())
                    .info("Enrichment of <{}> failed for <{}> due to <{}>.", outboundSignal.getSource().getClass(),
                            outboundSignal.getEntityId(), error);
        } else {
            // This error should not have happened during normal operation.
            // There is a (possibly transient) problem with the Ditto cluster. Request parent to restart.
            logger.withCorrelationId(outboundSignal.getSource())
                    .error("Enrichment of <{}> failed due to <{}>.", outboundSignal, error);
            final ConnectionFailure connectionFailure =
                    new ImmutableConnectionFailure(getSelf(), error, "Signal enrichment failed");
            clientActor.tell(connectionFailure, getSelf());
        }
        return outboundSignal.setTargets(Collections.singletonList(target));
    }

    private void logEnrichmentFailure(final OutboundSignal outboundSignal, final ConnectionId connectionId,
            final Throwable error) {

        final DittoRuntimeException errorToLog;
        if (error instanceof DittoRuntimeException) {
            errorToLog = SignalEnrichmentFailedException.dueTo((DittoRuntimeException) error);
        } else {
            errorToLog = SignalEnrichmentFailedException.newBuilder()
                    .dittoHeaders(outboundSignal.getSource().getDittoHeaders())
                    .build();
        }
        getMonitorsForMappedSignal(outboundSignal, connectionId)
                .forEach(monitor -> monitor.failure(outboundSignal.getSource(), errorToLog));
    }

    private void handleInboundMessage(final ExternalMessage externalMessage) {
        ConditionChecker.checkNotNull(externalMessage);
        logger.debug("Received inbound Message to map: {}", externalMessage);
        inboundSourceQueue.offer(new ExternalMessageWithSender(externalMessage, getSender()))
                .whenComplete((result, error) -> logger.debug(
                        "Result of inbound source queue offer: <{}>, Error of inbound source queue offer: <{}>",
                        result, error));
    }

    private Pair<Source<Signal<?>, ?>, ActorRef> mapInboundMessage(final ExternalMessageWithSender withSender) {
        final ExternalMessage externalMessage = withSender.externalMessage;
        if (logger.isDebugEnabled()) {
            final String correlationId = getCorrelationIdOrNull(externalMessage);
            logger.withCorrelationId(correlationId).debug("Handling ExternalMessage <{}>.", externalMessage);
        }
        try {
            return Pair.create(mapExternalMessageToSignal(withSender), withSender.sender);
        } catch (final Exception e) {
            handleInboundException(e, withSender, null, getAuthorizationContext(externalMessage).orElse(null));
            return Pair.create(Source.empty(), withSender.sender);
        }
    }

    @Nullable
    private static String getCorrelationIdOrNull(final ExternalMessage externalMessage) {
        final Map<String, String> headers = externalMessage.getHeaders();
        return headers.get(DittoHeaderDefinition.CORRELATION_ID.getKey());
    }

    private void handleInboundException(final Exception e,
            final ExternalMessageWithSender withSender,
            @Nullable final TopicPath topicPath,
            @Nullable final AuthorizationContext authorizationContext) {

        final ExternalMessage message = withSender.externalMessage;
        if (e instanceof DittoRuntimeException) {
            final DittoRuntimeException dittoRuntimeException = (DittoRuntimeException) e;
            responseMappedMonitor.getLogger()
                    .failure("Got exception {0} when processing external message: {1}",
                            dittoRuntimeException.getErrorCode(), e.getMessage());
            final ErrorResponse<?> errorResponse = toErrorResponseFunction.apply(dittoRuntimeException, topicPath);
            final DittoHeaders mappedHeaders = applyInboundHeaderMapping(errorResponse, message, authorizationContext,
                    message.getTopicPath().orElse(null), message.getInternalHeaders());
            logger.withCorrelationId(mappedHeaders)
                    .info("Resolved mapped headers of {} : with HeaderMapping {} : and external headers {}",
                            mappedHeaders, message.getHeaderMapping(), message.getHeaders());
            handleErrorResponse(dittoRuntimeException, errorResponse.setDittoHeaders(mappedHeaders),
                    ActorRef.noSender());
        } else {
            responseMappedMonitor.getLogger()
                    .failure("Got unknown exception when processing external message: {1}", e.getMessage());
            logger.withCorrelationId(message.getInternalHeaders())
                    .warn("Got <{}> when message was processed: <{}>", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private Source<Signal<?>, ?> mapExternalMessageToSignal(final ExternalMessageWithSender withSender) {
        return messageMappingProcessor.process(withSender.externalMessage,
                handleMappingResult(withSender, getAuthorizationContextOrThrow(withSender.externalMessage)));
    }

    private InboundMappingResultHandler handleMappingResult(final ExternalMessageWithSender withSender,
            final AuthorizationContext authorizationContext) {

        final ExternalMessage incomingMessage = withSender.externalMessage;
        final String source = incomingMessage.getSourceAddress().orElse("unknown");

        return InboundMappingResultHandler.newBuilder()
                .onMessageMapped(mappedInboundMessage -> {
                    final Signal<?> signal = mappedInboundMessage.getSignal();

                    final DittoHeaders mappedHeaders =
                            applyInboundHeaderMapping(signal, incomingMessage, authorizationContext,
                                    mappedInboundMessage.getTopicPath(), incomingMessage.getInternalHeaders());

                    final Signal<?> adjustedSignal = appendConnectionAcknowledgementsToSignal(incomingMessage,
                            signal.setDittoHeaders(mappedHeaders));

                    // enforce signal ID after header mapping was done
                    connectionMonitorRegistry.forInboundEnforced(connectionId, source)
                            .wrapExecution(adjustedSignal)
                            .execute(() -> applySignalIdEnforcement(incomingMessage, signal));
                    // the above throws an exception if signal id enforcement fails

                    return Source.single(adjustedSignal);
                })
                .onMessageDropped(() -> {
                    if (logger.isDebugEnabled()) {
                        logger.withCorrelationId(getCorrelationIdOrNull(incomingMessage))
                                .debug("Message mapping returned null, message is dropped.");
                    }
                })
                // skip the inbound stream directly to outbound stream
                .onException((exception, topicPath) -> handleInboundException(exception, withSender, topicPath,
                        authorizationContext))
                .inboundMapped(connectionMonitorRegistry.forInboundMapped(connectionId, source))
                .inboundDropped(connectionMonitorRegistry.forInboundDropped(connectionId, source))
                .infoProvider(InfoProviderFactory.forExternalMessage(incomingMessage))
                .build();
    }

    private void handleErrorResponse(final DittoRuntimeException exception, final ErrorResponse<?> errorResponse,
            final ActorRef sender) {

        final ThreadSafeDittoLogger l = logger.withCorrelationId(exception);

        if (l.isInfoEnabled()) {
            l.info("Got DittoRuntimeException '{}' when ExternalMessage was processed: {} - {}",
                    exception.getErrorCode(), exception.getMessage(), exception.getDescription().orElse(""));
        }
        if (l.isDebugEnabled()) {
            final String stackTrace = stackTraceAsString(exception);
            l.debug("Got DittoRuntimeException '{}' when ExternalMessage was processed: {} - {}. StackTrace: {}",
                    exception.getErrorCode(), exception.getMessage(), exception.getDescription().orElse(""),
                    stackTrace);
        }

        handleCommandResponse(errorResponse, exception, sender);
    }

    private void handleCommandResponse(final CommandResponse<?> response,
            @Nullable final DittoRuntimeException exception, final ActorRef sender) {

        final ThreadSafeDittoLogger l = logger.isDebugEnabled() ? logger.withCorrelationId(response) : logger;
        recordResponse(response, exception);
        if (!response.isOfExpectedResponseType()) {
            l.debug("Requester did not require response (via DittoHeader '{}') - not mapping back to ExternalMessage.",
                    DittoHeaderDefinition.EXPECTED_RESPONSE_TYPES);
            responseDroppedMonitor.success(response,
                    "Dropped response since requester did not require response via Header {0}.",
                    DittoHeaderDefinition.EXPECTED_RESPONSE_TYPES);
        } else {
            if (isSuccessResponse(response)) {
                l.debug("Received response <{}>.", response);
            } else if (l.isDebugEnabled()) {
                l.debug("Received error response <{}>.", response.toJsonString());
            }

            handleSignal(response, sender);
        }
    }

    private void recordResponse(final CommandResponse<?> response, @Nullable final DittoRuntimeException exception) {
        if (isSuccessResponse(response)) {
            responseDispatchedMonitor.success(response);
        } else {
            responseDispatchedMonitor.failure(response, exception);
        }
    }

    private Source<OutboundSignalWithId, ?> handleOutboundSignal(final OutboundSignalWithId outbound) {
        final Signal<?> source = outbound.getSource();
        if (logger.isDebugEnabled()) {
            logger.withCorrelationId(source).debug("Handling outbound signal <{}>.", source);
        }
        return mapToExternalMessage(outbound);
    }

    private void forwardToPublisherActor(final OutboundSignal.MultiMapped mappedEnvelop) {
        clientActor.tell(new PublishMappedMessage(mappedEnvelop), mappedEnvelop.getSender().orElse(null));
    }

    /**
     * Is called for responses or errors which were directly sent to the mapping actor as a response.
     *
     * @param signal the response/error
     */
    private void handleSignal(final Signal<?> signal, final ActorRef sender) {
        // map to outbound signal without authorized target (responses and errors are only sent to its origin)
        logger.withCorrelationId(signal).debug("Handling raw signal <{}>.", signal);
        getSelf().tell(OutboundSignalWithId.of(signal, sender), sender);
    }

    private Source<OutboundSignalWithId, ?> mapToExternalMessage(final OutboundSignalWithId outbound) {
        final Set<ConnectionMonitor> outboundMapped = getMonitorsForMappedSignal(outbound, connectionId);
        final Set<ConnectionMonitor> outboundDropped = getMonitorsForDroppedSignal(outbound, connectionId);

        final OutboundMappingResultHandler outboundMappingResultHandler = OutboundMappingResultHandler.newBuilder()
                .onMessageMapped(mappedOutboundSignal -> Source.single(outbound.mapped(mappedOutboundSignal)))
                .onMessageDropped(() -> logger.debug("Message mapping returned null, message is dropped."))
                .onException((exception, topicPath) -> {
                    if (exception instanceof DittoRuntimeException) {
                        final DittoRuntimeException e = (DittoRuntimeException) exception;
                        logger.withCorrelationId(e)
                                .info("Got DittoRuntimeException during processing Signal: {} - {}", e.getMessage(),
                                        e.getDescription().orElse(""));
                    } else {
                        logger.withCorrelationId(outbound.getSource())
                                .warn("Got unexpected exception during processing Signal <{}>.",
                                        exception.getMessage());
                    }
                })
                .outboundMapped(outboundMapped)
                .outboundDropped(outboundDropped)
                .infoProvider(InfoProviderFactory.forSignal(outbound.getSource()))
                .build();

        return messageMappingProcessor.process(outbound, outboundMappingResultHandler);
    }

    private Set<ConnectionMonitor> getMonitorsForDroppedSignal(final OutboundSignal outbound,
            final ConnectionId connectionId) {

        return getMonitorsForOutboundSignal(outbound, connectionId, DROPPED, LogType.DROPPED, responseDroppedMonitor);
    }

    private Set<ConnectionMonitor> getMonitorsForMappedSignal(final OutboundSignal outbound,
            final ConnectionId connectionId) {

        return getMonitorsForOutboundSignal(outbound, connectionId, MAPPED, LogType.MAPPED, responseMappedMonitor);
    }

    private Set<ConnectionMonitor> getMonitorsForOutboundSignal(final OutboundSignal outbound,
            final ConnectionId connectionId,
            final MetricType metricType,
            final LogType logType,
            final ConnectionMonitor responseMonitor) {

        if (outbound.getSource() instanceof CommandResponse) {
            return Collections.singleton(responseMonitor);
        } else {
            return outbound.getTargets()
                    .stream()
                    .map(Target::getOriginalAddress)
                    .map(address -> connectionMonitorRegistry.getMonitor(connectionId, metricType,
                            MetricDirection.OUTBOUND,
                            logType, LogCategory.TARGET, address))
                    .collect(Collectors.toSet());
        }
    }

    /**
     * Helper applying the {@link EnforcementFilter} of the passed in {@link ExternalMessage} by throwing a {@link
     * ConnectionSignalIdEnforcementFailedException} if the enforcement failed.
     */
    private void applySignalIdEnforcement(final ExternalMessage externalMessage, final Signal<?> signal) {
        externalMessage.getEnforcementFilter().ifPresent(enforcementFilter -> {
            logger.withCorrelationId(signal)
                    .debug("Connection Signal ID Enforcement enabled - matching Signal ID <{}> with filter <{}>.",
                            signal.getEntityId(), enforcementFilter);
            enforcementFilter.match(signal.getEntityId(), signal.getDittoHeaders());
        });
    }

    /**
     * Helper applying the {@link org.eclipse.ditto.model.connectivity.HeaderMapping}.
     */
    private DittoHeaders applyInboundHeaderMapping(final Signal<?> signal,
            final ExternalMessage externalMessage,
            @Nullable final AuthorizationContext authorizationContext,
            @Nullable final TopicPath topicPath,
            final DittoHeaders extraInternalHeaders) {

        final DittoHeaders dittoHeadersOfSignal = signal.getDittoHeaders();
        return externalMessage.getHeaderMapping()
                .map(mapping -> {
                    final ExpressionResolver expressionResolver =
                            Resolvers.forInbound(externalMessage, signal, topicPath, authorizationContext);

                    final DittoHeadersBuilder<?, ?> dittoHeadersBuilder = dittoHeadersOfSignal.toBuilder();

                    // Add mapped external headers as if they were injected into the Adaptable.
                    final Map<String, String> mappedExternalHeaders = mapping.getMapping()
                            .entrySet()
                            .stream()
                            .flatMap(e -> PlaceholderFilter.applyOrElseDelete(e.getValue(), expressionResolver)
                                    .stream()
                                    .map(resolvedValue -> new AbstractMap.SimpleEntry<>(e.getKey(), resolvedValue))
                            )
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                    dittoHeadersBuilder.putHeaders(messageMappingProcessor.getHeaderTranslator()
                            .fromExternalHeaders(mappedExternalHeaders));

                    final String correlationIdKey = DittoHeaderDefinition.CORRELATION_ID.getKey();
                    final boolean hasCorrelationId = mapping.getMapping().containsKey(correlationIdKey) ||
                            dittoHeadersOfSignal.getCorrelationId().isPresent();

                    final DittoHeaders newHeaders =
                            appendInternalHeaders(dittoHeadersBuilder, authorizationContext, extraInternalHeaders,
                                    !hasCorrelationId).build();

                    logger.withCorrelationId(newHeaders)
                            .debug("Result of header mapping <{}> are these headers: {}", mapping, newHeaders);
                    return newHeaders;
                })
                .orElseGet(() ->
                        appendInternalHeaders(
                                dittoHeadersOfSignal.toBuilder(),
                                authorizationContext,
                                extraInternalHeaders,
                                dittoHeadersOfSignal.getCorrelationId().isEmpty()
                        ).build()
                );
    }

    private DittoHeadersBuilder<?, ?> appendInternalHeaders(final DittoHeadersBuilder<?, ?> builder,
            @Nullable final AuthorizationContext authorizationContext,
            final DittoHeaders extraInternalHeaders,
            final boolean appendRandomCorrelationId) {

        builder.putHeaders(extraInternalHeaders).origin(connectionId);
        if (authorizationContext != null) {
            builder.authorizationContext(authorizationContext);
        }
        if (appendRandomCorrelationId && extraInternalHeaders.getCorrelationId().isEmpty()) {
            builder.randomCorrelationId();
        }
        return builder;
    }

    private <T> CompletionStage<Collection<OutboundSignal.MultiMapped>> toMultiMappedOutboundSignal(
            final Source<OutboundSignalWithId, T> source) {

        return source.runWith(Sink.seq(), materializer)
                .thenApply(outboundSignals -> {
                    if (outboundSignals.isEmpty()) {
                        return List.of();
                    } else {
                        final ActorRef sender = outboundSignals.get(0).sender;
                        final List<Mapped> mappedSignals = outboundSignals.stream()
                                .map(OutboundSignalWithId::asMapped)
                                .collect(Collectors.toList());
                        return List.of(OutboundSignalFactory.newMultiMappedOutboundSignal(mappedSignals, sender));
                    }
                });
    }

    /**
     * Appends the ConnectionId to the processed {@code commandResponse} payload.
     *
     * @param commandResponse the CommandResponse (or Acknowledgement as subtype) to append the ConnectionId to
     * @param connectionId the ConnectionId to append to the CommandResponse's DittoHeader
     * @param <T> the type of the CommandResponse
     * @return the CommandResponse with appended ConnectionId.
     */
    static <T extends CommandResponse<T>> T appendConnectionIdToAcknowledgementOrResponse(final T commandResponse,
            final ConnectionId connectionId) {

        final DittoHeaders newHeaders = DittoHeaders.newBuilder(commandResponse.getDittoHeaders())
                .putHeader(DittoHeaderDefinition.CONNECTION_ID.getKey(), connectionId.toString())
                .build();
        return commandResponse.setDittoHeaders(newHeaders);
    }

    static Acknowledgements appendConnectionIdToAcknowledgements(final Acknowledgements acknowledgements,
            final ConnectionId connectionId) {
        final List<Acknowledgement> acksList = acknowledgements.stream()
                .map(ack -> appendConnectionIdToAcknowledgementOrResponse(ack, connectionId))
                .collect(Collectors.toList());
        // Uses EntityId and StatusCode from input acknowledges expecting these were set when Acknowledgements was created
        return Acknowledgements.of(acknowledgements.getEntityId(), acksList, acknowledgements.getStatusCode(),
                acknowledgements.getDittoHeaders());
    }

    private static Collection<OutboundSignalWithId> applyFilter(final OutboundSignalWithId outboundSignalWithExtra,
            final FilteredTopic filteredTopic) {

        final Optional<String> filter = filteredTopic.getFilter();
        final Optional<JsonFieldSelector> extraFields = filteredTopic.getExtraFields();
        if (filter.isPresent() && extraFields.isPresent()) {
            // evaluate filter criteria again if signal enrichment is involved.
            final Criteria criteria = QueryFilterCriteriaFactory.modelBased()
                    .filterCriteria(filter.get(), outboundSignalWithExtra.getSource().getDittoHeaders());
            return outboundSignalWithExtra.getExtra()
                    .flatMap(extra -> {
                        final Signal<?> signal = outboundSignalWithExtra.getSource();
                        return ThingEventToThingConverter.mergeThingWithExtraFields(signal, extraFields.get(), extra)
                                .filter(ThingPredicateVisitor.apply(criteria))
                                .map(thing -> outboundSignalWithExtra);
                    })
                    .map(Collections::singletonList)
                    .orElseGet(Collections::emptyList);
        } else {
            // no signal enrichment: filtering is already done in SignalFilter since there is no ignored field
            return Collections.singletonList(outboundSignalWithExtra);
        }
    }

    private static String stackTraceAsString(final DittoRuntimeException exception) {
        final StringWriter stringWriter = new StringWriter();
        exception.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    private static boolean isSuccessResponse(final CommandResponse<?> response) {
        return response.getStatusCodeValue() < HttpStatusCode.BAD_REQUEST.toInt();
    }

    private static AuthorizationContext getAuthorizationContextOrThrow(final ExternalMessage externalMessage) {
        final Either<RuntimeException, AuthorizationContext> result = getAuthorizationContextAsEither(externalMessage);
        if (result.isRight()) {
            return result.right().get();
        } else {
            throw result.left().get();
        }
    }

    private static Optional<AuthorizationContext> getAuthorizationContext(final ExternalMessage externalMessage) {
        final Either<RuntimeException, AuthorizationContext> result = getAuthorizationContextAsEither(externalMessage);
        if (result.isRight()) {
            return Optional.of(result.right().get());
        } else {
            return Optional.empty();
        }
    }

    private static Either<RuntimeException, AuthorizationContext> getAuthorizationContextAsEither(
            final ExternalMessage externalMessage) {

        return externalMessage.getAuthorizationContext()
                .filter(authorizationContext -> !authorizationContext.isEmpty())
                .<Either<RuntimeException, AuthorizationContext>>map(authorizationContext -> {
                    try {
                        return new Right<>(PlaceholderFilter.applyHeadersPlaceholderToAuthContext(authorizationContext,
                                externalMessage.getHeaders()));
                    } catch (final RuntimeException e) {
                        return new Left<>(e);
                    }
                })
                .orElseGet(() ->
                        new Left<>(new IllegalArgumentException("No nonempty authorization context is available")));
    }

    /**
     * Split the targets of an outbound signal into 2 parts: those without extra fields and those with.
     *
     * @param outboundSignal The outbound signal.
     * @return A pair of lists. The first list contains targets without matching extra fields.
     * The second list contains targets together with their extra fields matching the outbound signal.
     */
    private static Pair<List<Target>, List<Pair<Target, FilteredTopic>>> splitTargetsByExtraFields(
            final OutboundSignal outboundSignal) {

        final Optional<StreamingType> streamingTypeOptional = StreamingType.fromSignal(outboundSignal.getSource());
        if (streamingTypeOptional.isPresent()) {
            // Find targets with a matching topic with extra fields
            final StreamingType streamingType = streamingTypeOptional.get();
            final List<Target> targetsWithoutExtraFields = new ArrayList<>(outboundSignal.getTargets().size());
            final List<Pair<Target, FilteredTopic>> targetsWithExtraFields =
                    new ArrayList<>(outboundSignal.getTargets().size());
            for (final Target target : outboundSignal.getTargets()) {
                final Optional<FilteredTopic> matchingExtraFields = target.getTopics()
                        .stream()
                        .filter(filteredTopic -> filteredTopic.getExtraFields().isPresent() &&
                                streamingType == StreamingType.fromTopic(filteredTopic.getTopic().getPubSubTopic()))
                        .findAny();
                if (matchingExtraFields.isPresent()) {
                    targetsWithExtraFields.add(Pair.create(target, matchingExtraFields.get()));
                } else {
                    targetsWithoutExtraFields.add(target);
                }
            }
            return Pair.create(targetsWithoutExtraFields, targetsWithExtraFields);
        } else {
            // The outbound signal has no streaming type: Do not attach extra fields.
            return Pair.create(outboundSignal.getTargets(), Collections.emptyList());
        }
    }

    private static boolean isTwinCommandResponseWithReplyTarget(final Signal<?> signal) {
        final DittoHeaders dittoHeaders = signal.getDittoHeaders();
        return signal instanceof CommandResponse &&
                !ProtocolAdapter.isLiveSignal(signal) &&
                dittoHeaders.getReplyTarget().isPresent();
    }

    private static boolean canRequestAcks(final Signal<?> signal) {
        return isApplicable(ThingModifyCommandAckRequestSetter.getInstance(), signal) ||
                isApplicable(ThingLiveCommandAckRequestSetter.getInstance(), signal) ||
                isApplicable(MessageCommandAckRequestSetter.getInstance(), signal);
    }

    private static <C extends WithDittoHeaders<? extends C>> boolean isApplicable(
            final AbstractCommandAckRequestSetter<C> setter, final Signal<?> signal) {

        return setter.getMatchedClass().isInstance(signal) &&
                setter.isApplicable(setter.getMatchedClass().cast(signal));
    }

    private static final class IncomingSignal {

        private final Signal<?> signal;
        private final ActorRef sender;
        private final boolean isAckRequesting;

        private IncomingSignal(final Signal<?> signal, final ActorRef sender, final boolean isAckRequesting) {
            this.signal = signal;
            this.sender = sender;
            this.isAckRequesting = isAckRequesting;
        }

    }

    private static final class ExternalMessageWithSender {

        private final ExternalMessage externalMessage;
        private final ActorRef sender;

        private ExternalMessageWithSender(final ExternalMessage externalMessage, final ActorRef sender) {
            this.externalMessage = externalMessage;
            this.sender = sender;
        }

    }

    static final class OutboundSignalWithId implements OutboundSignal, WithId {

        private final OutboundSignal delegate;
        private final EntityId entityId;
        private final ActorRef sender;

        @Nullable
        private final JsonObject extra;

        private OutboundSignalWithId(final OutboundSignal delegate,
                final EntityId entityId,
                final ActorRef sender,
                @Nullable final JsonObject extra) {

            this.delegate = delegate;
            this.entityId = entityId;
            this.sender = sender;
            this.extra = extra;
        }

        static OutboundSignalWithId of(final Signal<?> signal, final ActorRef sender) {
            final OutboundSignal outboundSignal =
                    OutboundSignalFactory.newOutboundSignal(signal, Collections.emptyList());
            final EntityId entityId = signal.getEntityId();
            return new OutboundSignalWithId(outboundSignal, entityId, sender, null);
        }

        static OutboundSignalWithId of(final OutboundSignal outboundSignal, final ActorRef sender) {
            final EntityId entityId = outboundSignal.getSource().getEntityId();
            return new OutboundSignalWithId(outboundSignal, entityId, sender, null);
        }

        @Override
        public Optional<JsonObject> getExtra() {
            return Optional.ofNullable(extra);
        }

        @Override
        public Signal<?> getSource() {
            return delegate.getSource();
        }

        @Override
        public List<Target> getTargets() {
            return delegate.getTargets();
        }

        @Override
        public JsonObject toJson(final JsonSchemaVersion schemaVersion, final Predicate<JsonField> predicate) {
            return delegate.toJson(schemaVersion, predicate);
        }

        @Override
        public EntityId getEntityId() {
            return entityId;
        }

        private OutboundSignalWithId setTargets(final List<Target> targets) {
            return new OutboundSignalWithId(OutboundSignalFactory.newOutboundSignal(delegate.getSource(), targets),
                    entityId, sender, extra);
        }

        private OutboundSignalWithId setExtra(final JsonObject extra) {
            return new OutboundSignalWithId(
                    OutboundSignalFactory.newOutboundSignal(delegate.getSource(), getTargets()),
                    entityId, sender, extra
            );
        }

        private OutboundSignalWithId mapped(final Mapped mapped) {
            return new OutboundSignalWithId(mapped, entityId, sender, extra);
        }

        private Mapped asMapped() {
            return (Mapped) delegate;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + " [" +
                    "delegate=" + delegate +
                    ", entityId=" + entityId +
                    ", sender=" + sender +
                    ", extra=" + extra +
                    "]";
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final OutboundSignalWithId that = (OutboundSignalWithId) o;
            return Objects.equals(delegate, that.delegate) &&
                    Objects.equals(entityId, that.entityId) &&
                    Objects.equals(sender, that.sender) &&
                    Objects.equals(extra, that.extra);
        }

        @Override
        public int hashCode() {
            return Objects.hash(delegate, entityId, sender, extra);
        }

    }

}

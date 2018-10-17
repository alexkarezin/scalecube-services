package io.scalecube.gateway.clientsdk.websocket;

import io.netty.buffer.ByteBuf;
import io.scalecube.gateway.clientsdk.ClientCodec;
import io.scalecube.gateway.clientsdk.ClientMessage;
import io.scalecube.gateway.clientsdk.ClientSettings;
import io.scalecube.gateway.clientsdk.ClientTransport;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.core.scheduler.Scheduler;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.netty.resources.LoopResources;

public final class WebsocketClientTransport implements ClientTransport {

  private static final Logger LOGGER = LoggerFactory.getLogger(WebsocketClientTransport.class);

  private static final String CLIENT_RECV_TIME = "client-recv-time";
  private static final String CLIENT_SEND_TIME = "client-send-time";

  private static final String STREAM_ID = "sid";
  private static final String SIGNAL = "sig";

  private static final AtomicReferenceFieldUpdater<WebsocketClientTransport, Mono>
      websocketMonoUpdater =
          AtomicReferenceFieldUpdater.newUpdater(
              WebsocketClientTransport.class, Mono.class, "websocketMono");

  private final ClientCodec<ByteBuf> codec;
  private final ClientSettings settings;
  private final HttpClient httpClient;
  private final AtomicLong sidCounter = new AtomicLong();

  private volatile Mono<?> websocketMono;

  /**
   * Creates instance of websocket client transport.
   *
   * @param settings client settings
   * @param codec client message codec
   * @param loopResources loop resources
   */
  public WebsocketClientTransport(
      ClientSettings settings, ClientCodec<ByteBuf> codec, LoopResources loopResources) {
    this.codec = codec;
    this.settings = settings;

    httpClient =
        HttpClient.newConnection()
            .tcpConfiguration(
                tcpClient ->
                    tcpClient.runOn(loopResources).host(settings.host()).port(settings.port()));
  }

  @Override
  public Mono<ClientMessage> requestResponse(ClientMessage request, Scheduler scheduler) {
    return Mono.defer(
        () -> {
          long sid = sidCounter.incrementAndGet();
          ByteBuf byteBuf = enrichRequest(request, sid);
          return getOrConnect()
              .flatMap(
                  session ->
                      session
                          .send(byteBuf, sid)
                          .then(
                              Mono.<ClientMessage>create(
                                  sink ->
                                      session
                                          .receive(sid, scheduler)
                                          .map(this::enrichResponse)
                                          .subscribe(sink::success, sink::error, sink::success)))
                          .doOnCancel(() -> handleCancel(sid, session)));
        });
  }

  @Override
  public Flux<ClientMessage> requestStream(ClientMessage request, Scheduler scheduler) {
    return Flux.defer(
        () -> {
          long sid = sidCounter.incrementAndGet();
          ByteBuf byteBuf = enrichRequest(request, sid);
          return getOrConnect()
              .flatMapMany(
                  session ->
                      session
                          .send(byteBuf, sid)
                          .thenMany(
                              Flux.<ClientMessage>create(
                                  sink ->
                                      session
                                          .receive(sid, scheduler)
                                          .map(this::enrichResponse)
                                          .subscribe(sink::next, sink::error, sink::complete)))
                          .doOnCancel(() -> handleCancel(sid, session)));
        });
  }

  @Override
  public Mono<Void> close() {
    return Mono.defer(
        () -> {
          // noinspection unchecked
          Mono<WebsocketSession> curr = websocketMonoUpdater.get(this);
          return (curr == null ? Mono.<Void>empty() : curr.flatMap(WebsocketSession::close))
              .doOnTerminate(() -> LOGGER.info("Closed websocket client sdk transport"));
        });
  }

  private Mono<WebsocketSession> getOrConnect() {
    // noinspection unchecked
    return Mono.defer(() -> websocketMonoUpdater.updateAndGet(this, this::getOrConnect0));
  }

  private Mono<WebsocketSession> getOrConnect0(Mono<WebsocketSession> prev) {
    if (prev != null) {
      return prev;
    }

    return Mono.create(
            (MonoSink<WebsocketSession> sink) ->
                httpClient
                    .websocket()
                    .uri("/")
                    .handle((in, out) -> handleWebsocket(sink, in, out))
                    .doOnError(
                        ex -> {
                          LOGGER.warn(
                              "Connection to {}:{} failed, cause: {}",
                              settings.host(),
                              settings.port(),
                              ex);
                          websocketMonoUpdater.getAndSet(this, null);
                          sink.error(ex);
                        }))
        .cache();
  }

  private Publisher<Void> handleWebsocket(
      MonoSink<WebsocketSession> sink, WebsocketInbound in, WebsocketOutbound out) {

    WebsocketSession session = new WebsocketSession(codec, in, out);

    LOGGER.info("Created {} on {}:{}", session, settings.host(), settings.port());
    Mono<Void> result =
        session.onClose(
            () -> LOGGER.info("Closed {} on {}:{}", session, settings.host(), settings.port()));
    sink.success(session);
    return result;
  }

  private Disposable handleCancel(long sid, WebsocketSession session) {
    ByteBuf byteBuf =
        codec.encode(
            ClientMessage.builder()
                .header(STREAM_ID, sid)
                .header(SIGNAL, Signal.CANCEL.codeAsString())
                .build());
    return session.send(byteBuf, sid).subscribe();
  }

  private ByteBuf enrichRequest(ClientMessage message, long sid) {
    return codec.encode(
        ClientMessage.from(message)
            .header(CLIENT_SEND_TIME, System.currentTimeMillis())
            .header(STREAM_ID, sid)
            .build());
  }

  private ClientMessage enrichResponse(ClientMessage message) {
    return ClientMessage.from(message).header(CLIENT_RECV_TIME, System.currentTimeMillis()).build();
  }
}

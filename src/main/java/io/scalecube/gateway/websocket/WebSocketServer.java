package io.scalecube.gateway.websocket;

import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.http.server.HttpServerRequest;
import reactor.ipc.netty.http.server.HttpServerResponse;
import reactor.ipc.netty.http.websocket.WebsocketInbound;
import reactor.ipc.netty.http.websocket.WebsocketOutbound;
import reactor.ipc.netty.tcp.BlockingNettyContext;

import org.reactivestreams.Publisher;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.BiFunction;

public class WebSocketServer {

  private final WebSocketAcceptor acceptor;
  private BlockingNettyContext server;

  public WebSocketServer(WebSocketAcceptor acceptor) {
    this.acceptor = acceptor;
  }

  public synchronized InetSocketAddress start() {
    return start(new InetSocketAddress("localhost", 0));
  }

  public synchronized InetSocketAddress start(InetSocketAddress listenAddress) {
    server = HttpServer.builder().listenAddress(listenAddress).build().start(new WebSocketServerBiFunction());
    server.installShutdownHook();
    return server.getContext().address();
  }

  public synchronized void stop(Duration timeout) {
    if (server != null) {
      server.setLifecycleTimeout(timeout);
      server.shutdown();
    }
  }

  public synchronized void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  private class WebSocketServerBiFunction
      implements BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> {

    @Override
    public Publisher<Void> apply(HttpServerRequest httpRequest, HttpServerResponse httpResponse) {
      return httpResponse.sendWebsocket((WebsocketInbound inbound, WebsocketOutbound outbound) -> {
        WebSocketSession session = new WebSocketSession(httpRequest, inbound, outbound);
        inbound.context().onClose(() -> acceptor.onDisconnect(session));
        return acceptor.onConnect(session);
      });
    }
  }
}

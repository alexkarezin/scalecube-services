package io.scalecube.services.transport.rsocket;

import io.rsocket.RSocket;
import io.rsocket.core.RSocketConnector;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.scalecube.net.Address;
import io.scalecube.services.transport.api.ClientChannel;
import io.scalecube.services.transport.api.ClientTransport;
import io.scalecube.services.transport.api.ServiceMessageCodec;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.netty.tcp.TcpClient;

public class RSocketClientTransport implements ClientTransport {

  private static final Logger LOGGER = LoggerFactory.getLogger(RSocketClientTransport.class);

  private final ThreadLocal<Map<Address, Mono<RSocket>>> rsockets =
      ThreadLocal.withInitial(ConcurrentHashMap::new);

  private final ServiceMessageCodec messageCodec;
  private final TcpClient tcpClient;

  /**
   * Constructor for this transport.
   *
   * @param messageCodec message codec
   * @param tcpClient tcp client
   */
  public RSocketClientTransport(ServiceMessageCodec messageCodec, TcpClient tcpClient) {
    this.messageCodec = messageCodec;
    this.tcpClient = tcpClient;
  }

  @Override
  public ClientChannel create(Address address) {
    final Map<Address, Mono<RSocket>> monoMap = rsockets.get(); // keep reference for threadsafety
    Mono<RSocket> rsocket =
        monoMap.computeIfAbsent(address, address1 -> connect(address1, monoMap));
    return new RSocketClientChannel(rsocket, messageCodec);
  }

  private Mono<RSocket> connect(Address address, Map<Address, Mono<RSocket>> monoMap) {
    TcpClient tcpClient = this.tcpClient.host(address.host()).port(address.port());
    return RSocketConnector.create()
        .payloadDecoder(PayloadDecoder.DEFAULT)
        .connect(() -> TcpClientTransport.create(tcpClient))
        .doOnSuccess(
            rsocket -> {
              LOGGER.info("Connected successfully on {}", address);
              // setup shutdown hook
              rsocket
                  .onClose()
                  .doOnTerminate(
                      () -> {
                        monoMap.remove(address);
                        LOGGER.info("Connection closed on {}", address);
                      })
                  .subscribe(
                      null, th -> LOGGER.warn("Exception on closing rsocket: {}", th.toString()));
            })
        .doOnError(
            th -> {
              LOGGER.warn("Connect failed on {}, cause: {}", address, th.toString());
              monoMap.remove(address);
            })
        .cache();
  }

  @Override
  public String toString() {
    return new StringJoiner(", ", RSocketClientTransport.class.getSimpleName() + "[", "]")
        .add("messageCodec=" + messageCodec)
        .add("tcpClient=" + tcpClient)
        .toString();
  }
}

package io.scalecube.services.examples.exceptions;

import io.scalecube.services.annotations.Service;
import io.scalecube.services.annotations.ServiceMethod;
import reactor.core.publisher.Mono;

@Service("example.serviceA")
public interface ServiceA {

  @ServiceMethod
  Mono<Integer> doStuff(int input);
}

package io.scalecube.services.registry.api;

import io.scalecube.services.ServiceInstance;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Service registry interface provides API to register/unregister services in the system and make services lookup by
 * service result.
 */
public interface ServiceRegistry {

  List<ServiceInstance> serviceLookup(String serviceName);
  List<ServiceInstance> serviceLookup(Predicate<? super ServiceInstance> filter);

  void registerService(Object serviceObject);

  Collection<ServiceInstance> services();

  Optional<ServiceInstance> getLocalInstance(String serviceName, String method);

  void start();


}

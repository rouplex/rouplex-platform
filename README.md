rouplex-platform -- a platform for distributed computing
=======

# README #
A platform for discovery and communication between various parts of a distributed service or application.

At its minimum, the platform is a library which can be used to serialize and deserialize application constructs into
payloads which it can then send to and receive from remote endpoints. For now it supports TCP and HTTP communication
protocols with WebSockets coming soon. It supports raw byte streams via TCP, as well as application/json via JAX-RS as
serialization protocols. It offers plain or SSL/TLS communication for security of your data in transit. For now it is
only available in Java, with bindings for other languages coming soon. The communication pattern is request-reply, with
fail fast semantics, and with service consumers (clients) knowing beforehand the coordinates of the service providers
(servers). Blocking API is available with all communication protocols and unblocking/asynchronous flavors are available
with TCP communication protocol.

The platform can also be used as a service, in which case a Discovery Service facilitates registration of the
service providers (servers) and service consumers (clients). In this case, the callers don't need to resolve or
even balance their calls towards various endpoints, since the Platform handles this tasks. In this case, the
pub/sub communication pattern will also be available (coming soon) with at-most-once delivery guarantees.

We intend to provide a Security Service for managing the keys/and certificates of various services as well as
Metrics and Logging services (coming soon).

The platform is extensible in every layer, so you can decide to bring your own implementations, and maybe share them
with the community as part of this open source project.

The JAX-RS implementation comes with Swagger support, providing a nice UI for your RESTful services, right out of the
box.

## What is this repository for? ##
This repo provides various libraries which can be used by your application or service to facilitate communication
between your distributed components. You will only need a subset of them depending on your use case. Here a quick list
of the modules:

1. rouplex-commons contains basic functionality, some of which is already available in the later JDK versions. They are
100% compatible with the ones of the recent JDKs. Most likely, you will not need to import it directly.

1. rouplex-platform contains the basic functionality, and mostly defines the types needed for binding of the different
modules. Most likely, you will not need to import it directly.

1. rouplex-platform-tcp contains the tcp server and client implementations which you can use if your application needs
low latency and high throughput. There is no payload transformation or wrapping, just communication, plain or secure.

1. rouplex-platform-jaxrs contains JAX-RS constructs which you can use if your service defines and implements its
functionality using JAX-RS annotations.

1. rouplex-platform-jersey provides a RouplexJerseyApplication class which you can inherit to implement your own
application. You can further use Swagger to decorate your functionality, and when compiled, a set of UI resources will
be created and deployed alongside your app in the WAR file for a very nice visual experience, right out of the box.

1. rouplex-platform-jaxrs-client (coming soon) will contain functionality for binding an auto generated JAX-RC client.

# Versioning #
We use semantic versioning, in its representation x.y.z, x stands for API update, y for dependencies update, and z for
build number.

# Build #

1. Maven is required to build the project. Please download and install it. Make sure the installation is successful by
typing `mvn -version` in a shell window; the command output should be showing the installation folder.

1. Java is required to build the project.. Make sure the installation is successful by typing `java -version`; the
command output should show the version.

1. On a shell window, and from the folder containing this README.txt file, type `mvn clean install` and if
successful, you will have the built artifacts in appropriate 'target' folders located under the appropriate modules.
The same jars will be installed in your local maven repo.

# Test #
`mvn test`

# Contribution guidelines #

* Writing tests
* Code review
* Other guidelines

# Who do I talk to? #

* Sole owner
andimullaraj@gmail.com
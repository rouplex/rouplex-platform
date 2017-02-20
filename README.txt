rouplex-platform -- a platform for distributed computing
=======

# README #
A platform for discovery and communication between various parts of a distributed application.

The platform comes with various communication protocols such as TCP/SSL, HTTP/HTTPS and its JAX-RS flavor, Web Sockets
(coming soon) addressing various performance needs; serialization protocols such as Raw or Json (IDL coming soon), an
internal Discovery service (coming soon), Logging/Metrics services (coming soon), Security service for trust management
(coming soon).

The platform is extensible in every layer, so you can decide to create your own implementations, and maybe share them
with the community as part of this open source project.

The JAX-RS implementation comes with Swagger support, providing a nice UI for your RESTful services, right out of the
box.

## What is this repository for? ##
This repo provides various libraries which can be used by your application or service to facilitate communication
between your distributed components. You will only need a subset of them depending on your use case. Here a quick list
of the modules:

1. commons contains basic functionality, some of which is already available in the later JDK versions. When present,
they are 100% compatible with the ones of the recent JDKs. Most likely, you will not need to import it directly.

1. platform contains the basic functionality, and mostly defines the types needed for binding of the different modules.
Most likely, you will not need to import it directly.

1. platform-tcp contains the tcp server and client implementations which you can use if your application needs low
latency and hight throughput. There is no payload transformation or wrapping, just communication, plain or secure.

1. platform-jaxrs contains JAX-RS constructs which you can use if your service defines its functionality in terms
of JAX-RS annotations.

1. platform-jersey provides a RouplexJerseyApplication class which you can inherit to implement your own application.
You can further use Swagger to decorate your functionality, and when compiled, a set of UI resources will be created
and deployed alongside your app in the WAR file for a very nice visual experience, right out of the box.

1. platform-jaxrs-client (coming soon) will contain functionality for binding an auto generated JAX-RC client.

### Description ###
(coming soon)

### Versioning ###
We use semantic versioning, int its representation x.y.z, z stands for API, y for dependencies, and z for build number.

## How do I get set up? ##

### Build ###

1. Maven is required to build the project. Please download and install it. Make sure the installation is successful by
typing `mvn -version` in a shell window; the command output should be showing the installation folder.

1. Java is required to build the project.. Make sure the installation is successful by typing `java -version`;
the command output should show the version.

1. On a shell window, and from the folder containing this README.txt file, type `mvn clean install` and if
successful, you will have the built artifacts in appropriate 'target' folders located under the appropriate modules.
The same jars will be installed in your local maven repo.

### Test ###
`mvn test`

## Contribution guidelines ##

* Writing tests
* Code review
* Other guidelines

## Who do I talk to? ##

* Repo owner or admin
andimullaraj@gmail.com
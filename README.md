# AOT-compatible Java Agent Guidelines

This maven-based project provides guidance for
implementing and deploying agents that are compatible
with use of an AOT cache.

The project's main branch presents a simple agent that
instruments code belonging to a small test application
and shows how to create an AOT cache for the application
and deploy the agent in a production run that uses the
cache.

More sophisticated versions of the agent, which implement
features agent writers often need to employ or exemplify
best practice for structuring an agent, are presented in
other branches of the project (listed and linked at the
bottom of this file). The README files in those branches
explain the additonal behaviour or structural benefits the
variant agent offers and details the changes needed to
build and deploy the agent compatibly with use of an AOT
cache.


### Introduction
This simple version of the agent performs one instrumentation
to method `HelloAgent.main()`.
```
HelloAgent.main():
   . . .        . . .
   RETURN  -->  INVOKESTATIC AOTAgentStatistics.print()
   . . .         RETURN
                 . . .  
```
The transformation locates any `RETURN` bytecode in the app's
main method and precedes it with a call to the static `print`
method of class `AOTAgentStatistics`.

Note that in this simple version of the agent stats are not
being collected so the print method reports no useful results.

### Build
The agent and application jars can be built using Maven.
```
mvn install
```
The Maven build should work using any JDK9+ Java release.

### Run
A JDK25+ Java release of OpenJDK is required in order to be
able to deploy the agent with an AOT cache (AOT caching is
not supported in earlier JDK releases). That also ensures
that the JDK includes the classfile API used to perform
bytecode transformation. However, the advice given here
will also apply for agents and apps that cane be compiled
using earlier releasesgoing back to JDK9 when they are run
without an AOT cache. In other words, the recommended command
line modifications that enable use of an AOT cache should
not cause a problem when switching from a JDK25+ release
to an earlier release.

### Normal run
The application is run by adding the app jar to the classpath
and specifying  `HelloAgent` as the main class.
```shell
$ java -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
```
#### Running the app with the AOT Agent
Running with the agent requires adding the `-javaagent` option
to the command line, pointing it at the agent jar. The app jar
also needs to be included in the classpath.
```shell
$ java -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
No agent statistics to report
```
The extra output shows that a call to `AOTAgentStatstics.print()`
has been successfully injected into method `HelloAgent.main`
just before it returns.
#### Running the app with an AOT cache
An AOT cache can be created by running the program as normal
except for the addition of the `AOTCacheOutput` command line
argument
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Temporary AOTConfiguration recorded: HelloAgent.aot.config
Launching child process /home/adinn/redhat/openjdk/jdkupdates/jdk25u/build/linux-x86_64-server-slowdebug/images/jdk/bin/java to assemble AOT cache HelloAgent.aot using configuration HelloAgent.aot.config
Picked up JAVA_TOOL_OPTIONS: -Djava.class.path=app/target/aotagent-app-1.0-SNAPSHOT.jar -XX:AOTCacheOutput=HelloAgent.aot -XX:AOTConfiguration=HelloAgent.aot.config -XX:AOTMode=create
Reading AOTConfiguration HelloAgent.aot.config and writing AOTCache HelloAgent.aot
AOTCache creation is complete: HelloAgent.aot 10936320 bytes
Removed temporary AOT configuration file HelloAgent.aot.config
```
The cache can then be used by rerunning with option `AOTCache`
specifying the same target for the AOT cache file
```shell
$ java -XX:AOTCache=HelloAgent.aot \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
```
With this simple program the cache may not noticeably
improve JDK or application starup or application warmup.
However, for many real applications using an AOT cache
provides a significant performance improvement.

#### Running the app with an AOT cache and the AOT agent
##### A standard cache build will fail
The standard cache built as decribed above is not suitable
for use with the AOT agent. However, that's not a big deal.
It is simple to create a cache that can be used.

In order to understand why it is necessary to change the cache
build steps it is best to see first how the normal cache fails
when an agent is configured.
```shell
$ java -XX:AOTCache=HelloAgent.aot \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
[0.016s][error][aot] An error has occurred while processing the AOT cache. Run with -Xlog:aot for details.
[0.016s][error][aot] Mismatched values for property jdk.module.addmods: java.instrument specified during runtime but not during dump time
[0.016s][error][aot] Disabling optimized module handling
[0.016s][error][aot] AOT cache has aot-linked classes. It cannot be used when archived full module graph is not used.
[0.016s][error][aot] Unable to map shared spaces
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
No agent statistics to report
```
If you really want all the gory details you can follow the
advice given and run the java command with extra argument
`-Xlog:aot`. However, the explanation is actually present in
the above brief report.

Configuring option `-javaagent` on the command requires the JVM
to add optional module `java.instrument` to the set of configured
modules. That's enough to make the AOT cache invalid -- or at the
very least some parts of it. The pre-calculated module graph that
was stored in the cache is based on a configuration that omits
`java.instrument`. So, even in the best case the production run
must drop the cached module graph and recalculate it.

However, this is merely one part of a bigger problem. Any change to
the modules and classes that lie in the bootstrap classpath can have
a knock-on effect to the visibility and linking of classes in the
system classpath. Although this is very unlikely to happen it does
mean that the class metadata pre-installed and pre-linked in the
AOT cache might have been constructed using a link order that is
inconsistent with the linkage that has been congigured in the
production runtime. In order to avoid this possibilty the JVM
rejects the whole AOT cache, prioritizing correctness over
performance.

#### Creating an agent-compatible AOT cache
The solution is to ensure that module `java.instrument` is included
in the module graph when the cache is built.
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot \
    --add-modules=java.instrument \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Temporary AOTConfiguration recorded: HelloAgent.aot.config
Launching child process /home/adinn/redhat/openjdk/jdkupdates/jdk25u/build/linux-x86_64-server-slowdebug/images/jdk/bin/java to assemble AOT cache HelloAgent.aot using configuration HelloAgent.aot.config
Picked up JAVA_TOOL_OPTIONS: -Djava.class.path=app/target/aotagent-app-1.0-SNAPSHOT.jar --add-modules=java.instrument -XX:AOTCacheOutput=HelloAgent.aot -XX:AOTConfiguration=HelloAgent.aot.config -XX:AOTMode=create
Reading AOTConfiguration HelloAgent.aot.config and writing AOTCache HelloAgent.aot
AOTCache creation is complete: HelloAgent.aot 11354112 bytes
Removed temporary AOT configuration file HelloAgent.aot.config
```
When the agent is deployed during a production run that consumes the
cache no update to the module graph is required and the bootstrap
classpath found at runtime matches the one used when building the
cache. This guarantees that the class linkage employed when building
the cache matches the class linkage used during the production run.
```shell
$ java -XX:AOTCache=HelloAgent.aot \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
```
This gets rid of the warning that the cache is not usable.
Unfortunately, there is still one small issue which is stopping
the agent doing its job properly. This can be seen in the output
above where it is clear that `AOTAgentStatistics.print()` has
not been called.

### Creating an AOT-cache compatible Java agent
The problem is that AOT caching has been too successful. Class
`HelloAgent` is itself included in the cache. That means that
during a production run `HelloAgent` is effectively 'pre-loaded'
by the System class loader before agent's transformer gets
installed. The cache provides the JVM with pre-computed metadata
for class `HelloAgent`, bypassing the need to load and process
the class's bytecode.

So, in effect, loading of class `HelloAgent` has 'already happened'
and there is no triggering of the `ClassFileLoadHook` event for
`HelloAgent` that would normally drive entry into the `transform`
method of the agent's `ClassFleTransformer`.

That doesn't mean the agent is denied a chance to transform the
class. The resolution is for the agent to check the loaded class
list immediately after it has installed its transformer, looking
for any classes that it wants to transform and explicitly scheduling
transformation via method `Instrumentation.redefineClasses()`.

By a lucky coincidence (!), the example agent provides an option
to do just that. Appending agent argument `"retransform"` to the
`-javaagent` option's argument string requests the agent to
redefine any target classes that it finds already loaded.

```shell
java -XX:AOTCache=HelloAgent.aot \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar=retransform \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
No agent statistics to report
```

Note that the fact that the redefined class (`HelloAgent`) is already
loaded means that a transformer may not change the visible 'shape' of
the class. Many agent transformers rely on being loaded early, before
the apphas started, in order to catch classes at first load and change
their shape i.e. add fields, change their super or implemented
interfaces, add or remove methods etc.

This is no longer something which can be guaranteed for app classes
since they may potentially be included in the AOT cache. With a few
exceptions that will apply for any class that was loaded by the
bootstrap or system classpath during the training run. Cached classes
must be treated as having been loaded before the agent, just like with
many JDK classes. Ideally a transformer should restrict itself to
updating method bytecode, i.e. behavioural changes, when it needs to
be used with an AOT cache. If not then it has to be be aware that a
transformation may fail with an `UnmodifiableClassException`.

#### An alternative solution that may or may not work
In some cases it may be possible to configure an agent during the
training run as well as in production. Whether this is possible
depends on what classes the agent actually transforms. With the simple
agent provided here there is nothing to stop the agent being used
during training.
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
[0.828s][warning][aot] Skipping HelloAgent: From ClassFileLoadHook
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
No agent statistics to report
[1.273s][warning][aot] Skipping org/my/aotagent/main/AOTAgentMain: Unsupported location
[1.273s][warning][aot] Skipping org/my/aotagent/internal/AOTAgentImpl: Unsupported location
[1.273s][warning][aot] Skipping org/my/aotagent/api/AOTAgentStatistics: Unsupported location
[1.273s][warning][aot] Skipping org/my/aotagent/internal/AOTAgentTransformer: Unsupported location
[1.273s][warning][aot] Skipping org/my/aotagent/internal/AOTAgentException: Unsupported location
Temporary AOTConfiguration recorded: HelloAgent.aot.config
Launching child process /home/adinn/redhat/openjdk/jdkupdates/jdk25u/build/linux-x86_64-server-slowdebug/images/jdk/bin/java to assemble AOT cache HelloAgent.aot using configuration HelloAgent.aot.config
Picked up JAVA_TOOL_OPTIONS: -Djava.class.path=app/target/aotagent-app-1.0-SNAPSHOT.jar -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar -XX:AOTCacheOutput=HelloAgent.aot -XX:AOTConfiguration=HelloAgent.aot.config -XX:AOTMode=create
Reading AOTConfiguration HelloAgent.aot.config and writing AOTCache HelloAgent.aot
AOTCache creation is complete: HelloAgent.aot 12095488 bytes
Removed temporary AOT configuration file HelloAgent.aot.config
```

Note that as a side-effect of training with the agent configured both
the issues encountered above are bypassed. Firstly, the AOT cache
build process excludes class `HelloAgent` from the cache because it
was transformed during the training run (the bytes used during
training are recognized as having been modified under the
`ClassFileLoadHook`). Also excluded are classes loaded from the agent
jar (`Unsupported location`).

Secondly, adding the agent to the command line during training avoids
the need to include module `java.instrument` on the command line. The
module is automatically added to the confiuration when the
`-javaagent` option is passed on the command line, leading to the same
configuration for the training and production runs.

```shell
`$ java -XX:AOTCache=HelloAgent.aot \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
No agent statistics to report
```

Since the main class `HelloAgent` has been excluded from the AOT cache
it gets loaded as normal after the agent transformer has been
installed. So, there is no need to pass the `retransform` option to
the agent in this case.

Although this looks like an easier way to configure the command line
for AOT cache creation note that it is not always possible to
configure an agent during training. This will be demonstrated and
explained when considering the next refinement of the agent.

### Further refinements

This main provides only the simplest example agent that jumps over the
most basic hurdles that get in the way of deploying the agent with an
AOT Cache. The repository contains several other branches which refine
the agent implementation to address successively more complex
requirements, documenting how each variant needs to be built and
deployed:

1. [Instrumenting JDK bootstrap classes](https://github.com/adinn/aotagent/tree/refinement1)
2. [Bundling library classes with the agent](https://github.com/adinn/aotagent/tree/refinement2)
3. [Encapsulating agent code in a module](https://github.com/adinn/aotagent/tree/refinement3)

Note that the third option of employing a modular agent jar, while
highly desirable from the point of view of code integrity and
security, is currently only achievable for agents that do not attempt
to transform JDK bootstrap classes (more precisely, they must not
perform transformations of JDK bootstrap classes which involve
reference to classes that are not already in the bootstrap). While
this restriction rules out most useful agents, the exposition
presented in the branch is still worth reading. As well as explaining
the nature of the problems involved, it also provides an indication of
indicate how one might in future deploy a modular agent in the
bootstrap module set alongside an AOT cache, assuming newer JDK
releases make it possible to resolve these issues.
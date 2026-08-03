# First Refinement of AOT-compatible Java Agent

The simple Java agent only transforms application classes.
However, agents commonly need to transform classes that
reside in the bootstrap classpath and this often means
that the agent needs some of its API classes and, perhaps,
implementation classes to be loaded by the bootstrap
loader. This branch explains how to do that in a way
that is compatible with use of an AOT cache.

### Introduction
This variant of the agent modifies the implementation
in the main branch to count successful calls to method
`java.lang.Thread.run()` by transforming it to call method
`AOTAgenStatistics.incrementRunCount()`. That change
requires class `AOTAgenStatistics` to be loaded by the
bootstrap loader, allowing the call to be successfully
resolved.

So, this time the Java agent instruments two methods
belonging to distinct classes:
```
java.lang.Thread.run():
   . . .        . . .
   RETURN  -->  INVOKESTATIC AOTAgentStatistics.incrementRunCount()
   . . .         RETURN
                 . . .  

HelloAgent.main()
   . . .        . . .
   RETURN  -->  INVOKESTATIC AOTAgentStatistics.print()
   . . .         RETURN
                 . . .  
```
The first transformation locates any `RETURN` bytecode in
`Thread.run` and precedes it with a call to the static method
`incrementRunCount` of class `AOTAgentStatistics`.

The second transformation locates any `RETURN` bytecode in
`HelloAgent.main` and precedes it with a call to static
method `print` of class `AOTAgentStatistics`.

This effectively records each successful call to `Thread.run()`
and prints a summary of the total number of calls when the app
exits.

### Build
The agent and application jars can be built using maven.
```
mvn install
```
### Run
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
to the command line, pointing it at the agent at the jar
```shell
$ java -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        0
```
The extra output shows that a call to `AOTAgentStatstics.print()`
has been successfully injected into method `HelloAgent.main`
just before it returns. However, it also shows that class thread
has not been transformed. This is because `-javagagent` only appends
the agent jar to the system classpath. So, agent classes are only being
loaded by the system clasloader. Attempting to inject a call to
`AOTAgentStatstics.print()`  into a method belonging to a bootstrap
class will lead to a link resolution failure.

The usual way agents fix this is by locating the agent jar during
agent startup and calling method `appendToBootstrapClassLoaderSearch`
of class `Instrumentation` to hoist the jar into the bootstrap
classpath. This must be done in the agent's premain entry routine
in order to ensure that all subsequent loading of agent classes finds
them using the bootstrap loader rather than the system loader.

Luckily, this version of the agent accepts a "hoist" command which
hoists the agent jar as required. The relevant code is in class
`AOTAgentMain` which provides the agent `premain` method referenced
from the agent jar's manifest file.
```shell
`$ java -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar=hoist \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        5
````
### Running the app with an AOT cache and the AOT agent
### Creating an agent compatible AOT Cache
As with the previous version of the agent, building an AOT cache
for use with the agent requires module `java.instrument` to be
included in the module graph:
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
cache the module graph found at runtime matches the one used when building
the cache. However, we now run into a different problem thanks to the hoist
```shell
$ java -XX:AOTCache=HelloAgent.aot \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar=hoist \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        5
```
The bootstrap module issue has been resolved but, unfortunately,
hoisting the agent jar leads to another potential source of
incompatibility. Classes in the agent jar inserted at the tail
of the bootstrap classpath now precede the system classes, potentially
invalidating assumptions about linkage used when building the
AOT cache. Once again the JVM drops the cache in order to guarantee
correctness over performance. The agent still gets to do its job but
the application fails to benefit from using an AOT cache.
### Creating an AOT-cache compatible Java agent
The resolution for this problem is to ensure that module `java.instrument`
and the agent jar are both included in the bootstrap path during assembly
and production without relying on the agent 'hoist'. That doesn't imply
the agent itself has to be configured during assembly, just that the jar is
included in the path. The command line option used to achieve this is
`-Xbootclasspath/a` which was introduced in JDK9.
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot \
    --add-modules=java.instrument \
    -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Temporary AOTConfiguration recorded: HelloAgent.aot.config
Launching child process /home/adinn/redhat/openjdk/jdkupdates/jdk25u/build/linux-x86_64-server-slowdebug/images/jdk/bin/java to assemble AOT cache HelloAgent.aot using configuration HelloAgent.aot.config
Picked up JAVA_TOOL_OPTIONS: -Djava.class.path=app/target/aotagent-app-1.0-SNAPSHOT.jar --add-modules=java.instrument -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar -XX:AOTCacheOutput=HelloAgent.aot -XX:AOTConfiguration=HelloAgent.aot.config -XX:AOTMode=create
Reading AOTConfiguration HelloAgent.aot.config and writing AOTCache HelloAgent.aot
AOTCache creation is complete: HelloAgent.aot 11358208 bytes
Removed temporary AOT configuration file HelloAgent.aot.config
```
The same option must be employed in a production run to ensure that
the jar is in the bootstrap classpath from the very start of the program
run.
```shell
java -XX:AOTCache=HelloAgent.aot  \
    -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar=retransform \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        5
```
Even though the agent jar has been added to the bootstrap it is still
necessary to provide argument `-javaagent` directing the JVM to look for
an agent main class and execute its `premain` method. There is no need
to provide option `--add-modules=java.instrument` to the production
run as it is implied by passing `-javaagent`. The `retransform` option
still needs to be passed as an agent argument because class `HelloAgent`
is included in the AOT cache.

It is worth noting that it is not possible to rely on the `hoist` capability
of the agent to install the agent jar into the bootstrap in production.
This fails because agent initialization happens after the JVM starts
using the cache i.e. too late to fix up the bootstrap classpath:
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot \
    -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
   -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar=hoist,retransform \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
[0.015s][warning][aot] boot classpath has fewer elements than expected
[0.015s][error  ][aot] An error has occurred while processing the AOT cache. Run with -Xlog:aot for details.
[0.015s][error  ][aot] shared class paths mismatch (hint: enable -Xlog:class+path=info to diagnose the failure)
[0.015s][error  ][aot] Unable to map shared spaces
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        5
```
#### The alternative solution does not work
With this version of the agent it is not possible to configure it
during the training run. Cache creation fails because this agent 
ransforms class `java.lang.Thread`:
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot  --add-modules=java.instrument  -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar  -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar  -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
[0.728s][warning][aot] Skipping java/lang/Thread: From ClassFileLoadHook
[0.863s][warning][aot] Skipping HelloAgent: From ClassFileLoadHook
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        5
[1.490s][warning][aot] Skipping java/lang/Thread: Has been redefined
[1.490s][warning][aot] Skipping java/lang/Thread: A scratch class
[1.490s][warning][aot] Skipping java/lang/BaseVirtualThread: Has been redefined
[1.490s][warning][aot] Skipping java/lang/ThreadBuilders$BoundVirtualThread: Has been redefined
[1.490s][warning][aot] Skipping java/lang/VirtualThread: Has been redefined
[1.491s][warning][aot] Skipping jdk/internal/misc/InnocuousThread: Has been redefined
[1.491s][warning][aot] Skipping java/lang/ref/Finalizer$FinalizerThread: Has been redefined
[1.491s][warning][aot] Skipping java/lang/ref/Reference$ReferenceHandler: Has been redefined
[1.493s][error  ][aot] An error has occurred while writing the shared archive file.
[1.493s][error  ][aot] Critical class java.lang.Thread has been excluded. AOT configuration file cannot be written.
```
The usual warnings are printed to record exclusion of the two classes
that have been transformed. We then see some extra warnings for
subclasses of `Thread` that are also excluded because they are derived
from an excluded class. Then we hit the error that blocks cache creation.

Class `Thread` belongs to a core set of 'well-known' classes that are
specially handled by the JVM during early start-up of the JDK runtime.
The JVM can only construct a complete, self-consistent AOT cache if it
includes metadata for all of these 'well-known' classes. However, the
cache cannot also safely include classes which were transformed during
training (at least not with the current cache creation process).

`Thread` is not the only class whose transformation causes training to
fail. Other notable examples include `String`, `Object`, `Class`,
`ClassLoader`, `Method`, `MethodHandle`, `Exception` and `Error`.
In cases where an agent needs to transform bootstrap classes it is
generally better to train the application without the agent present
following the guidance provided earlier. If the behaviour of the
application critically depends on the agent being present then
the best option is to limit the range of transformation to avoid
transforming many core classes like the ones listed above during
training or, in the worst case, not use an AOT cache.
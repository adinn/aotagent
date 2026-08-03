# Simple AOT-compatible Java Agent

A simple Java JVMTI  agent which shows how to reliably
instrument application classes when using an AOT cache.
### Introduction
The Java agent performs one simple instrumentation to
method `HelloAgent.main()`.
```
HelloAgent.main():
   . . .        . . .
   RETURN  -->  INVOKESTATIC AOTAgentStatistics.print()
   . . .         RETURN
                 . . .  
```
The transformation locates any `RETURN` bytecode in the main
and replaces it with a call to the static `print` method of
class `AOTAgentStatistics`.
Note that in this simple version of the agent stats are not
being collected so the print method reports no results.

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
$ java -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
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
except for the addition of the AOTCacheOutput command line
argument
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
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
The cache can then be used by rerunning with option AOTCache
specifying the same target for the AOT cache file
```shell
$ java -XX:AOTCache=HelloAgent.aot -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
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
##### The standard cache build will fail
The standard cache built as decribed above  is not suitable
for use with the AOT agent. However, that's not a big deal.
It is simple to create a cache that can be used.

In order to understand why it ie necessary to change the cache
build steps it is best to see first how the normal cache fails
when an agent is configured.
```shell
$ java -XX:AOTCache=HelloAgent.aot -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar  -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
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
`-Xlog:aot`. The explanation is actually present in th above
brief report. Configuring option `-javaagent` on the command
requires the JVM to add optional module `java.instrument` to
the default set of modules in the `java.se` module suite. That's
enough to make the AOT cache invalid -- or at least some parts
of it. The pre-calculated module graph that is saved in the cache
is no longer correct.

However, there is actually a bigger problem. Changing the set of
classes which lie in the bootstrap can result in a change to the
visibility and linking of classes in the system classpath. Although
this is very unlikely to happen it does mean that the class
metadata pre-installed and pre-linked in the AOT cache might have
been constructed using a link order that is inconsistent with the
linkage that is supposed occur in current runtime. In order to avoid
this possibilty the JVM rejects use of the cache, prioritizing
correctness before performance.

#### Creating an agent compatible AOT cache
The solution is to ensure that module `java.instrument` is included
in the module graph when the cache is built.
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot --add-modules=java.instrument -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
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
When the agent is during a production run that consumes the cache
no update to the module graph is required and the bootstrap classpath
found at runtime matches the one used when building the cache.
```shell
$ java -XX:AOTCache=HelloAgent.aot -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar  -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
```
There is no longer any warning that the cache is not usable.
Unfortunately, there is still one small issue which is stopping
the agent doing its job properly. This can be seen in the output
above where it is clear that `AOTAgentStatistics.print()` has
not been called.

### Creating an AOT-cache compatible Java agent
The problem is that AOT caching has been too successful. Class
`HelloAgent` is itself included in the cache. That means it is
already in the System class loader's loaded class list when the
agent's transformer is installed in the production run. The
cache provides metadata for `HelloAgent` pre-calculated from
the original bytecode. So, loading of class `HelloAgent` has
'already happened' (in much the same way as happens for classes
like `java.lang.Stirng`) and there is no triggering of the
`ClassFileLoadHook` event for `HelloAgent` that normally drives
entry into the `transform` method of the `ClassFleTransformer`
installed by the AOT agent.

The resolution is for the agent to check the loaded class list
immediately after it has installed its transformer, checking for
any classes that it wants to transform and explicitly scheduling
transformation via method `Instrumentation.redefineClasses()`.

By a lucky coincidence (!), the example agent provides an option
to do just that. Appending agent argument `"retransform"` to the
`-javaagent` command line option requests the agent to redefine
any target classes that it finds already loaded.

```shell
java -XX:AOTCache=HelloAgent.aot -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar=retransform  -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
No agent statistics to report
```

Note that the fact that the redefined class (`HelloAgent`) is
already loaded means that a transformer may not change the visible
'shape' of the class. A transformer cannot rely on catching classes
at first load in order to change their shape if they may potentially
have been included in the AOT cache which, by and large, means any
class in the bootstrap or system classpath that was loaded during
training. Ideally a transformer should restrict itself to updating
method bytecode, i.e. behavioural changes, if it needsto be used with
an AOT cache. If not it may fail with an `UnmodifiableClassException`.

#### An alternative solution that may or may not work
In some cases it may be possible to configure an agent during the
training run. Whether this is possible depends on  what classes the
agent actually transforms. With the simple agent provided here there
is nothing to stop the agent being used during training (later
examples will provide more details as to what does and does not work).
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
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
Note that the AOT cache build process excludes class `HelloAgent`
from the cache because it was transformed during the training run
(the bytes used during training are recognzied as having been
modified under the `ClassFileLoadHook`). Also excluded are classes
loaded from the agent jar (`Unsupported location`). 

Adding the agent to the command line during training avoids the need
to include module `java.instrument` on the command line. The module is
automatically added leading to the same configuration for the training
and production runs.

```shell
`$ java -XX:AOTCache=HelloAgent.aot -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
No agent statistics to report
```
Note also that since class `HelloAgent` has been excluded from the AOT 
cache it will be loaded as normal after the agent transformer has been
installed. So, there is no need to pass the retransform option to the agent.  

### Further refinements

This is only a simple example that jumps over the
most basic hurdle that gets in the way of deploying
an agent with an AOT Cache. The repository contains
several other variants of the agent which address
issues that arise when the agent tries to do more
complex things like instrumenting bootstrap classes.

Details to follow
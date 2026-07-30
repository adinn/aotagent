# Simple AOT-compatible Java Agent

A simple Java JVMTI  agent which shows how to reliably
instrument application classes loaded by the JDK bootstrap
when using an AOT cache.
### Introduction
The Java agent performs one simple instrumentation to
method `HelloAgent.main()`.
```
java.lang.Thread.run():
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
#### Running with the AOT Agent
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
#### Running with an AOT cache
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

#### Running with an AOT cache and the AOT agent
The cache that was just built is not suitable for use with
the AOT agent. However, it is simple to create a cache that
can be used. In order to understand what can be done to fix
the cache it is best to observe how the normal cache fails
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
If you really want to see all the gory details you can follow
the advice given and run the java command with extra argument
`-Xlog:aot`. The explanation is actually present in this more
brief report. Configuring option `-javaagent` on the command
requires the JVM to add optional module `java.instrument` to
the default set of modules in the `java.se` module suite. That's
enough to make the AOT cache invalid -- or at least some parts
of it. The module graph is pre-calculated and saved in the cache.

However, there is actually a bigger problem. Changing the set of
classes which lie in the bootstrap can result in a change to the
visibility and linking of classes in the system classpath. Although
that is very unlikely to happen it does mean that the class
metadata pre-installed and pre-linked in the AOT cache could have
been constructed using a link order that is inconsistent with the
linkage that should occur in current runtime. In order to avoid this 
ossibilty the JVM rejects use of the cache, prioritizing correctness
before performance.

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

The problem is that AOT caching has been too successful. Class
`HelloAgent` is itself included in the cache. That means it is
already in the System class loader's loaded class list when the
agent's transformer is installed. There is no subsequent load
of class `HelloAgent` and, consequently, no triggering of the
`ClassLoadHook` event that drives entry into the `transform`
method of the `ClassFleTransformer` registered by the AOT agent.

The resolution is for the agent to check the loaded class list
immediately after it has installed its transformer looking for
classes that it needs to transform and to explicitly schedule
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

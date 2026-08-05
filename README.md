# Second Refinement of AOT-compatible Java Agent

The Java agent now transforms both application and
bootstrap classes. It is built as a single jar which
is easily inserted into the bootstrap path as well
as being passed as argument to the `-javaagent`
command line option.

Building and dpeloying as one jar is easy when the agent
has no dependencies. However, in reality agents often rely
on library code. For example, agents commonly rely on ASM
or ByteBuddy to perform  bytecode rewriting, letting them be
used in JDK releases priot to JDK22 that do not include the
`java.lang.classfile` bytecode manipulation API.

In theory, it is perfectly possible to configure the system
or bootstrap classpaths to include the agent jar and
all its (recursive) dependencies as independent jars using
command line options `-cp` or `-Xbootclasspath/a'. In practice,
it is much easier for users to consume an agent if all the
code, for the agent and all its dependencies, is bundled into
a single jar.

This version of the agent uses the ASM library to perform
its bytecode transformations. The maven pom uses the maven
shade plugin to bundle all the required ASM 9.10.1 library
classes into the agent jar, allowing the agent still to be
consumed as a single, self-contained deliverable.

The shade plugin does more than just bundle up dependencies.
It also ensures that library classes are relocated into a
subpackage of the AOT agent package `org.my.aotagent`. That
also requires ensuring that any reference to those classes,
whether they are from agent classes or one library to another,
are modified to add the `org.my.aotagent` prefix to their
package (shading the transplanted packages from normal
references, hence the name).

Shading of embedded libraries is particularly important when
an agent jar is inserted ito the bootstrap. In the current
example the agent bundles ASM 9.10.1. If the agent were to
be deployed unshaded into an app that relied on some other
version of ASM then adding the agent to the bootstrap would
mean that application references to ASM classes would be
resolved against the bundled librray rather than the version
included to the system classpath.

### Introduction
This variant of the agent performs the same two transformations
as the previous one:
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
The main difference between this agent and its predecessor is
that transformation is performed using an ASM class visitor
rather than the JDK's own classfile bytecode transformer APIS.

The other difference is that the agent does not support the
"hoist" and "retransform" options. The user is expected to
add the jar to the bootstrap path and the agent always checks
to see if class `HelloAgent` has already been loaded.

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
Running with the agent requires adding the `-Xbootclasspath/a`
and `-javaagent` options to the command line, pointing each of
them at the agent jar
```shell
$ java -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        5
```
The extra output shows that the calls to
`AOTAgentStatstics,incrementRunCount()` and
`AOTAgentStatstics.print()` have been successfully injected
into the target classes `Thread` and `HelloAgent`.

The `jar` command shows that the ASM classes used by the agent
have been included in the agent jar:
```shell
$ jar tvf agent/target/aotagent-agent-1.0-SNAPSHOT.jar | grep asm | head -5
     0 Fri Feb 01 00:00:00 GMT 1980 org/my/aotagent/shaded/org/objectweb/asm/
  2453 Fri Feb 01 00:00:00 GMT 1980 org/my/aotagent/shaded/org/objectweb/asm/AnnotationVisitor.class
  9400 Fri Feb 01 00:00:00 GMT 1980 org/my/aotagent/shaded/org/objectweb/asm/AnnotationWriter.class
  1683 Fri Feb 01 00:00:00 GMT 1980 org/my/aotagent/shaded/org/objectweb/asm/Attribute$Set.class
  5923 Fri Feb 01 00:00:00 GMT 1980 org/my/aotagent/shaded/org/objectweb/asm/Attribute.class
```
### Running the app with an AOT cache and the AOT agent
### Creating an agent compatible AOT Cache
As with the previous version of the agent, building an AOT cache
for use with the agent requires module `java.instrument` to be
included in the module graph and the agent jar to be inserted into
the bootstrap classspath.
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot \
    -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    --add-modules=java.instrument \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Temporary AOTConfiguration recorded: HelloAgent.aot.config
Launching child process /home/adinn/redhat/openjdk/jdkupdates/jdk25u/build/linux-x86_64-server-slowdebug/images/jdk/bin/java to assemble AOT cache HelloAgent.aot using configuration HelloAgent.aot.config
Picked up JAVA_TOOL_OPTIONS: -Djava.class.path=app/target/aotagent-app-1.0-SNAPSHOT.jar -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar --add-modules=java.instrument -XX:AOTCacheOutput=HelloAgent.aot -XX:AOTConfiguration=HelloAgent.aot.config -XX:AOTMode=create
Reading AOTConfiguration HelloAgent.aot.config and writing AOTCache HelloAgent.aot
AOTCache creation is complete: HelloAgent.aot 11354112 bytes
Removed temporary AOT configuration file HelloAgent.aot.config
```
The agent can now be deployed with this cache in
production so long as the agent jar is added to the
bootstrap classpath:
```shell
$ java -XX:AOTCache=HelloAgent.aot \
    -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        5
```
#### The alternative solution does not work
As with the previous version of the agent it is not possible
to configure it during the training run for the same reason
as last time. Cache creation fails because this agent transforms
class `java.lang.Thread`:
# Refinement 3: Shipping as a Module

The previous version of the Java agent solves several
common problems faced by agent developers while still
maintaining copatibility with use of an AOT cache. It
transforms both application and bootstrap classes. It
simplifies deployment by bundling its dependencies (the
ASM library) into the agent jar. Lastly, it avoids
conflicts with overlapping application dependencies by
shading bundled libraries' classes into its own, unique
package space. However, that still leaves one packaging
issue that could be improved on.

The agent code is neatly factored into three packages.
The first two are API packages: `org.my.aotagrent.api`
contains all agent classes and methods referenced from
code injected by the agent; `org.my.aotagrent.main`
contains the agent entry class specified in the agent
jar's manifest.

The third package, `org.my.aotagrent.internal` contains
the agent implementation. Strictly, this code should be
private, invisible to other bootstrap code not provided
by the agent and also to classes loaded via the classpath.
At the very least this is a question of code  hygiene,
ensuring clients do not mistakenly use internal  classes.
A more important reason to hide the implementation
is that the `Instrumentation` instance passed to the agent
entry class's `premain` and `agentmain` methods is stored
in a static field of class `AOTAgentImpl`. That leaves it
accessible to any application class via reflection. Access
to an `Instrumentation` object grants clients the ability to
perform many operations that can subvert normal JVM and
application behaviour, making it easier for malicious code to
escalate a minor security exploit to a much more sophisticated
and dangerous exploit.

A nice way to avoid this security issue is to package the jar
as a module, exporting API packages but not the implementation
package. However, nice as that sounds it is only achievable
for the simple agent we started with (the one in the main
beanch) that has no dependencies and only transforms application
classes.

Building the agent jar as a module can be achieved simply by
including a `module-info.java` at the root of the source tree
and configuring it to export the `api` and `main` packages.
Deploying it as a module requires adding the jar to the module
path using command line option `--modulepath` and adding the
agent module into the JVM's module set using command line  option
`--add-module`.

However, when the agent needs to bundle library dependencies
as is the case with this version then that approach will not
work without some changes to the building and packaging steps
used to produce the agent jar. Resolving this issue requires
working around some limitations of the maven/javac build process.

A further problem is that the agent cannot be deployed as a
module and transform bootstrap classes. Of course, it is still
possible to insert the modular agent jar into the bootstrap
classpath, allowing it to inject references to its own classes
into bootstrap code. However, the JVM will not then add the jar
into the list of bootstrap modules, nor even treat the jar as a
module.

Combining option `-Xbootclasspath/a` with the other command
line options that configure modules, `--add-modules`,`--module-path`
and `--upgrade-module-path`, wil not remedy this problem. The
issue is that set of bootstrap modules is fixed during the JVM
build process and cannot be modified at runtime. It might be
possible to loosen this constraint in future JVMs, allowing
modular aent jars to be deployed into the bootstrap classpath
as modules. However, that option is not currently in the roadmap
for the Java platform module system.

These difficulties are explained below using this version of the
module to show the relevant configruation options and associated
JVM behaviour. The module code is not significantly changed.
The main difference is that the main implementation class prints
details of its classloader and module. The source tree also includes
a new directory implementing a dummy version of the desired module.
However, the build process is quite different, requiring some manual
intervention to address the operations that are not covered  by the
normal maven build process and normal JVM command line  deployment
options.

### How to package the shaded agent jar as a module jar 
There are three significant obstacles to bypass in the normal
build process in order to allow the shaded agent jar to be
packaged as a module. The first, basic issue is that a module
can normally only be built to consume dependent libraries which
have already been modularized and packaged as module jars.
Packaging a jar as a module also normally requires any such
dependent modules to be declared as requirements in the agent's
`module-info.java` file.

Secondly, dependent modules are normally expected to be deployed
in the module path along with the modular agent jar and listed
one by one as arguments to the `add-modules` command line option.
The module requirements of the agent jar cannot be met by
bundling a dependent module jar's contents into the agent jar
itself. Indeed, the maven shade plugin actually strips `module-info`
classes from all inputs when assembling content for its output jar.
It will not build a module jar even when starting with a module
and will erase any `module-info` files from the bundled jars.

The third problem is the one the shade plugin is meant to avoid
Including dependent modules required by the agent via the
`add-modules` command line option risks a version clash when
the same modules need to be consumed by the application code.
What is really needed is for any dependent code to be loaded
from the single agent jar. Also, it really needs to lie in
a package using the agent's package prefix. If not then an
app which employs the same library might see an error because
the library package classes are split across the module and the
classpath.

So, it seems that packaging the agent as a module cannot be achieved
as with the previous version i.e. by simply compiling the agent
code with library dependency jars on the compiler classpath and
then shading the required library classes into the agent jar.
However, it is clear that the previous jar contains all the code
needed for the agent to operate, that the deployed jar would benefit
from module packaging to hide all the implementation and shaded code
and that none of this extra, shaded content to infringes any module
restrictions.

These problems are easily finessed by a) building a dummy, throwaway
modular jar that includes a `module-info.class` file with the
relevant name, imports and exports and b) merging that file into the
previously built shaded jar. This version of the agent code provides
a dummy agent source tree in subdirectory `dummy-agent`. It
contains the desired `module-info.java` source plus two dummy
classes which serve to populate the exported packages -- a
requirement for the maven compiler plugin to play ball.

### Build
Running `mvn install` builds the agent and app jar products plus
jar file `dummy-agent/target/aotagent-dummy-agent-1.0-SNAPSHOT.jar`.
The latter contains the required `module-info.class`.
```shell
$ mvn install
  ...
$ $ ls -l dummy-agent/target/
total 4
-rw-r--r--. 1 adinn adinn 3063 Aug  6 16:41 aotagent-dummy-agent-1.0-SNAPSHOT.jar
drwxr-xr-x. 1 adinn adinn   40 Aug  6 16:41 classes
drwxr-xr-x. 1 adinn adinn   22 Aug  6 16:41 generated-sources
drwxr-xr-x. 1 adinn adinn   28 Aug  6 16:41 maven-archiver
drwxr-xr-x. 1 adinn adinn   42 Aug  6 16:41 maven-status
```
The `jar` tool can be used to extract this `module-info.class`
and insert it into the shaded jar that we want to convert to a
module.
```shell
$ jar -xvf agent/target/aotagent-agent-1.0-SNAPSHOT.jar module-info.class
extracting to directory: .../aotagent/refinement3
[adinn@zenade refinement3]$ jar -uvf agent/target/aotagent-agent-1.0-SNAPSHOT.jar module-info.class
updated module-info: module-info.class
```

So, we now have a shaded agent jar which encapsulates its code in
a module when deployed into the classpath using command line
option `--add-modules`. The agent still includes code to
transform application and bootstrap classes and still relies
on a bundled, shaded copy of the ASM library to do the
transformation. The benefit of module packaging that there is
no chance of application code accessing the private agent
classes located in the implementation subpackage, in particular
reading or writing their private static fields. Unfortunately,
deploying the agent into the classpath means that it is unable
to transform bootstrap classes. 

### Run
### Running the app with the AOT Agent as a classpath module
The application can be run with the agent deployed as a classpath
module (i.e. a module loaded by the application loader) by adding the
app jar to the module path, adding module `org.my.aotagent.agent`
to the modules set, adding the agent jar again as a java agent
and specifying `HelloAgent` as the main class.
```shell
$ java --module-path=agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    --add-modules=org.my.aotagent.agent \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
AOTAgentImpl running in module org.my.aotagent.agent
UAOTAgentImpl classloader is app
nable to transform bootstrap class java.lang.Thread
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        0
```
The first message printed by the agent shows that the agent
implementation class `AOTAgentImpl` resides in named module
`org.my.aotagent.agent`. The second message shows that it has
been loaded by the application classloader (named `app`).

### Running the app with the AOT Agent as a bootstrap module
#### Adding the agent jar to the bootstrap path does not work
Unfortunately, the `-Xbootclasspath/a` option cannot be used
to insert add the agent to the bootstrap module set.  Using
that option does allow the classes in the agent jar to be
loaded by the bootstrap loader but the jar is not recognized
as a module.
```shell
$ java -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
AOTAgentImpl running in module null
AOTAgentImpl classloader is null
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        5
```
The printout shows that the module of class `AOTAgentImpl` is
`null` i.e. it belongs to the unnamed module. The classloader
is also null whcih means it resides in the bootstrap. That is
also visible in the fact that it has successfully instrumented
class `Thread`, counting 5 calls to `Thread.run()`.
#### Options `--add-modules` and/or `--module-path` won't fix this
Adding options `--add-modules` and/or `--module-path`
to the command line does not remedy the problem
```shell
$ java -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    --add-modules=org.my.aotagent.agent  \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Error occurred during initialization of boot layer
java.lang.module.FindException: Module org.my.aotagent.agent not found
```
The lookup for module `org.my.aotagent.agent` fails because the
jar is in the system bootstrap path rather than in the module path.
Adding the jar to the module path resolves the lookup failure but
still results in classes in the module jar being loaded by the
application loader.
```shell
$ java -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    --add-modules=org.my.aotagent.agent  \
    --module-path=agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
AOTAgentImpl running in module org.my.aotagent.agent
AOTAgentImpl classloader is app
Unable to transform bootstrap class java.lang.Thread
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        0
```
The printouts indicate that the module has been installed
but also that its classes are loaded by the app loader
and hence that it cannot be used to transform bootstrap classes.

It doesn't help if the jar is added to the upgrade module path,
which is actually intended for use in upgrading existing modules:
```shell
$ java -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    --add-modules=org.my.aotagent.agent  \
    --upgrade-module-path=agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
AOTAgentImpl running in module org.my.aotagent.agent
AOTAgentImpl classloader is app
Unable to transform bootstrap class java.lang.Thread
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        0
```
The module jar is still found and processed but module classes are
loaded by the app loader rather than the bootstrap loader.

### Running the app with an AOT cache and the AOT agent as a classpath module
### Creating an agent compatible AOT Cache
As with the previous version of the agent, building an AOT cache
for use with the agent requires module `java.instrument` to be
included in the module graph. The OT agent also needs to be added
to the module graph and the agent jar needs to be inserted into
the module path
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot \
    --add-modules=java.instrument,org.my.aotagent.agent \
    --module-path=agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Temporary AOTConfiguration recorded: HelloAgent.aot.config
Launching child process /home/adinn/redhat/openjdk/jdkupdates/jdk25u/build/linux-x86_64-server-slowdebug/images/jdk/bin/java to assemble AOT cache HelloAgent.aot using configuration HelloAgent.aot.config
Picked up JAVA_TOOL_OPTIONS: -Djava.class.path=app/target/aotagent-app-1.0-SNAPSHOT.jar --add-modules=java.instrument,org.my.aotagent.agent --module-path=agent/target/aotagent-agent-1.0-SNAPSHOT.jar -XX:AOTCacheOutput=HelloAgent.aot -XX:AOTConfiguration=HelloAgent.aot.config -XX:AOTMode=create
Reading AOTConfiguration HelloAgent.aot.config and writing AOTCache HelloAgent.aot
AOTCache creation is complete: HelloAgent.aot 12054528 bytes
Removed temporary AOT configuration file HelloAgent.aot.config
```
#### Running the app using the AOT cache and deploying the AOT agent as a classpath module
The agent can now be deployed with this cache in
production so long as the agent jar is once again
configured as a module:
```shell
$ java -XX:AOTCache=HelloAgent.aot \
    --add-modules=java.instrument,org.my.aotagent.agent \
    --module-path=agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
AOTAgentImpl running in module org.my.aotagent.agent
AOTAgentImpl classloader is app
Unable to transform bootstrap class java.lang.Thread
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        0
```
### Running the app with an AOT cache and the modular AOT agent deployed as non-modular bootstrap jar
### Creating an agent compatible AOT Cache
It is still possible to create an agent compatible cache when
the agent is packaged as a modular jar. It requires the saem
command line configuration as was used for the non-modular jar
to insert the agent jar into the system classpath. Unfortunately,
this means the jar is not treated as a module and so the deployment
does not benefit from module encapsulation of internal agent classes.
```shell
$ java -XX:AOTCacheOutput=HelloAgent.aot \
    --add-modules=java.instrument \
    -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
`Hello from AOT Agent
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
``
#### Running the app using the AOT cache and deploying he AOT agent as a classpath module
The agent can now be deployed with this cache in
production using the saem command lne optiosn as
were required when using a non-modular jar:
```shell
$ java -XX:AOTCache=HelloAgent.aot \
    --add-modules=java.instrument \
    -Xbootclasspath/a:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -javaagent:agent/target/aotagent-agent-1.0-SNAPSHOT.jar \
    -classpath app/target/aotagent-app-1.0-SNAPSHOT.jar HelloAgent
AOTAgentImpl running in module null
AOTAgentImpl classloader is null
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Hello from AOT Agent
Total Thread.run count:        5
```